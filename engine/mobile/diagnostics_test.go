package mobile

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

type fakeOwner struct{}

func (fakeOwner) Lookup(network, src, dst string, sp, dp int) string {
	return `{"uid":12345,"package":"example.browser"}`
}

func TestOwnerBridgeRequiresSessionAuthenticationAndValidTuple(t *testing.T) {
	server, address, token, err := ownerServer(fakeOwner{})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	base := "http://" + address + "/owner?network=tcp&source=172.19.0.1&destination=203.0.113.1&sourcePort=1234&destinationPort=443"
	for _, test := range []struct {
		url, auth string
		status    int
	}{{base, "", 403}, {base, "Bearer wrong", 403}, {base, "Bearer " + token, 200}, {base + "&network=bad", "Bearer " + token, 200}, {"http://" + address + "/owner?source=not-ip", "Bearer " + token, 400}} {
		req, _ := http.NewRequestWithContext(context.Background(), "GET", test.url, nil)
		req.Header.Set("Authorization", test.auth)
		result, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(result.Body)
		result.Body.Close()
		if result.StatusCode != test.status {
			t.Fatalf("status %d", result.StatusCode)
		}
		if test.status == 200 && !strings.Contains(string(body), "example.browser") {
			t.Fatal("missing package")
		}
	}
}
func TestOfflineChoicesOnlyPrimeManualSelectorsAndPreserveCandidate(t *testing.T) {
	source := "proxy-groups:\n - {name: Manual, type: select, proxies: [DIRECT, REJECT]}\n - {name: Auto, type: url-test, proxies: [DIRECT, REJECT]}\n"
	prepared, err := PrimeStartupSelections(source, `{"Manual":"REJECT","Auto":"REJECT"}`)
	if err != nil {
		t.Fatal(err)
	}
	summary, _ := InspectSubscription(prepared)
	var parsed struct{ Groups []groupSummary }
	json.Unmarshal([]byte(summary), &parsed)
	if parsed.Groups[0].Members[0] != "REJECT" || parsed.Groups[1].Members[0] != "DIRECT" {
		t.Fatal(summary)
	}
	if !strings.Contains(prepared, "store-selected: false") {
		t.Fatal("core cache would override per-subscription choices")
	}
}
func TestConnectionRuleUsesDesktopLiteralValidationAndCompatiblePayload(t *testing.T) {
	raw, err := PrepareConnectionRule(`[]`, `{"name":"Apps","matchType":"processName","value":"org.example.browser"}`, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		Resource resourceInput
		Preview  struct{ Line string }
	}
	json.Unmarshal([]byte(raw), &result)
	if result.Preview.Line != "PROCESS-NAME,org.example.browser" {
		t.Fatal(raw)
	}
	library, _ := json.Marshal([]resourceInput{result.Resource})
	next, err := PrepareConnectionRule(string(library), `{"ruleSetId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","matchType":"domain","value":"Example.COM"}`, "unused")
	if err != nil || !strings.Contains(next, "DOMAIN-SUFFIX,example.com") || !strings.Contains(next, "org.example.browser") {
		t.Fatal(next, err)
	}
	if _, err = PrepareConnectionRule(`[]`, `{"name":"Bad","matchType":"processName","value":"app,REJECT"}`, "bad"); err == nil {
		t.Fatal("accepted rule separator injection")
	}
}
func TestAndroidConnectionRulesAcceptPackagesAndAddressesButNotPaths(t *testing.T) {
	for _, value := range []string{"/data/app/com.example/base.apk", `C:\Program Files\app.exe`, "org.example:service", "org.example.*"} {
		input, _ := json.Marshal(map[string]string{"name": "Apps", "matchType": "processName", "value": value})
		if _, err := PrepareConnectionRule(`[]`, string(input), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"); err == nil {
			t.Fatal("non-package application rule accepted")
		}
	}
	for _, entry := range []struct{ kind, value, expected string }{
		{"processName", " com.example.app ", "PROCESS-NAME,com.example.app"},
		{"processName", "android", "PROCESS-NAME,android"},
		{"domain", "Example.COM", "DOMAIN-SUFFIX,example.com"},
		{"ip", "203.0.113.2", "IP-CIDR,203.0.113.2/32"},
		{"ip", "2001:db8::2", "IP-CIDR,2001:db8::2/128"},
	} {
		input, _ := json.Marshal(map[string]string{"name": "Rules", "matchType": entry.kind, "value": entry.value})
		raw, err := PrepareConnectionRule(`[]`, string(input), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
		if err != nil || !strings.Contains(raw, entry.expected) {
			t.Fatalf("rule type %s not prepared: %v", entry.kind, err)
		}
	}
}
func TestCancelledDNSCannotFallBackToDirectForProxy(t *testing.T) {
	query, err := NewDNSQuery(`{"request":{"domain":"example.com","proxyDNS":"1.1.1.1","directDNS":"invalid","customDNS":"invalid","customProxy":true}}`)
	if err != nil {
		t.Fatal(err)
	}
	query.Cancel()
	started := time.Now()
	raw := query.Run()
	if time.Since(started) > time.Second || !strings.Contains(raw, "proxy_invalid") {
		t.Fatal(raw)
	}
}

func TestHomeCompositionHasStableBytesAcrossRepeatedIdenticalInputs(t *testing.T) {
	source := []byte("proxy-groups: [{name: Choose, type: select, proxies: [DIRECT, REJECT]}]\nrules: ['MATCH,Choose']\n")
	previous := ""
	for i := 0; i < 100; i++ {
		result, err := applyHome(source, RuntimeDefaults(), "rule", "mmdb", "memconservative", "gvisor")
		if err != nil {
			t.Fatal(err)
		}
		if i > 0 && string(result) != previous {
			t.Fatal("identical input changed candidate revision")
		}
		previous = string(result)
	}
}
