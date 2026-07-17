package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type tlsFixture struct {
	ca          *x509.Certificate
	certificate tls.Certificate
	leaf        *x509.Certificate
	certPath    string
	keyPath     string
}

func TestRepositoryGatewayCAMatchesFixedContract(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "trust", "aneb_gateway_ca.pem"))
	if err != nil {
		t.Fatal(err)
	}
	ca, err := parsePinnedGatewayCA(raw, fixedGatewayCADERSHA256)
	if err != nil {
		t.Fatalf("fixed repository CA rejected: %v", err)
	}
	if !ca.IsCA || ca.KeyUsage&x509.KeyUsageCertSign == 0 {
		t.Fatal("fixed repository certificate is not a signing CA")
	}
	if _, err := parsePinnedGatewayCA(raw, strings.Repeat("0", 64)); err == nil || !strings.Contains(err.Error(), "SHA-256 mismatch") {
		t.Fatalf("wrong CA fingerprint was not rejected: %v", err)
	}
}

func TestGatewayTLSIdentityContract(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	tests := []struct {
		name       string
		mutateLeaf func(*x509.Certificate)
		selfSigned bool
		address    string
		wantError  string
	}{
		{name: "valid", address: fixedGatewayManagementIP + ":9444"},
		{
			name:       "arbitrary self signed leaf",
			selfSigned: true,
			address:    fixedGatewayManagementIP + ":9444",
			wantError:  "not trusted by the fixed Debug App CA",
		},
		{
			name:       "CA leaf",
			mutateLeaf: func(leaf *x509.Certificate) { leaf.IsCA = true; leaf.KeyUsage |= x509.KeyUsageCertSign },
			address:    fixedGatewayManagementIP + ":9444",
			wantError:  "CA:FALSE",
		},
		{
			name:       "missing ServerAuth",
			mutateLeaf: func(leaf *x509.Certificate) { leaf.ExtKeyUsage = []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth} },
			address:    fixedGatewayManagementIP + ":9444",
			wantError:  "ServerAuth",
		},
		{
			name:       "wrong IP SAN",
			mutateLeaf: func(leaf *x509.Certificate) { leaf.IPAddresses = []net.IP{net.ParseIP("192.168.77.2")} },
			address:    fixedGatewayManagementIP + ":9444",
			wantError:  "SAN does not contain",
		},
		{
			name: "expired leaf",
			mutateLeaf: func(leaf *x509.Certificate) {
				leaf.NotBefore = now.Add(-2 * time.Hour)
				leaf.NotAfter = now.Add(-time.Hour)
			},
			address:   fixedGatewayManagementIP + ":9444",
			wantError: "outside its validity window",
		},
		{
			name:      "wrong management IP",
			address:   "192.168.77.2:9444",
			wantError: "management IP must be",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fixture := makeTLSFixture(t, now, test.mutateLeaf, test.selfSigned)
			err := verifyGatewayTLSIdentity(test.address, fixture.certificate, fixture.leaf, fixture.ca, now)
			if test.wantError == "" {
				if err != nil {
					t.Fatalf("valid TLS identity rejected: %v", err)
				}
				return
			}
			if err == nil || !strings.Contains(err.Error(), test.wantError) {
				t.Fatalf("error = %v, want substring %q", err, test.wantError)
			}
		})
	}
}

func TestLoadGatewayCertificateRejectsMismatchedPrivateKey(t *testing.T) {
	fixture := makeTLSFixture(t, time.Now().UTC(), nil, false)
	otherKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	otherDER, err := x509.MarshalECPrivateKey(otherKey)
	if err != nil {
		t.Fatal(err)
	}
	otherPath := filepath.Join(t.TempDir(), "other-key.pem")
	if err := os.WriteFile(otherPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: otherDER}), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := loadGatewayCertificate(fixture.certPath, otherPath, false); err == nil || !strings.Contains(err.Error(), "private key") {
		t.Fatalf("mismatched private key was not rejected: %v", err)
	}
}

func TestProductionTrustFileAndKeyOwnerPolicies(t *testing.T) {
	if err := validateRootTrustPolicy(0o644, true); err != nil {
		t.Fatalf("safe root-owned trust file rejected: %v", err)
	}
	if err := validateRootTrustPolicy(0o666, true); err == nil || !strings.Contains(err.Error(), "writable") {
		t.Fatalf("group/world-writable trust file was not rejected: %v", err)
	}
	if err := validateRootTrustPolicy(0o644, false); err == nil || !strings.Contains(err.Error(), "root-owned") {
		t.Fatalf("non-root trust file was not rejected: %v", err)
	}
	if !allowedPrivateKeyOwner(0, 1000, 2000) || !allowedPrivateKeyOwner(1000, 1000, 2000) || !allowedPrivateKeyOwner(2000, 1000, 2000) {
		t.Fatal("allowed private-key owner rejected")
	}
	if allowedPrivateKeyOwner(3000, 1000, 2000) {
		t.Fatal("unrelated private-key owner accepted")
	}
}

func makeTLSFixture(
	t *testing.T,
	now time.Time,
	mutateLeaf func(*x509.Certificate),
	selfSigned bool,
) tlsFixture {
	t.Helper()
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	caTemplate := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "ANEB test CA"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(time.Hour),
		BasicConstraintsValid: true,
		IsCA:                  true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	ca, err := x509.ParseCertificate(caDER)
	if err != nil {
		t.Fatal(err)
	}

	leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	leafTemplate := &x509.Certificate{
		SerialNumber:          big.NewInt(2),
		Subject:               pkix.Name{CommonName: "ANEB test gateway"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(time.Hour),
		BasicConstraintsValid: true,
		IsCA:                  false,
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IPAddresses:           []net.IP{net.ParseIP(fixedGatewayManagementIP)},
	}
	if mutateLeaf != nil {
		mutateLeaf(leafTemplate)
	}
	parent := caTemplate
	signer := any(caKey)
	if selfSigned {
		parent = leafTemplate
		signer = leafKey
	}
	leafDER, err := x509.CreateCertificate(rand.Reader, leafTemplate, parent, &leafKey.PublicKey, signer)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalECPrivateKey(leafKey)
	if err != nil {
		t.Fatal(err)
	}
	directory := t.TempDir()
	certPath := filepath.Join(directory, "leaf.pem")
	keyPath := filepath.Join(directory, "leaf-key.pem")
	if err := os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: leafDER}), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600); err != nil {
		t.Fatal(err)
	}
	certificate, parsedLeaf, err := loadGatewayCertificate(certPath, keyPath, false)
	if err != nil {
		t.Fatal(err)
	}
	return tlsFixture{
		ca:          ca,
		certificate: certificate,
		leaf:        parsedLeaf,
		certPath:    certPath,
		keyPath:     keyPath,
	}
}
