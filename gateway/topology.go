package gateway

import (
	"context"
	"fmt"
	"net"
	"os"
	"strings"
)

type InterfaceTopology struct {
	Exists    bool
	Up        bool
	Addresses []net.IP
}

type RuntimeTopology struct {
	WAN                        InterfaceTopology
	Management                 InterfaceTopology
	IPv4Forwarding             bool
	DefaultRouteDevices        []string
	ClientPrefixRouteDevices   []string
	ForwardedPublicRouteDevice string
	ManagementReturnDevice     string
}

func CaptureRuntimeTopology(
	ctx context.Context,
	attestation Attestation,
	executor CommandExecutor,
	ipCommand string,
	listen string,
) (RuntimeTopology, error) {
	if executor == nil || ipCommand == "" {
		return RuntimeTopology{}, fmt.Errorf("topology command executor and absolute ip command are required")
	}
	wan, err := captureInterface(attestation.WANInterface)
	if err != nil {
		return RuntimeTopology{}, err
	}
	management, err := captureInterface(attestation.ManagementInterface)
	if err != nil {
		return RuntimeTopology{}, err
	}
	forwardingRaw, err := os.ReadFile("/proc/sys/net/ipv4/ip_forward")
	if err != nil {
		return RuntimeTopology{}, fmt.Errorf("read IPv4 forwarding state: %w", err)
	}
	defaultRoutes, err := routeListingDevices(ctx, executor, ipCommand, "default")
	if err != nil {
		return RuntimeTopology{}, fmt.Errorf("inspect default IPv4 route: %w", err)
	}
	clientPrefixRoutes, err := routeListingDevices(ctx, executor, ipCommand, attestation.ExclusiveClientSubnet)
	if err != nil {
		return RuntimeTopology{}, fmt.Errorf("inspect exclusive client prefix route: %w", err)
	}
	clientProbe, err := clientProbeAddress(attestation.ExclusiveClientSubnet)
	if err != nil {
		return RuntimeTopology{}, err
	}
	listenIP, err := managementListenIP(listen)
	if err != nil {
		return RuntimeTopology{}, err
	}
	if err := requireStandardIPv4PolicyRules(ctx, executor, ipCommand); err != nil {
		return RuntimeTopology{}, fmt.Errorf("reject non-standard IPv4 policy routing: %w", err)
	}
	if err := requireIsolatedManagementRoutes(
		ctx, executor, ipCommand, attestation.WANInterface, attestation.ManagementInterface, attestation.ExclusiveClientSubnet,
	); err != nil {
		return RuntimeTopology{}, fmt.Errorf("reject non-isolated management routing: %w", err)
	}
	publicDevice, err := effectiveRouteDevice(
		ctx, executor, ipCommand, "1.1.1.1", clientProbe.String(), attestation.ManagementInterface,
	)
	if err != nil {
		return RuntimeTopology{}, fmt.Errorf("resolve forwarded client public IPv4 route: %w", err)
	}
	managementReturnDevice, err := effectiveRouteDevice(
		ctx, executor, ipCommand, clientProbe.String(), listenIP.String(), "",
	)
	if err != nil {
		return RuntimeTopology{}, fmt.Errorf("resolve management return route: %w", err)
	}
	return RuntimeTopology{
		WAN: wan, Management: management,
		IPv4Forwarding:             strings.TrimSpace(string(forwardingRaw)) == "1",
		DefaultRouteDevices:        defaultRoutes,
		ClientPrefixRouteDevices:   clientPrefixRoutes,
		ForwardedPublicRouteDevice: publicDevice,
		ManagementReturnDevice:     managementReturnDevice,
	}, nil
}

func ValidateRuntimeTopology(attestation Attestation, listen string, topology RuntimeTopology) (*net.IPNet, error) {
	listenIP, err := managementListenIP(listen)
	if err != nil {
		return nil, err
	}
	_, clientNetwork, err := net.ParseCIDR(attestation.ExclusiveClientSubnet)
	if err != nil || clientNetwork == nil || !clientNetwork.Contains(listenIP) {
		return nil, fmt.Errorf("management listener is outside the exclusive client subnet")
	}
	if !topology.WAN.Exists || !topology.WAN.Up {
		return nil, fmt.Errorf("attested WAN interface is missing or down")
	}
	if !topology.Management.Exists || !topology.Management.Up {
		return nil, fmt.Errorf("attested management interface is missing or down")
	}
	if attestation.WANInterface == attestation.ManagementInterface {
		return nil, fmt.Errorf("WAN and management interfaces must be distinct")
	}
	if !containsIP(topology.Management.Addresses, listenIP) {
		return nil, fmt.Errorf("management listen IP is not assigned to the attested management interface")
	}
	if containsIP(topology.WAN.Addresses, listenIP) {
		return nil, fmt.Errorf("management listen IP is also assigned to WAN")
	}
	if !topology.IPv4Forwarding {
		return nil, fmt.Errorf("IPv4 forwarding is disabled")
	}
	if len(topology.DefaultRouteDevices) != 1 || topology.DefaultRouteDevices[0] != attestation.WANInterface {
		return nil, fmt.Errorf("the unique default IPv4 route does not use the attested WAN interface")
	}
	if topology.ForwardedPublicRouteDevice != attestation.WANInterface {
		return nil, fmt.Errorf("forwarded client public IPv4 route does not use the attested WAN interface")
	}
	if len(topology.ClientPrefixRouteDevices) != 1 || topology.ClientPrefixRouteDevices[0] != attestation.ManagementInterface {
		return nil, fmt.Errorf("the exact exclusive client prefix route does not use the attested management interface")
	}
	if topology.ManagementReturnDevice != attestation.ManagementInterface {
		return nil, fmt.Errorf("management return traffic is not routed through the attested management interface")
	}
	return clientNetwork, nil
}

