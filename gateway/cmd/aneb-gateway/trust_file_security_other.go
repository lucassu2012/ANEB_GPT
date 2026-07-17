//go:build !linux

package main

import "os"

func validateRootTrustFile(os.FileInfo) error { return nil }

func validatePrivateKeyOwner(os.FileInfo) error { return nil }
