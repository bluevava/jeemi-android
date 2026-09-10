package chainproxy

import (
	"crypto/rand"
	"encoding/hex"
	"jeemi-android/engine/internal/config/document"
	"net/url"
	"regexp"
	"strings"
	"time"
)

var idPattern = regexp.MustCompile(`^[a-f0-9]{32}$`)

func NewID() (string, error) {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", failure("identity_failed")
	}
	return hex.EncodeToString(value[:]), nil
}

func ValidateLibrary(library Library) error {
	if library.Version != Version || library.Revision < 0 || len(library.Groups) > 128 {
		return failure("invalid_library")
	}
	seen, names := map[string]bool{}, map[string]bool{}
	for _, group := range library.Groups {
		if !idPattern.MatchString(group.ID) || seen[group.ID] || names[group.Name] {
			return failure("duplicate_group")
		}
		seen[group.ID], names[group.Name] = true, true
		if err := ValidateGroup(group); err != nil {
			return err
		}
	}
	return nil
}

func ValidateGroup(g Group) error {
	if g.Name == "" || strings.TrimSpace(g.Name) != g.Name || !validText(g.Name, 160) || !validText(g.SelectorFilter, 2048) || !validText(g.NodeFilter, 2048) {
		return failure("invalid_group")
	}
	if g.Kind != "manual" && g.Kind != "subscription" {
		return failure("invalid_group")
	}
	if g.Kind == "manual" && len(g.Sources) > 0 || g.Kind == "subscription" && len(g.Nodes) > 0 || len(g.Sources) > 64 || len(g.LandingNodes()) > 4096 {
		return failure("input_limit")
	}
	seen := map[string]bool{}
	for _, node := range g.LandingNodes() {
		if !idPattern.MatchString(node.ID) || seen[node.ID] {
			return failure("invalid_nodes")
		}
		seen[node.ID] = true
		parsed, err := document.Parse([]byte(node.YAML))
		if err != nil {
			return failure("invalid_nodes")
		}
		root := document.Root(parsed)
		if err := validateNode(root); err != nil {
			return err
		}
		if node.Name != scalar(value(root, "name")) || node.Type != scalar(value(root, "type")) || value(root, "dialer-proxy") != nil {
			return failure("invalid_nodes")
		}
		for _, text := range []string{node.DNSYAML, node.HostsYAML} {
			if text != "" {
				if _, err := document.Parse([]byte(text)); err != nil {
					return failure("invalid_nodes")
				}
			}
		}
	}
	for _, source := range g.Sources {
		if !idPattern.MatchString(source.ID) || seen[source.ID] {
			return failure("invalid_source")
		}
		seen[source.ID] = true
		if _, err := NormalizeURL(source.URL); err != nil {
			return err
		}
		if !validText(source.Filter, 2048) {
			return failure("invalid_source")
		}
		if _, err := time.Parse(time.RFC3339Nano, source.UpdatedAt); err != nil {
			return failure("invalid_source")
		}
	}
	return nil
}

func NormalizeURL(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	parsed, err := url.Parse(raw)
	if err != nil || !validText(raw, 8192) || parsed.Hostname() == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Opaque != "" {
		return "", failure("invalid_source")
	}
	parsed.Fragment = ""
	return parsed.String(), nil
}

func List(library Library) State {
	state := State{Revision: library.Revision, Groups: []GroupSummary{}}
	for _, g := range library.Groups {
		summary := GroupSummary{ID: g.ID, Name: g.Name, Kind: g.Kind, SelectorFilter: g.SelectorFilter, NodeFilter: g.NodeFilter, Nodes: []NodeSummary{}, Sources: []SourceSummary{}}
		for _, node := range g.Nodes {
			summary.Nodes = append(summary.Nodes, NodeSummary{ID: node.ID, Name: node.Name, Type: node.Type})
		}
		for _, source := range g.Sources {
			u, _ := url.Parse(source.URL)
			summary.Sources = append(summary.Sources, SourceSummary{ID: source.ID, Label: u.Scheme + "://" + u.Host, Filter: source.Filter, UpdatedAt: source.UpdatedAt, NodeCount: len(source.Nodes), Report: source.Report})
			for _, node := range source.Nodes {
				summary.Nodes = append(summary.Nodes, NodeSummary{ID: node.ID, Name: node.Name, Type: node.Type, SourceID: source.ID})
			}
		}
		state.Groups = append(state.Groups, summary)
	}
	return state
}

func FindGroup(library *Library, id string) (*Group, error) {
	for i := range library.Groups {
		if library.Groups[i].ID == id {
			return &library.Groups[i], nil
		}
	}
	return nil, failure("group_missing")
}

func SelectedGroups(library Library, ids []string) ([]Group, error) {
	if len(ids) > 128 {
		return nil, failure("input_limit")
	}
	result := []Group{}
	seen := map[string]bool{}
	for _, id := range ids {
		if seen[id] {
			return nil, failure("invalid_group")
		}
		seen[id] = true
		group, err := FindGroup(&library, id)
		if err != nil {
			return nil, err
		}
		result = append(result, *group)
	}
	return result, nil
}
