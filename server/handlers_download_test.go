package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestDownloadStreamsRequestedBytes(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/download?bytes=131123&chunk_kb=16")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusOK || len(body) != 131123 {
		t.Fatalf("status=%d bytes=%d", resp.StatusCode, len(body))
	}
	if got := resp.Header.Get("Content-Type"); got != "application/octet-stream" {
		t.Fatalf("content-type=%q", got)
	}
	if got := resp.Header.Get("Content-Encoding"); got != "identity" {
		t.Fatalf("content-encoding=%q", got)
	}
}

func TestDownloadRejectsInvalidParametersAndMethod(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	for _, path := range []string{
		"/api/v1/download?bytes=0",
		"/api/v1/download?bytes=not-a-number",
		"/api/v1/download?chunk_kb=2048",
	} {
		resp, err := http.Get(srv.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("%s status=%d", path, resp.StatusCode)
		}
	}

	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/api/v1/download", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("POST status=%d", resp.StatusCode)
	}
}
