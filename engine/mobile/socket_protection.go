package mobile

// SocketProtector is implemented by the owning VpnService. The descriptor is
// a received duplicate in this process, never the child's numeric FD.
type SocketProtector interface {
	Protect(descriptor int) bool
}

// StartCoreProtected protects outbound sockets individually so system/mixed
// TCP forwarder replies can still return through the VPN's own UID.
func StartCoreProtected(executable, home string, descriptor int, owner ConnectionOwner, protector SocketProtector) (*CoreProcess, error) {
	return startCore(executable, home, descriptor, owner, protector)
}
