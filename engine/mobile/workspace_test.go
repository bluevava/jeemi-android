package mobile

import (
	"encoding/json"
	"jeemi-android/engine/internal/config/compose"
	"jeemi-android/engine/internal/config/document"
	"jeemi-android/engine/internal/config/fallbackoverride"
	"jeemi-android/engine/internal/config/resources"
	"jeemi-android/engine/internal/localconfig"
	"jeemi-android/engine/internal/runtimeconfig"
	"strings"
	"testing"
)

func jsonText(t *testing.T, value any) string {
	t.Helper()
	b, e := json.Marshal(value)
	if e != nil {
		t.Fatal(e)
	}
	return string(b)
}
func TestAndroidSessionOwnsTunControllerAndGeoWithoutMutatingCandidate(t *testing.T) {
	source := "mode: rule\nexternal-controller: 0.0.0.0:9090\nsecret: unsafe\ntun: {enable: false, device: source}\n" +
		"listeners: [{name: extra, type: socks, port: 1234}]\ngeo-auto-update: true\ngeox-url: {mmdb: https://example.invalid/db}\nrules: ['MATCH,DIRECT']\n"
	text, err := AndroidSessionConfiguration(source, strings.Repeat("s", 64), 19090, false)
	if err != nil {
		t.Fatal(err)
	}
	doc, err := document.Parse([]byte(text))
	if err != nil {
		t.Fatal(err)
	}
	for path, value := range map[string]string{"/tun/file-descriptor": "3", "/tun/enable": "true", "/tun/auto-route": "false",
		"/external-controller": "127.0.0.1:19090", "/geo-auto-update": "false", "/geox-url/mmdb": "jeemi-resource://managed"} {
		node, found, err := document.Find(document.Root(doc), path)
		if err != nil || !found || node.Value != value {
			t.Errorf("service ownership failed: %s", path)
		}
	}
	for _, path := range []string{"/listeners", "/tun/device", "/tun/inet6-address"} {
		_, found, _ := document.Find(document.Root(doc), path)
		if found {
			t.Errorf("unexpected source field: %s", path)
		}
	}
	if !strings.Contains(source, "secret: unsafe") {
		t.Fatal("source changed")
	}
}
func projectTest(t *testing.T, input workspaceInput) projection {
	t.Helper()
	if input.Runtime == nil {
		input.Runtime = json.RawMessage(RuntimeDefaults())
	}
	if input.Mode == "" {
		input.Mode = "rule"
	}
	if input.GeoMode == "" {
		input.GeoMode = "mmdb"
	}
	if input.GeoLoader == "" {
		input.GeoLoader = "memconservative"
	}
	raw, e := ComposeWorkspace(jsonText(t, input))
	if e != nil {
		t.Fatal(e)
	}
	var out projection
	if e = json.Unmarshal([]byte(raw), &out); e != nil {
		t.Fatal(e)
	}
	return out
}
func TestAndroidCandidateUsesDesktopHomeDefaultsLast(t *testing.T) {
	source := "mode: global\nlog-level: debug\nipv6: false\ntun: {enable: true, device: surprise}\nport: 9090\ndns:\n  nameserver: [1.1.1.1, 114.114.114.114]\n  fake-ip-filter: [source.invalid]\n  use-hosts: false\nhosts: {source.invalid: 127.0.0.2}\nrules: [MATCH,DIRECT]\n"
	// YAML flow sequence must quote a rule containing a comma.
	source = strings.ReplaceAll(source, "[MATCH,DIRECT]", "['MATCH,DIRECT']")
	out := projectTest(t, workspaceInput{Configuration: source})
	for _, value := range []string{"mode: rule", "log-level: silent", "ipv6: true", "mixed-port: 7890", "223.5.5.5", "geodata-mode: false", "geo-auto-update: false", "source.invalid"} {
		if !strings.Contains(out.YAML, value) {
			t.Errorf("missing %s", value)
		}
	}
	doc, e := document.Parse([]byte(out.YAML))
	if e != nil {
		t.Fatal(e)
	}
	for _, path := range []string{"/tun/device", "/port"} {
		_, exists, _ := document.Find(document.Root(doc), path)
		if exists {
			t.Error("Android must remove", path)
		}
	}
	if strings.Count(out.YAML, "114.114.114.114") != 1 {
		t.Error("nameservers must deduplicate")
	}
	if strings.Contains(out.YAML, "fake-ip-filter: [source.invalid]") {
		t.Error("filter must replace")
	}
	if len(out.Providers) != 0 || len(out.Fallback.Selectors) != 0 {
		t.Error("empty collections must remain empty")
	}
}
func TestStructuredFieldsDisabledFalseAndWholeGraph(t *testing.T) {
	c := localconfig.Config{Name: "fields", Fields: []localconfig.Field{{Path: "/tcp-concurrent", ValueYAML: "false", Strategy: compose.StrategyReplace}},
		DisabledFields: []localconfig.Field{{Path: "/unified-delay", ValueYAML: "false", Strategy: compose.StrategyReplace}}, ResourcePlan: resources.DefaultPlan()}
	r := resourceInput{ID: strings.Repeat("a", 32), Name: "fields", Kind: "CONFIG", FormatVersion: 2, Content: jsonText(t, c)}
	out := projectTest(t, workspaceInput{Configuration: "tcp-concurrent: true\nunified-delay: true\nrules: ['MATCH,DIRECT']\n", Resources: []resourceInput{r}, HandlerID: r.ID})
	if !strings.Contains(out.YAML, "tcp-concurrent: false") || !strings.Contains(out.YAML, "unified-delay: true") {
		t.Error("disabled and false must differ")
	}
	c.Fields = append(c.Fields, localconfig.Field{Path: "/mode", ValueYAML: "direct", Strategy: compose.StrategyReplace})
	r.Content = jsonText(t, c)
	if _, e := PrepareStructuredResource(jsonText(t, r)); e == nil {
		t.Error("Home-owned field accepted")
	}
}
func TestHiddenFallbackAndProviderProjection(t *testing.T) {
	source := "proxies: []\nproxy-groups:\n  - {name: hidden, type: select, hidden: true, proxies: [DIRECT]}\nrule-providers:\n  local: {type: inline, behavior: classical, payload: [DOMAIN,example.invalid]}\nrules: ['RULE-SET,local,DIRECT','MATCH,DIRECT']\n"
	out := projectTest(t, workspaceInput{Configuration: source, DisabledProviders: []string{"local"}, Fallback: fallbackoverride.Selection{Mode: "selector", Selector: "hidden"}})
	if len(out.Providers) != 1 || out.Providers[0].Name != "local" {
		t.Error("provider manager must retain disabled entries")
	}
	if strings.Contains(out.YAML, "RULE-SET,local") || !strings.Contains(out.YAML, "MATCH,hidden") {
		t.Error("wrong provider/fallback order")
	}
	if len(out.Fallback.Selectors) != 1 || out.Fallback.Selectors[0] != "hidden" {
		t.Error("hidden selector excluded")
	}
}
func TestResourcePackageRemapsFullDependenciesAndRejectsBrokenReference(t *testing.T) {
	selector := resources.StrategyGroup{ID: strings.Repeat("b", 32), Name: "Selector", Kind: "selector", Type: "select"}
	plan := resources.DefaultPlan()
	plan.StrategyGroupIDs = []string{selector.ID}
	plan.Match = resources.LocalMatch{Mode: "selector", SelectorID: selector.ID}
	plan.RuleStrategy = compose.StrategyReplace
	c := localconfig.Config{Name: "Routing", Fields: []localconfig.Field{}, ResourcePlan: plan}
	config := resourceInput{ID: strings.Repeat("a", 32), Name: "Routing", Kind: "CONFIG", FormatVersion: 2, Content: jsonText(t, c)}
	group := resourceInput{ID: selector.ID, Name: selector.Name, Kind: "GROUPS", FormatVersion: 2, Content: jsonText(t, selector)}
	all := []resourceInput{config, group}
	if e := ValidateResourceLibrary(jsonText(t, all)); e != nil {
		t.Fatal(e)
	}
	source := "proxies:\n  - {name: One, type: socks5, server: example.invalid, port: 1080}\nproxy-groups: [{name: old, type: select, proxies: [One]}]\nrules: ['MATCH,old']"
	out := projectTest(t, workspaceInput{Configuration: source, Resources: all, HandlerID: config.ID})
	if !strings.Contains(out.YAML, "MATCH,Selector") || strings.Contains(out.YAML, "name: old") {
		t.Error("rebuild did not replace routing")
	}
	pkg, e := ExportResourcePackage(jsonText(t, config), jsonText(t, all))
	if e != nil {
		t.Fatal(e)
	}
	target := resourceInput{ID: strings.Repeat("c", 32), Kind: "CONFIG", Name: "target", FormatVersion: 2}
	raw, e := PrepareResourceImport(pkg, jsonText(t, target), "[]")
	if e != nil {
		t.Fatal(e)
	}
	var imported struct {
		Resources []resourceInput `json:"resources"`
	}
	if e = json.Unmarshal([]byte(raw), &imported); e != nil {
		t.Fatal(e)
	}
	if len(imported.Resources) != 2 {
		t.Error("missing dependency")
	}
	if e := ValidateResourceLibrary(jsonText(t, imported.Resources)); e != nil {
		t.Fatal(e)
	}
	if e := ValidateResourceLibrary(jsonText(t, []resourceInput{config})); e == nil {
		t.Error("missing referenced group accepted")
	}
	if _, e := PrepareResourceImport(strings.Replace(pkg, `"version": 1`, `"version": 99`, 1), jsonText(t, target), "[]"); e == nil {
		t.Error("unsupported package version accepted")
	}
}
func TestUpgradeRuntimeKeepsExplicitFalseAndOldReplace(t *testing.T) {
	raw, e := UpgradeRuntimePreferences(`{"/ipv6":"false","/dns/nameserver":"[1.1.1.1]"}`)
	if e != nil {
		t.Fatal(e)
	}
	var p runtimeconfig.Preferences
	if e = json.Unmarshal([]byte(raw), &p); e != nil {
		t.Fatal(e)
	}
	if p.IPv6 || p.DNSNameserverMerge != "override" || !p.DNSNameserverEnabled {
		t.Error("migration lost explicit values")
	}
}

