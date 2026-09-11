// Package providercache preserves HTTP rule and proxy providers across isolated core
// sessions. Downloads, parsing and expiry remain the responsibility of mihomo.
package providercache

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"path"

	configdoc "jeemi-android/engine/internal/config/document"

	"gopkg.in/yaml.v3"
)

const sessionDirectory = "provider-cache"
const maxConfigurationBytes = 4 << 20

var errInvalid = errors.New("invalid rule-provider cache input")

type entry struct {
	key      string
	previous string
}

func rewrite(contents []byte) ([]byte, []entry, error) {
	if len(contents) > maxConfigurationBytes {
		return nil, nil, errInvalid
	}
	parsed, err := configdoc.Parse(contents)
	if err != nil {
		return nil, nil, errInvalid
	}
	// Expand bounded, validated aliases before changing per-provider paths.
	// Shared YAML anchors must not make two provider identities share a file.
	document := expandAliases(parsed)
	var entries []entry
	for _, collection := range []string{"rule-providers", "proxy-providers"} {
		providers := field(document.Content[0], collection)
		if providers == nil {
			continue
		}
		if providers.Kind != yaml.MappingNode {
			return nil, nil, errInvalid
		}
		for i := 0; i < len(providers.Content); i += 2 {
			name, provider := providers.Content[i], providers.Content[i+1]
			kind := field(provider, "type")
			if kind == nil || kind.Value != "http" {
				continue
			}
			var identity map[string]any
			if provider.Decode(&identity) != nil {
				return nil, nil, errInvalid
			}
			// A new generation/path or an interval edit does not change the resource.
			// Include name, URL, headers and all other options to isolate different
			// sources, credentials, formats and provider definitions.
			delete(identity, "path")
			delete(identity, "interval")
			if _, ok := identity["format"]; !ok && collection == "rule-providers" {
				identity["format"] = "yaml"
			}
			encoded, err := json.Marshal([]any{1, collection, name.Value, identity})
			if err != nil {
				return nil, nil, errInvalid
			}
			digest := sha256.Sum256(encoded)
			item := entry{key: hex.EncodeToString(digest[:])}
			location := field(provider, "path")
			if location != nil {
				item.previous = location.Value
				*location = yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: path.Join(sessionDirectory, item.key)}
			} else {
				provider.Content = append(provider.Content,
					&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: "path"},
					&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: path.Join(sessionDirectory, item.key)})
			}
			entries = append(entries, item)
		}
	}
	if len(entries) == 0 {
		return contents, nil, nil
	}
	result, err := configdoc.Encode(document)
	if err != nil || len(result) > maxConfigurationBytes {
		return nil, nil, errInvalid
	}
	return result, entries, nil
}

func expandAliases(node *yaml.Node) *yaml.Node {
	if node.Kind == yaml.AliasNode {
		return expandAliases(node.Alias)
	}
	result := *node
	result.Anchor, result.Alias, result.Content = "", nil, nil
	for _, child := range node.Content {
		result.Content = append(result.Content, expandAliases(child))
	}
	return &result
}

func field(node *yaml.Node, name string) *yaml.Node {
	if node == nil || node.Kind != yaml.MappingNode {
		return nil
	}
	for i := 0; i+1 < len(node.Content); i += 2 {
		if node.Content[i].Value == name {
			return node.Content[i+1]
		}
	}
	return nil
}
