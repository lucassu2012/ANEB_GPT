package gateway

import (
	"context"
	"net"
	"strings"
	"testing"
)

type routeOutputExecutor struct {
	output string
	args   []string
}

func (e *routeOutputExecutor) Run(_ context.Context, _ string, args ...string) ([]byte, error) {
	e.args = append([]string(nil), args...)
	return []byte(e.output), nil
}

func validRuntimeTopology() (Attestation, RuntimeTopology) {
	attestation := Attestation{
		ContractVersion:       AttestationContractVersion,
		DedicatedGateway:      true,
		WANInterface:          "eth0",
		ManagementInterface:   "eth1",
		ExclusiveClientSubnet: "192.168.77.0/24",
	}
	return attestation, RuntimeTopology{
		WAN:                        InterfaceTopology{Exists: true, Up: true, Addresses: []net.IP{net.ParseIP("203.0.113.10")}},
		Management:                 InterfaceTopology{Exists: true, Up: true, Addresses: []net.IP{net.ParseIP("192.168.77.1")}},
		IPv4Forwarding:             true,
		DefaultRouteDevices:        []string{"eth0"},
		ClientPrefixRouteDevices:   []string{"eth1"},
		ForwardedPublicRouteDevice: "eth0",
		ManagementReturnDevice:     "eth1",
	}
}

func TestRuntimeTopologyAcceptsDedicatedTwoInterfaceGateway(t *testing.T) {
	attestation, topology := validRuntimeTopology()
	network, err := ValidateRuntimeTopology(attestation, "192.168.77.1:9444", topology)
	if err != nil {
		t.Fatal(err)
	}
	if network.String() != "192.168.77.0/24" {
		t.Fatalf("network=%s", network)
	}
}

func TestRuntimeTopologyRejectsUnsafeBindings(t *testing.T) {
	tests := []struct {
		name   string
		listen string
		mutate func(*Attestation, *RuntimeTopology)
		want   string
	}{
		{"wildcard", "0.0.0.0:9444", func(*Attestation, *RuntimeTopology) {}, "concrete private"},
		{"listen on wan", "192.168.77.1:9444", func(_ *Attestation, topology *RuntimeTopology) {
			topology.WAN.Addresses = append(topology.WAN.Addresses, net.ParseIP("192.168.77.1"))
		}, "also assigned to WAN"},
		{"wrong default route", "192.168.77.1:9444", func(_ *Attestation, topology *RuntimeTopology) {
			topology.ForwardedPublicRouteDevice = "eth9"
		}, "forwarded client public"},
		{"host route cannot spoof default", "192.168.77.1:9444", func(_ *Attestation, topology *RuntimeTopology) {
			topology.DefaultRouteDevices = []string{"eth9"}
			topology.ForwardedPublicRouteDevice = "eth0"
		}, "unique default IPv4 route"},
		{"multiple defaults rejected", "192.168.77.1:9444", func(_ *Attestation, topology *RuntimeTopology) {
			topology.DefaultRouteDevices = []string{"eth0", "eth9"}
		}, "unique default IPv4 route"},
		{"forwarding disabled", "192.168.77.1:9444", func(_ *Attestation, topology *RuntimeTopology) {
			topology.IPv4Forwarding = false
		}, "forwarding is disabled"},
		{"wrong client route", "192.168.77.1:9444", func(_ *Attestation, topology *RuntimeTopology) {
			topology.ManagementReturnDevice = "eth0"
		}, "management return traffic"},
		{"client host route cannot spoof prefix", "192.168.77.1:9444", func(_ *Attestation, topology *RuntimeTopology) {
			topology.ClientPrefixRouteDevices = []string{"eth9"}
			topology.ManagementReturnDevice = "eth1"
		}, "exact exclusive client prefix"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			attestation, topology := validRuntimeTopology()
			test.mutate(&attestation, &topology)
			_, err := ValidateRuntimeTopology(attestation, test.listen, topology)
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("error=%v, want %q", err, test.want)
			}
		})
	}
}

func TestClientProbeAddressUsesFirstClientSlot(t *testing.T) {
	probe, err := clientProbeAddress("10.77.0.0/30")
	if err != nil {
		t.Fatal(err)
	}
	if probe.String() != "10.77.0.2" {
		t.Fatalf("probe=%s", probe)
	}
}

func TestRouteListingRequiresOneDevicePerRouteAndPreservesMultiplicity(t *testing.T) {
	executor := &routeOutputExecutor{
		output: "default via 203.0.113.1 dev eth0 metric 10\ndefault via 203.0.113.2 dev eth1 metric 20\n",
	}
	devices, err := routeListingDevices(context.Background(), executor, "/usr/sbin/ip", "default")
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 2 || devices[0] != "eth0" || devices[1] != "eth1" {
		t.Fatalf("devices=%v", devices)
	}
	if _, err := routeListingDevices(context.Background(), &routeOutputExecutor{
		output: "default nexthop via 203.0.113.1 dev eth0 nexthop via 203.0.113.2 dev eth1\n",
	}, "/usr/sbin/ip", "default"); err == nil {
		t.Fatal("multipath route line was accepted")
	}
}

