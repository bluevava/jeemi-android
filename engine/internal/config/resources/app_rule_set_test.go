package resources

import (
	"reflect"
	"strings"
	"testing"
)

func TestAppRulesEditMixedASTWithoutLosingOrderCommentsOrDuplicates(t *testing.T) {
	source := "# routing\npayload:\n  - DOMAIN-SUFFIX,example.com # first\n  - 'PROCESS-NAME,com.example.one' # app one\n  - IP-CIDR,192.0.2.0/24\n  - PROCESS-NAME,com.example.two # app two\n  - PROCESS-NAME-REGEX,.*reader.*\n  - PROCESS-NAME,com.example.one\n"
	view, err := EditAppRules(source, AppRuleEdit{Action: "view"})
	if err != nil || view.PayloadYAML != source || view.OtherRuleCount != 3 || len(view.Apps) != 3 || len(view.Rows) != 6 {
		t.Fatalf("mixed view: %+v %v", view, err)
	}
	for i, row := range view.Rows {
		if row.Index != i || row.Rule == "" || row.PackageName != appRulePackage(row.Rule) {
			t.Fatalf("incomplete visible row: %+v", row)
		}
	}
	view, err = EditAppRules(source, AppRuleEdit{Action: "move", Index: 3, Step: -1})
	if err != nil {
		t.Fatal(err)
	}
	_, rules, _ := normalizeRuleSetPayloadYAML(view.PayloadYAML)
	want := []string{"DOMAIN-SUFFIX,example.com", "PROCESS-NAME,com.example.one", "PROCESS-NAME,com.example.two", "IP-CIDR,192.0.2.0/24", "PROCESS-NAME-REGEX,.*reader.*", "PROCESS-NAME,com.example.one"}
	if !reflect.DeepEqual(rules, want) {
		t.Fatalf("mixed order changed: %v", rules)
	}
	for _, comment := range []string{"# routing", "# first", "# app one", "# app two"} {
		if !strings.Contains(view.PayloadYAML, comment) {
			t.Fatalf("lost comment: %s", comment)
		}
	}
	view, err = EditAppRules(view.PayloadYAML, AppRuleEdit{Action: "delete", Index: 1})
	if err != nil || len(view.Apps) != 2 || view.OtherRuleCount != 3 {
		t.Fatal(view, err)
	}
	view, err = EditAppRules(view.PayloadYAML, AppRuleEdit{Action: "add", Packages: []string{"com.example.one", "com.example.new", "com.example.new"}})
	if err != nil || len(view.Apps) != 3 || view.Apps[2].PackageName != "com.example.new" || view.OtherRuleCount != 3 {
		t.Fatal(view, err)
	}
	_, rules, _ = normalizeRuleSetPayloadYAML(view.PayloadYAML)
	if rules[0] != want[0] || rules[2] != want[3] || rules[3] != want[4] {
		t.Fatalf("lost mixed rules: %v", rules)
	}
	unchanged, err := EditAppRules(source, AppRuleEdit{Action: "add", Packages: []string{"com.example.one"}})
	if err != nil || unchanged.PayloadYAML != source {
		t.Fatal("duplicate changed source", err)
	}
}

