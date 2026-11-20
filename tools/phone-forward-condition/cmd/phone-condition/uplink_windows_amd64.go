//go:build windows && amd64

package main

import (
	"errors"
	"fmt"
	"net/netip"
	"syscall"
	"unsafe"

	condition "aneb-phone-forward-condition"
)

const (
	winAFInet      = 2
	winAFInet6     = 23
	ifOperStatusUp = 1
)

type RouteProof struct {
	RemoteAddress      string `json:"remote_address"`
	BestInterfaceIndex uint32 `json:"best_interface_index"`
	NextHop            string `json:"next_hop,omitempty"`
}

type InterfaceUnicastSnapshot struct {
	InterfaceIndex uint32 `json:"interface_index"`
	InUcastOctets  uint64 `json:"in_ucast_octets"`
	InUcastPkts    uint64 `json:"in_ucast_pkts"`
	OutUcastOctets uint64 `json:"out_ucast_octets"`
	OutUcastPkts   uint64 `json:"out_ucast_pkts"`
}

type InterfaceUnicastDelta struct {
	InUcastOctets  uint64 `json:"in_ucast_octets"`
	InUcastPkts    uint64 `json:"in_ucast_pkts"`
	OutUcastOctets uint64 `json:"out_ucast_octets"`
	OutUcastPkts   uint64 `json:"out_ucast_pkts"`
}

type UplinkProof struct {
	APInterfaceIndex             uint32                    `json:"ap_interface_index"`
	APForwardingEnabled          bool                      `json:"ap_forwarding_enabled"`
	ExpectedUplinkInterfaceIndex uint32                    `json:"expected_uplink_interface_index"`
	Routes                       []RouteProof              `json:"routes"`
	UplinkOpen                   InterfaceUnicastSnapshot  `json:"uplink_open"`
	APOpen                       InterfaceUnicastSnapshot  `json:"ap_open"`
	UplinkClose                  *InterfaceUnicastSnapshot `json:"uplink_close,omitempty"`
	UplinkDelta                  *InterfaceUnicastDelta    `json:"uplink_delta,omitempty"`
	APClose                      *InterfaceUnicastSnapshot `json:"ap_close,omitempty"`
	APDelta                      *InterfaceUnicastDelta    `json:"ap_delta,omitempty"`
}

type netTableReader interface {
	IPForwardingEnabled(interfaceIndex uint32, ipv6 bool) (bool, error)
	BestRoute(remote netip.Addr) (RouteProof, error)
	InterfaceUnicast(interfaceIndex uint32, requireUp bool) (InterfaceUnicastSnapshot, error)
}

type sockaddrInet struct {
	Family   uint16
	Port     uint16
	FlowInfo uint32 // AF_INET sin_addr bytes @ offset 4..8; AF_INET6 sin6_flowinfo @ offset 4..8
	Addr6    [16]byte
	ScopeID  uint32
}

type ipAddressPrefix struct {
	Prefix       sockaddrInet
	PrefixLength uint8
	_            [3]byte
}

type mibIPForwardRow2 struct {
	InterfaceLuid        uint64
	InterfaceIndex       uint32
	DestinationPrefix    ipAddressPrefix
	NextHop              sockaddrInet
	SitePrefixLength     uint8
	_                    [3]byte
	ValidLifetime        uint32
	PreferredLifetime    uint32
	Metric               uint32
	Protocol             int32
	Loopback             uint8
	AutoconfigureAddress uint8
	Publish              uint8
	Immortal             uint8
	Age                  uint32
	Origin               int32
}

type mibIPInterfaceRow struct {
	Family                         uint16
	_                              [6]byte
	InterfaceLuid                  uint64
	InterfaceIndex                 uint32
	MaxReassemblySize              uint32
	InterfaceIdentifier            uint64
	MinRouterAdvertisementInterval uint32
	MaxRouterAdvertisementInterval uint32
	AdvertisingEnabled             uint8
	ForwardingEnabled              uint8
	Rest                           [126]byte
}

type mibIfRow2 struct {
	InterfaceLuid      uint64
	InterfaceIndex     uint32
	Header             [1140]byte
	TunnelType         int32
	OperStatus         int32
	AdminStatus        int32
	MediaConnectState  int32
	NetworkGuid        [16]byte
	ConnectionType     int32
	_                  [4]byte
	TransmitLinkSpeed  uint64
	ReceiveLinkSpeed   uint64
	InOctets           uint64
	InUcastPkts        uint64
	InNUcastPkts       uint64
	InDiscards         uint64
	InErrors           uint64
	InUnknownProtos    uint64
	InUcastOctets      uint64
	InMulticastOctets  uint64
	InBroadcastOctets  uint64
	OutOctets          uint64
	OutUcastPkts       uint64
	OutNUcastPkts      uint64
	OutDiscards        uint64
	OutErrors          uint64
	OutUcastOctets     uint64
	OutMulticastOctets uint64
	OutBroadcastOctets uint64
	OutQLen            uint64
}

