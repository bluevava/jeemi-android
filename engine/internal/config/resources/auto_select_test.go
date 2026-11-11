package resources

import (
	"jeemi-android/engine/internal/config/document"
	"strings"
	"testing"
)

func TestAutoCompanionsValidateNamesAndRetainLegacyBehavior(t *testing.T) {
	g, err := NormalizeGroup(StrategyGroup{ID: strings.Repeat("a", 32), Name: "AI", Kind: "selector", Type: "select", AutoSelect: true})
	if err != nil || g.Interval != 300 || g.URL == "" {
		t.Fatal("missing companion defaults", err)
	}
	other, err := NormalizeGroup(StrategyGroup{ID: strings.Repeat("b", 32), Name: "AI Auto", Kind: "selector", Type: "select"})
	if err != nil {
		t.Fatal(err)
	}
	if ValidateState(State{StrategyGroups: []StrategyGroup{g, other}}) == nil {
		t.Fatal("generated name collision accepted")
	}
	root, _ := document.Parse([]byte("proxy-providers: {remote: {type: http, url: 'https://example.invalid/nodes'}}"))
	if _, err := materializeSelectors([]string{g.ID}, map[string]StrategyGroup{g.ID: g}, []proxyRecord{{Name: "AI Auto", Type: "ss"}}, document.Root(root)); err == nil {
		t.Fatal("generated name collides with real node")
	}
	g.IncludeAllProviders = true
	result, err := materializeSelectors([]string{g.ID}, map[string]StrategyGroup{g.ID: g}, nil, document.Root(root))
	if err != nil || len(result.Content) != 2 {
		t.Fatal("provider-only companion unavailable", err)
	}
	for _, group := range result.Content {
		use, exists, _ := document.Find(group, "/use")
		if !exists || len(use.Content) != 1 || use.Content[0].Value != "remote" {
			t.Fatal("provider scope lost")
		}
	}
	g.AutoSelect = false
	result, err = materializeSelectors([]string{g.ID}, map[string]StrategyGroup{g.ID: g}, []proxyRecord{{Name: "Node", Type: "ss"}}, document.Root(root))
	if err != nil || len(result.Content) != 1 {
		t.Fatal("legacy select changed", err)
	}
	for _, kind := range []string{"url-test", "fallback", "load-balance"} {
		g.Type, g.AutoSelect = kind, true
		normalized, err := NormalizeGroup(g)
		if err != nil || normalized.AutoSelect {
			t.Fatal("non-select companion accepted", err)
		}
	}
}
