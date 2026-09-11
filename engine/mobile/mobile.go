// Author: Bluevava
// Open-source repository: https://github.com/bluevava/jeemi-android

// Package mobile is the narrow, offline business API used by Android.
// It does not embed mihomo or establish a VPN.
package mobile

import (
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"gopkg.in/yaml.v3"
	"jeemi-android/engine/internal/config/compose"
	"jeemi-android/engine/internal/config/document"
	"jeemi-android/engine/internal/subscriptionformat"
)

const maxBytes = 4 << 20

// Version describes the bridge schema, not the version of a running VPN core.
func Version() string { return "jeemi-business/1" }

func SubscriptionParserVersion() int { return subscriptionformat.Version }

// The packaged jeemi.2 core is based on this official protocol implementation.
const bundledProtocolVersion = "v1.19.30"

type nodeSummary struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

type normalized struct {
	YAML   string                    `json:"yaml"`
	Report subscriptionformat.Report `json:"report"`
	Nodes  []nodeSummary             `json:"nodes"`
}

// NormalizeSubscription returns normalized YAML and non-secret node summaries.
// Errors never include the raw subscription, URL, password, or YAML scalar.
func NormalizeSubscription(raw string) (string, error) {
	result, err := subscriptionformat.Normalize([]byte(raw))
	if err != nil {
		return "", err
	}
	if err := result.CheckCore(bundledProtocolVersion); err != nil {
		return "", err
	}
	ast, err := document.Parse(result.Contents)
	if err != nil {
		return "", fmt.Errorf("invalid_normalized_document")
	}
	nodes := make([]nodeSummary, 0)
	root := document.Root(ast)
	for i := 0; i < len(root.Content); i += 2 {
		if root.Content[i].Value != "proxies" {
			continue
		}
		proxies := root.Content[i+1]
		if proxies.Kind != yaml.SequenceNode {
			return "", fmt.Errorf("invalid_proxies")
		}
		for _, proxy := range proxies.Content {
			if proxy.Kind != yaml.MappingNode {
				return "", fmt.Errorf("invalid_proxy")
			}
			item := nodeSummary{}
			for j := 0; j < len(proxy.Content); j += 2 {
				switch proxy.Content[j].Value {
				case "name":
					item.Name = proxy.Content[j+1].Value
				case "type":
					item.Type = proxy.Content[j+1].Value
				}
			}
			nodes = append(nodes, item)
		}
	}
	result.Report.ProxyCount = len(nodes)
	encoded, err := json.Marshal(normalized{string(result.Contents), result.Report, nodes})
	return string(encoded), err
}

// ComposeConfiguration applies only the explicitly enabled JSON Pointer rules.
// This checks document/composition semantics; mihomo runtime validation is separate.
func ComposeConfiguration(subscription, overlay, rulesJSON string) (string, error) {
	if len(subscription) > maxBytes || len(overlay) > maxBytes || len(rulesJSON) > 256<<10 {
		return "", fmt.Errorf("size_limit")
	}
	var rules []compose.Rule
	decoder := json.NewDecoder(strings.NewReader(rulesJSON))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&rules); err != nil {
		return "", fmt.Errorf("invalid_composition_plan")
	}
	// A second JSON value must not silently disappear at the mobile boundary.
	var extra any
	if decoder.Decode(&extra) != io.EOF || strings.TrimSpace(rulesJSON) == "null" {
		return "", fmt.Errorf("invalid_composition_plan")
	}
	result, err := compose.Compose([]byte(subscription), []byte(overlay), rules)
	if err != nil {
		return "", fmt.Errorf("composition_failed")
	}
	return result.YAML, nil
}
