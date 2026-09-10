package mobile

import (
	"encoding/json"
	"strings"
	"testing"

	"jeemi-android/engine/internal/config/document"
	"jeemi-android/engine/internal/runtimeconfig"
)

func TestRuntimeEditorUsesDesktopYAMLAndAtomicValidation(t *testing.T) {
	result, err := ApplyRuntimeDrafts(RuntimeDefaults(), `{"dns.nameserver":"- 114.114.114.114\n- 1.1.1.1\n- 1.1.1.1","hosts":"example.invalid: [127.0.0.1, '::1']"}`)
	if err != nil {
		t.Fatal(err)
	}
	var parsed struct{ Preferences runtimeconfig.Preferences }
	if err := json.Unmarshal([]byte(result), &parsed); err != nil {
		t.Fatal(err)
	}
	if strings.Join(parsed.Preferences.DNSNameservers, ",") != "114.114.114.114,1.1.1.1" {
		t.Fatal("YAML list was not parsed and deduplicated")
	}
	if !strings.Contains(parsed.Preferences.HostsYAML, "example.invalid") {
		t.Fatal("disabled content must remain editable")
	}
	for _, drafts := range []string{
		`{"dns.nameserver":"-"}`, `{"dns.nameserver":"[]"}`,
		`{"dns.fake-ip-filter":"secret.invalid: ["}`, `{"rules.lan-bypass":"- MATCH,REJECT"}`,
		`{"hosts":"x: {nested: token}"}`, `{"tun.route-exclude-address":"- 192.168.0.0"}`,
	} {
		result, err = ApplyRuntimeDrafts(RuntimeDefaults(), drafts)
		if err != nil || !strings.Contains(result, `"issue"`) || strings.Contains(result, `"preferences"`) || strings.Contains(result, "secret.invalid") {
			t.Fatalf("invalid draft must return only a safe issue: %s %v", result, err)
		}
	}
	if _, err = ApplyRuntimeDrafts(RuntimeDefaults(), `{"secret":"x"}`); err == nil {
		t.Fatal("arbitrary fields accepted")
	}
	if strings.Contains(ValidateRuntimeFragment("private-token", "secret-value"), "private-token") {
		t.Fatal("unsafe field echoed")
	}
}

