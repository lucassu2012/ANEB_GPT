//go:build linux

package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strings"

	"golang.org/x/sys/unix"
)

const maximumPersistedResultArchives = 4096

type openedResultArchive struct {
	name string
	fd   int
	dev  uint64
	ino  uint64
}

func hardenExistingResultsStoragePlatform(dataDir string) error {
	trustedDataDir, err := openTrustedDataDirectory(dataDir)
	if err != nil {
		return err
	}
	if trustedDataDir < 0 {
		return nil
	}
	defer unix.Close(trustedDataDir)

	directoryFD, err := unix.Openat2(
		trustedDataDir,
		"results",
		&unix.OpenHow{
			Flags: uint64(unix.O_RDONLY | unix.O_DIRECTORY | unix.O_NOFOLLOW | unix.O_CLOEXEC | unix.O_NONBLOCK),
			Resolve: unix.RESOLVE_BENEATH |
				unix.RESOLVE_NO_SYMLINKS,
		},
	)
	if errors.Is(err, unix.ENOENT) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("open existing result storage: %w", err)
	}
	directoryOpen := true
	defer func() {
		if directoryOpen {
			_ = unix.Close(directoryFD)
		}
	}()

	directory := os.NewFile(uintptr(directoryFD), filepath.Join(dataDir, "results"))
	directoryOpen = false
	var directoryMetadata unix.Stat_t
	if err := unix.Fstat(int(directory.Fd()), &directoryMetadata); err != nil {
		_ = directory.Close()
		return fmt.Errorf("stat existing result storage: %w", err)
	}
	if directoryMetadata.Uid != uint32(unix.Geteuid()) ||
		directoryMetadata.Gid != uint32(unix.Getegid()) {
		_ = directory.Close()
		return fmt.Errorf("existing result storage owner does not match service identity")
	}
	entries := make([]string, 0)
	for {
		batch, readErr := directory.Readdirnames(128)
		entries = append(entries, batch...)
		if len(entries) > maximumPersistedResultArchives {
			_ = directory.Close()
			return fmt.Errorf("existing result storage exceeds archive count bound")
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			_ = directory.Close()
			return fmt.Errorf("enumerate existing result storage: %w", readErr)
		}
	}
	sort.Strings(entries)
	opened := make([]openedResultArchive, 0, len(entries))
	closeOpened := func() {
		for _, archive := range opened {
			_ = unix.Close(archive.fd)
		}
	}
	for _, name := range entries {
		if !persistedResultFilePattern.MatchString(name) {
			closeOpened()
			_ = directory.Close()
			return fmt.Errorf("unexpected entry in existing result storage")
		}
		fd, openErr := unix.Openat(
			int(directory.Fd()),
			name,
			unix.O_RDONLY|unix.O_NOFOLLOW|unix.O_CLOEXEC|unix.O_NONBLOCK,
			0,
		)
		if openErr != nil {
			closeOpened()
			_ = directory.Close()
			return fmt.Errorf("open existing result file: %w", openErr)
		}
		var metadata unix.Stat_t
		if statErr := unix.Fstat(fd, &metadata); statErr != nil {
			_ = unix.Close(fd)
			closeOpened()
			_ = directory.Close()
			return fmt.Errorf("stat existing result file: %w", statErr)
		}
		if metadata.Mode&unix.S_IFMT != unix.S_IFREG || metadata.Nlink != 1 {
			_ = unix.Close(fd)
			closeOpened()
			_ = directory.Close()
			return fmt.Errorf("existing result entry must be a single-link regular file")
		}
		if metadata.Uid != uint32(unix.Geteuid()) || metadata.Gid != uint32(unix.Getegid()) {
			_ = unix.Close(fd)
			closeOpened()
			_ = directory.Close()
			return fmt.Errorf("existing result file owner does not match service identity")
		}
		opened = append(opened, openedResultArchive{
			name: name,
			fd:   fd,
			dev:  uint64(metadata.Dev),
			ino:  metadata.Ino,
		})
	}

	if err := unix.Fchmod(int(directory.Fd()), 0o700); err != nil {
		closeOpened()
		_ = directory.Close()
		return fmt.Errorf("harden result storage directory: %w", err)
	}
	for _, archive := range opened {
		if err := unix.Fchmod(archive.fd, 0o600); err != nil {
			closeOpened()
			_ = directory.Close()
			return fmt.Errorf("harden existing result file %s: %w", archive.name, err)
		}
	}
	for _, archive := range opened {
		var current unix.Stat_t
		if err := unix.Fstatat(
			int(directory.Fd()), archive.name, &current, unix.AT_SYMLINK_NOFOLLOW,
		); err != nil {
			closeOpened()
			_ = directory.Close()
			return fmt.Errorf("revalidate existing result file %s: %w", archive.name, err)
		}
		if uint64(current.Dev) != archive.dev || current.Ino != archive.ino ||
			current.Mode&unix.S_IFMT != unix.S_IFREG || current.Nlink != 1 {
			closeOpened()
			_ = directory.Close()
			return fmt.Errorf("existing result file changed during hardening")
		}
	}
	if _, err := directory.Seek(0, io.SeekStart); err != nil {
		closeOpened()
		_ = directory.Close()
		return fmt.Errorf("rewind existing result storage: %w", err)
	}
	finalEntries := make([]string, 0, len(entries))
	for {
		batch, readErr := directory.Readdirnames(128)
		finalEntries = append(finalEntries, batch...)
		if len(finalEntries) > maximumPersistedResultArchives {
			closeOpened()
			_ = directory.Close()
			return fmt.Errorf("existing result storage exceeds archive count bound")
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			closeOpened()
			_ = directory.Close()
			return fmt.Errorf("re-enumerate existing result storage: %w", readErr)
		}
	}
	sort.Strings(finalEntries)
	if !slices.Equal(entries, finalEntries) {
		closeOpened()
		_ = directory.Close()
		return fmt.Errorf("existing result storage changed during hardening")
	}
	var currentDirectory unix.Stat_t
	if err := unix.Fstatat(
		trustedDataDir, "results", &currentDirectory, unix.AT_SYMLINK_NOFOLLOW,
	); err != nil {
		closeOpened()
		_ = directory.Close()
		return fmt.Errorf("revalidate existing result storage: %w", err)
	}
	if uint64(currentDirectory.Dev) != uint64(directoryMetadata.Dev) ||
		currentDirectory.Ino != directoryMetadata.Ino ||
		currentDirectory.Mode&unix.S_IFMT != unix.S_IFDIR {
		closeOpened()
		_ = directory.Close()
		return fmt.Errorf("existing result storage changed during hardening")
	}
	for index, archive := range opened {
		if err := unix.Close(archive.fd); err != nil {
			for _, remaining := range opened[index+1:] {
				_ = unix.Close(remaining.fd)
			}
			_ = directory.Close()
			return fmt.Errorf("close existing result file %s: %w", archive.name, err)
		}
	}
	if err := directory.Close(); err != nil {
		return fmt.Errorf("close existing result storage: %w", err)
	}
	return nil
}

