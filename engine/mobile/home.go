package mobile

import (
	"encoding/json"
	"fmt"

	"gopkg.in/yaml.v3"
	"jeemi-android/engine/internal/config/document"
	"jeemi-android/engine/internal/config/runtimeoverride"
	"jeemi-android/engine/internal/runtimeconfig"
)

func RuntimeDefaults() string {
	b, _ := json.Marshal(runtimeconfig.DefaultPreferences())
	return string(b)
}
func NormalizeRuntimePreferences(raw string) (string, error) {
	var p runtimeconfig.Preferences
	if len(raw) > 256<<10 || json.Unmarshal([]byte(raw), &p) != nil {
		return "", fmt.Errorf("invalid_runtime_preferences")
	}
	if err := runtimeconfig.Validate(p); err != nil {
		return "", fmt.Errorf("invalid_runtime_preferences")
	}
	b, _ := json.Marshal(p)
	return string(b), nil
}

// UpgradeRuntimePreferences keeps every previously explicit field value. Old
// optional resources used replace; their migration records that strategy.
func UpgradeRuntimePreferences(raw string) (string, error) {
	var old map[string]string
	if json.Unmarshal([]byte(raw), &old) != nil {
		return "", fmt.Errorf("invalid_runtime_preferences")
	}
	data := map[string]any{}
	_ = json.Unmarshal([]byte(RuntimeDefaults()), &data)
	keys := map[string]string{"/log-level": "logLevel", "/ipv6": "ipv6", "/mixed-port": "listenPort", "/allow-lan": "allowLan",
		"/dns/enable": "dnsEnabled", "/dns/ipv6": "dnsIpv6", "/dns/listen": "dnsListen", "/dns/enhanced-mode": "dnsEnhancedMode",
		"/dns/fake-ip-range": "dnsFakeIpRange", "/dns/fake-ip-range6": "dnsFakeIpRange6", "/dns/use-hosts": "dnsUseHosts"}
	resources := map[string][3]string{
		"/dns/nameserver":                     {"dnsNameservers", "dnsNameserverEnabled", "dnsNameserverMerge"},
		"/dns/fake-ip-filter":                 {"dnsFakeIpFilter", "dnsFakeIpFilterEnabled", "dnsFakeIpFilterMerge"},
		"/dns/proxy-server-nameserver":        {"dnsProxyServerNameservers", "dnsProxyServerNameserverEnabled", "dnsProxyServerNameserverMerge"},
		"/dns/nameserver-policy":              {"dnsNameserverPolicyYaml", "dnsNameserverPolicyEnabled", "dnsNameserverPolicyMerge"},
		"/dns/proxy-server-nameserver-policy": {"dnsProxyServerNameserverPolicyYaml", "dnsProxyServerNameserverPolicyEnabled", "dnsProxyServerNameserverPolicyMerge"},
		"/hosts":                              {"hostsYaml", "dnsUseHosts", "hostsMerge"},
	}
	for path, value := range old {
		if key, ok := keys[path]; ok {
			var decoded any
			if yaml.Unmarshal([]byte(value), &decoded) != nil {
				return "", fmt.Errorf("invalid_runtime_value")
			}
			data[key] = decoded
		}
		if fields, ok := resources[path]; ok {
			if fields[0] == "hostsYaml" || fields[0] == "dnsNameserverPolicyYaml" || fields[0] == "dnsProxyServerNameserverPolicyYaml" {
				data[fields[0]] = value
			} else {
				var list []string
				if yaml.Unmarshal([]byte(value), &list) != nil {
					return "", fmt.Errorf("invalid_runtime_value")
				}
				data[fields[0]] = list
			}
			data[fields[1]] = true
			data[fields[2]] = "override"
		}
	}
	if value, ok := old["/dns/use-hosts"]; ok {
		data["dnsUseHosts"] = value == "true"
	}
	b, _ := json.Marshal(data)
	return NormalizeRuntimePreferences(string(b))
}

// The candidate and actual VpnService session share one fixed Android stack.
const androidTUNStack = "gvisor"

func applyHome(contents []byte, raw, mode, geoMode, loader string) ([]byte, error) {
	var p runtimeconfig.Preferences
	if raw == "" {
		raw = "{}"
	}
	if len(raw) > 256<<10 || json.Unmarshal([]byte(raw), &p) != nil {
		return nil, fmt.Errorf("invalid_runtime_preferences")
	}
	p.OutboundMode = mode
	result, err := runtimeoverride.ApplyForPlatform(contents, p, "android")
	if err != nil {
		return nil, fmt.Errorf("invalid_runtime_preferences")
	}
	if geoMode != "mmdb" && geoMode != "dat" {
		return nil, fmt.Errorf("invalid_geo_mode")
	}
	if loader != "memconservative" && loader != "standard" {
		return nil, fmt.Errorf("invalid_geo_loader")
	}
	doc, err := document.Parse(result)
	if err != nil {
		return nil, err
	}
	root := document.Root(doc)
	for _, path := range []string{"/geo-update-interval", "/geox-url"} {
		if err = document.DeleteMappingPath(root, path); err != nil {
			return nil, err
		}
	}
	// Insertion order contributes to the candidate digest; keep it stable.
	for _, item := range []struct {
		path  string
		value any
	}{
		{"/geodata-mode", geoMode == "dat"}, {"/geodata-loader", loader}, {"/geo-auto-update", false},
		{"/tun/enable", true}, {"/tun/stack", androidTUNStack},
	} {
		path := item.path
		raw, _ := yaml.Marshal(item.value)
		node, _ := document.ParseValue(string(raw))
		if err = document.SetMappingPath(root, path, node); err != nil {
			return nil, err
		}
	}
	if _, err := androidRouteExclusions(root); err != nil {
		return nil, err
	}
	return document.Encode(doc)
}