func captureInterface(name string) (InterfaceTopology, error) {
	iface, err := net.InterfaceByName(name)
	if err != nil {
		return InterfaceTopology{}, fmt.Errorf("inspect interface %s: %w", name, err)
	}
	addresses, err := iface.Addrs()
	if err != nil {
		return InterfaceTopology{}, fmt.Errorf("inspect addresses on %s: %w", name, err)
	}
	result := InterfaceTopology{Exists: true, Up: iface.Flags&net.FlagUp != 0}
	for _, address := range addresses {
		value := address.String()
		if ip, _, parseErr := net.ParseCIDR(value); parseErr == nil && ip != nil {
			result.Addresses = append(result.Addresses, ip)
		}
	}
	return result, nil
}

func effectiveRouteDevice(
	ctx context.Context,
	executor CommandExecutor,
	ipCommand, target, source, incomingInterface string,
) (string, error) {
	if net.ParseIP(target) == nil || net.ParseIP(source) == nil {
		return "", fmt.Errorf("route target and source must be literal IP addresses")
	}
	args := []string{"-4", "route", "get", target, "from", source}
	if incomingInterface != "" {
		if !interfaceNamePattern.MatchString(incomingInterface) || incomingInterface == "lo" {
			return "", fmt.Errorf("route incoming interface is invalid")
		}
		args = append(args, "iif", incomingInterface)
	}
	output, err := executor.Run(ctx, ipCommand, args...)
	if err != nil {
		return "", fmt.Errorf("ip route get %s from %s: %w: %s", target, source, err, strings.TrimSpace(string(output)))
	}
	lines := nonEmptyLines(string(output))
	primaryLines := make([]string, 0, 1)
	for _, line := range lines {
		if line == "cache" || strings.HasPrefix(line, "cache ") {
			continue
		}
		primaryLines = append(primaryLines, line)
	}
	if len(primaryLines) != 1 {
		return "", fmt.Errorf("route output must contain exactly one result: %s", strings.TrimSpace(string(output)))
	}
	fields := strings.Fields(primaryLines[0])
	device := ""
	table := "main"
	for index := 0; index+1 < len(fields); index++ {
		switch fields[index] {
		case "dev":
			if device != "" || !interfaceNamePattern.MatchString(fields[index+1]) {
				return "", fmt.Errorf("route output has an ambiguous device: %s", strings.TrimSpace(string(output)))
			}
			device = fields[index+1]
		case "table":
			table = fields[index+1]
		case "vrf":
			return "", fmt.Errorf("VRF route is outside the dedicated gateway contract")
		}
	}
	if table != "main" && table != "254" {
		return "", fmt.Errorf("policy route selected non-main table %s", table)
	}
	if device == "" {
		return "", fmt.Errorf("route output has no valid device: %s", strings.TrimSpace(string(output)))
	}
	return device, nil
}

func requireStandardIPv4PolicyRules(ctx context.Context, executor CommandExecutor, ipCommand string) error {
	output, err := executor.Run(ctx, ipCommand, "-4", "rule", "show")
	if err != nil {
		return fmt.Errorf("ip rule show: %w: %s", err, strings.TrimSpace(string(output)))
	}
	want := map[string]string{"0": "local", "32766": "main", "32767": "default"}
	seen := make(map[string]bool, len(want))
	for _, line := range nonEmptyLines(string(output)) {
		fields := strings.Fields(line)
		if len(fields) != 5 || fields[1] != "from" || fields[2] != "all" || fields[3] != "lookup" {
			return fmt.Errorf("non-standard IPv4 policy rule: %s", line)
		}
		priority := strings.TrimSuffix(fields[0], ":")
		if want[priority] != fields[4] || seen[priority] {
			return fmt.Errorf("non-standard IPv4 policy rule: %s", line)
		}
		seen[priority] = true
	}
	if len(seen) != len(want) {
		return fmt.Errorf("the standard local/main/default IPv4 rules are incomplete")
	}
	return nil
}

