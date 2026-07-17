package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"runtime"
	"sort"
	"strings"
	"syscall"
	"time"

	gateway "aneb-gateway"
)

const (
	fixedGatewayManagementIP = "192.168.77.1"
	fixedGatewayCADERSHA256  = "2089A92C77B04FA392E24D1D71819EF1AC3D86B5131B0C6064BD6B092F5AD361"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	listen := flag.String("listen", "192.168.77.1:9444", "management HTTPS listen address")
	profilesDir := flag.String("profiles", "/etc/aneb-gateway/profiles", "versioned profile directory")
	wan := flag.String("wan", "eth0", "dedicated gateway WAN interface")
	ifb := flag.String("ifb", "ifb-aneb0", "IFB interface used for downlink impairment")
	attestationPath := flag.String("attestation", "/etc/aneb-gateway/dedicated-gateway.json", "dedicated gateway attestation")
	tokenPath := flag.String("token-file", "/etc/aneb-gateway/token", "bearer token file")
	auditPath := flag.String("audit", "/var/lib/aneb-gateway/audit.jsonl", "local JSONL operation log")
	tcStatePath := flag.String("tc-state", "/var/lib/aneb-gateway/tc-state.json", "persistent traffic-control ownership state")
	tlsCert := flag.String("tls-cert", "/etc/aneb-gateway/tls/cert.pem", "management TLS certificate")
	tlsKey := flag.String("tls-key", "/etc/aneb-gateway/tls/key.pem", "management TLS private key")
	tlsCA := flag.String("tls-ca", "", "fixed Debug App gateway CA certificate; required for non-dry-run")
	dryRun := flag.Bool("dry-run", false, "exercise API without changing qdiscs")
	allowInsecureLoopback := flag.Bool("allow-insecure-loopback", false, "allow HTTP only for a loopback dry-run")
	cleanupOnly := flag.Bool("cleanup-only", false, "verify and remove only ANEB-owned traffic-control resources, then exit")
	preflightOnly := flag.Bool("preflight-only", false, "perform read-only production prerequisites and resource checks, then exit")
	printProfiles := flag.Bool("print-profiles", false, "print allowlisted profile fingerprints and exit")
	flag.Parse()

	if *cleanupOnly && *dryRun {
		return fmt.Errorf("cleanup-only cannot be combined with dry-run")
	}
	if *preflightOnly && (*dryRun || *cleanupOnly) {
		return fmt.Errorf("preflight-only requires a non-dry-run gateway and cannot be combined with cleanup-only")
	}
	if *allowInsecureLoopback && (!*dryRun || !isLoopbackAddress(*listen)) {
		return fmt.Errorf("insecure management is restricted to an explicit loopback dry-run")
	}
	if *cleanupOnly {
		if runtime.GOOS != "linux" {
			return fmt.Errorf("cleanup-only requires Linux")
		}
		ipCommand, err := absoluteCommand("ip")
		if err != nil {
			return err
		}
		tcCommand, err := absoluteCommand("tc")
		if err != nil {
			return err
		}
		controller := gateway.TCController{
			WAN: *wan, IFB: *ifb, StatePath: *tcStatePath,
			IPCommand: ipCommand, TCCommand: tcCommand,
			Executor: gateway.RealCommandExecutor{},
		}
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		if err := controller.Clear(ctx); err != nil {
			return fmt.Errorf("cleanup-only verification failed: %w", err)
		}
		log.Printf("%s owned impairment cleanup verified", gateway.GatewayVersion)
		return nil
	}

	profiles, err := gateway.LoadProfiles(*profilesDir)
	if err != nil {
		return err
	}
	if *printProfiles {
		for _, profile := range sortedProfiles(profiles) {
			fmt.Printf("%s %s\n", profile.Ref(), profile.Fingerprint())
		}
		return nil
	}
	if !*dryRun && strings.TrimSpace(*tlsCA) == "" {
		return fmt.Errorf("non-dry-run gateway requires explicit -tls-ca")
	}

	var controller gateway.ImpairmentController
	var tcController *gateway.TCController
	var allowedClientSubnet *net.IPNet
	if *dryRun {
		controller = gateway.DryRunController{}
	} else {
		if runtime.GOOS != "linux" {
			return fmt.Errorf("non-dry-run gateway requires Linux")
		}
		attestation, err := gateway.LoadAttestation(*attestationPath, *wan)
		if err != nil {
			return err
		}
		ipCommand, err := absoluteCommand("ip")
		if err != nil {
			return err
		}
		tcCommand, err := absoluteCommand("tc")
		if err != nil {
			return err
		}
		topologyCheck := func(ctx context.Context) (*net.IPNet, error) {
			topology, captureErr := gateway.CaptureRuntimeTopology(
				ctx,
				attestation,
				gateway.RealCommandExecutor{},
				ipCommand,
				*listen,
			)
			if captureErr != nil {
				return nil, fmt.Errorf("capture dedicated gateway topology: %w", captureErr)
			}
			network, validateErr := gateway.ValidateRuntimeTopology(attestation, *listen, topology)
			if validateErr != nil {
				return nil, fmt.Errorf("dedicated gateway topology rejected: %w", validateErr)
			}
			return network, nil
		}
		productionController := gateway.TCController{
			WAN: *wan, IFB: *ifb, StatePath: *tcStatePath,
			IPCommand: ipCommand, TCCommand: tcCommand,
			Executor: gateway.RealCommandExecutor{},
			PreApplyCheck: func(ctx context.Context) error {
				_, checkErr := topologyCheck(ctx)
				return checkErr
			},
		}
		tcController = &productionController
		controller = productionController
		topologyCtx, topologyCancel := context.WithTimeout(context.Background(), 8*time.Second)
		allowedClientSubnet, err = topologyCheck(topologyCtx)
		topologyCancel()
		if err != nil {
			return err
		}
	}

	token, err := readSecret(*tokenPath)
	if err != nil {
		return err
	}
	listener, err := prepareListener(*listen, *tlsCert, *tlsKey, *tlsCA, !*dryRun, *allowInsecureLoopback)
	if err != nil {
		return err
	}
	if *preflightOnly {
		_ = listener.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if tcController == nil {
			return fmt.Errorf("production tc controller is unavailable")
		}
		if err := tcController.PreflightClean(ctx); err != nil {
			return fmt.Errorf("traffic-control preflight rejected: %w", err)
		}
		log.Printf("%s production preflight PASS", gateway.GatewayVersion)
		return nil
	}
	// No traffic-control mutation occurs before every file, topology, certificate,
	// and listen-port prerequisite above has succeeded.
	manager, err := gateway.NewManager(context.Background(), profiles, controller, &gateway.JSONLAuditor{Path: *auditPath})
	if err != nil {
		_ = listener.Close()
		return err
	}
	handler, err := (gateway.API{
		Manager: manager, Token: token, AllowedClientSubnet: allowedClientSubnet,
	}).Handler()
	if err != nil {
		_ = listener.Close()
		_ = manager.Close()
		return err
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

	serveErrors := make(chan error, 1)
	go func() { serveErrors <- server.Serve(listener) }()
	scheme := "https"
	if *allowInsecureLoopback {
		scheme = "http"
	}
	log.Printf("%s dry-run=%v listening on %s://%s", gateway.GatewayVersion, *dryRun, scheme, *listen)

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(signals)
	var serveErr error
	select {
	case signalValue := <-signals:
		log.Printf("received %s; locking manager and clearing impairment", signalValue)
	case serveErr = <-serveErrors:
		if serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			log.Printf("management listener failed: %v", serveErr)
		}
	}

	cleanupErr := manager.Close()
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 15*time.Second)
	shutdownErr := server.Shutdown(shutdownCtx)
	shutdownCancel()
	if errors.Is(serveErr, http.ErrServerClosed) {
		serveErr = nil
	}
	if serveErr != nil || cleanupErr != nil || shutdownErr != nil {
		return errors.Join(serveErr, wrapError("final impairment cleanup", cleanupErr), wrapError("HTTP shutdown", shutdownErr))
	}
	return nil
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
	info, err := secureRegularFile(path, 256, true)
	if err != nil {
		return "", fmt.Errorf("token file: %w", err)
	}
	if info.Size() <= 0 {
		return "", fmt.Errorf("token file is empty")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("read token file: %w", err)
	}
	value := strings.TrimSpace(string(raw))
	if !gateway.ValidBearerToken(value) {
		return "", fmt.Errorf("token must be exactly 32 random bytes encoded as 64 hex characters")
	}
	return value, nil
}