func TestEffectiveRouteUsesForwardingAndReturnFlowContext(t *testing.T) {
	forward := &routeOutputExecutor{output: "1.1.1.1 from 192.168.77.2 via 203.0.113.1 dev eth0\n    cache iif eth1"}
	device, err := effectiveRouteDevice(
		context.Background(), forward, "/usr/sbin/ip", "1.1.1.1", "192.168.77.2", "eth1",
	)
	if err != nil || device != "eth0" {
		t.Fatalf("forward route device=%q error=%v", device, err)
	}
	wantForward := "-4 route get 1.1.1.1 from 192.168.77.2 iif eth1"
	if strings.Join(forward.args, " ") != wantForward {
		t.Fatalf("forward route args=%q want=%q", strings.Join(forward.args, " "), wantForward)
	}

	returnRoute := &routeOutputExecutor{output: "192.168.77.2 from 192.168.77.1 dev eth1 src 192.168.77.1"}
	device, err = effectiveRouteDevice(
		context.Background(), returnRoute, "/usr/sbin/ip", "192.168.77.2", "192.168.77.1", "",
	)
	if err != nil || device != "eth1" {
		t.Fatalf("return route device=%q error=%v", device, err)
	}
	wantReturn := "-4 route get 192.168.77.2 from 192.168.77.1"
	if strings.Join(returnRoute.args, " ") != wantReturn {
		t.Fatalf("return route args=%q want=%q", strings.Join(returnRoute.args, " "), wantReturn)
	}
}

func TestEffectiveRouteRejectsPolicyTableAndVRF(t *testing.T) {
	for _, test := range []struct {
		name, output, want string
	}{
		{"policy table", "1.1.1.1 from 192.168.77.2 dev eth0 table 100", "non-main table"},
		{"VRF", "1.1.1.1 from 192.168.77.2 dev eth0 vrf blue", "VRF route"},
		{"ambiguous route", "1.1.1.1 dev eth0\n1.1.1.1 dev eth9", "exactly one result"},
	} {
		t.Run(test.name, func(t *testing.T) {
			executor := &routeOutputExecutor{output: test.output}
			_, err := effectiveRouteDevice(
				context.Background(), executor, "/usr/sbin/ip", "1.1.1.1", "192.168.77.2", "eth1",
			)
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("error=%v want=%q", err, test.want)
			}
		})
	}
}

func TestStandardIPv4PolicyRulesRejectsSourceFwmarkAndVRFRules(t *testing.T) {
	standard := "0: from all lookup local\n32766: from all lookup main\n32767: from all lookup default\n"
	if err := requireStandardIPv4PolicyRules(context.Background(), &routeOutputExecutor{output: standard}, "/usr/sbin/ip"); err != nil {
		t.Fatalf("standard rules rejected: %v", err)
	}
	for _, extra := range []string{
		"100: from 192.168.77.0/24 lookup 100\n",
		"100: from all fwmark 0x1 lookup 100\n",
		"1000: from all lookup [l3mdev-table]\n",
	} {
		executor := &routeOutputExecutor{output: standard + extra}
		if err := requireStandardIPv4PolicyRules(context.Background(), executor, "/usr/sbin/ip"); err == nil {
			t.Fatalf("non-standard rule was accepted: %s", strings.TrimSpace(extra))
		}
	}
}

func TestManagementMainRoutesAreDedicatedToExactClientSubnet(t *testing.T) {
	standard := "default via 203.0.113.1 dev eth0\n" +
		"203.0.113.0/24 dev eth0 proto kernel scope link src 203.0.113.10\n" +
		"192.168.77.0/24 dev eth1 proto kernel scope link src 192.168.77.1\n"
	executor := &routeOutputExecutor{output: standard}
	if err := requireIsolatedManagementRoutes(
		context.Background(), executor, "/usr/sbin/ip", "eth0", "eth1", "192.168.77.0/24",
	); err != nil {
		t.Fatalf("isolated main table rejected: %v", err)
	}
	wantArgs := "-4 route show table main type unicast"
	if strings.Join(executor.args, " ") != wantArgs {
		t.Fatalf("route inspection args=%q want=%q", strings.Join(executor.args, " "), wantArgs)
	}

	for _, test := range []struct {
		name, extra, want string
	}{
		{
			name:  "actual handset host route bypasses management",
			extra: "192.168.77.123/32 dev eth0\n",
			want:  "more-specific main route",
		},
		{
			name:  "special public server bypasses WAN",
			extra: "198.51.100.41/32 dev eth1\n",
			want:  "management interface has a non-client main route",
		},
		{
			name:  "management default bypasses WAN",
			extra: "default via 192.168.77.254 dev eth1 metric 5\n",
			want:  "management interface has a non-client main route",
		},
		{
			name:  "specific server bypasses WAN through tunnel",
			extra: "120.79.148.41/32 dev wg0\n",
			want:  "non-WAN forwarding interface",
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			executor := &routeOutputExecutor{output: standard + test.extra}
			err := requireIsolatedManagementRoutes(
				context.Background(), executor, "/usr/sbin/ip", "eth0", "eth1", "192.168.77.0/24",
			)
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("error=%v want=%q", err, test.want)
			}
		})
	}
}