func requireIsolatedManagementRoutes(
	ctx context.Context,
	executor CommandExecutor,
	ipCommand, wanInterface, managementInterface, clientSubnet string,
) error {
	_, clientNetwork, err := net.ParseCIDR(clientSubnet)
	if err != nil || clientNetwork == nil || clientNetwork.IP.To4() == nil {
		return fmt.Errorf("exclusive client subnet is invalid")
	}
	clientPrefix, _ := clientNetwork.Mask.Size()
	output, err := executor.Run(ctx, ipCommand, "-4", "route", "show", "table", "main", "type", "unicast")
	if err != nil {
		return fmt.Errorf("ip route show table main: %w: %s", err, strings.TrimSpace(string(output)))
	}
	for _, line := range nonEmptyLines(string(output)) {
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		destinationIndex := 0
		if fields[0] == "unicast" {
			destinationIndex = 1
		}
		if destinationIndex >= len(fields) {
			return fmt.Errorf("main route has no destination: %s", line)
		}
		destination := fields[destinationIndex]
		var routeNetwork *net.IPNet
		if destination != "default" {
			if strings.Contains(destination, "/") {
				_, routeNetwork, err = net.ParseCIDR(destination)
			} else if ip := net.ParseIP(destination); ip != nil && ip.To4() != nil {
				routeNetwork = &net.IPNet{IP: ip.To4(), Mask: net.CIDRMask(32, 32)}
			} else {
				return fmt.Errorf("main route has a non-IPv4 destination: %s", line)
			}
			if routeNetwork == nil || routeNetwork.IP.To4() == nil {
				return fmt.Errorf("main route has an invalid IPv4 destination: %s", line)
			}
		}
		device := ""
		for index := 0; index+1 < len(fields); index++ {
			if fields[index] == "dev" {
				if device != "" || !interfaceNamePattern.MatchString(fields[index+1]) {
					return fmt.Errorf("main route has an ambiguous device: %s", line)
				}
				device = fields[index+1]
			}
		}
		if routeNetwork != nil && clientNetwork.Contains(routeNetwork.IP) {
			routePrefix, _ := routeNetwork.Mask.Size()
			if routePrefix > clientPrefix {
				return fmt.Errorf("client subnet contains a more-specific main route: %s", line)
			}
		}
		if routeNetwork != nil && routeNetwork.String() == clientNetwork.String() {
			if device != managementInterface {
				return fmt.Errorf("exact client main route does not use the management interface: %s", line)
			}
			continue
		}
		if device == managementInterface {
			return fmt.Errorf("management interface has a non-client main route: %s", line)
		}
		if device != wanInterface {
			return fmt.Errorf("non-client main route uses a non-WAN forwarding interface: %s", line)
		}
	}
	return nil
}

func nonEmptyLines(value string) []string {
	var lines []string
	for _, line := range strings.Split(strings.TrimSpace(value), "\n") {
		if normalized := strings.Join(strings.Fields(line), " "); normalized != "" {
			lines = append(lines, normalized)
		}
	}
	return lines
}

func managementListenIP(listen string) (net.IP, error) {
	host, _, err := net.SplitHostPort(listen)
	if err != nil {
		return nil, fmt.Errorf("management listen address must be an IP and port: %w", err)
	}
	listenIP := net.ParseIP(host)
	if listenIP == nil || listenIP.To4() == nil || listenIP.IsLoopback() || listenIP.IsUnspecified() || !listenIP.IsPrivate() {
		return nil, fmt.Errorf("management listener must use a concrete private IPv4 address")
	}
	return listenIP.To4(), nil
}

func routeListingDevices(ctx context.Context, executor CommandExecutor, ipCommand, target string) ([]string, error) {
	args := []string{"-4", "route", "show"}
	if target == "default" {
		args = append(args, "default")
	} else {
		args = append(args, "exact", target)
	}
	output, err := executor.Run(ctx, ipCommand, args...)
	if err != nil {
		return nil, fmt.Errorf("ip route show %s: %w: %s", target, err, strings.TrimSpace(string(output)))
	}
	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	devices := make([]string, 0, len(lines))
	for _, line := range lines {
		if strings.TrimSpace(line) == "" {
			continue
		}
		fields := strings.Fields(line)
		lineDevices := make([]string, 0, 1)
		for index := 0; index+1 < len(fields); index++ {
			if fields[index] == "dev" && interfaceNamePattern.MatchString(fields[index+1]) {
				lineDevices = append(lineDevices, fields[index+1])
			}
		}
		if len(lineDevices) != 1 {
			return nil, fmt.Errorf("route line must contain exactly one valid device: %s", strings.TrimSpace(line))
		}
		devices = append(devices, lineDevices[0])
	}
	if len(devices) == 0 {
		return nil, fmt.Errorf("route listing is empty")
	}
	return devices, nil
}

func clientProbeAddress(cidr string) (net.IP, error) {
	_, network, err := net.ParseCIDR(cidr)
	if err != nil || network == nil || network.IP.To4() == nil {
		return nil, fmt.Errorf("exclusive client subnet is invalid")
	}
	probe := append(net.IP(nil), network.IP.To4()...)
	probe[3] += 2
	if !network.Contains(probe) {
		return nil, fmt.Errorf("exclusive client subnet has no probe address")
	}
	return probe, nil
}

func containsIP(values []net.IP, expected net.IP) bool {
	for _, value := range values {
		if value.Equal(expected) {
			return true
		}
	}
	return false
}
