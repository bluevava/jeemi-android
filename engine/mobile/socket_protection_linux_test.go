//go:build android || linux

package mobile

import (
	"io"
	"net"
	"os"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

type testProtector func(int) bool

func (f testProtector) Protect(fd int) bool { return f(fd) }

func protectionRequest(address, token string, descriptors ...int) bool {
	connection, err := net.DialTimeout("unix", address, time.Second)
	if err != nil {
		return false
	}
	defer connection.Close()
	connection.SetDeadline(time.Now().Add(time.Second))
	socket := connection.(*net.UnixConn)
	if _, _, err := socket.WriteMsgUnix([]byte(token), syscall.UnixRights(descriptors...), nil); err != nil {
		return false
	}
	var result [1]byte
	_, err = io.ReadFull(socket, result[:])
	return err == nil && result[0] == 1
}

func TestSocketProtectionTransfersDuplicatesAndFailsClosed(t *testing.T) {
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer syscall.Close(fd)
	var called atomic.Int32
	server, address, token, err := protectionServer(testProtector(func(received int) bool {
		called.Add(1)
		if received == fd {
			t.Error("received child descriptor number, not a duplicate")
		}
		return syscall.SetsockoptInt(received, syscall.SOL_SOCKET, syscall.SO_KEEPALIVE, 1) == nil
	}))
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	if !protectionRequest(address, token, fd) {
		t.Fatal("valid socket was not protected")
	}
	value, err := syscall.GetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_KEEPALIVE)
	if err != nil || value != 1 {
		t.Fatal("protection did not affect the original socket")
	}
	if protectionRequest(address, strings.Repeat("0", 64), fd) || protectionRequest(address, token, fd, fd) || protectionRequest(address, token) {
		t.Fatal("unauthenticated or invalid FD count accepted")
	}
	file, err := os.CreateTemp(t.TempDir(), "not-a-socket")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	if protectionRequest(address, token, int(file.Fd())) {
		t.Fatal("regular file accepted")
	}
	if called.Load() != 1 {
		t.Fatal("invalid requests reached VpnService")
	}
	server.Close()
	if protectionRequest(address, token, fd) {
		t.Fatal("closed session accepted a socket")
	}
	denied, address, token, err := protectionServer(testProtector(func(int) bool { return false }))
	if err != nil {
		t.Fatal(err)
	}
	defer denied.Close()
	if protectionRequest(address, token, fd) {
		t.Fatal("failed protection was treated as success")
	}
	if _, err := syscall.GetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_KEEPALIVE); err != nil {
		t.Fatal("bridge closed caller socket")
	}
}
