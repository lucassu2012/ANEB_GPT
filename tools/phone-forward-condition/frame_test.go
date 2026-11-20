package condition

import (
	"encoding/binary"
	"testing"
	"time"
)

func testForwardFrame(size int) Frame {
	p := make([]byte, size)
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:4], uint16(size))
	copy(p[16:20], []byte{192, 0, 2, 8})
	a := Address{TimestampQPC: 123, Word: 1}
	binary.LittleEndian.PutUint32(a.Data[0:4], 12)
	a.Data[63] = 0xa5
	return Frame{Packet: p, Address: a}
}

func TestFrameScopeRejectsOtherDestinationsInterfacesLayersAndSniffModes(t *testing.T) {
	plan, _ := NewPlan(Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000})
	if err := plan.CheckFrame(Limit, testForwardFrame(20)); err != nil {
		t.Fatal(err)
	}
	cases := map[string]func(*Frame){
		"other_phone":          func(f *Frame) { f.Packet[19] = 9 },
		"upstream_source_only": func(f *Frame) { copy(f.Packet[12:16], f.Packet[16:20]); copy(f.Packet[16:20], []byte{198, 51, 100, 1}) },
		"other_interface":      func(f *Frame) { binary.LittleEndian.PutUint32(f.Address.Data[:4], 13) },
		"host_layer":           func(f *Frame) { f.Address.Word = 0 },
		"non_packet_event":     func(f *Frame) { f.Address.Word = 1 | 1<<8 },
		"copy_not_owned":       func(f *Frame) { f.Address.Word |= 0x10000 },
		"truncated_ip":         func(f *Frame) { f.Packet = f.Packet[:19] },
		"wrong_ip_length":      func(f *Frame) { f.Packet[3] = 21 },
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			f := testForwardFrame(20)
			change(&f)
			if err := plan.CheckFrame(Limit, f); err == nil {
				t.Fatal("out-of-scope or copy packet accepted for pacing")
			}
		})
	}
	copyFrame := testForwardFrame(20)
	copyFrame.Address.Word |= 0x10000
	if err := plan.CheckFrame(Inspect, copyFrame); err != nil {
		t.Fatal(err)
	}
	if err := plan.CheckFrame(Inspect, testForwardFrame(20)); err == nil {
		t.Fatal("blocking capture was called a nonblocking sniff")
	}
}

func testUplinkForwardFrame(size int, ifIdx uint32, outbound bool) Frame {
	p := make([]byte, size)
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:4], uint16(size))
	copy(p[12:16], []byte{192, 0, 2, 8})
	copy(p[16:20], []byte{198, 51, 100, 1})
	word := uint32(1)
	if outbound {
		word |= 0x20000
	}
	a := Address{TimestampQPC: 456, Word: word}
	binary.LittleEndian.PutUint32(a.Data[0:4], ifIdx)
	return Frame{Packet: p, Address: a}
}

func TestClassifyFrameDirectionalAcceptanceAndGuardrails(t *testing.T) {
	plan, err := NewPlan(Config{
		PhoneAddresses:         []string{"192.0.2.8"},
		InterfaceIndex:         16,
		UplinkInterfaceIndex:   12,
		TTL:                    time.Second,
		DownlinkBytesPerSecond: 1000,
		UplinkBytesPerSecond:   1000,
	})
	if err != nil {
		t.Fatal(err)
	}
	down := testForwardFrame(20)
	binary.LittleEndian.PutUint32(down.Address.Data[0:4], 16)
	down.Address.Word |= 0x20000 // Outbound=1 must be accepted for downlink too
	dir, err := plan.ClassifyFrame(Limit, down)
	if err != nil || dir != DirectionDownlink {
		t.Fatalf("downlink frame misclassified: dir=%s err=%v", dir, err)
	}

	for _, outbound := range []bool{false, true} {
		up := testUplinkForwardFrame(20, 12, outbound)
		dir, err := plan.ClassifyFrame(Limit, up)
		if err != nil || dir != DirectionUplink {
			t.Fatalf("uplink frame (outbound=%t) misclassified: dir=%s err=%v", outbound, dir, err)
		}
	}

	// Swapped interfaces must fail closed.
	swappedDown := testForwardFrame(20)
	binary.LittleEndian.PutUint32(swappedDown.Address.Data[0:4], 12)
	if dir, err := plan.ClassifyFrame(Limit, swappedDown); err == nil || dir != DirectionUnknown {
		t.Fatalf("downlink frame on uplink ifIdx accepted: dir=%s err=%v", dir, err)
	}
	swappedUp := testUplinkForwardFrame(20, 16, true)
	if dir, err := plan.ClassifyFrame(Limit, swappedUp); err == nil || dir != DirectionUnknown {
		t.Fatalf("uplink frame on AP ifIdx accepted: dir=%s err=%v", dir, err)
	}

	// Hairpin src == dst == phoneIp must fail closed.
	hairpin := testUplinkForwardFrame(20, 16, true)
	copy(hairpin.Packet[16:20], []byte{192, 0, 2, 8})
	if dir, err := plan.ClassifyFrame(Limit, hairpin); err == nil || dir != DirectionUnknown {
		t.Fatalf("hairpin phone-to-phone frame accepted: dir=%s err=%v", dir, err)
	}

	// Post-NAT uplink matching when UplinkNatAddresses is configured.
	natPlan, err := NewPlan(Config{
		PhoneAddresses:         []string{"192.168.137.113"},
		InterfaceIndex:         16,
		UplinkInterfaceIndex:   12,
		TTL:                    30 * time.Second,
		DownlinkBytesPerSecond: 250000,
		UplinkBytesPerSecond:   62500,
		UplinkNatAddresses:     []string{"10.10.8.9"},
	})
	if err != nil {
		t.Fatal(err)
	}
	postNatUp := testUplinkForwardFrame(20, 12, true)
	copy(postNatUp.Packet[12:16], []byte{10, 10, 8, 9})
	if dir, err := natPlan.ClassifyFrame(Limit, postNatUp); err != nil || dir != DirectionUplink {
		t.Fatalf("post-NAT uplink frame misclassified: dir=%s err=%v", dir, err)
	}
	preNatUp := testUplinkForwardFrame(20, 12, true)
	copy(preNatUp.Packet[12:16], []byte{192, 168, 137, 113})
	if dir, err := natPlan.ClassifyFrame(Limit, preNatUp); err == nil || dir != DirectionUnknown {
		t.Fatalf("pre-NAT frame accepted when UplinkNatAddresses is set: dir=%s err=%v", dir, err)
	}
}