func prepareListener(address, certificatePath, keyPath, caPath string, requireFixedCA, insecureLoopback bool) (net.Listener, error) {
	if insecureLoopback {
		listener, err := net.Listen("tcp", address)
		if err != nil {
			return nil, fmt.Errorf("reserve loopback management listener: %w", err)
		}
		return listener, nil
	}
	certificate, leaf, err := loadGatewayCertificate(certificatePath, keyPath, requireFixedCA)
	if err != nil {
		return nil, err
	}
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return nil, fmt.Errorf("parse TLS listen address: %w", err)
	}
	if requireFixedCA {
		if err := verifyGatewayTLSIdentityAgainstHash(caPath, address, certificate, leaf, fixedGatewayCADERSHA256, time.Now()); err != nil {
			return nil, fmt.Errorf("fixed Debug App TLS trust contract: %w", err)
		}
	} else {
		if err := leaf.VerifyHostname(host); err != nil {
			return nil, fmt.Errorf("TLS certificate SAN does not contain management IP %s: %w", host, err)
		}
		now := time.Now()
		if now.Before(leaf.NotBefore) || now.After(leaf.NotAfter) {
			return nil, fmt.Errorf("TLS certificate is outside its validity window")
		}
	}
	rawListener, err := net.Listen("tcp", address)
	if err != nil {
		return nil, fmt.Errorf("reserve TLS management listener: %w", err)
	}
	config := &tls.Config{
		Certificates: []tls.Certificate{certificate},
		MinVersion:   tls.VersionTLS12,
	}
	return tls.NewListener(rawListener, config), nil
}

