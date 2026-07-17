package gateway

import (
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type CommandExecutor interface {
	Run(ctx context.Context, name string, args ...string) ([]byte, error)
}

type RealCommandExecutor struct{}

func (RealCommandExecutor) Run(ctx context.Context, name string, args ...string) ([]byte, error) {
	return exec.CommandContext(ctx, name, args...).CombinedOutput()
}

type DryRunController struct{}

func (DryRunController) Apply(context.Context, Profile) error { return nil }
func (DryRunController) Clear(context.Context) error          { return nil }

type TCController struct {
	WAN       string
	IFB       string
	StatePath string
	IPCommand string
	TCCommand string
	Executor  CommandExecutor
	// PreApplyCheck revalidates the dedicated forwarding topology after any
	// stale owned cleanup and immediately before a new ownership state is
	// persisted or traffic control is mutated.
	PreApplyCheck func(context.Context) error
}

const (
	anebRootHandle = "1a1e:"
	anebFilterPref = "49152"
	anebIFBAlias   = "aneb-gateway-owned-v1"
)

func (c TCController) Validate() error {
	if c.Executor == nil {
		return fmt.Errorf("command executor is required")
	}
	if !interfaceNamePattern.MatchString(c.WAN) || c.WAN == "lo" {
		return fmt.Errorf("invalid WAN interface %q", c.WAN)
	}
	if !interfaceNamePattern.MatchString(c.IFB) || c.IFB == "lo" || c.IFB == c.WAN {
		return fmt.Errorf("invalid IFB interface %q", c.IFB)
	}
	if c.StatePath == "" || !filepath.IsAbs(c.StatePath) {
		return fmt.Errorf("absolute tc ownership state path is required")
	}
	return nil
}

var interfaceNamePattern = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,15}$`)

func (c TCController) Apply(ctx context.Context, profile Profile) error {
	if err := c.Validate(); err != nil {
		return err
	}
	if err := profile.Validate(); err != nil {
		return err
	}
	// Startup and every new experiment begin from a known state. Cleanup is
	// deliberately best-effort because "not found" is the expected clean state.
	if err := c.Clear(ctx); err != nil {
		return fmt.Errorf("pre-apply cleanup: %w", err)
	}
	if c.PreApplyCheck != nil {
		if err := c.PreApplyCheck(ctx); err != nil {
			return fmt.Errorf("pre-apply topology check: %w", err)
		}
	}
	baseline, err := c.cleanBaseline(ctx)
	if err != nil {
		return err
	}
	state := tcOwnershipState{
		ContractVersion: tcStateContractVersion,
		WAN:             c.WAN, IFB: c.IFB, IFBAlias: anebIFBAlias,
		BaselineQdisc: baseline,
	}
	if err := writeTCState(c.StatePath, state); err != nil {
		return fmt.Errorf("persist tc ownership before mutation: %w", err)
	}
	commands := [][]string{
		{c.ipCommand(), "link", "add", c.IFB, "type", "ifb"},
		{c.ipCommand(), "link", "set", "dev", c.IFB, "alias", anebIFBAlias},
		{c.ipCommand(), "link", "set", "dev", c.IFB, "up"},
		{c.tcCommand(), "qdisc", "replace", "dev", c.WAN, "handle", "ffff:", "ingress"},
		{c.tcCommand(), "filter", "replace", "dev", c.WAN, "parent", "ffff:", "protocol", "all", "pref", anebFilterPref, "u32", "match", "u32", "0", "0", "action", "mirred", "egress", "redirect", "dev", c.IFB},
		append([]string{c.tcCommand(), "qdisc", "replace", "dev", c.WAN, "root", "handle", anebRootHandle, "netem"}, netemArgs(profile.Uplink)...),
		append([]string{c.tcCommand(), "qdisc", "replace", "dev", c.IFB, "root", "handle", anebRootHandle, "netem"}, netemArgs(profile.Downlink)...),
	}
	for _, command := range commands {
		commandCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		output, err := c.Executor.Run(commandCtx, command[0], command[1:]...)
		cancel()
		if err != nil {
			applyErr := fmt.Errorf("%s failed: %w: %s", strings.Join(command, " "), err, strings.TrimSpace(string(output)))
			clearCtx, clearCancel := context.WithTimeout(context.Background(), 10*time.Second)
			clearErr := c.Clear(clearCtx)
			clearCancel()
			if clearErr != nil {
				return fmt.Errorf("%w; compensating cleanup failed: %v", applyErr, clearErr)
			}
			return applyErr
		}
	}
	return nil
}

// PreflightClean is strictly read-only. It refuses an installation or restart
// whenever ownership state or traffic-control resources need recovery first.
func (c TCController) PreflightClean(ctx context.Context) error {
	if err := c.Validate(); err != nil {
		return err
	}
	state, err := loadTCState(c.StatePath)
	if err != nil {
		return err
	}
	if state != nil {
		return fmt.Errorf("tc ownership state exists; cleanup verification is required before preflight")
	}
	inspection, err := c.inspect(ctx)
	if err != nil {
		return err
	}
	if inspection.hasANEBRoot || inspection.hasIngress || inspection.hasClsact || inspection.hasAnyFilter || inspection.ifbExists {
		return fmt.Errorf("traffic-control resources are not clean")
	}
	return validateSafeBaseline(inspection.qdisc)
}

func netemArgs(p DirectionPolicy) []string {
	args := []string{"limit", "100000"}
	if p.DelayMs > 0 {
		args = append(args, "delay", strconv.Itoa(p.DelayMs)+"ms")
		if p.JitterMs > 0 {
			args = append(args, strconv.Itoa(p.JitterMs)+"ms")
		}
	}
	if p.LossPct > 0 {
		args = append(args, "loss", formatDecimal(p.LossPct)+"%")
	}
	if p.RateMbps > 0 {
		args = append(args, "rate", formatDecimal(p.RateMbps)+"mbit")
	}
	return args
}

func formatDecimal(value float64) string {
	return strconv.FormatFloat(value, 'f', -1, 64)
}

func (c TCController) Clear(ctx context.Context) error {
	if err := c.Validate(); err != nil {
		return err
	}
	state, err := loadTCState(c.StatePath)
	if err != nil {
		return err
	}
	inspection, err := c.inspect(ctx)
	if err != nil {
		return err
	}
	if state == nil {
		if inspection.hasANEBRoot || inspection.hasIngress || inspection.hasClsact || inspection.hasAnyFilter || inspection.ifbExists {
			return fmt.Errorf("unowned traffic-control resources detected; refusing cleanup")
		}
		if err := validateSafeBaseline(inspection.qdisc); err != nil {
			return fmt.Errorf("%w; refusing mutation", err)
		}
		return nil
	}
	if state.WAN != c.WAN || state.IFB != c.IFB || state.IFBAlias != anebIFBAlias {
		return fmt.Errorf("tc ownership state does not match runtime interfaces")
	}
	baseline, err := parseRestorableBaseline(state.BaselineQdisc)
	if err != nil {
		return fmt.Errorf("recorded WAN baseline is not safely restorable: %w", err)
	}
	if !inspection.hasANEBRoot {
		current, currentErr := parseRestorableBaseline(rootQdiscLine(inspection.qdisc))
		if currentErr != nil || !baseline.equal(current) {
			return fmt.Errorf("WAN root qdisc is neither the owned ANEB qdisc nor the recorded baseline")
		}
	}
	if inspection.hasForeignFilter {
		return fmt.Errorf("foreign ingress filter detected; refusing cleanup: %s", inspection.foreignFilterReason)
	}
	if inspection.hasClsact {
		return fmt.Errorf("clsact qdisc is not an ANEB-owned ingress resource; refusing cleanup")
	}
	if inspection.hasIngress && !inspection.hasOwnedFilter {
		return fmt.Errorf("ingress qdisc has no exact ANEB-owned redirect filter; refusing cleanup")
	}
	if !inspection.hasIngress && inspection.hasAnyFilter {
		return fmt.Errorf("ingress filter exists without the exact ANEB-owned ingress qdisc; refusing cleanup")
	}
	if inspection.ifbExists && !inspection.ifbOwned {
		return fmt.Errorf("IFB %s does not have both type=ifb and the exact ANEB alias; refusing cleanup", c.IFB)
	}

	if inspection.hasANEBRoot {
		var output []byte
		var restoreErr error
		if baseline.Kind == "noqueue" {
			output, restoreErr = c.run(ctx, c.tcCommand(), "qdisc", "del", "dev", c.WAN, "root")
		} else {
			args := []string{"qdisc", "replace", "dev", c.WAN, "root", baseline.Kind}
			args = append(args, baseline.Args...)
			output, restoreErr = c.run(ctx, c.tcCommand(), args...)
		}
		if restoreErr != nil {
			return fmt.Errorf("restore WAN root qdisc baseline: %w: %s", restoreErr, strings.TrimSpace(string(output)))
		}
	}
	if inspection.hasIngress {
		// The exact redirect filter was verified above. Delete its owning ingress
		// qdisc in one operation so a failed command leaves the filter as durable
		// ownership evidence for a safe retry. Deleting the filter first would
		// create an unrecoverable state: ingress present but no provably owned
		// filter.
		output, deleteErr := c.run(ctx, c.tcCommand(), "qdisc", "del", "dev", c.WAN, "ingress")
		if deleteErr != nil {
			return fmt.Errorf("remove owned ingress qdisc: %w: %s", deleteErr, strings.TrimSpace(string(output)))
		}
		afterIngress, inspectErr := c.inspect(ctx)
		if inspectErr != nil {
			return fmt.Errorf("verify owned ingress qdisc removal: %w", inspectErr)
		}
		if afterIngress.hasIngress || afterIngress.hasClsact || afterIngress.hasAnyFilter {
			return fmt.Errorf("verify owned ingress qdisc removal: ingress traffic-control resource remains")
		}
		if afterIngress.ifbExists && !afterIngress.ifbOwned {
			return fmt.Errorf("verify owned ingress qdisc removal: IFB ownership changed")
		}
		inspection = afterIngress
	}
	if inspection.ifbExists {
		if inspection.hasIngress || inspection.hasClsact || inspection.hasAnyFilter || !inspection.ifbOwned {
			return fmt.Errorf("refusing to remove IFB before redirect and ingress qdisc cleanup is verified")
		}
		output, linkErr := c.run(ctx, c.ipCommand(), "link", "set", "dev", c.IFB, "down")
		if linkErr != nil {
			return fmt.Errorf("set owned IFB down: %w: %s", linkErr, strings.TrimSpace(string(output)))
		}
		beforeDelete, inspectErr := c.inspect(ctx)
		if inspectErr != nil {
			return fmt.Errorf("verify owned IFB before deletion: %w", inspectErr)
		}
		if !beforeDelete.ifbExists || !beforeDelete.ifbOwned || beforeDelete.hasIngress || beforeDelete.hasClsact || beforeDelete.hasAnyFilter {
			return fmt.Errorf("verify owned IFB before deletion: ownership or redirect state changed")
		}
		output, linkErr = c.run(ctx, c.ipCommand(), "link", "del", c.IFB)
		if linkErr != nil {
			return fmt.Errorf("remove owned IFB: %w: %s", linkErr, strings.TrimSpace(string(output)))
		}
	}
	after, err := c.inspect(ctx)
	if err != nil {
		return fmt.Errorf("verify owned cleanup: %w", err)
	}
	if after.hasANEBRoot || after.hasIngress || after.hasClsact || after.hasAnyFilter || after.ifbExists {
		return fmt.Errorf("verify owned cleanup: ANEB traffic-control resource remains")
	}
	restored, restoreParseErr := parseRestorableBaseline(after.qdisc)
	if restoreParseErr != nil || !baseline.equal(restored) {
		return fmt.Errorf("verify owned cleanup: WAN qdisc baseline mismatch: got %q want %q", normalizeQdisc(after.qdisc), normalizeQdisc(state.BaselineQdisc))
	}
	if err := removeTCState(c.StatePath); err != nil {
		return err
	}
	return nil
}

type tcInspection struct {
	qdisc               string
	hasANEBRoot         bool
	hasIngress          bool
	hasClsact           bool
	hasAnyFilter        bool
	hasOwnedFilter      bool
	hasForeignFilter    bool
	foreignFilterReason string
	ifbExists           bool
	ifbOwned            bool
}

func (c TCController) inspect(ctx context.Context) (tcInspection, error) {
	qdisc, err := c.run(ctx, c.tcCommand(), "qdisc", "show", "dev", c.WAN)
	if err != nil {
		return tcInspection{}, fmt.Errorf("inspect WAN qdisc: %w: %s", err, strings.TrimSpace(string(qdisc)))
	}
	qdiscText := normalizeQdisc(string(qdisc))
	inspection := tcInspection{
		qdisc:       qdiscText,
		hasANEBRoot: strings.Contains(qdiscText, "qdisc netem "+anebRootHandle+" root"),
		hasIngress:  strings.Contains(qdiscText, "qdisc ingress "),
		hasClsact:   strings.Contains(qdiscText, "qdisc clsact "),
	}
	filters, filterErr := c.run(ctx, c.tcCommand(), "filter", "show", "dev", c.WAN, "parent", "ffff:")
	if filterErr != nil && !isMissingParent(string(filters)) {
		return tcInspection{}, fmt.Errorf("inspect WAN ingress filters: %w: %s", filterErr, strings.TrimSpace(string(filters)))
	}
	filterText := strings.TrimSpace(string(filters))
	if filterText != "" && filterText != "[]" {
		inspection.hasAnyFilter = true
		if ownershipErr := validateOwnedIngressFilter(filterText, c.IFB); ownershipErr != nil {
			inspection.hasForeignFilter = true
			inspection.foreignFilterReason = ownershipErr.Error()
		} else {
			inspection.hasOwnedFilter = true
		}
	}
	ifb, ifbErr := c.run(ctx, c.ipCommand(), "-d", "-j", "link", "show", "dev", c.IFB)
	if ifbErr == nil {
		var links []struct {
			IfName   string `json:"ifname"`
			IfAlias  string `json:"ifalias"`
			LinkInfo struct {
				InfoKind string `json:"info_kind"`
			} `json:"linkinfo"`
		}
		if err := json.Unmarshal(ifb, &links); err != nil || len(links) != 1 || links[0].IfName != c.IFB {
			return tcInspection{}, fmt.Errorf("inspect IFB returned an ambiguous link contract: %s", strings.TrimSpace(string(ifb)))
		}
		inspection.ifbExists = true
		inspection.ifbOwned = links[0].IfAlias == anebIFBAlias && links[0].LinkInfo.InfoKind == "ifb"
	} else if !isMissingInterface(string(ifb)) {
		return tcInspection{}, fmt.Errorf("inspect IFB: %w: %s", ifbErr, strings.TrimSpace(string(ifb)))
	}
	return inspection, nil
}

func (c TCController) cleanBaseline(ctx context.Context) (string, error) {
	inspection, err := c.inspect(ctx)
	if err != nil {
		return "", err
	}
	if inspection.hasANEBRoot || inspection.hasIngress || inspection.hasClsact || inspection.hasAnyFilter || inspection.ifbExists {
		return "", fmt.Errorf("traffic-control resources are not clean")
	}
	if err := validateSafeBaseline(inspection.qdisc); err != nil {
		return "", err
	}
	return normalizeQdisc(inspection.qdisc), nil
}

func validateSafeBaseline(qdisc string) error {
	_, err := parseRestorableBaseline(qdisc)
	return err
}

func validateOwnedIngressFilter(value, expectedIFB string) error {
	headerPattern := regexp.MustCompile(`^filter protocol all pref ` + regexp.QuoteMeta(anebFilterPref) + ` u32(?: chain 0)?(?: |$)`)
	prefPattern := regexp.MustCompile(`\bpref\s+(\d+)\b`)
	actionPattern := regexp.MustCompile(`^action order [0-9]+: mirred \(Egress Redirect to device ` + regexp.QuoteMeta(expectedIFB) + `\) stolen$`)
	headerCount := 0
	terminalCount := 0
	matchCount := 0
	actionCount := 0
	for _, rawLine := range strings.Split(value, "\n") {
		line := strings.TrimSpace(rawLine)
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "filter ") {
			headerCount++
			if !headerPattern.MatchString(line) {
				return fmt.Errorf("filter header is not exclusively ANEB pref=%s protocol=all kind=u32", anebFilterPref)
			}
			if strings.Contains(" "+line+" ", " terminal ") {
				terminalCount++
			}
			continue
		}
		if strings.HasPrefix(line, "match ") {
			matchCount++
			if line != "match 00000000/00000000 at 0" {
				return fmt.Errorf("u32 selector is not the ANEB match-all selector")
			}
			continue
		}
		if strings.HasPrefix(line, "action ") {
			actionCount++
			if !actionPattern.MatchString(line) {
				return fmt.Errorf("filter action is not mirred egress redirect to %s", expectedIFB)
			}
		}
	}
	prefs := prefPattern.FindAllStringSubmatch(value, -1)
	if headerCount == 0 || len(prefs) != headerCount {
		return fmt.Errorf("filter has an ambiguous preference contract")
	}
	for _, match := range prefs {
		if len(match) != 2 || match[1] != anebFilterPref {
			return fmt.Errorf("filter uses a foreign preference")
		}
	}
	if terminalCount != 1 || matchCount != 1 || actionCount != 1 {
		return fmt.Errorf("expected one terminal match-all selector and one redirect action; got terminal=%d match=%d action=%d", terminalCount, matchCount, actionCount)
	}
	return nil
}

func rootQdiscLine(value string) string {
	for _, line := range strings.Split(normalizeQdisc(value), "\n") {
		if strings.Contains(line, " root") {
			return line
		}
	}
	return ""
}

func isMissingParent(value string) bool {
	lower := strings.ToLower(value)
	return strings.Contains(lower, "parent qdisc") || strings.Contains(lower, "no such file")
}

func isMissingInterface(value string) bool {
	lower := strings.ToLower(value)
	return strings.Contains(lower, "does not exist") || strings.Contains(lower, "cannot find device") || strings.Contains(lower, "device not found")
}

func (c TCController) run(ctx context.Context, name string, args ...string) ([]byte, error) {
	commandCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return c.Executor.Run(commandCtx, name, args...)
}

func (c TCController) ipCommand() string {
	if c.IPCommand != "" {
		return c.IPCommand
	}
	return "ip"
}

func (c TCController) tcCommand() string {
	if c.TCCommand != "" {
		return c.TCCommand
	}
	return "tc"
}
