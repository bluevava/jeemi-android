package mobile

import (
	"encoding/json"
	"jeemi-android/engine/internal/config/resources"
	"strings"
	"testing"
)

func TestAppDraftBridgeAndConnectionRulesDefaultToClassical(t *testing.T) {
	raw, err := EditAppRuleSet("payload:\n", `{"action":"add","packages":["com.example.reader"]}`)
	if err != nil || !strings.Contains(raw, `"packageName":"com.example.reader"`) || !strings.Contains(raw, `"rule":"PROCESS-NAME,com.example.reader"`) {
		t.Fatal(raw, err)
	}
	if _, err := EditAppRuleSet("payload: null", `{"action":"view"}`); err == nil {
		t.Fatal("null accepted")
	}
	prepared, err := PrepareConnectionRule(`[]`, `{"name":"Apps","matchType":"processName","value":"com.example.reader"}`, strings.Repeat("a", 32))
	if err != nil {
		t.Fatal(err)
	}
	var result struct{ Resource resourceInput }
	json.Unmarshal([]byte(prepared), &result)
	var rules resources.RuleSet
	json.Unmarshal([]byte(result.Resource.Content), &rules)
	if rules.Behavior != "classical" || rules.EditorMode != "" || len(rules.Payload) != 1 {
		t.Fatal(rules)
	}
	library, _ := json.Marshal([]resourceInput{result.Resource})
	prepared, err = PrepareConnectionRule(string(library), `{"ruleSetId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","matchType":"processName","value":"com.example.reader"}`, "unused")
	if err != nil {
		t.Fatal(err)
	}
	json.Unmarshal([]byte(prepared), &result)
	json.Unmarshal([]byte(result.Resource.Content), &rules)
	if len(rules.Payload) != 1 || rules.EditorMode != "" {
		t.Fatal("duplicate or mode lost", rules)
	}
}
