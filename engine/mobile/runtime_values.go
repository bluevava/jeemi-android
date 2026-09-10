package mobile

import (
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"slices"
	"strconv"

	"gopkg.in/yaml.v3"
	"jeemi-android/engine/internal/config/compose"
	"jeemi-android/engine/internal/config/document"
)

// ApplyRuntimeValues handles explicitly enabled Android preferences, last in the candidate pipeline.
// It never enables a VPN or injects a desktop TUN/controller configuration.
func ApplyRuntimeValues(configuration, valuesJSON string) (string, error) {
	if len(valuesJSON) > 256<<10 {
		return "", fmt.Errorf("size_limit")
	}
	var values map[string]string
	if err := json.Unmarshal([]byte(valuesJSON), &values); err != nil {
		return "", fmt.Errorf("invalid_runtime_values")
	}
	kinds := map[string]string{
		"/log-level": "enum", "/ipv6": "bool", "/mixed-port": "port", "/allow-lan": "bool",
		"/tcp-concurrent": "bool", "/unified-delay": "bool",
		"/dns/enable": "bool", "/dns/ipv6": "bool", "/dns/listen": "listen", "/dns/enhanced-mode": "enum",
		"/dns/fake-ip-range": "cidr", "/dns/fake-ip-range6": "cidr", "/dns/use-hosts": "bool",
		"/dns/nameserver": "seq", "/dns/default-nameserver": "seq", "/dns/proxy-server-nameserver": "seq",
		"/dns/fallback": "seq", "/dns/fake-ip-filter": "seq", "/dns/nameserver-policy": "map",
		"/dns/proxy-server-nameserver-policy": "map", "/hosts": "map",
	}
	overlay := &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
	plan := []compose.Rule{}
	paths := make([]string, 0, len(values))
	for path := range values {
		paths = append(paths, path)
	}
	slices.Sort(paths)
	for _, path := range paths {
		kind, ok := kinds[path]
		if !ok {
			return "", fmt.Errorf("unsupported_runtime_value")
		}
		node, err := document.ParseValue(values[path])
		if err != nil {
			return "", fmt.Errorf("invalid_runtime_value")
		}
		valid := true
		switch kind {
		case "bool":
			valid = node.Tag == "!!bool"
		case "port":
			p, e := strconv.Atoi(node.Value)
			valid = e == nil && p > 0 && p <= 65535 && node.Tag == "!!int"
		case "listen":
			_, port, e := net.SplitHostPort(node.Value)
			p, pe := strconv.Atoi(port)
			valid = e == nil && pe == nil && p > 0 && p <= 65535 && node.Tag == "!!str"
		case "cidr":
			_, e := netip.ParsePrefix(node.Value)
			valid = e == nil && node.Tag == "!!str"
		case "seq":
			valid = node.Kind == yaml.SequenceNode
			for _, n := range node.Content {
				valid = valid && n.Tag == "!!str" && n.Value != ""
			}
		case "map":
			valid = node.Kind == yaml.MappingNode
		case "enum":
			if path == "/log-level" {
				valid = slices.Contains([]string{"silent", "error", "warning", "info", "debug"}, node.Value)
			} else {
				valid = slices.Contains([]string{"fake-ip", "redir-host"}, node.Value)
			}
		}
		if !valid {
			return "", fmt.Errorf("invalid_runtime_type")
		}
		if err := document.SetMappingPath(overlay, path, node); err != nil {
			return "", fmt.Errorf("invalid_runtime_path")
		}
		plan = append(plan, compose.Rule{Path: path, Strategy: compose.StrategyReplace})
	}
	raw, err := document.Encode(&yaml.Node{Kind: yaml.DocumentNode, Content: []*yaml.Node{overlay}})
	if err != nil {
		return "", fmt.Errorf("invalid_runtime_values")
	}
	result, err := compose.Compose([]byte(configuration), raw, plan)
	if err != nil {
		return "", fmt.Errorf("runtime_composition_failed")
	}
	return result.YAML, nil
}
