package condition

// Address is the pinned WinDivert2.2.2 ABI, not a reconstructed IP address.
// It stays with its unchanged packet through reinjection.
type Address struct {
	TimestampQPC int64
	Word         uint32
	Reserved     uint32
	Data         [64]byte
}
