package mobile

import (
	"encoding/json"
	"jeemi-android/engine/internal/config/document"
	"strings"
	"testing"
)

func TestDashboardFinalLayerOverridesSourceAndKeepsPrivateController(t *testing.T) {
	original := "external-ui: /untrusted\nexternal-ui-url: https://untrusted.test/ui.zip\nexternal-ui-name: other\nexternal-controller-cors: {allow-origins: ['*']}\nproxies: []\nrules: ['MATCH,DIRECT']\n"
	config, err := AndroidSessionConfiguration(original, strings.Repeat("s", 48), 12345, true)
	if err != nil {
		t.Fatal(err)
	}
	enabled, err := AndroidDashboardConfiguration(config, "v3.26.0")
	if err != nil {
		t.Fatal(err)
	}
	on, _ := document.Parse([]byte(enabled))
	stack, found, _ := document.Find(document.Root(on), "/tun/stack")
	if strings.Contains(enabled, "untrusted") || !strings.Contains(enabled, "127.0.0.1:12345") ||
		!strings.Contains(enabled, "external-ui/zashboard/v3.26.0/dist") || !found || stack.Value != "gvisor" {
		t.Fatal("invalid managed UI configuration")
	}
	disabled, err := AndroidDashboardConfiguration(enabled, "")
	if err != nil {
		t.Fatal(err)
	}
	parsed, _ := document.Parse([]byte(disabled))
	for _, name := range []string{"/external-ui", "/external-ui-url", "/external-ui-name", "/external-controller-cors"} {
		if _, found, _ := document.Find(document.Root(parsed), name); found {
			t.Fatal("disabled UI retained", name)
		}
	}
	for _, version := range []string{"../outside", "v1.2.3/../other", "latest", "https://untrusted.test"} {
		if _, err := AndroidDashboardConfiguration(config, version); err == nil {
			t.Fatal("untrusted UI version accepted")
		}
	}
}

func TestURLScriptPortablePackageRetainsCachedSourceOffline(t *testing.T) {
	script := resourceInput{ID: strings.Repeat("a", 32), Kind: "SCRIPT", Name: "URL script",
		Content: "function main(config) { return config; }", FormatVersion: 2, SourceURL: "https://example.invalid/script.js?token=private"}
	raw, _ := json.Marshal(script)
	exported, err := ExportResourcePackage(string(raw), "[]")
	if err != nil {
		t.Fatal(err)
	}
	imported, err := PrepareResourceImport(exported, string(raw), "[]")
	if err != nil {
		t.Fatal(err)
	}
	var result struct{ Target resourceInput }
	if json.Unmarshal([]byte(imported), &result) != nil || result.Target.Content != script.Content || result.Target.SourceURL != script.SourceURL {
		t.Fatal("portable package lost source or cached code")
	}
	script.Content = "function broken("
	raw, _ = json.Marshal(script)
	if _, err := PrepareStructuredResource(string(raw)); err == nil {
		t.Fatal("invalid downloaded code accepted")
	}
}

func TestDownloadCancellationBeforeDispatch(t *testing.T) {
	request := NewResourceDownload()
	request.Cancel()
	if _, err := request.Script("https://example.invalid/private"); err == nil || strings.Contains(err.Error(), "private") {
		t.Fatal("cancelled script request was not safely rejected")
	}
}
