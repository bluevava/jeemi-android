package mobile

import (
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
	"testing"
)

func builtinLabels() string {
	m := map[string]string{}
	for _, key := range strings.Fields("general apps jeemi ai im video domestic appDirect domesticRules appDirectRules github apple aiDomains google telegramDomains telegramIP discord whatsapp line netflix spotify twitter instagram youtube") {
		m[key] = key
	}
	b, _ := json.Marshal(m)
	return string(b)
}

func TestBuiltinCopiesAreIndependentAndNeverOverwrite(t *testing.T) {
	existing := []resourceInput{{ID: strings.Repeat("a", 32), Name: "🚀 jeemi", Kind: "SCRIPT", Content: "function main(config) { return config; }"}}
	old, _ := json.Marshal(existing)
	for _, kind := range []string{"general", "apps", "general"} {
		raw, err := PrepareBuiltinRules(kind, string(old), builtinLabels())
		if err != nil {
			t.Fatal(err)
		}
		var result struct {
			Resources []resourceInput
			ConfigID  string
		}
		if json.Unmarshal([]byte(raw), &result) != nil {
			t.Fatal("invalid result")
		}
		if len(result.Resources) != 21 || result.Resources[20].ID != result.ConfigID {
			t.Fatal("incomplete resource graph")
		}
		for _, r := range result.Resources {
			for _, previous := range existing {
				if r.ID == previous.ID || r.Name == previous.Name {
					t.Fatal("copy must not overwrite or alias existing resources")
				}
			}
		}
		existing = append(existing, result.Resources...)
		old, _ = json.Marshal(existing)
		if err := ValidateResourceLibrary(string(old)); err != nil {
			t.Fatal(err)
		}
	}
	if existing[0].Content != "function main(config) { return config; }" {
		t.Fatal("existing content changed")
	}
}

func TestBuiltinCapacityAndInvalidRequests(t *testing.T) {
	for _, count := range []int{79, 80} {
		existing := []resourceInput{}
		for i := 0; i < count; i++ {
			existing = append(existing, resourceInput{ID: fmt.Sprintf("%032x", i+1), Name: fmt.Sprintf("script-%d", i), Kind: "SCRIPT", Content: "function main(config) { return config; }"})
		}
		old, _ := json.Marshal(existing)
		result, err := PrepareBuiltinRules("general", string(old), builtinLabels())
		if count == 79 && err != nil {
			t.Fatal(err)
		}
		if count == 80 && (err == nil || err.Error() != "resource_limit" || result != "") {
			t.Fatal("capacity failure must be atomic")
		}
	}
	for _, kind := range []string{"", "windows", "process"} {
		if _, err := PrepareBuiltinRules(kind, "[]", builtinLabels()); err == nil {
			t.Fatal("unsupported preset accepted")
		}
	}
}

func TestBuiltinEqualTranslatedLabelsKeepSeparateRulesAndReferences(t *testing.T) {
	var labels map[string]string
	_ = json.Unmarshal([]byte(builtinLabels()), &labels)
	labels["github"], labels["apple"] = "Sites", "Sites"
	raw, err := PrepareBuiltinRules("general", "[]", jsonText(t, labels))
	if err != nil {
		t.Fatal(err)
	}
	var result struct{ Resources []resourceInput }
	if err = json.Unmarshal([]byte(raw), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Resources) != 21 || result.Resources[0].ID == result.Resources[1].ID ||
		result.Resources[0].Name != "Sites" || result.Resources[1].Name != "Sites (2)" {
		t.Fatal("translated labels must not alias resource identities")
	}
	if err := ValidateResourceLibrary(jsonText(t, result.Resources)); err != nil {
		t.Fatal(err)
	}
}

func TestRuleEmojiSurvivesNormalizationAndCatalogIsValid(t *testing.T) {
	rule := resourceInput{ID: strings.Repeat("a", 32), Name: "Rules", Kind: "RULES", FormatVersion: 2, Content: `{"sourceType":"inline","behavior":"classical","emoji":"📷","payloadYaml":"payload:\n  - DOMAIN,example.invalid\n"}`}
	p, err := prepareTyped(rule)
	if err != nil {
		t.Fatal(err)
	}
	var content map[string]any
	_ = json.Unmarshal([]byte(p.Content), &content)
	if content["emoji"] != "📷" {
		t.Fatal("emoji lost")
	}
	rule.Content = strings.Replace(rule.Content, `"emoji":"📷"`, `"emoji":"invalid"`, 1)
	if _, err = prepareTyped(rule); err == nil {
		t.Fatal("invalid icon accepted")
	}
	var choices []string
	_ = json.Unmarshal([]byte(ResourceEmojiChoices()), &choices)
	if len(choices) < 80 || !reflect.DeepEqual(choices, uniqueStrings(choices)) {
		t.Fatal("invalid emoji catalog")
	}
}

func uniqueStrings(values []string) []string {
	seen := map[string]bool{}
	result := []string{}
	for _, v := range values {
		if !seen[v] {
			seen[v] = true
			result = append(result, v)
		}
	}
	return result
}
