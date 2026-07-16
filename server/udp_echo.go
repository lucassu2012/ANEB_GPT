package main

import (
	"bytes"
	"fmt"
	"net"
)

var udpProbeMagic = []byte{'A', 'N', 'E', 'B', '1'}

// udpProbeFilteringConn lets raw ANEB datagrams and QUIC/HTTP3 share one
// already-approved UDP port. Probe packets are consumed and echoed here;
// every other datagram is passed through untouched to quic-go.
type udpProbeFilteringConn struct{ net.PacketConn }

func newUDPProbeFilteringConn(conn net.PacketConn) net.PacketConn {
	return &udpProbeFilteringConn{PacketConn: conn}
}

func (c *udpProbeFilteringConn) ReadFrom(buf []byte) (int, net.Addr, error) {
	for {
		n, peer, err := c.PacketConn.ReadFrom(buf)
		if err != nil {
			return 0, nil, err
		}
		if isUDPProbePacket(buf[:n]) {
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

// serveUDPEcho provides the v1 sequenced application-datagram probe. It only
// returns exact-size authenticated-shape packets and never amplifies payloads.
// This measures application datagram non-return to the ANEB node, not IP loss.
func serveUDPEcho(addr string) error {
	conn, err := net.ListenPacket("udp", addr)
	if err != nil {
		return err
	}
	defer conn.Close()
	return serveUDPEchoConn(conn)
}

func serveUDPEchoConn(conn net.PacketConn) error {
	buf := make([]byte, 512)
	for {
		n, peer, err := conn.ReadFrom(buf)
		if err != nil {
			return err
		}
		if n > len(buf) || !isUDPProbePacket(buf[:n]) {
			continue
		}
		if written, err := conn.WriteTo(buf[:n], peer); err != nil {
			return fmt.Errorf("write udp echo: %w", err)
		} else if written != n {
			return fmt.Errorf("short udp echo write: %d/%d", written, n)
		}
	}
}

func isUDPProbePacket(packet []byte) bool {
	return len(packet) >= 17 && len(packet) <= 512 && bytes.Equal(packet[:len(udpProbeMagic)], udpProbeMagic)
}
