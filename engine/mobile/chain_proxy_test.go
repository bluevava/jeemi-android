package mobile

import (
	"encoding/json"
	"strings"
	"testing"

	"jeemi-android/engine/internal/chainproxy"
)

const chainFixture = "proxies:\n  - {name: HK GM, type: socks5, server: 192.0.2.1, port: 1080}\n  - {name: JP GM, type: socks5, server: 192.0.2.2, port: 1080}\nproxy-groups:\n  - {name: Main, type: select, proxies: [HK GM]}\n  - {name: Japan, type: select, proxies: [JP GM]}\nrules: ['MATCH,Main']\n"
const landingFixture = "anytls://fixture@192.0.2.10:443?type=tcp&security=tls&tfo=false#Landing"

func mutateChain(t *testing.T, library string, input map[string]any) string {
	t.Helper()
	state, err := readChainLibrary(library)
	if err != nil {
		t.Fatal(err)
	}
	input["revision"] = state.Revision
	raw, _ := json.Marshal(input)
	result, err := MutateChainLibrary(library, string(raw))
	if err != nil {
		t.Fatal(err)
	}
	var output struct {
		Library json.RawMessage `json:"library"`
	}
	if json.Unmarshal([]byte(result), &output) != nil {
		t.Fatal("invalid mutation result")
	}
	return string(output.Library)
}

func manualChain(t *testing.T) (string, string) {
	t.Helper()
	library := mutateChain(t, "", map[string]any{"action": "save_group", "name": "Landing group", "kind": "manual"})
	state, _ := readChainLibrary(library)
	id := state.Groups[0].ID
	library = mutateChain(t, library, map[string]any{"action": "import_nodes", "groupId": id, "contents": landingFixture})
	return library, id
}

func chainProjection(t *testing.T, library string, ids []string, original string, resources []resourceInput, handler string, fallback string) projection {
	t.Helper()
	request := workspaceInput{Original: original, Configuration: "rules: ['MATCH,DIRECT']", Resources: resources, HandlerID: handler,
		ChainGroupIDs: ids, Mode: "rule", GeoMode: "mmdb", GeoLoader: "memconservative"}
	if library != "" {
		request.ChainLibrary = json.RawMessage(library)
	}
	if len(ids) > 0 {
		request.ChainRevision = 1
	}
	if fallback != "" {
		request.Fallback.Mode = "selector"
		request.Fallback.Selector = fallback
	}
	raw, _ := json.Marshal(request)
	text, err := ComposeWorkspace(string(raw))
	if err != nil {
		t.Fatal(err)
	}
	var result projection
	if json.Unmarshal([]byte(text), &result) != nil {
		t.Fatal("invalid projection")
	}
	return result
}

func TestChainWorkspaceUsesCurrentOriginalAndRunsAfterHandlerAndFallback(t *testing.T) {
	library, id := manualChain(t)
	script := resourceInput{ID: "script", Name: "script", Kind: "SCRIPT", Content: "function main(c) { c['proxy-groups'][1].name = 'Script group'; return c; }"}
	result := chainProjection(t, library, []string{id}, chainFixture, []resourceInput{script}, "script", "Script group")
	if result.Chains.Generated != 1 || !strings.Contains(result.YAML, "dialer-proxy: JP GM") || strings.Contains(result.YAML, "dialer-proxy: HK GM") {
		t.Fatal("wrong phase or per-selector intermediary")
	}
	if result.Normalization == nil || result.Normalization.Fingerprint == "" || result.Chains.Fingerprint == "" {
		t.Fatal("missing source fingerprints")
	}
	if len(result.Nodes) != 3 || result.Nodes[2].Type != "anytls" {
		t.Fatal("missing generated node summary")
	}
	if !strings.Contains(result.YAML, "tfo: false") {
		t.Fatal("lost explicit false")
	}
}

