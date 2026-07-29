// aneb-server：ANEB Probe 的 Agent 流量仿真服务器（阶段 0）。
// HTTP/SSE 主路径使用标准库；实时交互端点使用 gorilla/websocket。
// 与《测量红队清单》R-04/R-06/R-07/R-08/R-17/R-20/R-23/R-24。
package main

import (
	"crypto/tls"
	"flag"
	"log"
	"net"
	"net/http"
	"sync"
	"time"
)

const serverVersion = "aneb-server/0.8.3"

// app 汇集全部 handler 依赖（profile 表、数据目录、故障注入开关）。
type app struct {
	profiles              map[string]*Profile
	dataDir               string
	executionCapabilities serverCapabilityReceipt
	requestAudit          requestAuditEmitter
	realtimeSummary       realtimeProtocolSummaryEmitter
	// allowInject 放行 /stream 的 &inject= 故障注入钩子（P0-C13 前置：
	// 客户端 seq join/截断/畸形 event 健壮性验收需要服务端可控注入）。
	// 默认 false；生产/取证部署绝不开启——注入流不是测量数据。
	allowInject bool
	// h3Enabled 记录 -h3 开关状态，仅供 /serverinfo 如实上报（h3_enabled）。
	// 注意其语义是"服务端配置启用了 h3"，不是"本响应经 h3 协商"——后者
	// 看 X-Aneb-Proto 头（红队项：QUIC 启用 ≠ 协商 h3）。
	h3Enabled bool
	resultsMu sync.Mutex
	// impairments keeps per-run, aggregate user-space rate limiters. It never
	// changes host qdisc/firewall/radio state, so normal and concurrent runs
	// remain isolated from synthetic weak-network traffic.
	impairments syntheticImpairmentRegistry
}

// routes 构建完整 handler 树（含 X-Aneb-Server 版本头中间件）。
func (a *app) routes() http.Handler {
	api := http.NewServeMux()
	api.HandleFunc("/api/v1/echo", a.handleEcho)
	api.HandleFunc("/api/v1/profiles", a.handleProfiles)
	api.HandleFunc("/api/v1/stream", a.handleStream)
	api.HandleFunc("/api/v1/token-sim", a.handleTokenSim)
	api.HandleFunc("/api/v1/realtime-sim", a.handleRealtimeSim)
	api.HandleFunc("/api/v1/download", a.handleDownload)
	api.HandleFunc("/api/v1/upload", a.handleUpload)
	api.HandleFunc("/api/v1/toolloop", a.handleToolLoop)
	api.HandleFunc("/api/v1/results", a.handleResults)
	api.HandleFunc("/api/v1/serverinfo", a.handleServerInfo)
	api.HandleFunc("/api/v1/impairments", a.handleSyntheticImpairments)
	auditSink := a.requestAudit
	if auditSink == nil {
		auditSink = defaultRequestAuditSink()
	}

	root := http.NewServeMux()
	root.Handle("/synthetic/", a.syntheticImpairmentHandler(api))
	root.Handle("/", api)
	// Audit outside the synthetic impairment layer so malformed, unsupported,
	// and active-outage early returns remain visible exactly once.
	return withServerHeader(withRequestAuditSink(root, auditSink))
}

// withServerHeader 为所有响应附加 X-Aneb-Server 版本头——服务端指纹，
// 供客户端做路径劫持检测（响应不带指纹即判路径劫持而非计入失败率）。
func withServerHeader(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Aneb-Server", serverVersion)
		next.ServeHTTP(w, r)
	})
}

