//go:build linux

package main

import (
	"fmt"
	"os"
	"os/user"
	"strconv"
	"syscall"
)

func validateRootTrustFile(info os.FileInfo) error {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return fmt.Errorf("cannot determine file owner")
	}
	return validateRootTrustPolicy(info.Mode(), stat.Uid == 0)
}

func validatePrivateKeyOwner(info os.FileInfo) error {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return fmt.Errorf("cannot determine file owner")
	}
	serviceUser, err := user.Lookup("aneb-gateway")
	if err != nil {
		if stat.Uid == 0 || stat.Uid == uint32(os.Geteuid()) {
			return nil
		}
		return fmt.Errorf("must be owned by root, the service process, or aneb-gateway")
	}
	serviceUID, err := strconv.ParseUint(serviceUser.Uid, 10, 32)
	if err != nil || !allowedPrivateKeyOwner(stat.Uid, uint32(os.Geteuid()), uint32(serviceUID)) {
		return fmt.Errorf("must be owned by root, the service process, or aneb-gateway")
	}
	return nil
}
