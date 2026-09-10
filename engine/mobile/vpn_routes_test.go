package mobile

import (
	"encoding/json"
	"net/netip"
	"strings"
	"testing"
)

func routeCovers(plan vpnRoutePlan, address string) bool {
	addr := netip.MustParseAddr(address)
	best, included := -1, false
	for _, entry := range plan.Include {
		prefix := netip.MustParsePrefix(entry)
		if prefix.Contains(addr) && prefix.Bits() >= best {
			best, included = prefix.Bits(), true
		}
	}
	for _, entry := range plan.Exclude {
		prefix := netip.MustParsePrefix(entry)
		if prefix.Contains(addr) && prefix.Bits() >= best {
			best, included = prefix.Bits(), false
		}
	}
	// The service installs this route after exclusions, including equal prefixes.
	if address == "172.19.0.2" {
		return true
	}
	return included
}

func TestVpnRoutePlansMatchAcrossAndroidVersions(t *testing.T) {
	for _, ipv6 := range []bool{false, true} {
		for _, legacy := range []bool{false, true} {
			raw, err := AndroidVpnRoutes("tun: {route-exclude-address: [10.7.0.1/8, 10.2.0.0/16, 10.0.0.0/8, 172.16.0.0/12, 'fc00::/7']}", ipv6, legacy)
			if err != nil {
				t.Fatal(err)
			}
			var plan vpnRoutePlan
			_ = json.Unmarshal([]byte(raw), &plan)
			if len(plan.Include) > maxAndroidRoutes || plan.Include[len(plan.Include)-1] != vpnDNSRoute {
				t.Fatal("invalid plan bounds or service DNS")
			}
			if legacy && len(plan.Exclude) != 0 {
				t.Fatal("legacy needs positive routes only")
			}
			for address, want := range map[string]bool{"9.255.255.255": true, "10.0.0.0": false, "10.255.255.255": false, "11.0.0.0": true,
				"172.19.0.2": true, "172.19.0.3": false, "192.168.0.1": true, "fc00::1": false, "fdff:ffff::1": false, "2001:db8::1": ipv6} {
				if routeCovers(plan, address) != want {
					t.Fatalf("legacy=%v ipv6=%v address=%s", legacy, ipv6, address)
				}
			}
		}
	}
}

func TestVpnRoutesValidateAndBoundBeforeEstablish(t *testing.T) {
	for _, yaml := range []string{"tun: {route-exclude-address: [bad]}", "tun: {route-exclude-address: [true]}", "tun: {route-exclude-address: ['::ffff:192.0.2.0/120']}"} {
		if _, err := AndroidVpnRoutes(yaml, true, true); err == nil {
			t.Fatal("invalid Android prefix accepted")
		}
	}
	var addresses []string
	for i := 0; i < 100; i++ {
		addresses = append(addresses, netip.PrefixFrom(netip.AddrFrom16([16]byte{0x20, 0x01, byte(i), byte(i * 7), byte(i * 11)}), 128).String())
	}
	yaml := "tun: {route-exclude-address: " + jsonText(t, addresses) + "}"
	if _, err := AndroidVpnRoutes(yaml, true, true); err == nil || err.Error() != "vpn_route_limit" {
		t.Fatal("legacy route explosion not bounded")
	}
	if _, err := AndroidVpnRoutes(yaml, true, false); err != nil {
		t.Fatal("native exclusions need no complement expansion")
	}
	for _, legacy := range []bool{false, true} {
		raw, err := AndroidVpnRoutes("tun: {route-exclude-address: ['0.0.0.0/0','::/0','172.19.0.2/32']}", true, legacy)
		if err != nil {
			t.Fatal(err)
		}
		var plan vpnRoutePlan
		_ = json.Unmarshal([]byte(raw), &plan)
		if routeCovers(plan, "1.1.1.1") || routeCovers(plan, "2001:db8::1") || !routeCovers(plan, "172.19.0.2") {
			t.Fatal("default exclusion or DNS reservation failed")
		}
	}
}

func TestAndroidRouteOverrideCanClearSource(t *testing.T) {
	defaults := strings.ReplaceAll(RuntimeDefaults(), `"tunRouteExcludeAddressEnabled":false`, `"tunRouteExcludeAddressEnabled":true`)
	defaults = strings.ReplaceAll(defaults, `"tunRouteExcludeAddressMerge":"append"`, `"tunRouteExcludeAddressMerge":"override"`)
	out := projectTest(t, workspaceInput{Configuration: "tun: {route-exclude-address: [10.0.0.0/8]}\nrules: ['MATCH,DIRECT']", Runtime: json.RawMessage(defaults)})
	if strings.Contains(out.YAML, "10.0.0.0/8]") {
		t.Fatal("empty override must clear exclusions")
	}
	raw, err := AndroidVpnRoutes(out.YAML, false, true)
	if err != nil {
		t.Fatal(err)
	}
	var plan vpnRoutePlan
	_ = json.Unmarshal([]byte(raw), &plan)
	if !routeCovers(plan, "10.0.0.1") {
		t.Fatal("empty override still bypassed VPN")
	}
}
