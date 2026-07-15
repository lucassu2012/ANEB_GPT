// capture：P1-C08 标定实验的 SSE 路径签名采集器（独立 main，不入 aneb-server 主二进制）。
//
// 连接 aneb-server /api/v1/stream，逐 token event 记录
// {seq, sched_us, pre_flush_us, arrival_us}，EOF 后以 JSONL（一行一事件）写入 -o 文件。
// arrival_us 为客户端单调时钟（time.Now() 的 monotonic 分量相对连接发起时刻的差，µs）。
//
// 代理策略（D-16：测量流量默认直连）：
//   - 默认 http.Transport{Proxy: nil}，忽略系统/环境代理；
//   - -proxy=http://127.0.0.1:33210 时显式经该代理中转（proxied 签名组专用）。
//
// 用法示例：
//
//	capture -url "http://127.0.0.1:8443/api/v1/stream?tokens=600&rate_tps=40" -o clean_run1.jsonl
//	capture -url "http://120.79.148.0:8443/api/v1/stream?tokens=600&rate_tps=40" -proxy http://127.0.0.1:33210 -o proxied_run1.jsonl
package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// tokenEvent 是 /stream token event 的 data JSON 中本工具关心的字段（payload 忽略）。
type tokenEvent struct {
	Seq        int64 `json:"seq"`
	SchedUs    int64 `json:"sched_us"`
	PreFlushUs int64 `json:"pre_flush_us"`
}

// record 是 JSONL 输出行：服务端三元组 + 客户端单调到达时刻。
type record struct {
	Seq        int64 `json:"seq"`
	SchedUs    int64 `json:"sched_us"`
	PreFlushUs int64 `json:"pre_flush_us"`
	ArrivalUs  int64 `json:"arrival_us"`
}

func main() {
	streamURL := flag.String("url", "", "full /api/v1/stream URL (required)")
	outPath := flag.String("o", "", "output JSONL file (required)")
	proxy := flag.String("proxy", "", "explicit HTTP proxy URL (empty = direct, system proxy DISABLED)")
	timeout := flag.Duration("timeout", 120*time.Second, "overall request timeout")
	flag.Parse()
	if *streamURL == "" || *outPath == "" {
		flag.Usage()
		os.Exit(2)
	}

	// 代理配置：默认 Proxy:nil 强制直连（不读 HTTP_PROXY/系统代理）；
	// -proxy 显式给定时才走代理（proxied 签名组）。
	tr := &http.Transport{Proxy: nil}
	if *proxy != "" {
		pu, err := url.Parse(*proxy)
		if err != nil {
			log.Fatalf("bad -proxy: %v", err)
		}
		tr.Proxy = http.ProxyURL(pu)
	}
	client := &http.Client{Transport: tr, Timeout: *timeout}

	req, err := http.NewRequest(http.MethodGet, *streamURL, nil)
	if err != nil {
		log.Fatalf("build request: %v", err)
	}
	req.Header.Set("Accept", "text/event-stream")

	anchor := time.Now() // 单调锚点：请求发出前
	resp, err := client.Do(req)
	if err != nil {
		log.Fatalf("request: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		log.Fatalf("unexpected status: %s", resp.Status)
	}
	log.Printf("connected: %s (X-Aneb-Server=%s, proxy=%q)",
		*streamURL, resp.Header.Get("X-Aneb-Server"), *proxy)

	records, summaryLine, err := readSSE(bufio.NewReaderSize(resp.Body, 64*1024), anchor)
	if err != nil {
		log.Fatalf("read stream: %v", err)
	}

	f, err := os.Create(*outPath)
	if err != nil {
		log.Fatalf("create output: %v", err)
	}
	defer f.Close()
	w := bufio.NewWriter(f)
	enc := json.NewEncoder(w)
	for _, rec := range records {
		if err := enc.Encode(rec); err != nil {
			log.Fatalf("write output: %v", err)
		}
	}
	if err := w.Flush(); err != nil {
		log.Fatalf("flush output: %v", err)
	}
	fmt.Printf("captured %d token events -> %s (summary_present=%v)\n",
		len(records), *outPath, summaryLine != "")
}

// readSSE 逐行解析 SSE 流。arrival_us 取「读到该 event 首行（event: token）」
// 的时刻——即该 event 字节抵达客户端并被读线程取走的最近时刻。
// 返回全部 token 记录与（可选的）summary data 行原文。
func readSSE(r *bufio.Reader, anchor time.Time) ([]record, string, error) {
	var (
		records     []record
		summaryLine string
		curEvent    string
		curArrival  int64
		haveArrival bool
	)
	for {
		line, err := r.ReadString('\n')
		now := time.Since(anchor).Microseconds()
		if len(line) > 0 {
			line = strings.TrimRight(line, "\r\n")
			switch {
			case line == "":
				// event 边界：复位
				curEvent, haveArrival = "", false
			case strings.HasPrefix(line, ":"):
				// 注释帧（prelude）：忽略
			case strings.HasPrefix(line, "event: "):
				curEvent = strings.TrimPrefix(line, "event: ")
				curArrival, haveArrival = now, true
			case strings.HasPrefix(line, "data: "):
				data := strings.TrimPrefix(line, "data: ")
				if curEvent == "token" {
					var ev tokenEvent
					if jsonErr := json.Unmarshal([]byte(data), &ev); jsonErr != nil {
						// 畸形 data（如注入流）：跳过不静默错位——记录到 stderr
						log.Printf("skip malformed data line: %v", jsonErr)
						break
					}
					arr := now
					if haveArrival {
						arr = curArrival
					}
					records = append(records, record{
						Seq: ev.Seq, SchedUs: ev.SchedUs, PreFlushUs: ev.PreFlushUs, ArrivalUs: arr,
					})
				} else if curEvent == "summary" {
					summaryLine = data
				}
			}
		}
		if err != nil {
			// EOF 属正常收尾；其余错误上抛
			if errors.Is(err, io.EOF) {
				return records, summaryLine, nil
			}
			return records, summaryLine, err
		}
	}
}