var (
	_ [28]byte   = [unsafe.Sizeof(sockaddrInet{})]byte{}
	_ [32]byte   = [unsafe.Sizeof(ipAddressPrefix{})]byte{}
	_ [104]byte  = [unsafe.Sizeof(mibIPForwardRow2{})]byte{}
	_ [8]byte    = [unsafe.Offsetof(mibIPForwardRow2{}.InterfaceIndex)]byte{}
	_ [44]byte   = [unsafe.Offsetof(mibIPForwardRow2{}.NextHop)]byte{}
	_ [84]byte   = [unsafe.Offsetof(mibIPForwardRow2{}.Metric)]byte{}
	_ [168]byte  = [unsafe.Sizeof(mibIPInterfaceRow{})]byte{}
	_ [16]byte   = [unsafe.Offsetof(mibIPInterfaceRow{}.InterfaceIndex)]byte{}
	_ [41]byte   = [unsafe.Offsetof(mibIPInterfaceRow{}.ForwardingEnabled)]byte{}
	_ [1352]byte = [unsafe.Sizeof(mibIfRow2{})]byte{}
	_ [8]byte    = [unsafe.Offsetof(mibIfRow2{}.InterfaceIndex)]byte{}
	_ [1156]byte = [unsafe.Offsetof(mibIfRow2{}.OperStatus)]byte{}
	_ [1216]byte = [unsafe.Offsetof(mibIfRow2{}.InUcastPkts)]byte{}
	_ [1256]byte = [unsafe.Offsetof(mibIfRow2{}.InUcastOctets)]byte{}
	_ [1288]byte = [unsafe.Offsetof(mibIfRow2{}.OutUcastPkts)]byte{}
	_ [1320]byte = [unsafe.Offsetof(mibIfRow2{}.OutUcastOctets)]byte{}
)

var (
	iphlpapiDLL                    = syscall.NewLazyDLL("iphlpapi.dll")
	procInitializeIpInterfaceEntry = iphlpapiDLL.NewProc("InitializeIpInterfaceEntry")
	procGetIpInterfaceEntry        = iphlpapiDLL.NewProc("GetIpInterfaceEntry")
	procGetBestRoute2              = iphlpapiDLL.NewProc("GetBestRoute2")
	procGetIfEntry2                = iphlpapiDLL.NewProc("GetIfEntry2")
)

type iphlpapiReader struct{}

func toSockaddrInet(addr netip.Addr) (sockaddrInet, error) {
	addr = addr.Unmap()
	if addr.Is4() {
		var s sockaddrInet
		s.Family = winAFInet
		v4 := addr.As4()
		*(*[4]byte)(unsafe.Pointer(&s.FlowInfo)) = v4
		return s, nil
	}
	if addr.Is6() {
		var s sockaddrInet
		s.Family = winAFInet6
		s.Addr6 = addr.As16()
		return s, nil
	}
	return sockaddrInet{}, fmt.Errorf("invalid IP address: %v", addr)
}

func fromSockaddrInet(s sockaddrInet) (netip.Addr, bool) {
	switch s.Family {
	case winAFInet:
		v4 := *(*[4]byte)(unsafe.Pointer(&s.FlowInfo))
		return netip.AddrFrom4(v4), true
	case winAFInet6:
		return netip.AddrFrom16(s.Addr6), true
	default:
		return netip.Addr{}, false
	}
}

func (iphlpapiReader) IPForwardingEnabled(interfaceIndex uint32, ipv6 bool) (bool, error) {
	var row mibIPInterfaceRow
	procInitializeIpInterfaceEntry.Call(uintptr(unsafe.Pointer(&row)))
	if ipv6 {
		row.Family = winAFInet6
	} else {
		row.Family = winAFInet
	}
	row.InterfaceLuid = 0
	row.InterfaceIndex = interfaceIndex
	ret, _, _ := procGetIpInterfaceEntry.Call(uintptr(unsafe.Pointer(&row)))
	if ret != 0 {
		return false, fmt.Errorf("GetIpInterfaceEntry(ifIdx=%d, family=%d): win32 error %d", interfaceIndex, row.Family, ret)
	}
	return row.ForwardingEnabled != 0, nil
}

