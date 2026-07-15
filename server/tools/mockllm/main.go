// mockllm：阶段 2 API 探针的本机 OpenAI 兼容 SSE mock（独立 main，不入 aneb-server 主二进制）。
//
// 目的：无真实 API key（E-03 未就绪）时验证探针全链路——请求构造、SSE 批读打戳、
// OpenAI 兼容协议适配器解析、TTFT/ITL 计算、Room 落库与 APIPROBE_RESULT 日志合同。
// 模拟器内探针连 http://10.0.2.2:<port>（走系统默认网络，探针豁免 D-16，见 ApiProbe KDoc）。
//
// 行为：POST /v1/chat/completions（stream 必须为 true 语义，本 mock 一律流式回）
//   - 校验 Authorization: Bearer 非空（缺失回 401，验证探针 key header 路径）；
//   - 固定 -tokens 个 content chunk 按 -tps 节奏（绝对时刻表 pacing，同主服务端防漂移
//     口径）逐帧 Flush；首帧为 role 帧（content=""，探针不得计为 token）；
//   - 尾帧带 finish_reason=stop 与 usage（Kimi 风格），随后 data: [DONE]。
//
// 用法：
//
//	go run ./tools/mockllm -addr :18081 -tokens 20 -tps 20
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"
)

var (
	addr   = flag.String("addr", ":18081", "listen address")
	tokens = flag.Int("tokens", 20, "content chunks per completion")
	tps    = flag.Float64("tps", 20, "tokens per second (absolute-schedule pacing)")
)

type delta struct {
	Role    string `json:"role,omitempty"`
	Content string `json:"content,omitempty"`
}

type choice struct {
	Index        int     `json:"index"`
	Delta        delta   `json:"delta"`
	FinishReason *string `json:"finish_reason"`
}

type usage struct {
	PromptTokens     int `json:"prompt_tokens"`
	CompletionTokens int `json:"completion_tokens"`
	TotalTokens      int `json:"total_tokens"`
}

type chunk struct {
	ID      string   `json:"id"`
	Object  string   `json:"object"`
	Created int64    `json:"created"`
	Model   string   `json:"model"`
	Choices []choice `json:"choices"`
	Usage   *usage   `json:"usage,omitempty"`
}

func writeChunk(w http.ResponseWriter, f http.Flusher, c chunk) error {
	b, err := json.Marshal(c)
	if err != nil {
		return err
	}
	if _, err := fmt.Fprintf(w, "data: %s\n\n", b); err != nil {
		return err
	}
	f.Flush()
	return nil
}

func completions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":{"type":"invalid_request_error","message":"POST only"}}`, http.StatusMethodNotAllowed)
		return
	}
	auth := r.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") || strings.TrimSpace(strings.TrimPrefix(auth, "Bearer ")) == "" {
		// 401：验证探针把 key 放进了 Authorization header（mock 不校验 key 值）
		http.Error(w, `{"error":{"type":"authentication_error","message":"missing bearer token"}}`, http.StatusUnauthorized)
		return
	}
	var req struct {
		Model     string `json:"model"`
		MaxTokens int    `json:"max_tokens"`
		Stream    bool   `json:"stream"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":{"type":"invalid_request_error","message":"bad json"}}`, http.StatusBadRequest)
		return
	}
	f, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	model := req.Model
	if model == "" {
		model = "mock-llm"
	}
	n := *tokens
	// 尊重探针的 max_tokens 硬顶（烧钱护栏语义在 mock 侧同样成立）
	if req.MaxTokens > 0 && req.MaxTokens < n {
		n = req.MaxTokens
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(http.StatusOK)

	id := fmt.Sprintf("chatcmpl-mock-%d", time.Now().UnixNano())
	created := time.Now().Unix()
	base := chunk{ID: id, Object: "chat.completion.chunk", Created: created, Model: model}

	// 首帧：role 帧（content 空——探针适配器不得计为 token 到达）
	first := base
	first.Choices = []choice{{Index: 0, Delta: delta{Role: "assistant", Content: ""}}}
	if err := writeChunk(w, f, first); err != nil {
		return
	}

	// 绝对时刻表 pacing（同主服务端口径：time.Until(start+expected)，防累加漂移）
	interval := time.Duration(float64(time.Second) / *tps)
	start := time.Now()
	for i := 0; i < n; i++ {
		time.Sleep(time.Until(start.Add(time.Duration(i+1) * interval)))
		c := base
		c.Choices = []choice{{Index: 0, Delta: delta{Content: fmt.Sprintf("词%d", i)}}}
		if err := writeChunk(w, f, c); err != nil {
			return
		}
	}

	// 尾帧：finish_reason + usage（Kimi 风格随尾帧携带）
	stop := "stop"
	last := base
	last.Choices = []choice{{Index: 0, Delta: delta{}, FinishReason: &stop}}
	last.Usage = &usage{PromptTokens: 12, CompletionTokens: n, TotalTokens: 12 + n}
	if err := writeChunk(w, f, last); err != nil {
		return
	}
	if _, err := fmt.Fprint(w, "data: [DONE]\n\n"); err != nil {
		return
	}
	f.Flush()
	log.Printf("completions served: model=%s tokens=%d tps=%.1f remote=%s", model, n, *tps, r.RemoteAddr)
}

func main() {
	flag.Parse()
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/chat/completions", completions)
	log.Printf("mockllm listening on %s (tokens=%d tps=%.1f)", *addr, *tokens, *tps)
	log.Fatal(http.ListenAndServe(*addr, mux))
}
