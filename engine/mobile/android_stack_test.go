package mobile

import (
	"encoding/json"
	"jeemi-android/engine/internal/config/document"
	"strings"
	"testing"
)

func TestAndroidFixedStackOverridesSourceAndLocalProcessing(t *testing.T) {
	source := "tun: {enable: false, stack: invalid, auto-route: true, device: desktop}\nrules: ['MATCH,DIRECT']\n"
	for _, handler := range []resourceInput{
		{},
		{ID: "config", Name: "Legacy local config", Kind: "CONFIG", Strategy: "auto", Content: "tun: {enable: false, stack: mixed}\n"},
		{ID: "script", Name: "Local script", Kind: "SCRIPT", FormatVersion: 2,
			Content: `function main(config) { config.tun = {enable: false, stack: "system"}; return config; }`},
	} {
		t.Run(handler.ID, func(t *testing.T) {
			input := workspaceInput{Configuration: source}
			if handler.ID != "" {
				input.HandlerID, input.Resources = handler.ID, []resourceInput{handler}
			}
			candidate := projectTest(t, input)
			assertAndroidTunFields(t, candidate.YAML, map[string]string{"/tun/stack": "gvisor", "/tun/enable": "true"})
		})
	}
}

func TestAndroidIgnoresRetiredStackPreference(t *testing.T) {
	input := map[string]any{"configuration": "rules: ['MATCH,DIRECT']\n", "mode": "rule", "geoMode": "mmdb", "geoLoader": "memconservative"}
	baseline, err := ComposeWorkspace(jsonText(t, input))
	if err != nil {
		t.Fatal(err)
	}
	for _, legacy := range []string{"system", "gvisor", "mixed", "invalid"} {
		input["tunStack"] = legacy
		got, err := ComposeWorkspace(jsonText(t, input))
		if err != nil || got != baseline {
			t.Fatalf("retired preference %q changed the candidate: %v", legacy, err)
		}
	}
}

func TestAndroidSessionForcesStackEvenForStaleCandidates(t *testing.T) {
	for _, stack := range []string{"system", "gvisor", "mixed", "invalid"} {
		for _, ipv6 := range []bool{false, true} {
			source := "tun: {enable: false, stack: " + stack + ", auto-route: true, device: desktop, route-exclude-address: [198.51.100.0/24]}\nrules: ['MATCH,DIRECT']\n"
			session, err := AndroidSessionConfiguration(source, strings.Repeat("s", 64), 19090, ipv6)
			if err != nil {
				t.Fatal(err)
			}
			assertAndroidTunFields(t, session, map[string]string{"/tun/stack": "gvisor", "/tun/enable": "true",
				"/tun/file-descriptor": "3", "/tun/auto-route": "false", "/tun/auto-redirect": "false"})
			doc, _ := document.Parse([]byte(session))
			root := document.Root(doc)
			if _, found, _ := document.Find(root, "/tun/device"); found {
				t.Fatal("source device leaked")
			}
			if _, found, _ := document.Find(root, "/tun/inet6-address"); found != ipv6 {
				t.Fatal("IPv6 ownership changed")
			}
			if !strings.Contains(session, "198.51.100.0/24") {
				t.Fatal("validated route exclusion lost")
			}
		}
	}
}

func assertAndroidTunFields(t *testing.T, configuration string, fields map[string]string) {
	t.Helper()
	doc, err := document.Parse([]byte(configuration))
	if err != nil {
		t.Fatal(err)
	}
	for path, expected := range fields {
		node, found, _ := document.Find(document.Root(doc), path)
		if !found || node.Value != expected {
			t.Fatalf("unexpected Android field %s", path)
		}
	}
}

func TestConvertedSubscriptionFeedsAssociatedScriptAndConfiguration(t *testing.T) {
	original := "socks5://user:password@proxy.example.invalid:1080#Imported"
	result, err := NormalizeSubscription(original)
	if err != nil {
		t.Fatal(err)
	}
	var normalized normalized
	if err = json.Unmarshal([]byte(result), &normalized); err != nil {
		t.Fatal(err)
	}
	script := resourceInput{ID: "script", Name: "Inspect normalized input", Kind: "SCRIPT", FormatVersion: 2,
		Content: `function main(config) { if (!config.proxies || config.proxies[0].name !== "Imported") throw new Error("not normalized"); config["x-normalized"] = true; return config; }`}
	candidate := projectTest(t, workspaceInput{Configuration: normalized.YAML, HandlerID: script.ID, Resources: []resourceInput{script}})
	if !strings.Contains(candidate.YAML, "x-normalized: true") {
		t.Fatal("script did not receive normalized nodes")
	}
	config := resourceInput{ID: "config", Name: "Local merge", Kind: "CONFIG", Strategy: "auto", Content: "sniffer: {enable: true}\n"}
	candidate = projectTest(t, workspaceInput{Configuration: normalized.YAML, HandlerID: config.ID, Resources: []resourceInput{config}})
	if !strings.Contains(candidate.YAML, "sniffer:") || !strings.Contains(candidate.YAML, "Imported") {
		t.Fatal("local configuration lost normalized nodes")
	}
}
