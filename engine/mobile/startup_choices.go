package mobile

import (
	"encoding/json"
	"fmt"
	"gopkg.in/yaml.v3"
	"jeemi-android/engine/internal/config/document"
	"slices"
)

// PrimeStartupSelections sets the first static member of manual selectors in
// the ephemeral session before TUN reads begin. Provider-backed selections are
// validated again by the controller once providers have loaded.
func PrimeStartupSelections(configuration, selections string) (string, error) {
	var choices map[string]string
	if len(configuration) > maxBytes || len(selections) > 256<<10 || json.Unmarshal([]byte(selections), &choices) != nil {
		return "", fmt.Errorf("invalid_selections")
	}
	doc, err := document.Parse([]byte(configuration))
	if err != nil {
		return "", err
	}
	root := document.Root(doc)
	if groups, ok, _ := document.Find(root, "/proxy-groups"); ok && groups.Kind == yaml.SequenceNode {
		for _, group := range groups.Content {
			name, hasName, _ := document.Find(group, "/name")
			kind, hasKind, _ := document.Find(group, "/type")
			if !hasName || !hasKind || kind.Value != "select" {
				continue
			}
			all, found, _ := document.Find(group, "/proxies")
			if !found || all.Kind != yaml.SequenceNode {
				continue
			}
			chosen := choices[name.Value]
			if chosen == "" {
				if value, ok, _ := document.Find(group, "/default-selected"); ok {
					chosen = value.Value
				}
			}
			index := slices.IndexFunc(all.Content, func(n *yaml.Node) bool { return n.Value == chosen })
			if index > 0 {
				node := all.Content[index]
				copy(all.Content[1:index+1], all.Content[:index])
				all.Content[0] = node
			}
		}
	}
	disabled, _ := document.ParseValue("false")
	if err = document.SetMappingPath(root, "/profile/store-selected", disabled); err != nil {
		return "", err
	}
	raw, err := document.Encode(doc)
	return string(raw), err
}
