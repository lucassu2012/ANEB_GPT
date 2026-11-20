package main

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// profile 声明 token_bytes.histogram：流内 token 字节仅取声明桶值。
func TestStreamHistogramFromProfile(t *testing.T) {
	prof := &Profile{
		ProfileID: "hist_prof", Version: "t@1",
		Phases: []Phase{{
			Type: "token_stream", Tokens: 30, RateTps: 5000, Seed: 1,
			TokenBytes: &TokenBytes{Histogram: []SizeBin{{Size: 100, Weight: 1}, {Size: 400, Weight: 1}}},
		}},
	}
	a := &app{profiles: map[string]*Profile{"hist_prof": prof}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	frames := fetchStream(t, srv.URL+"/api/v1/stream?profile=hist_prof")
	for i := 1; i <= 30; i++ {
		var td tokenData
		if err := json.Unmarshal([]byte(frames[i].data), &td); err != nil {
			t.Fatalf("token %d: %v", i, err)
		}
		raw, err := base64.StdEncoding.DecodeString(td.Payload)
		if err != nil {
			t.Fatalf("seq %d payload not base64", i)
		}
		if len(raw) != 100 && len(raw) != 400 {
			t.Fatalf("seq %d payload size %d, want 100 or 400 (histogram)", i, len(raw))
		}
	}
}

func TestStreamHistogramInvalid(t *testing.T) {
	bad := map[string][]SizeBin{
		"size too small": {{Size: 10, Weight: 1}},   // < tokenBytesMin(30)
		"size too big":   {{Size: 5000, Weight: 1}}, // > tokenBytesMax(2000)
		"weight zero":    {{Size: 100, Weight: 0}},
		"weight neg":     {{Size: 100, Weight: -1}},
	}
	for name, h := range bad {
		prof := &Profile{
			ProfileID: "hb", Version: "t@1",
			Phases: []Phase{{Type: "token_stream", Tokens: 5, RateTps: 5000, Seed: 1,
				TokenBytes: &TokenBytes{Histogram: h}}},
		}
		a := &app{profiles: map[string]*Profile{"hb": prof}, dataDir: t.TempDir()}
		srv := httptest.NewServer(a.routes())
		resp, err := http.Get(srv.URL + "/api/v1/stream?profile=hb")
		if err != nil {
			srv.Close()
			t.Fatalf("%s: %v", name, err)
		}
		code := resp.StatusCode
		resp.Body.Close()
		srv.Close()
		if code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400", name, code)
		}
	}
}
