package main

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
	"time"
)

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
	binary.BigEndian.PutUint32(packet[5:9], 42)
	binary.BigEndian.PutUint64(packet[9:17], 123456)
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
	probe := make([]byte, 32)
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
