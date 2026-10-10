package resources

import (
	"bytes"
	"fmt"
	"regexp"
	"strings"

	"gopkg.in/yaml.v3"
)

var androidPackagePattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$`)

func IsAndroidPackageName(value string) bool {
	return len(value) >= 1 && len(value) <= 255 && androidPackagePattern.MatchString(value)
}

type AppRuleEdit struct {
	Action   string   `json:"action"`
	Index    int      `json:"index"`
	Step     int      `json:"step"`
	Packages []string `json:"packages"`
}

type AppRuleRow struct {
	Index       int    `json:"index"`
	PackageName string `json:"packageName,omitempty"`
	Rule        string `json:"rule,omitempty"`
}

type AppRuleView struct {
	PayloadYAML    string       `json:"payloadYaml"`
	Apps           []AppRuleRow `json:"apps"`
	Rows           []AppRuleRow `json:"rows"`
	OtherRuleCount int          `json:"otherRuleCount"`
}

// Only exact package matches get app metadata. Other classical rules are shown
// verbatim in the same list and remain editable in the complete YAML editor.
func appRulePackage(rule string) string {
	prefix, value, found := strings.Cut(strings.TrimSpace(rule), ",")
	if found && strings.ToUpper(strings.TrimSpace(prefix)) == "PROCESS-NAME" && IsAndroidPackageName(value) {
		return value
	}
	return ""
}

// EditAppRules changes the YAML AST, not a second list of rules. A move swaps
// an app with the adjacent YAML entry, matching the visible list. Reading or
// adding an existing package leaves the source text byte-for-byte intact.
func EditAppRules(source string, edit AppRuleEdit) (AppRuleView, error) {
	fail := func() (AppRuleView, error) { return AppRuleView{}, fmt.Errorf("invalid_app_rules") }
	_, rules, err := normalizeRuleSetPayloadYAML(source)
	if err != nil || validateRuleSetPayloadRules("classical", rules) != nil {
		return fail()
	}
	var doc yaml.Node
	if yaml.Unmarshal([]byte(source), &doc) != nil {
		return fail()
	}
	payload := doc.Content[0].Content[1]
	apps := make([]AppRuleRow, 0)
	rows := make([]AppRuleRow, 0, len(rules))
	seen := make(map[string]bool)
	for index, rule := range rules {
		name := appRulePackage(rule)
		rows = append(rows, AppRuleRow{Index: index, PackageName: name, Rule: rule})
		if name != "" {
			apps = append(apps, AppRuleRow{Index: index, PackageName: name})
			seen[name] = true
		}
	}
	changed := false
	switch edit.Action {
	case "view":
	case "add":
		if len(edit.Packages) == 0 || len(edit.Packages) > maxPayloadRules {
			return fail()
		}
		for _, name := range edit.Packages {
			if !IsAndroidPackageName(name) {
				return fail()
			}
			if seen[name] {
				continue
			}
			seen[name] = true
			payload.Content = append(payload.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Style: yaml.SingleQuotedStyle, Value: "PROCESS-NAME," + name})
			changed = true
		}
	case "delete", "move":
		position := -1
		for i, row := range apps {
			if row.Index == edit.Index {
				position = i
				break
			}
		}
		if position < 0 {
			return fail()
		}
		if edit.Action == "delete" {
			payload.Content = append(payload.Content[:edit.Index], payload.Content[edit.Index+1:]...)
		} else {
			if (edit.Step != -1 && edit.Step != 1) || edit.Index+edit.Step < 0 || edit.Index+edit.Step >= len(rules) {
				return fail()
			}
			other := edit.Index + edit.Step
			payload.Content[edit.Index], payload.Content[other] = payload.Content[other], payload.Content[edit.Index]
		}
		changed = true
	default:
		return fail()
	}
	if changed {
		emptyHeader := implicitEmptyRuleSetPayload(payload)
		if (emptyHeader || payload.Style&yaml.FlowStyle != 0) && payload.LineComment != "" {
			payload.HeadComment = strings.TrimSpace(payload.HeadComment + "\n" + payload.LineComment)
			payload.LineComment = ""
		}
		payload.Kind, payload.Tag, payload.Style, payload.Value = yaml.SequenceNode, "!!seq", 0, ""
		if len(payload.Content) == 0 {
			// yaml.v3 cannot reliably place a mapping-key line comment before
			// an empty block sequence. Keep every header comment above payload.
			key := doc.Content[0].Content[0]
			key.HeadComment = strings.TrimSpace(strings.Join([]string{key.HeadComment, key.LineComment, payload.HeadComment, payload.LineComment, payload.FootComment}, "\n"))
			key.LineComment, payload.HeadComment, payload.LineComment, payload.FootComment = "", "", "", ""
			payload.Style = yaml.FlowStyle
		}
		var output bytes.Buffer
		encoder := yaml.NewEncoder(&output)
		encoder.SetIndent(2)
		if encoder.Encode(&doc) != nil || encoder.Close() != nil {
			return fail()
		}
		// Revalidate size/count and all mixed rules before giving the draft back.
		return EditAppRules(output.String(), AppRuleEdit{Action: "view"})
	}
	return AppRuleView{PayloadYAML: source, Apps: apps, Rows: rows, OtherRuleCount: len(rules) - len(apps)}, nil
}
