package schema

import "strings"

// These desktop endpoints are owned by VpnService. Never offer a local field
// that the Android session would silently ignore or use for a second TUN.
func init() {
	for i := range catalog.Categories {
		for j := range catalog.Categories[i].Fields {
			f := &catalog.Categories[i].Fields[j]
			if strings.HasPrefix(f.Path, "/tun/") || catalog.Categories[i].ID == "controller" ||
				f.Path == "/redir-port" || f.Path == "/tproxy-port" || f.Path == "/listeners" || f.Path == "/tunnels" ||
				f.Path == "/interface-name" || f.Path == "/routing-mark" ||
				f.Path == "/proxies/*/interface-name" || f.Path == "/proxies/*/routing-mark" {
				f.Locked = true
				f.Hidden = true
			}
		}
	}
}
