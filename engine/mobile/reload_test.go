package mobile

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestReloadChecksActualListeningPorts(t *testing.T) {
	config := "mixed-port: 7890\n"
	if err := ValidateReloadedConfiguration(config, `{"port":0,"socks-port":0,"mixed-port":7890}`); err != nil {
		t.Fatal(err)
	}
	for _, status := range []string{`{}`, `{"port":0,"socks-port":0,"mixed-port":0}`, `{"port":0,"socks-port":1,"mixed-port":7890}`, `{"port":0,"socks-port":0,"mixed-port":"7890"}`} {
		if ValidateReloadedConfiguration(config, status) == nil {
			t.Fatal("unverified port accepted")
		}
	}
}

func TestReloadDiagnosticsRetainEndpointsAndRecomputeRouting(t *testing.T) {
	input := "proxy-groups: [{name: A, type: select, proxies: [DIRECT]}]\nrules: ['MATCH,A']\n"
	raw, err := PrepareDNSDiagnostics(input, strings.Repeat("a", 64))
	if err != nil {
		t.Fatal(err)
	}
	var before, after struct {
		Configuration string
		Session       map[string]any
	}
	if json.Unmarshal([]byte(raw), &before) != nil {
		t.Fatal("invalid DNS envelope")
	}
	session, _ := json.Marshal(before.Session)
	next := strings.Replace(strings.Replace(input, "name: A", "name: B", 1), "MATCH,A", "MATCH,B", 1)
	raw, err = ReloadDNSDiagnostics(next, string(session))
	if err != nil || json.Unmarshal([]byte(raw), &after) != nil {
		t.Fatal(err)
	}
	for _, field := range []string{"ProxyAddress", "DirectAddress", "Username", "Password"} {
		if before.Session[field] != after.Session[field] {
			t.Fatal("reload changed private endpoint")
		}
	}
	if before.Session["MatchTarget"] != "A" || after.Session["MatchTarget"] != "B" {
		t.Fatal("stale MATCH metadata")
	}
}