func (iphlpapiReader) BestRoute(remote netip.Addr) (RouteProof, error) {
	dest, err := toSockaddrInet(remote)
	if err != nil {
		return RouteProof{}, err
	}
	var row mibIPForwardRow2
	var bestSrc sockaddrInet
	ret, _, _ := procGetBestRoute2.Call(
		0,
		0,
		0,
		uintptr(unsafe.Pointer(&dest)),
		0,
		uintptr(unsafe.Pointer(&row)),
		uintptr(unsafe.Pointer(&bestSrc)),
	)
	if ret != 0 {
		return RouteProof{}, fmt.Errorf("GetBestRoute2(%s): win32 error %d", remote.String(), ret)
	}
	proof := RouteProof{
		RemoteAddress:      remote.Unmap().String(),
		BestInterfaceIndex: row.InterfaceIndex,
	}
	if nh, ok := fromSockaddrInet(row.NextHop); ok && !nh.IsUnspecified() {
		proof.NextHop = nh.String()
	}
	return proof, nil
}

func (iphlpapiReader) InterfaceUnicast(interfaceIndex uint32, requireUp bool) (InterfaceUnicastSnapshot, error) {
	var row mibIfRow2
	row.InterfaceIndex = interfaceIndex
	ret, _, _ := procGetIfEntry2.Call(uintptr(unsafe.Pointer(&row)))
	if ret != 0 {
		return InterfaceUnicastSnapshot{}, fmt.Errorf("GetIfEntry2(ifIdx=%d): win32 error %d", interfaceIndex, ret)
	}
	if requireUp && row.OperStatus != ifOperStatusUp {
		return InterfaceUnicastSnapshot{}, fmt.Errorf("interface %d is not operational Up (oper_status=%d)", interfaceIndex, row.OperStatus)
	}
	return InterfaceUnicastSnapshot{
		InterfaceIndex: interfaceIndex,
		InUcastOctets:  row.InUcastOctets,
		InUcastPkts:    row.InUcastPkts,
		OutUcastOctets: row.OutUcastOctets,
		OutUcastPkts:   row.OutUcastPkts,
	}, nil
}

func requiredForwardingFamilies(request condition.Request) (needV4, needV6 bool, err error) {
	for _, raw := range append(append([]string(nil), request.PhoneAddresses...), request.ProbeRemoteAddresses...) {
		addr, parseErr := netip.ParseAddr(raw)
		if parseErr != nil {
			return false, false, parseErr
		}
		addr = addr.Unmap()
		if addr.Is6() {
			needV6 = true
		} else {
			needV4 = true
		}
	}
	if !needV4 && !needV6 {
		needV4 = true
	}
	return needV4, needV6, nil
}

func verifyUplinkOpen(request condition.Request, reader netTableReader) (*UplinkProof, error) {
	if reader == nil {
		return nil, errors.New("missing native network table reader")
	}
	needV4, needV6, err := requiredForwardingFamilies(request)
	if err != nil {
		return nil, err
	}
	if needV4 {
		enabled, fwdErr := reader.IPForwardingEnabled(request.InterfaceIndex, false)
		if fwdErr != nil {
			return nil, fwdErr
		}
		if !enabled {
			return nil, fmt.Errorf("AP interface %d IPv4 forwarding is disabled; refusing readiness", request.InterfaceIndex)
		}
	}
	if needV6 {
		enabled, fwdErr := reader.IPForwardingEnabled(request.InterfaceIndex, true)
		if fwdErr != nil {
			return nil, fwdErr
		}
		if !enabled {
			return nil, fmt.Errorf("AP interface %d IPv6 forwarding is disabled; refusing readiness", request.InterfaceIndex)
		}
	}
	routes := make([]RouteProof, 0, len(request.ProbeRemoteAddresses))
	for _, raw := range request.ProbeRemoteAddresses {
		remote, parseErr := netip.ParseAddr(raw)
		if parseErr != nil {
			return nil, parseErr
		}
		remote = remote.Unmap()
		route, routeErr := reader.BestRoute(remote)
		if routeErr != nil {
			return nil, routeErr
		}
		route.RemoteAddress = remote.String()
		if route.BestInterfaceIndex != request.ExpectedUplinkInterfaceIndex {
			return nil, fmt.Errorf("best route for %s resolved to interface %d, expected uplink interface %d; refusing readiness", route.RemoteAddress, route.BestInterfaceIndex, request.ExpectedUplinkInterfaceIndex)
		}
		routes = append(routes, route)
	}
	uplinkOpen, err := reader.InterfaceUnicast(request.ExpectedUplinkInterfaceIndex, true)
	if err != nil {
		return nil, fmt.Errorf("expected uplink interface %d: %w", request.ExpectedUplinkInterfaceIndex, err)
	}
	apOpen, err := reader.InterfaceUnicast(request.InterfaceIndex, true)
	if err != nil {
		return nil, fmt.Errorf("AP forwarding interface %d: %w", request.InterfaceIndex, err)
	}
	return &UplinkProof{
		APInterfaceIndex:             request.InterfaceIndex,
		APForwardingEnabled:          true,
		ExpectedUplinkInterfaceIndex: request.ExpectedUplinkInterfaceIndex,
		Routes:                       routes,
		UplinkOpen:                   uplinkOpen,
		APOpen:                       apOpen,
	}, nil
}

