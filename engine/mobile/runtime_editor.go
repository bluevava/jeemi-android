package mobile

import (
	"encoding/json"
	"fmt"
	"sort"

	"jeemi-android/engine/internal/runtimeconfig"
)

// The Android editor uses the same bounded YAML parser as the desktop editor.
// Parser messages may contain source values, so only safe locations cross JNI.
func ValidateRuntimeFragment(field, contents string) string {
	result := runtimeconfig.ValidateYAMLFragment(runtimeconfig.YAMLFragmentInput{Field: field, Contents: contents})
	if result.Issue != nil {
		result.Issue.Message = "runtime_yaml_invalid"
		result.Field = ""
	}
	data, _ := json.Marshal(result)
	return string(data)
}

var runtimeDraftFields = map[string]struct {
	key  string
	list bool
}{
	"dns.nameserver":                     {"dnsNameservers", true},
	"dns.fake-ip-filter":                 {"dnsFakeIpFilter", true},
	"dns.proxy-server-nameserver":        {"dnsProxyServerNameservers", true},
	"dns.nameserver-policy":              {"dnsNameserverPolicyYaml", false},
	"dns.proxy-server-nameserver-policy": {"dnsProxyServerNameserverPolicyYaml", false},
	"hosts":                              {"hostsYaml", false},
	"rules.lan-bypass":                   {"lanBypassRules", true},
	"tun.route-exclude-address":          {"tunRouteExcludeAddress", true},
}

// ApplyRuntimeDrafts is a pure transaction. Even disabled edited resources are
// validated; an invalid draft can never overwrite the last saved preferences.
func ApplyRuntimeDrafts(raw, drafts string) (string, error) {
	var preferences map[string]any
	var edits map[string]string
	if len(raw) > 256<<10 || len(drafts) > 1<<20 || json.Unmarshal([]byte(raw), &preferences) != nil ||
		preferences == nil || json.Unmarshal([]byte(drafts), &edits) != nil || len(edits) > len(runtimeDraftFields) {
		return "", fmt.Errorf("invalid_runtime_preferences")
	}
	keys := make([]string, 0, len(edits))
	for field := range edits {
		keys = append(keys, field)
	}
	sort.Strings(keys)
	for _, field := range keys {
		definition, ok := runtimeDraftFields[field]
		if !ok {
			return "", fmt.Errorf("invalid_runtime_field")
		}
		result := runtimeconfig.ValidateYAMLFragment(runtimeconfig.YAMLFragmentInput{Field: field, Contents: edits[field]})
		if !result.Valid {
			data, _ := json.Marshal(map[string]any{"issue": map[string]any{"field": field, "line": result.Issue.Line, "column": result.Issue.Column}})
			return string(data), nil
		}
		if definition.list {
			preferences[definition.key] = result.Values
		} else {
			preferences[definition.key] = result.NormalizedYAML
		}
	}
	data, _ := json.Marshal(preferences)
	normalized, err := NormalizeRuntimePreferences(string(data))
	if err != nil {
		return "", err
	}
	result, _ := json.Marshal(map[string]any{"preferences": json.RawMessage(normalized)})
	return string(result), nil
}