func TestAndroidOptionalRuntimeStrategiesReachCandidate(t *testing.T) {
	cases := []struct{ field, key, enabled, merge, source, home, off, append, override string }{
		{"dns.nameserver", "dnsNameservers", "dnsNameserverEnabled", "dnsNameserverMerge", "dns: {nameserver: [1.1.1.1, 223.5.5.5]}", "[223.5.5.5, 114.114.114.114]", "[1.1.1.1,223.5.5.5]", "[1.1.1.1,223.5.5.5,114.114.114.114]", "[223.5.5.5,114.114.114.114]"},
		{"dns.fake-ip-filter", "dnsFakeIpFilter", "dnsFakeIpFilterEnabled", "dnsFakeIpFilterMerge", "dns: {fake-ip-filter: [a, b]}", "[b, c]", "[a,b]", "[a,b,c]", "[b,c]"},
		{"dns.proxy-server-nameserver", "dnsProxyServerNameservers", "dnsProxyServerNameserverEnabled", "dnsProxyServerNameserverMerge", "dns: {proxy-server-nameserver: [a, b]}", "[b, c]", "[a,b]", "[a,b,c]", "[b,c]"},
		{"tun.route-exclude-address", "tunRouteExcludeAddress", "tunRouteExcludeAddressEnabled", "tunRouteExcludeAddressMerge", "tun: {file-descriptor: 999, auto-route: true, route-exclude-address: [10.0.0.0/8, 192.168.0.0/16]}", "[192.168.0.0/16, 'fc00::/7']", "[10.0.0.0/8,192.168.0.0/16]", "[10.0.0.0/8,192.168.0.0/16,fc00::/7]", "[192.168.0.0/16,fc00::/7]"},
		{"dns.nameserver-policy", "dnsNameserverPolicyYaml", "dnsNameserverPolicyEnabled", "dnsNameserverPolicyMerge", "dns: {nameserver-policy: {a: [old, gone], b: kept}}", "{a: [new], c: added}", "{a:[old,gone],b:kept}", "{a:[new],b:kept,c:added}", "{a:[new],c:added}"},
		{"dns.proxy-server-nameserver-policy", "dnsProxyServerNameserverPolicyYaml", "dnsProxyServerNameserverPolicyEnabled", "dnsProxyServerNameserverPolicyMerge", "dns: {proxy-server-nameserver-policy: {a: [old, gone], b: kept}}", "{a: [new], c: added}", "{a:[old,gone],b:kept}", "{a:[new],b:kept,c:added}", "{a:[new],c:added}"},
		{"hosts", "hostsYaml", "dnsUseHosts", "hostsMerge", "dns: {use-hosts: false}\nhosts: {a: [127.0.0.1, '::1'], b: 127.0.0.2}", "{a: 127.0.0.3, c: 127.0.0.4}", "{a:[127.0.0.1,::1],b:127.0.0.2}", "{a:127.0.0.3,b:127.0.0.2,c:127.0.0.4}", "{a:127.0.0.3,c:127.0.0.4}"},
	}
	for _, c := range cases {
		t.Run(c.field, func(t *testing.T) {
			for _, mode := range []string{"off", "append", "override"} {
				var data map[string]any
				_ = json.Unmarshal([]byte(RuntimeDefaults()), &data)
				data[c.enabled] = mode != "off"
				data[c.merge] = "append"
				if mode == "override" {
					data[c.merge] = mode
				}
				raw, err := ApplyRuntimeDrafts(jsonText(t, data), jsonText(t, map[string]string{c.field: c.home}))
				if err != nil {
					t.Fatal(err)
				}
				var result struct{ Preferences json.RawMessage }
				_ = json.Unmarshal([]byte(raw), &result)
				out := projectTest(t, workspaceInput{Configuration: c.source + "\nrules: ['MATCH,DIRECT']", Runtime: result.Preferences})
				doc, _ := document.Parse([]byte(out.YAML))
				node, found, err := document.Find(document.Root(doc), "/"+strings.ReplaceAll(c.field, ".", "/"))
				if !found || err != nil {
					t.Fatal("missing optional field")
				}
				var value any
				_ = node.Decode(&value)
				actual := strings.ReplaceAll(jsonText(t, value), `"`, "")
				want := map[string]string{"off": c.off, "append": c.append, "override": c.override}[mode]
				if actual != want {
					t.Fatalf("%s: got %s want %s", mode, actual, want)
				}
				if strings.Contains(out.YAML, "file-descriptor") || strings.Contains(out.YAML, "auto-route") {
					t.Fatal("source tunnel ownership escaped")
				}
				if c.field == "hosts" {
					node, _, _ := document.Find(document.Root(doc), "/dns/use-hosts")
					if (node.Value == "true") != (mode != "off") {
						t.Fatal("hosts switch not honored")
					}
				}
			}
		})
	}
}

func TestAndroidSessionPreservesExplicitProcessModesAndFalse(t *testing.T) {
	for _, mode := range []string{"strict", "always", "off"} {
		prefs := runtimeconfig.DefaultPreferences()
		prefs.FindProcessMode = mode
		prefs.IPv6 = false
		prefs.DNSEnabled = false
		prefs.AllowLAN = false
		out := projectTest(t, workspaceInput{Configuration: "find-process-mode: always\nipv6: true\ndns: {enable: true}\nrules: ['MATCH,DIRECT']", Runtime: json.RawMessage(jsonText(t, prefs))})
		session, err := AndroidSessionConfiguration(out.YAML, strings.Repeat("a", 32), 19090, false)
		if err != nil {
			t.Fatal(err)
		}
		doc, _ := document.Parse([]byte(session))
		for path, want := range map[string]string{"/find-process-mode": mode, "/ipv6": "false", "/dns/enable": "false", "/allow-lan": "false"} {
			node, found, _ := document.Find(document.Root(doc), path)
			if !found || node.Value != want {
				t.Fatalf("actual session lost %s", path)
			}
		}
	}
}
