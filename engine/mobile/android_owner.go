package mobile

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"time"
)

// ConnectionOwner is implemented by the active Android VpnService. The
// callback returns only a UID and an unambiguous package name, never an inventory.
type ConnectionOwner interface {
	Lookup(network, source, destination string, sourcePort, destinationPort int) string
}

func ownerServer(owner ConnectionOwner) (*http.Server, string, string, error) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, "", "", err
	}
	var nonce [32]byte
	if _, err = rand.Read(nonce[:]); err != nil {
		listener.Close()
		return nil, "", "", err
	}
	token := hex.EncodeToString(nonce[:])
	slots := make(chan struct{}, 16)
	server := &http.Server{ReadHeaderTimeout: time.Second, ReadTimeout: time.Second, WriteTimeout: 2 * time.Second, IdleTimeout: 5 * time.Second, MaxHeaderBytes: 4096}
	server.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" || r.URL.Path != "/owner" || len(r.RequestURI) > 2048 || subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+token)) != 1 {
			http.Error(w, "denied", 403)
			return
		}
		q := r.URL.Query()
		network, source, destination := q.Get("network"), q.Get("source"), q.Get("destination")
		src, e1 := netip.ParseAddr(source)
		dst, e2 := netip.ParseAddr(destination)
		sp, e3 := strconv.Atoi(q.Get("sourcePort"))
		dp, e4 := strconv.Atoi(q.Get("destinationPort"))
		if (network != "tcp" && network != "udp") || e1 != nil || e2 != nil || e3 != nil || e4 != nil || sp < 1 || sp > 65535 || dp < 1 || dp > 65535 {
			http.Error(w, "invalid", 400)
			return
		}
		select {
		case slots <- struct{}{}:
			defer func() { <-slots }()
		default:
			http.Error(w, "busy", 503)
			return
		}
		result := owner.Lookup(network, src.Unmap().String(), dst.Unmap().String(), sp, dp)
		if len(result) > 4096 || result == "" {
			http.Error(w, "unavailable", 404)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, result)
	})
	go func() { _ = server.Serve(listener) }()
	return server, listener.Addr().String(), token, nil
}

// StartCoreWithOwner installs the Android package resolver for this child only.
func StartCoreWithOwner(executable, home string, descriptor int, owner ConnectionOwner) (*CoreProcess, error) {
	return startCore(executable, home, descriptor, owner)
}
