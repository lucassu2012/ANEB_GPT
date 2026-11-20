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
