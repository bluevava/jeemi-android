package mobile

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestNativeImportKeepsUnknownFieldsAndSummarizesNodes(t *testing.T) {
	input := "# retained\nproxies:\n  - name: One\n    type: socks5\n    server: example.com\n    port: 1080\n    password: secret\nx-custom: keep\n"
	actual, err := NormalizeSubscription(input)
	if err != nil {
		t.Fatal(err)
	}
	var got normalized
	if err := json.Unmarshal([]byte(actual), &got); err != nil {
		t.Fatal(err)
	}
	if got.YAML != input || got.Report.ProxyCount != 1 || len(got.Nodes) != 1 || got.Nodes[0].Name != "One" {
		t.Fatalf("unexpected normalized result")
	}
	summary, _ := json.Marshal(got.Nodes)
	if strings.Contains(string(summary), "secret") || strings.Contains(string(summary), "example.com") {
		t.Fatal("summary contains endpoint or credential")
	}
}

func TestSparseCompositionDoesNotOutputDisabledValues(t *testing.T) {
	actual, err := ComposeConfiguration("mode: rule\nlog-level: silent\nx-custom: keep\n", "mode: global\n", `[{"path":"/mode","strategy":"replace"}]`)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(actual, "mode: global") || !strings.Contains(actual, "log-level: silent") || !strings.Contains(actual, "x-custom: keep") {
		t.Fatalf("unexpected YAML: %s", actual)
	}
}

func TestCompositionRejectsUndeclaredLocalValues(t *testing.T) {
	if _, err := ComposeConfiguration("mode: rule", "mode: global\nlog-level: debug", `[{"path":"/mode","strategy":"replace"}]`); err == nil {
		t.Fatal("unplanned overlay value must not be silently applied")
	}
}

func TestErrorsDoNotEchoSecrets(t *testing.T) {
	_, err := NormalizeSubscription("password: secret\npassword: other\n")
	if err == nil || strings.Contains(err.Error(), "secret") {
		t.Fatal("expected redacted error")
	}
	for _, plan := range []string{`null`, `[] {}`, `[{"path":"/mode","stratgey":"replace"}]`} {
		if _, err := ComposeConfiguration("mode: rule", "mode: global", plan); err == nil {
			t.Fatalf("accepted invalid plan: %s", plan)
		}
	}
}