func openTrustedDataDirectory(dataDir string) (int, error) {
	absolute, err := filepath.Abs(dataDir)
	if err != nil {
		return -1, fmt.Errorf("resolve data directory: %w", err)
	}
	rootFD, err := unix.Open(
		string(filepath.Separator),
		unix.O_RDONLY|unix.O_DIRECTORY|unix.O_CLOEXEC,
		0,
	)
	if err != nil {
		return -1, fmt.Errorf("open filesystem root: %w", err)
	}
	defer unix.Close(rootFD)
	relative := strings.TrimPrefix(filepath.Clean(absolute), string(filepath.Separator))
	var fd int
	if relative == "" {
		fd, err = unix.Dup(rootFD)
		if err == nil {
			unix.CloseOnExec(fd)
		}
	} else {
		fd, err = unix.Openat2(
			rootFD,
			relative,
			&unix.OpenHow{
				Flags: uint64(unix.O_RDONLY | unix.O_DIRECTORY | unix.O_NOFOLLOW | unix.O_CLOEXEC | unix.O_NONBLOCK),
				Resolve: unix.RESOLVE_BENEATH |
					unix.RESOLVE_NO_SYMLINKS,
			},
		)
	}
	if errors.Is(err, unix.ENOENT) {
		return -1, nil
	}
	if err != nil {
		return -1, fmt.Errorf("open trusted data directory: %w", err)
	}
	var metadata unix.Stat_t
	if err := unix.Fstat(fd, &metadata); err != nil {
		_ = unix.Close(fd)
		return -1, fmt.Errorf("stat trusted data directory: %w", err)
	}
	if metadata.Uid != uint32(unix.Geteuid()) || metadata.Gid != uint32(unix.Getegid()) {
		_ = unix.Close(fd)
		return -1, fmt.Errorf("data directory owner does not match service identity")
	}
	return fd, nil
}
