package mobile

import (
	"encoding/json"
	"fmt"
	"jeemi-android/engine/internal/config/resources"
	"regexp"
	"strings"
)

var androidPackageName = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$`)

// PrepareConnectionRule returns a reviewable resource and a literal preview.
// Android commits it atomically with the affected subscription candidates.
func PrepareConnectionRule(library, request, newID string) (string, error) {
	var inputs []resourceInput
	var input resources.RuleSetEntryInput
	if len(library) > 32<<20 || len(request) > 16384 || json.Unmarshal([]byte(library), &inputs) != nil || json.Unmarshal([]byte(request), &input) != nil {
		return "", fmt.Errorf("invalid_rule_entry")
	}
	if input.MatchType != "domain" && input.MatchType != "ip" && input.MatchType != "processName" {
		return "", fmt.Errorf("invalid_rule_entry")
	}
	if input.MatchType == "processName" {
		input.Value = strings.TrimSpace(input.Value)
		if !androidPackageName.MatchString(input.Value) {
			return "", fmt.Errorf("invalid_rule_entry")
		}
	}
	state, err := resourceState(inputs)
	if err != nil {
		return "", err
	}
	input.ExpectedRevision = state.Revision
	candidate, preview, err := resources.PrepareEntry(input, state)
	if err != nil {
		return "", err
	}
	if candidate.ID == "" {
		if !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(newID) {
			return "", fmt.Errorf("invalid_id")
		}
		candidate.ID = newID
	}
	raw, _ := json.Marshal(candidate)
	resource := resourceInput{ID: candidate.ID, Name: candidate.Name, Description: candidate.Description, Kind: "RULES", Content: string(raw), Strategy: "auto", FormatVersion: 2}
	resource, err = prepareTyped(resource)
	if err != nil {
		return "", err
	}
	result, _ := json.Marshal(struct {
		Resource resourceInput                 `json:"resource"`
		Preview  resources.RuleSetEntryPreview `json:"preview"`
	}{resource, preview})
	return string(result), nil
}
