//go:build windows && amd64

package condition

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestNativeRejectsMissingPinnedDependencyBeforeLibraryOpen(t *testing.T) {
	plan, _ := NewPlan(Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000})
	link, err := OpenNative(plan, Inspect, t.TempDir())
	if link != nil || !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("missing pinned dependency not rejected: link=%v err=%v", link, err)
	}
}

func TestNativeNeverCallsSendForInspectOrSniffedCopies(t *testing.T) {
	for _, mode := range []Mode{Inspect, Limit} {
		frame := testForwardFrame(20)
		if mode == Limit {
			frame.Address.Word |= 0x10000
		}
		link := &nativeLink{mode: mode}
		n, err := link.Inject(frame)
		if n != 0 || !errors.Is(err, ErrSniffInjection) {
			t.Fatalf("mode%v copied original could be injected: n%d err%v", mode, n, err)
		}
	}
}

func TestNativeRejectsAlteredVendorBytesBeforeLibraryOpen(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "WinDivert.dll"), make([]byte, 47616), 0600); err != nil {
		t.Fatal(err)
	}
	plan, _ := NewPlan(Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000})
	link, err := OpenNative(plan, Inspect, dir)
	if link != nil || err == nil || !strings.Contains(err.Error(), "pinned dependency mismatch") {
		t.Fatalf("altered DLL accepted: %v %v", link, err)
	}
}
