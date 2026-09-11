package mobile

import (
	"encoding/json"
	"fmt"
	"jeemi-android/engine/internal/config/document"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// CoreProcess owns the child; Android owns the original VPN descriptor.
type CoreProcess struct {
	process      coreChild
	stopped      atomic.Bool
	done         chan struct{}
	stopOnce     sync.Once
	tunnelStatus *tunnelStatus
	ownerServer  *http.Server
}

type coreChild interface {
	Wait() error
	Signal(os.Signal) error
	Kill() error
}
type tunnelStatus struct {
	mutex     sync.Mutex
	pending   string
	errorText string
}

func (s *tunnelStatus) Write(p []byte) (int, error) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.pending += string(p)
	for {
		index := strings.IndexByte(s.pending, '\n')
		if index < 0 {
			break
		}
		line := s.pending[:index]
		s.pending = s.pending[index+1:]
		if position := strings.Index(line, "Start TUN listening error:"); position >= 0 {
			s.errorText = line[position:]
			if len(s.errorText) > 512 {
				s.errorText = s.errorText[:512]
			}
		}
	}
	if len(s.pending) > 4096 {
		s.pending = s.pending[len(s.pending)-4096:]
	}
	return len(p), nil
}

// Only the service-owned TUN failure is retained, never subscription logs.
func (p *CoreProcess) TunnelError() string {
	p.tunnelStatus.mutex.Lock()
	defer p.tunnelStatus.mutex.Unlock()
	return p.tunnelStatus.errorText
}

// StartCore takes ownership of a dup of the VPN FD, passing it as child FD 3.
// The executable must be the PackageManager-installed ABI resource.
func StartCore(executable, home string, descriptor int) (*CoreProcess, error) {
	return startCore(executable, home, descriptor, nil)
}
func startCore(executable, home string, descriptor int, owner ConnectionOwner) (*CoreProcess, error) {
	if descriptor < 0 {
		return nil, fmt.Errorf("invalid_vpn_descriptor")
	}
	tun := os.NewFile(uintptr(descriptor), "vpn")
	if tun == nil {
		return nil, fmt.Errorf("invalid_vpn_descriptor")
	}
	if !filepath.IsAbs(executable) || !filepath.IsAbs(home) {
		tun.Close()
		return nil, fmt.Errorf("invalid_core_location")
	}
	status := &tunnelStatus{}
	environment := []string{"PATH=/system/bin", "HOME=" + home}
	result := &CoreProcess{done: make(chan struct{}), tunnelStatus: status}
	if owner != nil {
		server, address, token, err := ownerServer(owner)
		if err != nil {
			tun.Close()
			return nil, fmt.Errorf("owner_bridge_failed")
		}
		result.ownerServer = server
		environment = append(environment, "JEEMI_ANDROID_OWNER="+address, "JEEMI_ANDROID_TOKEN="+token)
	}
	started := make(chan error, 1)
	go func() {
		defer func() {
			if result.ownerServer != nil {
				_ = result.ownerServer.Close()
			}
		}()
		// Linux parent-death notification belongs to this thread. Keep it alive
		// until the child exits so ordinary Go thread retirement cannot kill it.
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		child, err := startCoreChild(executable, []string{"-d", home, "-f", filepath.Join(home, "config.yaml")}, environment, tun, status)
		tun.Close()
		if err != nil {
			result.stopped.Store(true)
			close(result.done)
			started <- fmt.Errorf("core_exec_failed")
			return
		}
		result.process = child
		started <- nil
		_ = child.Wait()
		result.stopped.Store(true)
		close(result.done)
	}()
	if err := <-started; err != nil {
		return nil, err
	}
	return result, nil
}
func (p *CoreProcess) IsRunning() bool { return !p.stopped.Load() }
func (p *CoreProcess) Stop() {
	p.stopOnce.Do(func() {
		if p.stopped.Load() {
			return
		}
		_ = p.process.Signal(os.Interrupt)
		select {
		case <-p.done:
			return
		case <-time.After(2 * time.Second):
		}
		_ = p.process.Kill()
		<-p.done
	})
}

// Android owns the TUN and authenticated local controller. Source values
// cannot create extra desktop listeners, select interfaces or schedule GEO.
func AndroidSessionConfiguration(candidate, secret string, port int, ipv6 bool) (string, error) {
	if len(candidate) > maxBytes || len(secret) < 32 || port < 1024 || port > 65535 {
		return "", fmt.Errorf("invalid_session")
	}
	doc, err := document.Parse([]byte(candidate))
	if err != nil {
		return "", fmt.Errorf("invalid_session_configuration")
	}
	root := document.Root(doc)
	exclusions, err := androidRouteExclusions(root)
	if err != nil {
		return "", err
	}
	for _, path := range []string{"/tun", "/external-controller", "/external-controller-tls", "/external-controller-unix",
		"/external-controller-pipe", "/external-ui", "/external-ui-url", "/external-ui-name", "/external-controller-cors",
		"/interface-name", "/routing-mark", "/redir-port", "/tproxy-port", "/listeners", "/tunnels", "/geox-url", "/geo-update-interval"} {
		if err = document.DeleteMappingPath(root, path); err != nil {
			return "", err
		}
	}
	tun := map[string]any{"enable": true, "file-descriptor": 3, "stack": androidTUNStack, "auto-route": false,
		"auto-detect-interface": false, "auto-redirect": false, "mtu": 1500, "dns-hijack": []string{"any:53", "tcp://any:53"}}
	if len(exclusions) > 0 {
		tun["route-exclude-address"] = exclusions
	}
	if ipv6 {
		tun["inet6-address"] = []string{"fdfe:dcba:9876::1/126"}
	}
	values := map[string]any{"/tun": tun, "/secret": secret, "/external-controller": fmt.Sprintf("127.0.0.1:%d", port),
		"/geo-auto-update": false, "/log-level": "error",
		// GEO is managed by Android. A missing/invalid session resource must
		// fail instead of silently downloading and changing the active revision.
		"/geox-url": map[string]string{"mmdb": "jeemi-resource://managed", "geoip": "jeemi-resource://managed",
			"geosite": "jeemi-resource://managed", "asn": "jeemi-resource://managed"}}
	for path, value := range values {
		data, _ := json.Marshal(value)
		node, e := document.ParseValue(string(data))
		if e != nil {
			return "", e
		}
		if e = document.SetMappingPath(root, path, node); e != nil {
			return "", e
		}
	}
	data, err := document.Encode(doc)
	return string(data), err
}
