//go:build !windows || !amd64

package condition

import "errors"

func OpenNative(Plan, Mode, string) (Link, error) {
	return nil, errors.New("native forwarding supported only on Windows amd64; no fallback")
}
