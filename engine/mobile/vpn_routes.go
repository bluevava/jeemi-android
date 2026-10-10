package mobile

import (
	"encoding/json"
	"fmt"
	"net/netip"
	"sort"

	"gopkg.in/yaml.v3"
	"jeemi-android/engine/internal/config/document"
	"jeemi-android/engine/internal/runtimeconfig"
)

const vpnDNSRoute = "172.19.0.2/32"
const maxAndroidRoutes = 2048

type vpnRoutePlan struct {
	Include []string `json:"include"`
	Exclude []string `json:"exclude"`
	Address string   `json:"address"`
}

// mihomo v1.19.32 config.parseTun derives its IPv4 listener address from
// dns.fake-ip-range and uses a /30 prefix, including in redir-host mode.
// VpnService must assign that exact address before system/mixed can bind TCP.
func androidTunAddress(root *yaml.Node) (string, error) {
	value := "198.18.0.1/16"
	node, found, err := document.Find(root, "/dns/fake-ip-range")
	if err != nil {
		return "", fmt.Errorf("invalid_vpn_address")
	}
	if found {
		if node.Kind != yaml.ScalarNode || node.Tag != "!!str" {
			return "", fmt.Errorf("invalid_vpn_address")
		}
		if node.Value != "" {
			value = node.Value
		}
	}
	prefix, err := netip.ParsePrefix(value)
	if err != nil || !prefix.Addr().Is4() || prefix.Addr().IsUnspecified() || prefix.Addr().IsLoopback() || prefix.Addr().IsMulticast() {
		return "", fmt.Errorf("invalid_vpn_address")
	}
	return netip.PrefixFrom(prefix.Addr(), 30).String(), nil
}

func androidRouteExclusions(root *yaml.Node) ([]string, error) {
	node, found, err := document.Find(root, "/tun/route-exclude-address")
	if err != nil {
		return nil, fmt.Errorf("invalid_vpn_routes")
	}
	if !found {
		return []string{}, nil
	}
	text, err := document.EncodeValue(node)
	if err != nil {
		return nil, fmt.Errorf("invalid_vpn_routes")
	}
	result := runtimeconfig.ValidateYAMLFragment(runtimeconfig.YAMLFragmentInput{Field: runtimeconfig.YAMLFieldTUNRouteExcludeAddress, Contents: text})
	if !result.Valid {
		return nil, fmt.Errorf("invalid_vpn_routes")
	}
	for _, value := range result.Values {
		prefix, err := netip.ParsePrefix(value)
		if err != nil || prefix.Addr().Is4In6() {
			return nil, fmt.Errorf("invalid_vpn_routes")
		}
	}
	return result.Values, nil
}

// Android 13+ supports excluded routes. Earlier releases receive the bounded
// CIDR complement instead. The service DNS host remains reachable in both plans.
func AndroidVpnRoutes(candidate string, ipv6, legacy bool) (string, error) {
	if len(candidate) > maxBytes {
		return "", fmt.Errorf("invalid_vpn_routes")
	}
	doc, err := document.Parse([]byte(candidate))
	if err != nil {
		return "", fmt.Errorf("invalid_vpn_routes")
	}
	values, err := androidRouteExclusions(document.Root(doc))
	if err != nil {
		return "", err
	}
	address, err := androidTunAddress(document.Root(doc))
	if err != nil {
		return "", err
	}
	prefixes := make([]netip.Prefix, 0, len(values))
	for _, value := range values {
		prefix, _ := netip.ParsePrefix(value)
		if !ipv6 && prefix.Addr().Is6() {
			continue
		}
		prefixes = append(prefixes, prefix.Masked())
	}
	sort.Slice(prefixes, func(i, j int) bool {
		if prefixes[i].Bits() != prefixes[j].Bits() {
			return prefixes[i].Bits() < prefixes[j].Bits()
		}
		return prefixes[i].Addr().Less(prefixes[j].Addr())
	})
	unique := make([]netip.Prefix, 0, len(prefixes))
	for _, prefix := range prefixes {
		covered := false
		for _, previous := range unique {
			if previous.Addr().BitLen() == prefix.Addr().BitLen() && previous.Contains(prefix.Addr()) {
				covered = true
				break
			}
		}
		if !covered {
			unique = append(unique, prefix)
		}
	}
	plan := vpnRoutePlan{Include: []string{}, Exclude: []string{}, Address: address}
	roots := []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")}
	if ipv6 {
		roots = append(roots, netip.MustParsePrefix("::/0"))
	}
	if !legacy {
		for _, root := range roots {
			plan.Include = append(plan.Include, root.String())
		}
		for _, prefix := range unique {
			plan.Exclude = append(plan.Exclude, prefix.String())
		}
	} else {
		var visit func(netip.Prefix) error
		visit = func(current netip.Prefix) error {
			split := false
			for _, excluded := range unique {
				if current.Addr().BitLen() != excluded.Addr().BitLen() {
					continue
				}
				if excluded.Bits() <= current.Bits() && excluded.Contains(current.Addr()) {
					return nil
				}
				if current.Contains(excluded.Addr()) {
					split = true
				}
			}
			if !split {
				if len(plan.Include) >= maxAndroidRoutes-1 {
					return fmt.Errorf("vpn_route_limit")
				}
				plan.Include = append(plan.Include, current.String())
				return nil
			}
			bits := current.Bits()
			left := netip.PrefixFrom(current.Addr(), bits+1)
			bytes := current.Addr().AsSlice()
			bytes[bits/8] |= 1 << (7 - bits%8)
			rightAddr, _ := netip.AddrFromSlice(bytes)
			if err := visit(left); err != nil {
				return err
			}
			return visit(netip.PrefixFrom(rightAddr, bits+1))
		}
		for _, root := range roots {
			if err := visit(root); err != nil {
				return "", err
			}
		}
	}
	// Installed last by the service: even an explicit exclusion of the local
	// resolver must not send its virtual address to the physical network.
	plan.Include = append(plan.Include, vpnDNSRoute)
	data, _ := json.Marshal(plan)
	return string(data), nil
}
