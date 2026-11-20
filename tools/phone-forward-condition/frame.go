package condition

import (
	"encoding/binary"
	"errors"
	"net/netip"
)

func (a Address) IsSniffed() bool { return a.Word&0x10000 != 0 }

func (p Plan) CheckFrame(mode Mode, frame Frame) error {
	if mode != Inspect && mode != Limit {
		return errors.New("unsupported capture mode")
	}
	if frame.Address.Word&0xffff != 1 || binary.LittleEndian.Uint32(frame.Address.Data[:4]) != p.Config.InterfaceIndex {
		return errors.New("packet not from expected NETWORK_FORWARD interface/event")
	}
	if frame.Address.IsSniffed() != (mode == Inspect) {
		return errors.New("sniff copy/owned packet mode mismatch")
	}
	packet := frame.Packet
	if len(packet) < 20 || len(packet) > 65575 {
		return errors.New("incomplete or oversized IP frame")
	}
	var destination netip.Addr
	switch packet[0] >> 4 {
	case 4:
		ihl := int(packet[0]&15) * 4
		if ihl < 20 || ihl > len(packet) || int(binary.BigEndian.Uint16(packet[2:4])) != len(packet) || frame.Address.Word&0x100000 != 0 {
			return errors.New("invalid IPv4 frame length or family metadata")
		}
		destination = netip.AddrFrom4([4]byte(packet[16:20]))
	case 6:
		if len(packet) < 40 || 40+int(binary.BigEndian.Uint16(packet[4:6])) != len(packet) || frame.Address.Word&0x100000 == 0 {
			return errors.New("invalid IPv6 frame length or family metadata")
		}
		destination = netip.AddrFrom16([16]byte(packet[24:40]))
	default:
		return errors.New("unsupported IP version")
	}
	if _, ok := p.destinations[destination]; !ok {
		return errors.New("packet destination not in the configured phone address set")
	}
	return nil
}
