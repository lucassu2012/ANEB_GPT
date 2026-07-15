// gencert：P2-C05 本地双栈（https+h3）联调用自签证书生成器。
//
// 生成一张自签 ECDSA P-256 证书（IsCA=true，同时作为服务端叶证书与客户端信任锚），
// SAN 含 IP:10.0.2.2（模拟器→宿主机）、IP:127.0.0.1（回环/adb reverse）与 DNS:localhost。
// 输出 PEM：aneb_local_cert.pem / aneb_local_key.pem。
//
// 用途边界：仅本地联调（客户端 debug 变体 network_security_config 信任锚 +
// server -tls-cert/-tls-key）。绝不用于生产/取证部署——私钥就在开发机上，
// 该证书不能证明任何路径完整性。
package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"flag"
	"log"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"
)

func main() {
	outDir := flag.String("out", ".", "output directory for the generated PEM pair")
	days := flag.Int("days", 730, "validity in days")
	// mode=local（默认）：既有本地联调证书（SAN IP:10.0.2.2/127.0.0.1 + DNS:localhost）。
	// mode=ip：阶段 3 bare-IP 蜂窝通道自签 IP-SAN 证书（SAN IP:<-ip>），
	//   兼作叶证书（服务端 -tls-cert-ip）与客户端信任锚（debug res/raw/aneb_ip_ca.pem）。
	mode := flag.String("mode", "local", "certificate mode: local (dev loopback SAN) or ip (bare-IP IP-SAN)")
	ipStr := flag.String("ip", "120.79.148.0", "public IP for IP-SAN in -mode=ip (E-01 bare-IP cellular path)")
	flag.Parse()

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		log.Fatalf("generate key: %v", err)
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		log.Fatalf("serial: %v", err)
	}

	tmpl := x509.Certificate{
		SerialNumber: serial,
		NotBefore:    time.Now().Add(-1 * time.Hour),
		NotAfter:     time.Now().Add(time.Duration(*days) * 24 * time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment |
			x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  true, // 自签叶=信任锚（NSC <certificates src="@raw/...">）
	}

	var certName, keyName, sanDesc string
	switch *mode {
	case "local":
		tmpl.Subject = pkix.Name{CommonName: "ANEB Local Dev CA (P2-C05)", Organization: []string{"ANEB"}}
		tmpl.IPAddresses = []net.IP{net.ParseIP("10.0.2.2"), net.ParseIP("127.0.0.1")}
		tmpl.DNSNames = []string{"localhost"}
		certName, keyName = "aneb_local_cert.pem", "aneb_local_key.pem"
		sanDesc = "IP:10.0.2.2, IP:127.0.0.1, DNS:localhost"
	case "ip":
		ip := net.ParseIP(*ipStr)
		if ip == nil {
			log.Fatalf("mode=ip: invalid -ip %q", *ipStr)
		}
		tmpl.Subject = pkix.Name{CommonName: "ANEB IP-SAN CA (" + *ipStr + ")", Organization: []string{"ANEB"}}
		tmpl.IPAddresses = []net.IP{ip}
		certName, keyName = "aneb_ip_cert.pem", "aneb_ip_key.pem"
		sanDesc = "IP:" + *ipStr
	default:
		log.Fatalf("unknown -mode %q (want local|ip)", *mode)
	}

	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		log.Fatalf("create certificate: %v", err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		log.Fatalf("marshal key: %v", err)
	}

	if err := os.MkdirAll(*outDir, 0o755); err != nil {
		log.Fatalf("mkdir %s: %v", *outDir, err)
	}
	certPath := filepath.Join(*outDir, certName)
	keyPath := filepath.Join(*outDir, keyName)

	writePEM(certPath, "CERTIFICATE", der, 0o644)
	writePEM(keyPath, "EC PRIVATE KEY", keyDER, 0o600)
	log.Printf("written %s and %s (mode=%s SAN: %s; %d days)",
		certPath, keyPath, *mode, sanDesc, *days)
}

func writePEM(path, blockType string, der []byte, mode os.FileMode) {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, mode)
	if err != nil {
		log.Fatalf("open %s: %v", path, err)
	}
	defer f.Close()
	if err := pem.Encode(f, &pem.Block{Type: blockType, Bytes: der}); err != nil {
		log.Fatalf("encode %s: %v", path, err)
	}
}
