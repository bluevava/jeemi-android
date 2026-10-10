package schema

import "strings"

// Android owns these endpoints. The field tree may explicitly reveal their
// structure as locked, but local configuration must never edit them.
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