func main() {
	addr := flag.String("addr", ":8443", "listen address")
	// 默认路径用正斜杠：Go 在 Windows 同样接受，目标部署环境（Linux VM）
	// 反斜杠不是路径分隔符，`..\profiles` 会被当成字面文件名导致启动失败。
	profilesDir := flag.String("profiles", "../profiles", "profiles directory (versioned scenario JSON)")
	executionProfilesDir := flag.String("execution-profiles", "../profiles/published", "published Profile bundles used for execution capability preflight")
	dataDir := flag.String("data", "./data", "data directory (results JSONL)")
	tlsCert := flag.String("tls-cert", "", "TLS certificate file (optional; default/LE cert for named SNI)")
	tlsKey := flag.String("tls-key", "", "TLS key file (optional)")
	// SNI 双通道：-tls-cert-ip/-tls-key-ip 指向自签 IP-SAN 证书（含 IP:120.79.148.0），
	// 供蜂窝 bare-IP 通道。缺省则 bare-IP 分支回退默认证书并日志告警（fail-open）。
	tlsCertIP := flag.String("tls-cert-ip", "", "TLS certificate file for bare-IP/empty-SNI (IP-SAN); optional")
	tlsKeyIP := flag.String("tls-key-ip", "", "TLS key file for bare-IP/empty-SNI; optional")
	allowInject := flag.Bool("allow-inject", false,
		"enable /stream fault-injection hooks (&inject=...) — test rigs only, NEVER in production")
	h3Enabled := flag.Bool("h3", false,
		"serve HTTP/3 (quic-go) on the same port over UDP in parallel with TCP — requires -tls-cert/-tls-key (fail-closed)")
	udpEchoAddr := flag.String("udp-echo-addr", ":8443", "ANEB sequenced UDP echo address; may share the h3 UDP port")
	flag.Parse()

	// 配置级 fail-closed 校验放在一切资源加载之前：配错即刻退出。
	altSvc := ""
	if *h3Enabled {
		if err := validateH3Prereqs(*tlsCert, *tlsKey); err != nil {
			log.Fatalf("h3: %v", err)
		}
		v, err := altSvcValue(*addr)
		if err != nil {
			log.Fatalf("h3: %v", err)
		}
		altSvc = v
	}
	if *tlsCert != "" || *tlsKey != "" {
		if *tlsCert == "" || *tlsKey == "" {
			log.Fatalf("tls: -tls-cert and -tls-key must be given together")
		}
		if err := validateTLSFiles(*tlsCert, *tlsKey); err != nil {
			log.Fatalf("tls: %v", err)
		}
	}
	if err := validateIPCertPair(*tlsCertIP, *tlsKeyIP); err != nil {
		log.Fatalf("tls: %v", err)
	}
	// -tls-cert-ip 只在启用 TLS（给了默认证书）时有意义。
	if (*tlsCertIP != "" || *tlsKeyIP != "") && (*tlsCert == "" || *tlsKey == "") {
		log.Fatalf("tls: -tls-cert-ip/-tls-key-ip require -tls-cert/-tls-key (SNI selection needs a default cert)")
	}

	profiles, err := loadProfiles(*profilesDir)
	if err != nil {
		log.Fatalf("load profiles: %v", err)
	}
	for id, p := range profiles {
		log.Printf("profile loaded: %s v%s (%d phases)", id, p.Version, len(p.Phases))
	}
	executionCapabilities, err := loadExecutionCapabilityReceipt(*executionProfilesDir)
	if err != nil {
		log.Fatalf("load execution profiles: %v", err)
	}
	if err := validateExecutionRuntimeConfig(executionCapabilities, *addr, *udpEchoAddr); err != nil {
		log.Fatalf("execution runtime: %v", err)
	}
	for _, profile := range executionCapabilities.ValidatedProfiles {
		log.Printf("execution profile validated: %s v%s (%s)", profile.ProfileID, profile.ProfileVersion, profile.ProfileSHA256)
	}

	// Use the process singleton so one instance identity, one worker, and one
	// contiguous sequence cover the entire server lifetime.
	auditSink := defaultRequestAuditSink()
	a := &app{
		profiles:              profiles,
		dataDir:               *dataDir,
		executionCapabilities: executionCapabilities,
		requestAudit:          auditSink,
		allowInject:           *allowInject,
		h3Enabled:             *h3Enabled,
	}
	if *allowInject {
		log.Printf("WARNING: -allow-inject enabled — /stream accepts fault injection, runs are NOT evidential")
	}

	// 超时策略：
	//   - ReadHeaderTimeout 防 slowloris（只限制读请求头，不影响 SSE 响应体流式写出）；
	//   - IdleTimeout 回收 keep-alive 空闲连接，防连接堆积；
	//   - 刻意不设 WriteTimeout：流式端点（S2 流 ~90s）会被整连接写超时截断，
	//     交给客户端 readTimeout 兜底；/stream pacing 循环内另有 r.Context()
	//     断开检测，客户端断开即退出。
	// 启用 TLS 时构建 SNI 分流的 tls.Config（GetCertificate 回调）；TCP 与 h3 共用。
	var tlsConf *tls.Config
	if *tlsCert != "" && *tlsKey != "" {
		tc, err := newTLSConfig(*tlsCert, *tlsKey, *tlsCertIP, *tlsKeyIP)
		if err != nil {
			log.Fatalf("tls: %v", err)
		}
		tlsConf = tc
	}

	srv := &http.Server{
		Addr:              *addr,
		Handler:           a.tcpHandler(altSvc),
		TLSConfig:         tlsConf,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
		// P3-C05：把底层连接塞进每请求 context，供 /stream 流末尾对同一条
		// 连接读 TCP_INFO（tcpinfo.go）。h3 侧无此机制（QUIC 无 TCP_INFO），
		// summary 的 retrans_total 在 h3 上天然缺省（n/a）。
		ConnContext: connContext,
	}

	log.Printf("%s listening on %s (profiles=%s execution-profiles=%s data=%s, mono-anchor wall=%d)",
		serverVersion, *addr, *profilesDir, *executionProfilesDir, *dataDir, anchorWallUnixNs)

	// -h3：同端口 UDP 上并行起 http3.Server，复用同一路由树与中间件；
	// TCP 侧照旧（仅多 Alt-Svc/X-Aneb-Proto 头）。任一侧监听失败都整体
	// 退出——半瘸状态（只剩 TCP 却广告着 h3）会污染 A/B 分组。
	if *h3Enabled {
		h3srv := a.newH3Server(*addr, tlsConf)
		if *udpEchoAddr == *addr {
			packetConn, err := net.ListenPacket("udp", *addr)
			if err != nil {
				log.Fatalf("udp shared listener: %v", err)
			}
			go func() {
				log.Fatalf("h3 shared server: %v", h3srv.Serve(newUDPProbeFilteringConnWithAudit(packetConn, auditSink)))
			}()
			log.Printf("udp echo: ANEB datagram probe shares h3 udp%s", *addr)
		} else {
			go func() {
				log.Fatalf("h3 server: %v", h3srv.ListenAndServe())
			}()
		}
		log.Printf("h3: HTTP/3 enabled on udp%s (Alt-Svc: %s)", *addr, altSvc)
	}
	if *udpEchoAddr != "" && (!*h3Enabled || *udpEchoAddr != *addr) {
		go func() {
			log.Fatalf("udp echo server: %v", serveUDPEchoWithAudit(*udpEchoAddr, auditSink))
		}()
		log.Printf("udp echo: sequenced application datagram probe enabled on udp%s", *udpEchoAddr)
	}

	var serveErr error
	if *tlsCert != "" && *tlsKey != "" {
		// TLSConfig 已含 GetCertificate（SNI 分流），证书文件参数留空。
		serveErr = srv.ListenAndServeTLS("", "")
	} else {
		log.Printf("WARNING: no -tls-cert/-tls-key given, serving PLAINTEXT HTTP — dev only, do not use for evidential runs")
		serveErr = srv.ListenAndServe()
	}
	auditSink.Close()
	log.Fatal(serveErr)
}
