package condition

import (
	"encoding/binary"
	"errors"
	"net/netip"
)

type Direction string

const (
	DirectionUnknown  Direction = "unknown"
	DirectionDownlink Direction = "downlink"
	DirectionUplink   Direction = "uplink"
)

func (d Direction) String() string { return string(d) }

func (a Address) IsSniffed() bool { return a.Word&0x10000 != 0 }

func (p Plan) ClassifyFrame(mode Mode, frame Frame) (Direction, error) {
	if mode != Inspect && mode != Limit {
		return DirectionUnknown, errors.New("unsupported capture mode")
	}
	if frame.Address.Word&0xffff != 1 {
		return DirectionUnknown, errors.New("packet not from expected NETWORK_FORWARD event")
	}
	if frame.Address.IsSniffed() != (mode == Inspect) {
		return DirectionUnknown, errors.New("sniff copy/owned packet mode mismatch")
	}
	packet := frame.Packet
	if len(packet) < 20 || len(packet) > 65575 {
		return DirectionUnknown, errors.New("incomplete or oversized IP frame")
	}
	var source, destination netip.Addr
	switch packet[0] >> 4 {
	case 4:
		ihl := int(packet[0]&15) * 4
		if ihl < 20 || ihl > len(packet) || int(binary.BigEndian.Uint16(packet[2:4])) != len(packet) || frame.Address.Word&0x100000 != 0 {
			return DirectionUnknown, errors.New("invalid IPv4 frame length or family metadata")
		}
		source = netip.AddrFrom4([4]byte(packet[12:16]))
		destination = netip.AddrFrom4([4]byte(packet[16:20]))
	case 6:
		if len(packet) < 40 || 40+int(binary.BigEndian.Uint16(packet[4:6])) != len(packet) || frame.Address.Word&0x100000 == 0 {
			return DirectionUnknown, errors.New("invalid IPv6 frame length or family metadata")
		}
		source = netip.AddrFrom16([16]byte(packet[8:24]))
		destination = netip.AddrFrom16([16]byte(packet[24:40]))
	default:
		return DirectionUnknown, errors.New("unsupported IP version")
	}
	_, dstInPhones := p.phones[destination]
	_, srcInPhones := p.phones[source]
	_, srcInUplink := p.uplinkSources[source]
	if dstInPhones && (srcInPhones || srcInUplink) {
		return DirectionUnknown, errors.New("ambiguous phone-to-phone frame")
	}
	ifIdx := binary.LittleEndian.Uint32(frame.Address.Data[:4])
	if dstInPhones {
		if ifIdx != p.Config.InterfaceIndex {
			return DirectionUnknown, errors.New("downlink packet not from expected forwarding interface")
		}
		return DirectionDownlink, nil
	}
	if srcInUplink {
		if !p.Config.HasUplink() {
			return DirectionUnknown, errors.New("uplink packet received while uplink shaping is disabled")
		}
		if ifIdx != p.Config.UplinkInterfaceIndex {
			return DirectionUnknown, errors.New("uplink packet not from expected uplink interface")
		}
		return DirectionUplink, nil
	}
	if srcInPhones && !p.Config.HasUplink() {
		return DirectionUnknown, errors.New("uplink packet received while uplink shaping is disabled")
	}
	return DirectionUnknown, errors.New("packet source/destination not in the configured phone or uplink address set")
}

func (p Plan) CheckFrame(mode Mode, frame Frame) error {
	_, err := p.ClassifyFrame(mode, frame)
	return err
}