func TestChainWorkspaceUnlinkedChangesDoNotInvalidateSelectedFingerprint(t *testing.T) {
	library, id := manualChain(t)
	before := chainProjection(t, library, []string{id}, chainFixture, nil, "", "")
	next := mutateChain(t, library, map[string]any{"action": "save_group", "name": "Unrelated", "kind": "subscription"})
	after := chainProjection(t, next, []string{id}, chainFixture, nil, "", "")
	if before.Chains.Fingerprint != after.Chains.Fingerprint || before.YAML != after.YAML {
		t.Fatal("unrelated group changed projection")
	}
	state, _ := readChainLibrary(next)
	next = mutateChain(t, next, map[string]any{"action": "save_group", "groupId": id, "name": "Changed", "kind": "manual", "nodeFilter": "missing"})
	changed := chainProjection(t, next, []string{id}, chainFixture, nil, "", "")
	if changed.Chains.Generated != 0 || changed.Chains.Fingerprint == after.Chains.Fingerprint || len(state.Groups) != 2 {
		t.Fatal("linked change not reflected")
	}
}

func TestChainMutationCASDeletionAndImmutableGroupType(t *testing.T) {
	library, id := manualChain(t)
	state, _ := readChainLibrary(library)
	for _, input := range []map[string]any{
		{"action": "save_group", "revision": 0, "groupId": id, "name": "stale", "kind": "manual"},
		{"action": "save_group", "revision": state.Revision, "groupId": id, "name": "changed", "kind": "subscription"},
		{"action": "delete_group", "revision": state.Revision, "groupId": id, "usedGroups": []string{id}},
		{"action": "import_nodes", "revision": state.Revision, "groupId": id, "id": state.Groups[0].Nodes[0].ID, "contents": landingFixture + "\n" + strings.Replace(landingFixture, "Landing", "Second", 1)},
	} {
		raw, _ := json.Marshal(input)
		if result, err := MutateChainLibrary(library, string(raw)); err == nil || result != "" {
			t.Fatal("invalid mutation succeeded")
		}
	}
	loaded, err := readChainLibrary(library)
	if err != nil || loaded.Revision != state.Revision || len(loaded.Groups[0].Nodes) != 1 {
		t.Fatal("input mutated on failure")
	}
	next := mutateChain(t, library, map[string]any{"action": "import_nodes", "groupId": id, "id": state.Groups[0].Nodes[0].ID, "contents": strings.Replace(landingFixture, "fixture", "new-fixture", 1)})
	edited, _ := readChainLibrary(next)
	if edited.Groups[0].Nodes[0].ID != state.Groups[0].Nodes[0].ID {
		t.Fatal("manual identity changed")
	}
}

func TestChainSourceRefreshStableIdentityFilteringAndCredentialSafeList(t *testing.T) {
	library := mutateChain(t, "", map[string]any{"action": "save_group", "name": "URL group", "kind": "subscription"})
	state, _ := readChainLibrary(library)
	groupID := state.Groups[0].ID
	input := map[string]any{"action": "save_source", "groupId": groupID, "url": "https://example.invalid/sub?token=secret", "filter": "landing", "contents": landingFixture}
	library = mutateChain(t, library, input)
	state, _ = readChainLibrary(library)
	source := state.Groups[0].Sources[0]
	public, err := ChainProxyState(library)
	if err != nil || strings.Contains(public, "token") || strings.Contains(public, "fixture@") || strings.Contains(public, "192.0.2.10") {
		t.Fatal("public state leaked source secrets")
	}
	input["id"] = source.ID
	input["contents"] = strings.Replace(landingFixture, "fixture", "changed-password", 1)
	updated := mutateChain(t, library, input)
	state, _ = readChainLibrary(updated)
	if state.Groups[0].Sources[0].Nodes[0].ID != source.Nodes[0].ID {
		t.Fatal("source node ID changed with credentials")
	}
	input["filter"] = "not matched"
	updated = mutateChain(t, updated, input)
	state, _ = readChainLibrary(updated)
	if len(state.Groups[0].Sources[0].Nodes) != 0 {
		t.Fatal("empty source filter result must be allowed")
	}
	text, err := ChainProxyItem(updated, groupID, source.ID, "source")
	if err != nil || !strings.Contains(text, "token=secret") {
		t.Fatal("explicit editor lost URL")
	}
}

