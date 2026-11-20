//go:build !windows || !amd64

package main

import (
	"errors"
	"io"
)

func executeInspection([]byte, io.Writer) error {
	return errors.New("inspection execution requires Windows amd64; no fallback")
}

func inspectWorker([]byte, string) error {
	return errors.New("inspection worker requires Windows amd64; no fallback")
}

func executeLimit([]byte, io.Writer) error {
	return errors.New("limit execution requires Windows amd64; no fallback")
}

func limitWorker([]byte, string) error {
	return errors.New("limit worker requires Windows amd64; no fallback")
}
