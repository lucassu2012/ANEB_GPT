package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"fmt"
	"net/http"
	"os"
	"regexp"
	"runtime"
	"strings"
)

var resultUploadBearerPattern = regexp.MustCompile(`^[A-Fa-f0-9]{64}$`)

type resultUploadAuthorization interface {
	authorized(*http.Request) bool
}

func loadResultUploadAuthorizer(path string) (*resultUploadAuthorizer, error) {
	if strings.TrimSpace(path) == "" {
		return nil, fmt.Errorf("result upload token file path is empty")
	}
	info, err := os.Lstat(path)
	if err != nil {
		return nil, fmt.Errorf("inspect result upload token file: %w", err)
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("result upload token file must be a regular file")
	}
	if info.Size() <= 0 || info.Size() > 256 {
		return nil, fmt.Errorf("result upload token file size is invalid")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return nil, fmt.Errorf("result upload token file permissions must not grant group or other access")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read result upload token file: %w", err)
	}
	return newResultUploadAuthorizer(strings.TrimSpace(string(raw)))
}

type resultUploadAuthorizer struct {
	expectedAuthorizationHash [sha256.Size]byte
}

func newResultUploadAuthorizer(token string) (*resultUploadAuthorizer, error) {
	if !resultUploadBearerPattern.MatchString(token) {
		return nil, fmt.Errorf("result upload token must be exactly 32 random bytes encoded as 64 hex characters")
	}
	return &resultUploadAuthorizer{
		expectedAuthorizationHash: sha256.Sum256([]byte("Bearer " + token)),
	}, nil
}

func (a *resultUploadAuthorizer) authorized(request *http.Request) bool {
	actual := sha256.Sum256([]byte(request.Header.Get("Authorization")))
	return subtle.ConstantTimeCompare(actual[:], a.expectedAuthorizationHash[:]) == 1
}
