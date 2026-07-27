package main

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"log"
	"net"
	"strings"
	"testing"
	"time"
)

func networkAuditProbePacket(t *testing.T, sequence uint32, size int) []byte {
	t.Helper()
	rawRunID, err := hex.DecodeString(strings.ReplaceAll(testRunID, "-", ""))
	if err != nil {
		t.Fatal(err)
	}
	packet := make([]byte, size)
	copy(packet, udpProbeMagic)
	copy(packet[5:21], rawRunID)
	binary.BigEndian.PutUint32(packet[21:25], sequence)
	binary.BigEndian.PutUint64(packet[25:33], 123456)
	return packet
}

func TestUDPEchoAuditsV2RunSequenceAndBytesBeforeReply(t *testing.T) {
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	var audit bytes.Buffer
	sink := newAsyncRequestAuditSinkForInstance(log.New(&audit, "", 0), 4, testAuditInstanceID)
	defer sink.Close()
	go func() { _ = serveUDPEchoConnWithAudit(server, sink) }()

	client, err := net.Dial("udp", server.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(time.Second))
	packet := networkAuditProbePacket(t, 42, 256)
	if _, err := client.Write(packet); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 512)
	if _, err := client.Read(reply); err != nil {
		t.Fatal(err)
	}
	sink.Close()

	want := "ANEB_REQUEST_AUDIT instance_id=" + testAuditInstanceID +
		" seq=1 class=business method=DATAGRAM path=/api/v1/udp-echo role=none" +
		" scope=network_run run_id=" + testRunID + " datagram_seq=42 datagram_bytes=256\n"
	if got := audit.String(); got != want {
		t.Fatalf("unexpected UDP audit record:\n got: %q\nwant: %q", got, want)
	}
}

func TestUDPEchoReturnsValidProbeExactly(t *testing.T) {
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	go func() { _ = serveUDPEchoConn(server) }()

	client, err := net.Dial("udp", server.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(time.Second))
	packet := make([]byte, 64)
	copy(packet, udpProbeMagic)
	copy(packet[5:21], []byte{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3})
	binary.BigEndian.PutUint32(packet[21:25], 42)
	binary.BigEndian.PutUint64(packet[25:33], 123456)
	if _, err := client.Write(packet); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, 128)
	n, err := client.Read(got)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(packet, got[:n]) {
		t.Fatalf("echo mismatch: %x", got[:n])
	}
}

func TestUDPEchoIgnoresUnknownPayload(t *testing.T) {
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	go func() { _ = serveUDPEchoConn(server) }()
	client, err := net.Dial("udp", server.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(80 * time.Millisecond))
	_, _ = client.Write([]byte("not-aneb"))
	if _, err := client.Read(make([]byte, 32)); err == nil {
		t.Fatal("unexpected response")
	}
}

func TestUDPProbeAcceptsLegacyV1OnlyForCompatibility(t *testing.T) {
	legacy := make([]byte, 17)
	copy(legacy, udpProbeLegacyV1Magic)
	if !isUDPProbePacket(legacy) {
		t.Fatal("legacy ANEB1 packet should remain echo-compatible")
	}
	if isUDPProbePacket(legacy[:16]) {
		t.Fatal("truncated legacy packet must be rejected")
	}
}

func TestUDPProbeLegacyV1IsNotAttributedToNetworkRunAudit(t *testing.T) {
	var audit bytes.Buffer
	sink := newAsyncRequestAuditSinkForInstance(log.New(&audit, "", 0), 2, testAuditInstanceID)
	legacy := make([]byte, 17)
	copy(legacy, udpProbeLegacyV1Magic)
	emitUDPProbeAudit(legacy, sink)
	sink.Close()
	if got := audit.String(); got != "" {
		t.Fatalf("legacy UDP traffic must remain unscoped compatibility only: %q", got)
	}
}

func TestUDPProbeFilterEchoesProbeAndPassesQUICPayload(t *testing.T) {
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	filtered := newUDPProbeFilteringConn(server)
	passed := make(chan []byte, 1)
	go func() {
		buf := make([]byte, 512)
		n, _, err := filtered.ReadFrom(buf)
		if err == nil {
			passed <- append([]byte(nil), buf[:n]...)
		}
	}()

	client, err := net.Dial("udp", server.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(time.Second))
	probe := make([]byte, 33)
	copy(probe, udpProbeMagic)
	if _, err := client.Write(probe); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 64)
	n, err := client.Read(reply)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(probe, reply[:n]) {
		t.Fatal("probe echo mismatch")
	}

	quicLike := []byte{0xc0, 0, 0, 0, 1, 7, 8, 9}
	if _, err := client.Write(quicLike); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-passed:
		if !bytes.Equal(quicLike, got) {
			t.Fatalf("pass-through mismatch: %x", got)
		}
	case <-time.After(time.Second):
		t.Fatal("non-probe packet was not passed through")
	}
}

func TestSharedH3UDPFilterAuditsV2Probe(t *testing.T) {
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	var audit bytes.Buffer
	sink := newAsyncRequestAuditSinkForInstance(log.New(&audit, "", 0), 2, testAuditInstanceID)
	filtered := newUDPProbeFilteringConnWithAudit(server, sink)
	go func() {
		_, _, _ = filtered.ReadFrom(make([]byte, 512))
	}()

	client, err := net.Dial("udp", server.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(time.Second))
	packet := networkAuditProbePacket(t, 7, 256)
	if _, err := client.Write(packet); err != nil {
		t.Fatal(err)
	}
	if _, err := client.Read(make([]byte, 512)); err != nil {
		t.Fatal(err)
	}
	sink.Close()
	if !strings.Contains(
		audit.String(),
		"scope=network_run run_id="+testRunID+" datagram_seq=7 datagram_bytes=256",
	) {
		t.Fatalf("shared H3 UDP path did not emit run-bound audit: %q", audit.String())
	}
}
