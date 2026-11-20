package condition

import (
	"testing"
	"unsafe"
)

func TestAddressMatchesPinnedWinDivert222ABI(t *testing.T) {
	var address Address
	if unsafe.Sizeof(address) != 80 || unsafe.Alignof(address) != 8 || unsafe.Offsetof(address.Word) != 8 || unsafe.Offsetof(address.Reserved) != 12 || unsafe.Offsetof(address.Data) != 16 {
		t.Fatalf("unsupported WinDivert ABI: size=%d align=%d word=%d reserved=%d union=%d", unsafe.Sizeof(address), unsafe.Alignof(address), unsafe.Offsetof(address.Word), unsafe.Offsetof(address.Reserved), unsafe.Offsetof(address.Data))
	}
}
