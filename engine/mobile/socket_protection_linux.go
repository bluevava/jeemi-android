//go:build android || linux

package mobile

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
	"syscall"
	"time"
)

type socketProtectionServer struct {
	listener    *net.UnixListener
	mu          sync.Mutex
	closed      bool
	connections map[*net.UnixConn]bool
}

func (s *socketProtectionServer) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	err := s.listener.Close()
	for c := range s.connections {
		_ = c.Close()
	}
	return err
}

func protectionServer(protector SocketProtector) (io.Closer, string, string, error) {
	var nonce [32]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, "", "", err
	}
	token := hex.EncodeToString(nonce[:])
	address := "@jeemi-protect-" + token[:24]
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: address, Net: "unix"})
	if err != nil {
		return nil, "", "", err
	}
	server := &socketProtectionServer{listener: listener, connections: make(map[*net.UnixConn]bool)}
	go func() {
		for {
			connection, err := listener.AcceptUnix()
			if err != nil {
				return
			}
			server.mu.Lock()
			if server.closed || len(server.connections) >= 64 {
				server.mu.Unlock()
				connection.Close()
				continue
			}
			server.connections[connection] = true
			server.mu.Unlock()
			go func() {
				defer func() {
					connection.Close()
					server.mu.Lock()
					delete(server.connections, connection)
					server.mu.Unlock()
				}()
				_ = connection.SetDeadline(time.Now().Add(2 * time.Second))
				if !sameUID(connection) {
					return
				}
				if protectReceivedSocket(connection, token, protector) == nil {
					_, _ = connection.Write([]byte{1})
				}
			}()
		}
	}()
	return server, address, token, nil
}

func sameUID(connection *net.UnixConn) bool {
	raw, err := connection.SyscallConn()
	if err != nil {
		return false
	}
	matched := false
	if err := raw.Control(func(fd uintptr) {
		cred, err := syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
		matched = err == nil && cred.Uid == uint32(os.Getuid())
	}); err != nil {
		return false
	}
	return matched
}

func protectReceivedSocket(connection *net.UnixConn, token string, protector SocketProtector) error {
	payload, control := make([]byte, 65), make([]byte, syscall.CmsgSpace(4*4))
	n, oob, flags, _, err := connection.ReadMsgUnix(payload, control)
	if err != nil {
		return err
	}
	messages, err := syscall.ParseSocketControlMessage(control[:oob])
	if err != nil {
		return err
	}
	var descriptors []int
	defer func() {
		for _, fd := range descriptors {
			syscall.Close(fd)
		}
	}()
	for _, message := range messages {
		fds, err := syscall.ParseUnixRights(&message)
		if err != nil {
			return err
		}
		descriptors = append(descriptors, fds...)
	}
	if flags&syscall.MSG_CTRUNC != 0 || len(descriptors) != 1 || n > len(token) {
		return fmt.Errorf("invalid protection request")
	}
	if n < len(token) {
		if _, err := io.ReadFull(connection, payload[n:len(token)]); err != nil {
			return err
		}
	}
	if subtle.ConstantTimeCompare(payload[:len(token)], []byte(token)) != 1 {
		return fmt.Errorf("denied")
	}
	fd := descriptors[0]
	syscall.CloseOnExec(fd)
	// Do not allow files, TUN descriptors or listeners to be submitted here.
	typeValue, err := syscall.GetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_TYPE)
	if err != nil || (typeValue != syscall.SOCK_STREAM && typeValue != syscall.SOCK_DGRAM) {
		return fmt.Errorf("not a socket")
	}
	address, err := syscall.Getsockname(fd)
	if err != nil {
		return err
	}
	switch address.(type) {
	case *syscall.SockaddrInet4, *syscall.SockaddrInet6:
	default:
		return fmt.Errorf("not an IP socket")
	}
	listening, err := syscall.GetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_ACCEPTCONN)
	if err != nil || listening != 0 || !protector.Protect(fd) {
		return fmt.Errorf("socket protection failed")
	}
	return nil
}