func TestChainLibraryRoundTripRejectsUnknownCorruptAndOversizedInputs(t *testing.T) {
	library, _ := manualChain(t)
	normalized, err := NormalizeChainLibrary(library)
	if err != nil || normalized != library {
		t.Fatal("library normalization changed saved document")
	}
	for _, raw := range []string{"null", "{}", library + " {}", strings.Replace(library, `"version":1`, `"version":9`, 1), strings.Repeat(" ", maxChainLibraryBytes+1)} {
		if _, err := NormalizeChainLibrary(raw); err == nil {
			t.Fatal("invalid library accepted")
		}
	}
	if _, err := chainproxy.SelectedGroups(chainproxy.Library{Version: 1}, []string{"missing"}); err == nil {
		t.Fatal("missing group accepted")
	}
}

func TestMobileURIRecoveryUsesNewParserBeforeAssociatedScript(t *testing.T) {
	raw := "broken://secret-must-not-leak\n" + landingFixture
	script := resourceInput{ID: "script", Name: "script", Kind: "SCRIPT", Content: "function main(c) { c['x-node-count'] = c.proxies.length; return c; }"}
	result := chainProjection(t, "", nil, raw, []resourceInput{script}, "script", "")
	if result.Normalization.SkippedNodes != 1 || len(result.Nodes) != 1 || !strings.Contains(result.YAML, "x-node-count: 1") {
		t.Fatal("recovery or pipeline failed")
	}
	report, _ := json.Marshal(result.Normalization)
	if strings.Contains(string(report), "secret-must-not-leak") {
		t.Fatal("report leaked URI")
	}
}

func TestChainWorkspaceBooleanFiltersReachGeneratedNodes(t *testing.T) {
	queries := []string{"hk | jp & gm & !ev", "hk&gm&!ev|jp", "!ev&gm|jp|hk", "　hk｜jp ＆gm！ev　"}
	for _, query := range queries {
		t.Run(query, func(t *testing.T) {
			library := mutateChain(t, "", map[string]any{"action": "save_group", "name": "Filtered", "kind": "subscription",
				"selectorFilter": "main | japan & a ! skipped", "nodeFilter": query})
			state, _ := readChainLibrary(library)
			id := state.Groups[0].ID
			contents := strings.Join([]string{
				strings.Replace(landingFixture, "#Landing", "#US%20Exit", 1),
				strings.Replace(landingFixture, "#Landing", "#SG%20Exit", 1),
				strings.Replace(landingFixture, "#Landing", "#US%20Exit%20Expired", 1),
				strings.Replace(landingFixture, "#Landing", "#JP%20Exit", 1),
			}, "\n")
			library = mutateChain(t, library, map[string]any{"action": "save_source", "groupId": id,
				"url": "https://example.invalid/fixture", "contents": contents, "filter": "us | sg & exit ! expired"})
			state, _ = readChainLibrary(library)
			if len(state.Groups[0].Sources[0].Nodes) != 2 {
				t.Fatal("landing filter was not applied at the mobile mutation boundary")
			}
			original := strings.Replace(chainFixture, "proxy-groups:", "  - {name: HK GM EV, type: socks5, server: 192.0.2.3, port: 1080}\nproxy-groups:", 1)
			original = strings.Replace(original, "proxies: [HK GM]", "proxies: [HK GM, HK GM EV]", 1)
			result := chainProjection(t, library, []string{id}, original, nil, "", "")
			if result.Chains.Generated != 4 || len(result.Nodes) != 7 || strings.Contains(result.YAML, "dialer-proxy: HK GM EV") {
				t.Fatal("expected two landing nodes through each of the two matching intermediaries")
			}
			if !strings.Contains(result.YAML, "dialer-proxy: HK GM") || !strings.Contains(result.YAML, "dialer-proxy: JP GM") {
				t.Fatal("matching intermediaries missing from generated node cards")
			}
		})
	}
}
