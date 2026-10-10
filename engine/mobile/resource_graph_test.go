package mobile

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"

	"jeemi-android/engine/internal/config/resources"
	"jeemi-android/engine/internal/localconfig"
)

func graphTest(t *testing.T, all []resourceInput, subs []graphSubscription, chains []graphChain) resourceGraph {
	t.Helper()
	raw, err := InspectResourceGraph(jsonText(t, all), jsonText(t, subs), jsonText(t, chains))
	if err != nil {
		t.Fatal(err)
	}
	var graph resourceGraph
	if err := json.Unmarshal([]byte(raw), &graph); err != nil {
		t.Fatal(err)
	}
	return graph
}

func TestResourceGraphUsesTransitiveStableReferencesAndCountsEachSubscriptionOnce(t *testing.T) {
	plan := resources.DefaultPlan()
	plan.StrategyGroupIDs = []string{"group"}
	plan.DefaultProxySelectorID = "group"
	plan.Match = resources.LocalMatch{Mode: "selector", SelectorID: "group"}
	plan.DisabledStrategyGroupIDs = []string{"group"}
	all := []resourceInput{
		{ID: "config", Name: "Config", Kind: "CONFIG", FormatVersion: 2, Content: jsonText(t, localconfig.Config{ResourcePlan: plan})},
		{ID: "group", Name: "Group", Kind: "GROUPS", FormatVersion: 2, Content: jsonText(t, resources.StrategyGroup{RuleSetReferences: []resources.RuleSetReference{{RuleSetID: "rules"}}})},
		{ID: "rules", Name: "Rules", Kind: "RULES", FormatVersion: 2, Content: `{"url":"https://secret.invalid/token"}`},
		{ID: "script", Name: "Script", Kind: "SCRIPT", FormatVersion: 2, Content: `function main(c) { c.note = 'rules group config'; return c; }`, Description: "config group rules"},
	}
	graph := graphTest(t, all, []graphSubscription{
		{ID: "first", HandlerID: "config", ChainGroupIDs: []string{"landing"}},
		{ID: "second", HandlerID: "config"},
		{ID: "text-only", HandlerID: "script"},
	}, []graphChain{{ID: "landing", Name: "Landing"}})
	for _, key := range []string{"resource:config", "resource:group", "resource:rules"} {
		found := false
		for _, node := range graph.Nodes {
			if node.Key == key {
				found = true
				if !reflect.DeepEqual(node.Subscriptions, []string{"first", "second"}) {
					t.Fatalf("wrong affected subscriptions for %s: %v", key, node.Subscriptions)
				}
			}
		}
		if !found {
			t.Fatalf("missing %s", key)
		}
	}
	for _, edge := range graph.Edges {
		if edge.From == "resource:config" && edge.Enabled {
			t.Fatal("disabled references must stay visible and marked")
		}
	}
	raw := jsonText(t, graph)
	if strings.Contains(raw, "secret.invalid") || strings.Contains(raw, "function main") {
		t.Fatal("graph leaked document or source URL")
	}
}

func TestResourceGraphRetainsMissingAndLegacyReferencesWithoutGuessing(t *testing.T) {
	graph := graphTest(t, []resourceInput{{ID: "legacy", Kind: "RULES", FormatVersion: 1, Content: "- DOMAIN,config.invalid,DIRECT"}},
		[]graphSubscription{{ID: "sub", ResourceIDs: []string{"legacy"}, ChainGroupIDs: []string{"missing"}}}, nil)
	if len(graph.Edges) != 2 || len(graph.Nodes) != 3 {
		t.Fatalf("wrong graph shape: %+v", graph)
	}
	missing := graph.Nodes[2]
	if !missing.Missing || missing.Key != "chain:missing" || !reflect.DeepEqual(missing.Subscriptions, []string{"sub"}) {
		t.Fatalf("missing link cannot be repaired: %+v", missing)
	}
	if _, err := InspectResourceGraph(`[{"id":"same"},{"id":"same"}]`, "[]", "[]"); err == nil {
		t.Fatal("duplicate resource identity accepted")
	}
}
