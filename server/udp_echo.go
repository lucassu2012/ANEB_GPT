package main

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"net"
)

var (
	udpProbeMagic         = []byte{'A', 'N', 'E', 'B', '2'}
	udpProbeLegacyV1Magic = []byte{'A', 'N', 'E', 'B', '1'}
)

// udpProbeFilteringConn lets raw ANEB datagrams and QUIC/HTTP3 share one
// already-approved UDP port. Probe packets are consumed and echoed here;
// every other datagram is passed through untouched to quic-go.
type udpProbeFilteringConn struct {
	net.PacketConn
	audit requestAuditEmitter
}

func newUDPProbeFilteringConn(conn net.PacketConn) net.PacketConn {
	return newUDPProbeFilteringConnWithAudit(conn, nil)
}

func newUDPProbeFilteringConnWithAudit(conn net.PacketConn, audit requestAuditEmitter) net.PacketConn {
	return &udpProbeFilteringConn{PacketConn: conn, audit: audit}
}

func (c *udpProbeFilteringConn) ReadFrom(buf []byte) (int, net.Addr, error) {
	for {
		n, peer, err := c.PacketConn.ReadFrom(buf)
		if err != nil {
			return 0, nil, err
		}
		if isUDPProbePacket(buf[:n]) {
			emitUDPProbeAudit(buf[:n], c.audit)
			if written, err := c.PacketConn.WriteTo(buf[:n], peer); err != nil {
				return 0, nil, fmt.Errorf("write shared udp echo: %w", err)
			} else if written != n {
				return 0, nil, fmt.Errorf("short shared udp echo write: %d/%d", written, n)
			}
			continue
		}
		return n, peer, nil
	}
}

// serveUDPEcho provides the v2 run-bound sequenced application-datagram probe. It only
// returns exact-size authenticated-shape packets and never amplifies payloads.
// This measures application datagram non-return to the ANEB node, not IP loss.
func serveUDPEcho(addr string) error {
	return serveUDPEchoWithAudit(addr, nil)
}

func serveUDPEchoWithAudit(addr string, audit requestAuditEmitter) error {
	conn, err := net.ListenPacket("udp", addr)
	if err != nil {
		return err
	}
	defer conn.Close()
	return serveUDPEchoConnWithAudit(conn, audit)
}

func serveUDPEchoConn(conn net.PacketConn) error {
	return serveUDPEchoConnWithAudit(conn, nil)
}

func serveUDPEchoConnWithAudit(conn net.PacketConn, audit requestAuditEmitter) error {
	buf := make([]byte, 512)
	for {
		n, peer, err := conn.ReadFrom(buf)
		if err != nil {
			return err
		}
		if n > len(buf) || !isUDPProbePacket(buf[:n]) {
			continue
		}
		emitUDPProbeAudit(buf[:n], audit)
		if written, err := conn.WriteTo(buf[:n], peer); err != nil {
			return fmt.Errorf("write udp echo: %w", err)
		} else if written != n {
			return fmt.Errorf("short udp echo write: %d/%d", written, n)
		}
	}
}

// emitUDPProbeAudit attributes only v2 packets. Legacy ANEB1 remains echo-only
// compatibility traffic because it carries no run identity and cannot be used
// as same-run Network Quick evidence.
func emitUDPProbeAudit(packet []byte, audit requestAuditEmitter) {
	if audit == nil || len(packet) < 33 || !bytes.Equal(packet[:len(udpProbeMagic)], udpProbeMagic) {
		return
	}
	rawRunID := hex.EncodeToString(packet[5:21])
	runID := rawRunID[:8] + "-" + rawRunID[8:12] + "-" + rawRunID[12:16] + "-" + rawRunID[16:20] + "-" + rawRunID[20:]
	scope := "network_run"
	if !canonicalAuditUUID.MatchString(runID) {
		scope = "invalid_header"
		runID = "redacted"
	}
	_ = audit.TryEmit(requestAuditRecord{
		class:         "business",
		method:        "DATAGRAM",
		path:          "/api/v1/udp-echo",
		role:          requestAuditRoleNone,
		scope:         scope,
		runID:         runID,
		datagram:      true,
		datagramSeq:   binary.BigEndian.Uint32(packet[21:25]),
		datagramBytes: len(packet),
	})
}

func isUDPProbePacket(packet []byte) bool {
	if len(packet) > 512 {
		return false
	}
	return (len(packet) >= 33 && bytes.Equal(packet[:len(udpProbeMagic)], udpProbeMagic)) ||
		(len(packet) >= 17 && bytes.Equal(packet[:len(udpProbeLegacyV1Magic)], udpProbeLegacyV1Magic))
}