func loadGatewayCertificate(certificatePath, keyPath string, requireProductionOwnership bool) (tls.Certificate, *x509.Certificate, error) {
	certificateInfo, err := secureRegularFile(certificatePath, 1<<20, false)
	if err != nil {
		return tls.Certificate{}, nil, fmt.Errorf("TLS certificate: %w", err)
	}
	if requireProductionOwnership {
		if err := validateRootTrustFile(certificateInfo); err != nil {
			return tls.Certificate{}, nil, fmt.Errorf("TLS certificate: %w", err)
		}
	}
	keyInfo, err := secureRegularFile(keyPath, 1<<20, true)
	if err != nil {
		return tls.Certificate{}, nil, fmt.Errorf("TLS private key: %w", err)
	}
	if requireProductionOwnership {
		if err := validatePrivateKeyOwner(keyInfo); err != nil {
			return tls.Certificate{}, nil, fmt.Errorf("TLS private key: %w", err)
		}
	}
	certificate, err := tls.LoadX509KeyPair(certificatePath, keyPath)
	if err != nil {
		return tls.Certificate{}, nil, fmt.Errorf("load TLS certificate pair: %w", err)
	}
	if len(certificate.Certificate) == 0 {
		return tls.Certificate{}, nil, fmt.Errorf("TLS certificate chain is empty")
	}
	leaf, err := x509.ParseCertificate(certificate.Certificate[0])
	if err != nil {
		return tls.Certificate{}, nil, fmt.Errorf("parse TLS leaf certificate: %w", err)
	}
	certificate.Leaf = leaf
	return certificate, leaf, nil
}

func verifyGatewayTLSIdentityAgainstHash(
	caPath, address string,
	certificate tls.Certificate,
	leaf *x509.Certificate,
	expectedHash string,
	now time.Time,
) error {
	ca, err := loadPinnedGatewayCA(caPath, expectedHash)
	if err != nil {
		return err
	}
	return verifyGatewayTLSIdentity(address, certificate, leaf, ca, now)
}

