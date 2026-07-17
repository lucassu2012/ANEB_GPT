package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"syscall"
	"time"

	gateway "aneb-gateway"
)

func main() {
	listen := flag.String("listen", "192.168.77.1:9444", "management HTTPS listen address")
	profilesDir := flag.String("profiles", "/etc/aneb-gateway/profiles", "versioned profile directory")
	wan := flag.String("wan", "eth0", "dedicated gateway WAN interface")
	ifb := flag.String("ifb", "ifb-aneb0", "IFB interface used for downlink impairment")
	attestationPath := flag.String("attestation", "/etc/aneb-gateway/dedicated-gateway.json", "dedicated gateway attestation")
	tokenPath := flag.String("token-file", "/etc/aneb-gateway/token", "bearer token file")
	auditPath := flag.String("audit", "/var/lib/aneb-gateway/audit.jsonl", "append-only experiment audit")
	tlsCert := flag.String("tls-cert", "/etc/aneb-gateway/tls/cert.pem", "management TLS certificate")
	tlsKey := flag.String("tls-key", "/etc/aneb-gateway/tls/key.pem", "management TLS private key")
	dryRun := flag.Bool("dry-run", false, "exercise API without changing qdiscs")
	allowInsecureLoopback := flag.Bool("allow-insecure-loopback", false, "allow HTTP only when listen host is loopback")
	printProfiles := flag.Bool("print-profiles", false, "print allowlisted profile fingerprints and exit")
	flag.Parse()

	profiles, err := gateway.LoadProfiles(*profilesDir)
	if err != nil {
		log.Fatal(err)
	}
	if *printProfiles {
		for _, profile := range sortedProfiles(profiles) {
			fmt.Printf("%s %s\n", profile.Ref(), profile.Fingerprint())
		}
		return
	}
	token, err := readSecret(*tokenPath)
	if err != nil {
		log.Fatal(err)
	}

	var controller gateway.ImpairmentController
	if *dryRun {
		controller = gateway.DryRunController{}
	} else {
		if runtime.GOOS != "linux" {
			log.Fatal("non-dry-run gateway requires Linux")
		}
		if _, err := gateway.LoadAttestation(*attestationPath, *wan); err != nil {
			log.Fatal(err)
		}
		for _, command := range []string{"ip", "tc"} {
			if _, err := exec.LookPath(command); err != nil {
				log.Fatalf("required command %s is unavailable", command)
			}
		}
		controller = gateway.TCController{WAN: *wan, IFB: *ifb, Executor: gateway.RealCommandExecutor{}}
	}

	manager, err := gateway.NewManager(context.Background(), profiles, controller, &gateway.JSONLAuditor{Path: *auditPath})
	if err != nil {
		log.Fatal(err)
	}
	defer func() {
		if err := manager.Close(); err != nil {
			log.Printf("final cleanup failed: %v", err)
		}
	}()

	handler, err := (gateway.API{Manager: manager, Token: token}).Handler()
	if err != nil {
		log.Fatal(err)
	}
	server := &http.Server{
		Addr:              *listen,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	errors := make(chan error, 1)
	go func() {
		if *allowInsecureLoopback {
			if !isLoopbackAddress(*listen) {
				errors <- fmt.Errorf("insecure management is restricted to loopback")
				return
			}
			log.Printf("%s dry-run=%v listening on http://%s", gateway.GatewayVersion, *dryRun, *listen)
			errors <- server.ListenAndServe()
			return
		}
		if !regularFile(*tlsCert) || !regularFile(*tlsKey) {
			errors <- fmt.Errorf("TLS certificate and key are required")
			return
		}
		log.Printf("%s dry-run=%v listening on https://%s", gateway.GatewayVersion, *dryRun, *listen)
		errors <- server.ListenAndServeTLS(*tlsCert, *tlsKey)
	}()

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	select {
	case signal := <-signals:
		log.Printf("received %s; clearing impairment", signal)
	case err := <-errors:
		if err != nil && err != http.ErrServerClosed {
			log.Printf("server stopped: %v", err)
		}
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Printf("HTTP shutdown failed: %v", err)
	}
}

func sortedProfiles(profiles map[string]gateway.Profile) []gateway.Profile {
	result := make([]gateway.Profile, 0, len(profiles))
	for _, profile := range profiles {
		result = append(result, profile)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].Ref() < result[j].Ref() })
	return result
}

func readSecret(path string) (string, error) {
	info, err := os.Stat(path)
	if err != nil {
		return "", fmt.Errorf("stat token file: %w", err)
	}
	if !info.Mode().IsRegular() {
		return "", fmt.Errorf("token file is not regular")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return "", fmt.Errorf("token file permissions must be 0600 or stricter")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("read token file: %w", err)
	}
	value := strings.TrimSpace(string(raw))
	if len(value) < 32 {
		return "", fmt.Errorf("token must contain at least 32 characters")
	}
	return value, nil
}

func isLoopbackAddress(address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func regularFile(path string) bool {
	info, err := os.Stat(filepath.Clean(path))
	return err == nil && info.Mode().IsRegular()
}