func TestAppRulesEmptyFlowAndUnsafeOperations(t *testing.T) {
	for _, source := range []string{"payload:\n", "# root\npayload: # header\n  # empty\n", "payload: [] # flow\n"} {
		view, err := EditAppRules(source, AppRuleEdit{Action: "add", Packages: []string{"android", "com.example.app"}})
		if err != nil || len(view.Apps) != 2 || !strings.Contains(view.PayloadYAML, "  - 'PROCESS-NAME,android'") {
			t.Fatal(view, err)
		}
		for _, comment := range []string{"# root", "# header", "# empty", "# flow"} {
			if strings.Contains(source, comment) && !strings.Contains(view.PayloadYAML, comment) {
				t.Fatalf("lost %s", comment)
			}
		}
		for len(view.Apps) > 0 {
			previous := view.PayloadYAML
			view, err = EditAppRules(view.PayloadYAML, AppRuleEdit{Action: "delete", Index: 0})
			if err != nil {
				t.Fatalf("deleting from %q: %v", previous, err)
			}
		}
		if view.Apps == nil || view.OtherRuleCount != 0 {
			t.Fatal(view)
		}
	}
	for _, value := range []string{"", " com.example.app", "com.example.app ", "org.example:service", "/data/app/a.apk", "a,REJECT", "a\nb", "a..b", strings.Repeat("a", 256)} {
		if _, err := EditAppRules("payload:\n", AppRuleEdit{Action: "add", Packages: []string{"com.valid", value}}); err == nil {
			t.Fatalf("accepted %q", value)
		}
	}
	for _, source := range []string{"payload: null", "payload: ~", "payload:\n  -", "payload: [example.com]", "payload: [*alias]", "payload: []\n---\npayload: []"} {
		if _, err := EditAppRules(source, AppRuleEdit{Action: "view"}); err == nil {
			t.Fatalf("accepted %q", source)
		}
	}
	for _, edit := range []AppRuleEdit{{Action: "delete", Index: 0}, {Action: "move", Index: 0, Step: 1}, {Action: "move", Index: 1, Step: 1}, {Action: "move", Index: 1, Step: 2}, {Action: "bad"}} {
		if _, err := EditAppRules("payload: ['DOMAIN,x', 'PROCESS-NAME,com.app']", edit); err == nil {
			t.Fatalf("unsafe operation: %+v", edit)
		}
	}
}

func TestAppListPreferenceSurvivesResourceGraphWithoutChangingRuntimeOrDuplicateHandling(t *testing.T) {
	var runtime string
	for _, mode := range []string{"", "app", "yaml"} {
		plan, state := localMatchFixture()
		rule, err := NormalizeRuleSet(RuleSet{ID: ruleSetID, Name: "Apps", SourceType: "inline", Behavior: "classical", EditorMode: mode, PayloadYAML: "payload: ['PROCESS-NAME,com.app', 'DOMAIN,example.com']\n"})
		if err != nil {
			t.Fatal(err)
		}
		state.RuleSets = []RuleSet{rule}
		groups, rules, err := ExportGraph(plan, state)
		if err != nil || rules[0].EditorMode != mode {
			t.Fatal(rules, err)
		}
		imported, err := PrepareImport(State{}, plan, groups, rules)
		if err != nil || imported.State.RuleSets[0].EditorMode != mode {
			t.Fatal(imported, err)
		}
		state.StrategyGroups[1].RuleOutput = RuleOutputRuleSet
		base, err := PrepareSource([]byte(reconstructionSource), plan)
		if err != nil {
			t.Fatal(err)
		}
		output, err := Apply(base, state, plan)
		if err != nil || strings.Contains(string(output), "editorMode") || strings.Contains(string(output), "behavior: app") || !strings.Contains(string(output), "behavior: classical") {
			t.Fatalf("wrong runtime: %s %v", output, err)
		}
		if runtime != "" && string(output) != runtime {
			t.Fatal("display preference changed runtime")
		}
		runtime = string(output)
		for _, modify := range []func(*RuleSet){func(r *RuleSet) { r.SourceType = "http" }, func(r *RuleSet) { r.Behavior = "domain" }, func(r *RuleSet) { r.EditorMode = "unknown" }} {
			copy := rule
			copy.EditorMode = "yaml"
			modify(&copy)
			if _, err := NormalizeRuleSet(copy); err == nil {
				t.Fatalf("invalid app metadata accepted: %+v", copy)
			}
		}
		updated, _, err := PrepareEntry(RuleSetEntryInput{RuleSetID: ruleSetID, MatchType: "processName", Value: "com.app", ExpectedRevision: state.Revision}, state)
		if err != nil || len(updated.Payload) != 2 || updated.EditorMode != mode {
			t.Fatal(updated, err)
		}
	}
}