func computeUnicastDelta(label string, openSnap, closeSnap InterfaceUnicastSnapshot) (*InterfaceUnicastDelta, error) {
	if openSnap.InterfaceIndex != closeSnap.InterfaceIndex {
		return nil, fmt.Errorf("%s unicast snapshot interface mismatch: %d vs %d", label, openSnap.InterfaceIndex, closeSnap.InterfaceIndex)
	}
	if closeSnap.InUcastOctets < openSnap.InUcastOctets ||
		closeSnap.InUcastPkts < openSnap.InUcastPkts ||
		closeSnap.OutUcastOctets < openSnap.OutUcastOctets ||
		closeSnap.OutUcastPkts < openSnap.OutUcastPkts {
		return nil, fmt.Errorf("%s interface %d unicast counter rollback/reset detected during run", label, openSnap.InterfaceIndex)
	}
	return &InterfaceUnicastDelta{
		InUcastOctets:  closeSnap.InUcastOctets - openSnap.InUcastOctets,
		InUcastPkts:    closeSnap.InUcastPkts - openSnap.InUcastPkts,
		OutUcastOctets: closeSnap.OutUcastOctets - openSnap.OutUcastOctets,
		OutUcastPkts:   closeSnap.OutUcastPkts - openSnap.OutUcastPkts,
	}, nil
}

func completeUplinkClose(proof *UplinkProof, reader netTableReader) error {
	if proof == nil {
		return nil
	}
	if reader == nil {
		return errors.New("missing native network table reader on close")
	}
	uplinkClose, uplinkErr := reader.InterfaceUnicast(proof.ExpectedUplinkInterfaceIndex, false)
	if uplinkErr == nil {
		proof.UplinkClose = &uplinkClose
		var deltaErr error
		proof.UplinkDelta, deltaErr = computeUnicastDelta("uplink", proof.UplinkOpen, uplinkClose)
		uplinkErr = deltaErr
	}
	apClose, apErr := reader.InterfaceUnicast(proof.APInterfaceIndex, false)
	if apErr == nil {
		proof.APClose = &apClose
		var deltaErr error
		proof.APDelta, deltaErr = computeUnicastDelta("ap", proof.APOpen, apClose)
		apErr = deltaErr
	}
	return errors.Join(uplinkErr, apErr)
}

func validateCompletedUplinkProof(request condition.Request, proof *UplinkProof) error {
	if request.ExpectedUplinkInterfaceIndex == 0 {
		return nil
	}
	if proof == nil {
		return errors.New("worker missing required uplink_proof")
	}
	if !proof.APForwardingEnabled ||
		proof.APInterfaceIndex != request.InterfaceIndex ||
		proof.ExpectedUplinkInterfaceIndex != request.ExpectedUplinkInterfaceIndex ||
		proof.UplinkOpen.InterfaceIndex != request.ExpectedUplinkInterfaceIndex ||
		proof.APOpen.InterfaceIndex != request.InterfaceIndex ||
		len(proof.Routes) != len(request.ProbeRemoteAddresses) {
		return errors.New("worker uplink_proof identity or route count mismatch")
	}
	for i, rp := range proof.Routes {
		if rp.RemoteAddress != request.ProbeRemoteAddresses[i] || rp.BestInterfaceIndex != request.ExpectedUplinkInterfaceIndex {
			return errors.New("worker uplink_proof route binding mismatch")
		}
	}
	if proof.UplinkClose == nil || proof.UplinkDelta == nil || proof.APClose == nil || proof.APDelta == nil {
		return errors.New("worker uplink_proof missing close/delta unicast snapshots")
	}
	uplinkDelta, err := computeUnicastDelta("uplink", proof.UplinkOpen, *proof.UplinkClose)
	if err != nil || *uplinkDelta != *proof.UplinkDelta {
		return errors.New("worker uplink_delta inconsistent with open/close snapshots")
	}
	apDelta, err := computeUnicastDelta("ap", proof.APOpen, *proof.APClose)
	if err != nil || *apDelta != *proof.APDelta {
		return errors.New("worker ap_delta inconsistent with open/close snapshots")
	}
	return nil
}
