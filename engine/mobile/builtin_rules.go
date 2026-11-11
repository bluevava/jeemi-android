package mobile

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"

	"jeemi-android/engine/internal/config/presets"
	"jeemi-android/engine/internal/config/resources"
	"jeemi-android/engine/internal/localconfig"
)

func ResourceEmojiChoices() string {
	encoded, _ := json.Marshal(resources.EmojiChoices())
	return string(encoded)
}

// PrepareBuiltinRules creates an independent resource graph without mutating
// the current library. Android validates candidates and commits it atomically.
func PrepareBuiltinRules(kind, libraryJSON, labelsJSON string) (string, error) {
	if len(labelsJSON) > 32<<10 {
		return "", fmt.Errorf("invalid_builtin_rules")
	}
	if err := ValidateResourceLibrary(libraryJSON); err != nil {
		return "", err
	}
	var labels map[string]string
	if json.Unmarshal([]byte(labelsJSON), &labels) != nil {
		return "", fmt.Errorf("invalid_builtin_rules")
	}
	p, err := presets.Load(kind, labels)
	if err != nil {
		return "", fmt.Errorf("invalid_builtin_rules")
	}
	var existing []resourceInput
	_ = json.Unmarshal([]byte(libraryJSON), &existing)
	if len(existing)+1+len(p.Groups)+len(p.Rules) > 100 {
		return "", fmt.Errorf("resource_limit")
	}
	usedIDs, names := map[string]bool{}, map[string]bool{}
	for _, r := range existing {
		usedIDs[r.ID], names[r.Name] = true, true
		if r.Kind == "GROUPS" && r.FormatVersion >= 2 {
			var g resources.StrategyGroup
			_ = json.Unmarshal([]byte(r.Content), &g)
			g.Name = r.Name
			names[resources.SelectorDisplayName(g)] = true
			if g.AutoSelect {
				names[resources.SelectorDisplayName(g)+" Auto"] = true
			}
		}
	}
	newID := func() (string, error) {
		for {
			var b [16]byte
			if _, err := rand.Read(b[:]); err != nil {
				return "", fmt.Errorf("invalid_builtin_rules")
			}
			value := hex.EncodeToString(b[:])
			if !usedIDs[value] {
				usedIDs[value] = true
				return value, nil
			}
		}
	}
	uniqueName := func(base, emoji string, auto bool) string {
		name := base
		for n := 2; names[name] || names[strings.TrimSpace(emoji+" "+name)] || (auto && names[strings.TrimSpace(emoji+" "+name)+" Auto"]); n++ {
			name = fmt.Sprintf("%s (%d)", base, n)
		}
		names[name], names[strings.TrimSpace(emoji+" "+name)] = true, true
		if auto {
			names[strings.TrimSpace(emoji+" "+name)+" Auto"] = true
		}
		return name
	}
	ids := map[string]string{}
	for _, g := range p.Groups {
		ids[g.ID], err = newID()
		if err != nil {
			return "", err
		}
	}
	for _, r := range p.Rules {
		ids[r.ID], err = newID()
		if err != nil {
			return "", err
		}
	}
	added := []resourceInput{}
	appendResource := func(id, name, kind string, value any) error {
		content, err := json.Marshal(value)
		if err != nil {
			return err
		}
		input, err := prepareTyped(resourceInput{ID: id, Name: name, Kind: kind, Content: string(content), Strategy: "auto", FormatVersion: 2})
		if err == nil {
			added = append(added, input)
		}
		return err
	}
	for _, r := range p.Rules {
		r.ID, r.Name = ids[r.ID], uniqueName(r.Name, "", false)
		if err = appendResource(r.ID, r.Name, "RULES", r); err != nil {
			return "", err
		}
	}
	for _, g := range p.Groups {
		g.ID, g.Name = ids[g.ID], uniqueName(g.Name, g.Emoji, g.AutoSelect)
		for i := range g.RuleSetReferences {
			g.RuleSetReferences[i].RuleSetID = ids[g.RuleSetReferences[i].RuleSetID]
		}
		if err = appendResource(g.ID, g.Name, "GROUPS", g); err != nil {
			return "", err
		}
	}
	for i := range p.Plan.StrategyGroupIDs {
		p.Plan.StrategyGroupIDs[i] = ids[p.Plan.StrategyGroupIDs[i]]
	}
	p.Plan.DefaultProxySelectorID = ids[p.Plan.DefaultProxySelectorID]
	p.Plan.Match.SelectorID = ids[p.Plan.Match.SelectorID]
	configID, err := newID()
	if err != nil {
		return "", err
	}
	if err = appendResource(configID, uniqueName(p.Name, "", false), "CONFIG", localconfig.Config{ResourcePlan: p.Plan}); err != nil {
		return "", err
	}
	combined, _ := json.Marshal(append(existing, added...))
	if err = ValidateResourceLibrary(string(combined)); err != nil {
		return "", err
	}
	encoded, err := json.Marshal(struct {
		Resources []resourceInput `json:"resources"`
		ConfigID  string          `json:"configId"`
	}{added, configID})
	return string(encoded), err
}
