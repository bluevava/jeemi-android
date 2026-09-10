package mobile

import (
	"encoding/json"
	"jeemi-android/engine/internal/config/document"
	"strings"
	"testing"
)

func TestAndroidStacksOverrideSourceAndSurviveSession(t *testing.T) {
	source := "tun: {enable: false, stack: invalid, auto-route: true, device: desktop}\nrules: ['MATCH,DIRECT']\n"
	revisions := map[string]bool{}
	for _, stack := range []string{"system", "gvisor", "mixed"} {
		candidate := projectTest(t, workspaceInput{Configuration: source, TunStack: stack})
		revisions[candidate.YAML] = true
		session, err := AndroidSessionConfiguration(candidate.YAML, strings.Repeat("s", 64), 19090, true)
		if err != nil {
			t.Fatal(err)
		}
		doc, _ := document.Parse([]byte(session))
		for path, expected := range map[string]string{"/tun/stack": stack, "/tun/enable": "true", "/tun/file-descriptor": "3", "/tun/auto-route": "false"} {
			node, found, _ := document.Find(document.Root(doc), path)
			if !found || node.Value != expected {
				t.Fatalf("%s did not preserve %s", stack, path)
			}
		}
		if _, found, _ := document.Find(document.Root(doc), "/tun/device"); found {
			t.Fatal("source device leaked")
		}
	}
	if len(revisions) != 3 {
		t.Fatal("stack changes must change candidate revisions")
	}
	if _, err := ComposeWorkspace(jsonText(t, workspaceInput{Configuration: source, TunStack: "invalid", GeoMode: "mmdb", GeoLoader: "memconservative", Mode: "rule"})); err == nil {
		t.Fatal("invalid stack accepted")
	}
	if _, err := AndroidSessionConfiguration(source, strings.Repeat("s", 64), 19090, false); err == nil {
		t.Fatal("invalid session stack accepted")
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