func verifyGatewayTLSIdentity(
	address string,
	certificate tls.Certificate,
	leaf, ca *x509.Certificate,
	now time.Time,
) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return fmt.Errorf("parse TLS listen address: %w", err)
	}
	managementIP := net.ParseIP(host)
	if managementIP == nil || !managementIP.Equal(net.ParseIP(fixedGatewayManagementIP)) {
		return fmt.Errorf("management IP must be %s", fixedGatewayManagementIP)
	}
	if leaf == nil {
		return fmt.Errorf("TLS leaf certificate is missing")
	}
	if ca == nil {
		return fmt.Errorf("fixed TLS CA certificate is missing")
	}
	if !leaf.BasicConstraintsValid || leaf.IsCA {
		return fmt.Errorf("TLS leaf certificate must explicitly declare CA:FALSE")
	}
	serverAuth := false
	for _, usage := range leaf.ExtKeyUsage {
		if usage == x509.ExtKeyUsageServerAuth {
			serverAuth = true
			break
		}
	}
	if !serverAuth {
		return fmt.Errorf("TLS leaf certificate must explicitly allow ServerAuth")
	}
	if now.Before(leaf.NotBefore) || now.After(leaf.NotAfter) {
		return fmt.Errorf("TLS leaf certificate is outside its validity window")
	}
	hasManagementIP := false
	for _, candidate := range leaf.IPAddresses {
		if candidate.Equal(managementIP) {
			hasManagementIP = true
			break
		}
	}
	if !hasManagementIP {
		return fmt.Errorf("TLS leaf certificate SAN does not contain management IP %s", fixedGatewayManagementIP)
	}

	roots := x509.NewCertPool()
	roots.AddCert(ca)
	intermediates := x509.NewCertPool()
	for _, raw := range certificate.Certificate[1:] {
		intermediate, parseErr := x509.ParseCertificate(raw)
		if parseErr != nil {
			return fmt.Errorf("parse TLS intermediate certificate: %w", parseErr)
		}
		intermediates.AddCert(intermediate)
	}
	if _, err := leaf.Verify(x509.VerifyOptions{
		DNSName:       fixedGatewayManagementIP,
		Roots:         roots,
		Intermediates: intermediates,
		KeyUsages:     []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		CurrentTime:   now,
	}); err != nil {
		return fmt.Errorf("TLS leaf is not trusted by the fixed Debug App CA: %w", err)
	}
	return nil
}

func loadPinnedGatewayCA(path, expectedHash string) (*x509.Certificate, error) {
	if strings.TrimSpace(path) == "" {
		return nil, fmt.Errorf("TLS CA path is required")
	}
	info, err := secureRegularFile(path, 1<<20, false)
	if err != nil {
		return nil, fmt.Errorf("TLS CA: %w", err)
	}
	if err := validateRootTrustFile(info); err != nil {
		return nil, fmt.Errorf("TLS CA: %w", err)
	}
	if info.Size() <= 0 {
		return nil, fmt.Errorf("TLS CA is empty")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read TLS CA: %w", err)
	}
	return parsePinnedGatewayCA(raw, expectedHash)
}

func parsePinnedGatewayCA(raw []byte, expectedHash string) (*x509.Certificate, error) {
	block, rest := pem.Decode(raw)
	if block == nil || block.Type != "CERTIFICATE" || len(bytes.TrimSpace(rest)) != 0 {
		return nil, fmt.Errorf("TLS CA must contain exactly one PEM certificate")
	}
	ca, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse TLS CA: %w", err)
	}
	digest := sha256.Sum256(ca.Raw)
	actualHash := strings.ToUpper(hex.EncodeToString(digest[:]))
	if actualHash != expectedHash {
		return nil, fmt.Errorf("TLS CA DER SHA-256 mismatch: expected %s, got %s", expectedHash, actualHash)
	}
	if !ca.BasicConstraintsValid || !ca.IsCA || ca.KeyUsage&x509.KeyUsageCertSign == 0 {
		return nil, fmt.Errorf("TLS CA certificate is not authorized to sign certificates")
	}
	return ca, nil
}

func secureRegularFile(path string, maxSize int64, secret bool) (os.FileInfo, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return nil, fmt.Errorf("must be a regular non-symlink file")
	}
	if info.Size() > maxSize {
		return nil, fmt.Errorf("exceeds maximum size")
	}
	if secret && runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return nil, fmt.Errorf("permissions must be 0600 or stricter")
	}
	return info, nil
}

func validateRootTrustPolicy(mode os.FileMode, rootOwned bool) error {
	if mode.Perm()&0o022 != 0 {
		return fmt.Errorf("must not be group/world writable")
	}
	if !rootOwned {
		return fmt.Errorf("must be root-owned")
	}
	return nil
}

func allowedPrivateKeyOwner(ownerUID, effectiveUID, serviceUID uint32) bool {
	return ownerUID == 0 || ownerUID == effectiveUID || ownerUID == serviceUID
}

func absoluteCommand(name string) (string, error) {
	path, err := exec.LookPath(name)
	if err != nil {
		return "", fmt.Errorf("required command %s is unavailable", name)
	}
	if !strings.HasPrefix(path, "/") {
		return "", fmt.Errorf("required command %s did not resolve to an absolute path", name)
	}
	return path, nil
}

func isLoopbackAddress(address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func wrapError(label string, err error) error {
	if err == nil {
		return nil
	}
	return fmt.Errorf("%s: %w", label, err)
}
