//go:build android || linux

package mobile

import (
	"encoding/binary"
	"fmt"
	"syscall"
	"unsafe"
)

// AndroidTunnelActive observes the interface attached to the caller-owned TUN
// descriptor. It neither takes ownership nor reads packets or changes flags.
// Poll alone misses an Android-revoked interface that is still held open.
func AndroidTunnelActive(descriptor int) (bool, error) {
	if descriptor < 0 {
		return false, nil
	}
	// Linux ifreq has a 16-byte name and a union of at most 24 bytes on the
	// supported 64-bit ABIs. TUNGETIFF returns the name of this exact FD.
	var request [40]byte
	if err := tunnelIoctl(descriptor, syscall.TUNGETIFF, &request); err != 0 {
		return tunnelQueryError(err)
	}
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		return false, fmt.Errorf("tunnel_status_unavailable")
	}
	defer syscall.Close(fd)
	if err := tunnelIoctl(fd, syscall.SIOCGIFFLAGS, &request); err != 0 {
		return tunnelQueryError(err)
	}
	return binary.NativeEndian.Uint16(request[16:18])&syscall.IFF_UP != 0, nil
}

func tunnelIoctl(fd int, operation uintptr, request *[40]byte) syscall.Errno {
	for {
		_, _, err := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), operation, uintptr(unsafe.Pointer(request)))
		if err != syscall.EINTR {
			return err
		}
	}
}

func tunnelQueryError(err syscall.Errno) (bool, error) {
	switch err {
	case syscall.EBADF, syscall.ENODEV, syscall.ENXIO, syscall.ENOTTY, syscall.EINVAL:
		return false, nil
	default:
		return false, fmt.Errorf("tunnel_status_unavailable")
	}
}
