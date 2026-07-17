package gateway

import (
	"context"
	"fmt"
	"os/exec"
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
	WAN      string
	IFB      string
	Executor CommandExecutor
}

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
	commands := [][]string{
		{"ip", "link", "add", c.IFB, "type", "ifb"},
		{"ip", "link", "set", "dev", c.IFB, "up"},
		{"tc", "qdisc", "replace", "dev", c.WAN, "handle", "ffff:", "ingress"},
		{"tc", "filter", "replace", "dev", c.WAN, "parent", "ffff:", "protocol", "all", "u32", "match", "u32", "0", "0", "action", "mirred", "egress", "redirect", "dev", c.IFB},
		append([]string{"tc", "qdisc", "replace", "dev", c.WAN, "root", "handle", "1:", "netem"}, netemArgs(profile.Uplink)...),
		append([]string{"tc", "qdisc", "replace", "dev", c.IFB, "root", "handle", "1:", "netem"}, netemArgs(profile.Downlink)...),
	}
	for _, command := range commands {
		commandCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		output, err := c.Executor.Run(commandCtx, command[0], command[1:]...)
		cancel()
		if err != nil {
			_ = c.Clear(context.Background())
			return fmt.Errorf("%s failed: %w: %s", strings.Join(command, " "), err, strings.TrimSpace(string(output)))
		}
	}
	return nil
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
	commands := [][]string{
		{"tc", "qdisc", "del", "dev", c.WAN, "root"},
		{"tc", "qdisc", "del", "dev", c.WAN, "ingress"},
		{"tc", "qdisc", "del", "dev", c.IFB, "root"},
		{"ip", "link", "set", "dev", c.IFB, "down"},
		{"ip", "link", "del", c.IFB},
	}
	for _, command := range commands {
		commandCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		_, _ = c.Executor.Run(commandCtx, command[0], command[1:]...)
		cancel()
	}
	verifyCtx, cancelVerify := context.WithTimeout(ctx, 5*time.Second)
	defer cancelVerify()
	qdiscs, err := c.Executor.Run(verifyCtx, "tc", "qdisc", "show", "dev", c.WAN)
	if err != nil {
		return fmt.Errorf("verify WAN qdisc cleanup: %w: %s", err, strings.TrimSpace(string(qdiscs)))
	}
	qdiscText := string(qdiscs)
	if strings.Contains(qdiscText, "netem") || strings.Contains(qdiscText, "ingress") {
		return fmt.Errorf("verify WAN qdisc cleanup: impairment still present: %s", strings.TrimSpace(qdiscText))
	}
	ifbOutput, ifbErr := c.Executor.Run(verifyCtx, "ip", "link", "show", "dev", c.IFB)
	if ifbErr == nil {
		return fmt.Errorf("verify IFB cleanup: %s still exists: %s", c.IFB, strings.TrimSpace(string(ifbOutput)))
	}
	return nil
}
