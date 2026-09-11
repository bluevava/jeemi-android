package mobile

import (
	"encoding/json"
	"testing"
)

func selectorGroups(t *testing.T, raw []byte) []groupSummary {
	t.Helper()
	var structure struct {
		Groups []groupSummary `json:"groups"`
	}
	if err := json.Unmarshal(raw, &structure); err != nil {
		t.Fatal(err)
	}
	return structure.Groups
}

func TestSelectorIconProjectionPreservesNamesAndIgnoresNonStringIcons(t *testing.T) {
	source := `proxy-groups:
  - {name: '🇨🇳 Domestic', type: select, icon: 'https://images.example.invalid/domestic.png', proxies: [DIRECT]}
  - {name: '🏡 No icon', type: select, proxies: [DIRECT]}
  - {name: 'Invalid image', type: select, icon: '[image](https://images.example.invalid/a.png)', proxies: [DIRECT]}
  - {name: 'Invalid field', type: select, icon: {url: 'https://images.example.invalid/a.png'}, proxies: [DIRECT]}
  - {name: 'Null field', type: select, icon: null, proxies: [DIRECT]}
`
	raw, err := InspectSubscription(source)
	if err != nil {
		t.Fatal(err)
	}
	groups := selectorGroups(t, []byte(raw))
	if len(groups) != 5 || groups[0].Name != "🇨🇳 Domestic" || groups[0].Icon != "https://images.example.invalid/domestic.png" {
		t.Fatal("selector name or optional URL was lost")
	}
	if groups[1].Icon != "" || groups[3].Icon != "" || groups[4].Icon != "" {
		t.Fatal("missing/non-string icons must have an empty presentation value")
	}
	if groups[2].Icon != "[image](https://images.example.invalid/a.png)" {
		t.Fatal("inspection must not rewrite user-provided values")
	}
}

func TestSelectorIconComesFromFinalCandidate(t *testing.T) {
	source := "proxy-groups: [{name: '🏡 Domestic', type: select, icon: 'https://images.example.invalid/source.png', proxies: [DIRECT]}]\nrules: ['MATCH,🏡 Domestic']\n"
	for _, kind := range []string{"CONFIG", "SCRIPT"} {
		t.Run(kind, func(t *testing.T) {
			content := "proxy-groups: [{name: '🏡 Domestic', type: select, icon: 'https://images.example.invalid/final.png', proxies: [DIRECT]}]\n"
			if kind == "SCRIPT" {
				content = "function main(config) { config['proxy-groups'][0].icon = 'https://images.example.invalid/final.png'; return config; }"
			}
			resource := resourceInput{ID: "icons", Name: "Icons", Kind: kind, Content: content, Strategy: "replace", FormatVersion: 1}
			out := projectTest(t, workspaceInput{Original: source, HandlerID: resource.ID, Resources: []resourceInput{resource}})
			groups := selectorGroups(t, out.Structure)
			if len(groups) != 1 || groups[0].Name != "🏡 Domestic" || groups[0].Icon != "https://images.example.invalid/final.png" {
				t.Fatal("selector UI must use the final candidate icon and exact group name")
			}
		})
	}
}
