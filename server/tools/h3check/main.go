// h3check：P2-C05 诊断工具——用 quic-go http3 客户端直连验证服务端 h3 栈
// （独立于 Android/Cronet，切分"服务端 h3 不通"与"客户端/NAT 路径不通"）。
// 仅联调诊断用：-insecure 跳过证书校验（自签联调证书场景）。
package main

import (
	"crypto/tls"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"

	"github.com/quic-go/quic-go/http3"
)

func main() {
	url := flag.String("url", "https://127.0.0.1:8443/api/v1/serverinfo", "URL to GET over HTTP/3")
	insecure := flag.Bool("insecure", true, "skip TLS verification (local self-signed cert)")
	flag.Parse()

	rt := &http3.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: *insecure},
	}
	defer rt.Close()
	client := &http.Client{Transport: rt}

	resp, err := client.Get(*url)
	if err != nil {
		log.Fatalf("h3 GET %s failed: %v", *url, err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	fmt.Printf("proto=%s status=%d x-aneb-proto=%q\nbody=%s\n",
		resp.Proto, resp.StatusCode, resp.Header.Get("X-Aneb-Proto"), string(body))
}
