package mobile

import (
	"encoding/json"
	"strings"
	"testing"

	"jeemi-android/engine/internal/runtimeconfig"
)

func TestRuntimeInputFormatsCoverEveryEditableYAMLField(t *testing.T) {
	cases := []struct {
		field, key, valid, invalid string
	}{
		{"dns.nameserver", "dnsNameservers", "# resolvers\n- 223.5.5.5\n- https://dns.alidns.com/dns-query", "dns:\n  nameserver: [223.5.5.5]"},
		{"dns.proxy-server-nameserver", "dnsProxyServerNameservers", "[https://doh.pub/dns-query, \"https://[2001:db8::1]/dns-query#h3=true\"]", "- {server: https://doh.pub/dns-query}"},
		{"dns.fake-ip-filter", "dnsFakeIpFilter", "[\"*.example.invalid\", +.lan, \"true\", \"123\"]", "- true"},
		{"dns.nameserver-policy", "dnsNameserverPolicyYaml", "\"geosite:cn\":\n  - https://dns.alidns.com/dns-query\n\"+.example.invalid\": [223.5.5.5, https://doh.pub/dns-query]", "same: 1.1.1.1\nsame: 223.5.5.5"},
		{"dns.proxy-server-nameserver-policy", "dnsProxyServerNameserverPolicyYaml", "\"node.example.invalid\": https://doh.pub/dns-query", "node.example.invalid: {nested: 1.1.1.1}"},
		{"hosts", "hostsYaml", "example.invalid: 192.0.2.1\nipv6.example.invalid: [192.0.2.2, \"2001:db8::2\"]", "example.invalid: [192.0.2.1, false]"},
		{"rules.lan-bypass", "lanBypassRules", "- DOMAIN-SUFFIX,lan,DIRECT\n- IP-CIDR,192.168.0.0/16,DIRECT,no-resolve\n- IP-CIDR6,fc00::/7,DIRECT,no-resolve", "- IP-CIDR,192.168.0.0/16,DIRECT"},
		{"tun.route-exclude-address", "tunRouteExcludeAddress", "[192.168.0.0/16, 'fc00::/7']", "- 192.168.0.1"},
	}
	if len(cases) != len(runtimeDraftFields) {
		t.Fatal("a runtime YAML field is missing from the input audit")
	}
	for _, c := range cases {
		t.Run(c.field, func(t *testing.T) {
			result := runtimeconfig.ValidateYAMLFragment(runtimeconfig.YAMLFragmentInput{Field: c.field, Contents: c.valid})
			if !result.Valid {
				t.Fatalf("desktop-style input rejected: %+v", result.Issue)
			}
			again := runtimeconfig.ValidateYAMLFragment(runtimeconfig.YAMLFragmentInput{Field: c.field, Contents: result.NormalizedYAML})
			if !again.Valid || again.NormalizedYAML != result.NormalizedYAML {
				t.Fatal("normalization is not stable")
			}
			raw, err := ApplyRuntimeDrafts(RuntimeDefaults(), jsonText(t, map[string]string{c.field: c.valid}))
			if err != nil || !strings.Contains(raw, "\"preferences\"") {
				t.Fatalf("valid draft cannot be saved: %s %v", raw, err)
			}
			var saved struct{ Preferences map[string]any }
			if json.Unmarshal([]byte(raw), &saved) != nil || saved.Preferences[c.key] == nil {
				t.Fatal("the edited value was not retained")
			}
			for _, invalid := range []string{c.invalid, c.valid + "\n---\n[]", "secret: &secret [1.1.1.1]\nother: *secret"} {
				raw, err := ApplyRuntimeDrafts(RuntimeDefaults(), jsonText(t, map[string]string{c.field: invalid}))
				if err != nil || !strings.Contains(raw, "\"issue\"") || strings.Contains(raw, "\"preferences\"") || strings.Contains(raw, "secret") {
					t.Fatalf("invalid input must return a safe issue and no preferences: %s %v", raw, err)
				}
			}
			blank := runtimeconfig.ValidateYAMLFragment(runtimeconfig.YAMLFragmentInput{Field: c.field, Contents: ""})
			if blank.Valid != (c.field != "dns.nameserver") {
				t.Fatal("empty input differs from the desktop field semantics")
			}
		})
	}
}

func TestRuntimeScalarTextUsesDesktopValidationWithoutSilentReset(t *testing.T) {
	cases := []struct {
		key     string
		valid   any
		invalid []any
	}{
		{"listenPort", 7891, []any{0, 65536, 3.5, "7890x", "", "- 7890"}},
		{"dnsListen", "[::1]:1054", []any{"https://dns.example.invalid", "localhost:1053", "::1:1053", "127.0.0.1:7890", "127.0.0.1:65536", "dns.listen: 127.0.0.1:1053"}},
		{"dnsFakeIpRange", "198.18.0.1/16", []any{"198.18.0.1", "fdfe:dcba:9876::1/64", "198.18.0.1/33", "- 198.18.0.1/16"}},
		{"dnsFakeIpRange6", "fdfe:dcba:9876::1/64", []any{"fdfe:dcba:9876::1", "198.18.0.1/16", "fdfe:dcba:9876::1/129", "[fdfe:dcba:9876::1]/64"}},
	}
	for _, c := range cases {
		t.Run(c.key, func(t *testing.T) {
			for _, valid := range []bool{true, false} {
				values := c.invalid
				if valid {
					values = []any{c.valid}
				}
				for _, value := range values {
					var preferences map[string]any
					_ = json.Unmarshal([]byte(RuntimeDefaults()), &preferences)
					preferences[c.key] = value
					result, err := ApplyRuntimeDrafts(jsonText(t, preferences), "{}")
					if valid {
						if err != nil {
							t.Fatal(err)
						}
						var saved struct{ Preferences map[string]any }
						_ = json.Unmarshal([]byte(result), &saved)
						if jsonText(t, saved.Preferences[c.key]) != jsonText(t, value) {
							t.Fatal("valid scalar silently replaced by a default")
						}
					} else if err == nil || result != "" || err.Error() != "invalid_runtime_preferences" {
						t.Fatal("invalid scalar must fail without echoing its content")
					}
				}
			}
		})
	}
}
