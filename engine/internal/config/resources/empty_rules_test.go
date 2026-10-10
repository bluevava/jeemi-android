package resources

import (
	"strings"
	"testing"

	"jeemi-android/engine/internal/config/compose"
	"jeemi-android/engine/internal/config/document"
	configinspect "jeemi-android/engine/internal/config/inspect"
)

func TestEmptyLocalRuleSetIsEditableButEmitsNoRulesOrProviders(t *testing.T) {
	for _, source := range []string{"payload: []\n", "payload:\n", "# local rules\npayload: # add below\n  # no entries yet\n"} {
		for _, behavior := range []string{"classical", "domain", "ipcidr"} {
			rule, err := NormalizeRuleSet(RuleSet{ID: ruleSetID, Name: "Empty", SourceType: "inline", Behavior: behavior, PayloadYAML: source})
			if err != nil || len(rule.Payload) != 0 {
				t.Fatalf("empty %s placeholder rejected: %v", behavior, err)
			}
			if rule.PayloadYAML != source || rule.Payload == nil {
				t.Fatalf("source changed or payload is null: %+v", rule)
			}
			for _, output := range []string{RuleOutputInline, RuleOutputRuleSet} {
				plan, state := localMatchFixture()
				plan.RuleStrategy = compose.StrategyReplace
				plan.Match = LocalMatch{Mode: "selector", SelectorID: selectorID}
				state.RuleSets = []RuleSet{rule}
				state.StrategyGroups[1].RuleOutput = output
				base, err := PrepareSource([]byte(reconstructionSource), plan)
				if err != nil {
					t.Fatal(err)
				}
				result, err := Apply(base, state, plan)
				if err != nil {
					t.Fatal(err)
				}
				parsed, _ := document.Parse(result)
				rules, _, _ := document.Find(document.Root(parsed), "/rules")
				providers, _, _ := document.Find(document.Root(parsed), "/rule-providers")
				if len(rules.Content) != 1 || rules.Content[0].Value != "MATCH,Local" || len(providers.Content) != 0 {
					t.Fatalf("empty placeholder emitted routing: %s", result)
				}
				if count, err := RuleProviderCount(plan, state); err != nil || count != 0 {
					t.Fatalf("empty provider count %d: %v", count, err)
				}
				if err := configinspect.ValidateReferences(result); err != nil {
					t.Fatal(err)
				}
			}
		}
	}
	for _, invalid := range []string{"", "# only a comment", "other:", "payload: null", "payload: ~", "payload: !!null", "payload: &empty", "payload: {}", "payload: ['']", "payload: [null]", "payload:\n  -\n", "payload:\nextra: []", "payload:\n---\npayload: []"} {
		if _, _, err := normalizeRuleSetPayloadYAML(invalid); err == nil {
			t.Fatalf("invalid payload accepted: %q", invalid)
		}
	}
	for _, source := range []string{"payload: []\n", "payload:\n", "# local rules\npayload: # add below\n  # no entries yet\n"} {
		state := State{RuleSets: []RuleSet{{ID: ruleSetID, Name: "Direct", SourceType: "inline", Behavior: "classical", PayloadYAML: source}}}
		updated, preview, err := prepareRuleSetEntry(RuleSetEntryInput{RuleSetID: ruleSetID, MatchType: "processName", Value: "com.example.reader"}, state)
		if err != nil || !preview.Valid || len(updated.Payload) != 1 || updated.Payload[0] != "PROCESS-NAME,com.example.reader" {
			t.Fatalf("first app rule could not be added: %v %+v", err, preview)
		}
		if !strings.Contains(updated.PayloadYAML, "  - 'PROCESS-NAME,com.example.reader'") {
			t.Fatalf("first rule is not a block list: %q", updated.PayloadYAML)
		}
		if strings.Contains(source, "# local rules") {
			for _, comment := range []string{"# local rules", "# add below", "# no entries yet"} {
				if !strings.Contains(updated.PayloadYAML, comment) {
					t.Fatalf("lost comment %q when appending: %q", comment, updated.PayloadYAML)
				}
			}
		}
	}
}

func TestUnfilteredSelectorCanRetainAllProviderSourcesDuringRebuild(t *testing.T) {
	for _, source := range []string{reconstructionSource, strings.Replace(reconstructionSource, "proxies: [{name: node, type: ss}]", "proxies: []", 1)} {
		for _, include := range []bool{false, true} {
			plan, state := localMatchFixture()
			plan.RuleStrategy = compose.StrategyReplace
			plan.Match = LocalMatch{Mode: "selector", SelectorID: selectorID}
			state.StrategyGroups[0].IncludeAllProviders = include
			base, err := PrepareSource([]byte(source), plan)
			if err != nil {
				t.Fatal(err)
			}
			result, err := Apply(base, state, plan)
			if err != nil {
				t.Fatal(err)
			}
			parsed, _ := document.Parse(result)
			groups, _, _ := document.Find(document.Root(parsed), "/proxy-groups")
			use, exists, _ := document.Find(groups.Content[0], "/use")
			if exists != include || (include && (len(use.Content) != 1 || use.Content[0].Value != "nodes")) {
				t.Fatalf("provider use mismatch: %s", result)
			}
			if err := configinspect.ValidateReferences(result); err != nil {
				t.Fatal(err)
			}
		}
	}
	for _, filter := range []NodeFilter{{NamePatterns: []string{"HK"}}, {ProxyTypes: []string{"ss"}}} {
		if _, err := NormalizeGroup(StrategyGroup{Name: "Selector", Kind: "selector", Type: "select", IncludeAllProviders: true, Filter: filter}); err == nil {
			t.Fatal("remote members must not silently bypass a local-only filter")
		}
	}
}