func TestGlobalModeProjectsCoreSelectorWithoutChangingTheCandidate(t *testing.T) {
	base := "proxies: [{name: Node, type: socks5, server: example.invalid, port: 1080}]\nproxy-groups: [{name: Route, type: select, proxies: [Node, DIRECT]}]\n"
	decode := func(source string) []groupSummary {
		t.Helper()
		value, err := InspectSubscription(source)
		if err != nil {
			t.Fatal(err)
		}
		var result struct {
			Groups []groupSummary `json:"groups"`
		}
		if err := json.Unmarshal([]byte(value), &result); err != nil {
			t.Fatal(err)
		}
		return result.Groups
	}
	global := decode("mode: global\n" + base)
	if len(global) != 2 || global[0].Name != "GLOBAL" || strings.Join(global[0].Members, ",") != "DIRECT,REJECT,Node,Route" {
		t.Fatalf("missing runtime GLOBAL projection: %+v", global)
	}
	if rule := decode("mode: rule\n" + base); len(rule) != 1 || rule[0].Name != "Route" {
		t.Fatalf("rule mode gained GLOBAL: %+v", rule)
	}
	custom := decode("mode: global\nproxy-groups: [{name: GLOBAL, type: select, proxies: [REJECT, DIRECT]}]\n")
	if len(custom) != 1 || strings.Join(custom[0].Members, ",") != "REJECT,DIRECT" {
		t.Fatalf("custom GLOBAL was replaced: %+v", custom)
	}
}
