package mobile

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"slices"
	"strings"
	"time"

	"jeemi-android/engine/internal/chainproxy"
	"jeemi-android/engine/internal/subscriptionformat"
)

const maxChainLibraryBytes = 16 << 20

func chainError(code string) error { return &chainproxy.Error{Code: code} }

func readChainLibrary(raw string) (chainproxy.Library, error) {
	if len(raw) > maxChainLibraryBytes {
		return chainproxy.Library{}, chainError("input_limit")
	}
	if strings.TrimSpace(raw) == "" {
		return chainproxy.Library{Version: chainproxy.Version, Groups: []chainproxy.Group{}}, nil
	}
	var library chainproxy.Library
	if len(raw) > maxChainLibraryBytes || decodeChainJSON(raw, &library) != nil {
		return library, chainError("invalid_library")
	}
	return library, chainproxy.ValidateLibrary(library)
}

func decodeChainJSON(raw string, target any) error {
	d := json.NewDecoder(strings.NewReader(raw))
	d.DisallowUnknownFields()
	if err := d.Decode(target); err != nil {
		return err
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return chainError("invalid_library")
	}
	return nil
}

// Android commits this private document together with subscription associations
// in one AtomicFile. No Go filesystem, network or desktop store is used.
func NormalizeChainLibrary(raw string) (string, error) {
	library, err := readChainLibrary(raw)
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(library)
	if len(data) > maxChainLibraryBytes {
		return "", chainError("input_limit")
	}
	return string(data), err
}

func ChainProxyState(raw string) (string, error) {
	library, err := readChainLibrary(raw)
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(chainproxy.List(library))
	return string(data), err
}

// Full URLs and credentials are returned only for an explicitly opened editor.
func ChainProxyItem(raw, groupID, itemID, kind string) (string, error) {
	library, err := readChainLibrary(raw)
	if err != nil {
		return "", err
	}
	group, err := chainproxy.FindGroup(&library, groupID)
	if err != nil {
		return "", err
	}
	if kind == "source" {
		for _, source := range group.Sources {
			if source.ID == itemID {
				data, e := json.Marshal(chainproxy.SourceInput{Revision: library.Revision, GroupID: groupID, ID: itemID, URL: source.URL, Filter: source.Filter})
				return string(data), e
			}
		}
		return "", chainError("source_missing")
	}
	if kind == "node" {
		for _, node := range group.LandingNodes() {
			if node.ID == itemID {
				return chainproxy.EditableNode(node), nil
			}
		}
	}
	return "", chainError("node_missing")
}

type chainMutation struct {
	Action         string   `json:"action"`
	Revision       int      `json:"revision"`
	GroupID        string   `json:"groupId"`
	ID             string   `json:"id"`
	Name           string   `json:"name"`
	Kind           string   `json:"kind"`
	SelectorFilter string   `json:"selectorFilter"`
	NodeFilter     string   `json:"nodeFilter"`
	Contents       string   `json:"contents"`
	URL            string   `json:"url"`
	Filter         string   `json:"filter"`
	UsedGroups     []string `json:"usedGroups"`
}

// The caller must validate affected subscriptions before atomically publishing
// the returned library. A stale editor/fetch result cannot overwrite newer data.
func MutateChainLibrary(raw, request string) (string, error) {
	library, err := readChainLibrary(raw)
	if err != nil {
		return "", err
	}
	var input chainMutation
	if len(request) > 8<<20 || decodeChainJSON(request, &input) != nil {
		return "", chainError("invalid_group")
	}
	if input.Revision != library.Revision {
		return "", chainError("revision_conflict")
	}
	var report *subscriptionformat.Report
	if input.Action == "save_group" {
		group := chainproxy.Group{ID: input.GroupID, Name: strings.TrimSpace(input.Name), Kind: input.Kind,
			SelectorFilter: strings.TrimSpace(input.SelectorFilter), NodeFilter: strings.TrimSpace(input.NodeFilter),
			Nodes: []chainproxy.Node{}, Sources: []chainproxy.Source{}}
		if group.ID == "" {
			group.ID, err = chainproxy.NewID()
			if err != nil {
				return "", err
			}
			library.Groups = append(library.Groups, group)
		} else {
			previous, e := chainproxy.FindGroup(&library, group.ID)
			if e != nil {
				return "", e
			}
			if previous.Kind != group.Kind {
				return "", chainError("invalid_group")
			}
			group.Nodes, group.Sources = previous.Nodes, previous.Sources
			*previous = group
		}
	} else {
		group, e := chainproxy.FindGroup(&library, input.GroupID)
		if e != nil {
			return "", e
		}
		switch input.Action {
		case "import_nodes":
			if group.Kind != "manual" {
				return "", chainError("invalid_group")
			}
			parsed, e := chainproxy.Parse([]byte(input.Contents), "")
			if e != nil {
				return "", e
			}
			if len(parsed.Nodes) == 0 || input.ID != "" && len(parsed.Nodes) != 1 {
				return "", chainError("single_node_required")
			}
			report = &parsed.Report
			if input.ID != "" {
				index := slices.IndexFunc(group.Nodes, func(n chainproxy.Node) bool { return n.ID == input.ID })
				if index < 0 {
					return "", chainError("node_missing")
				}
				parsed.Nodes[0].ID = input.ID
				group.Nodes[index] = parsed.Nodes[0]
			} else {
				for _, node := range parsed.Nodes {
					node.ID, err = chainproxy.NewID()
					if err != nil {
						return "", err
					}
					group.Nodes = append(group.Nodes, node)
				}
			}
			names := map[string]bool{}
			for _, node := range group.Nodes {
				if names[node.Name] {
					return "", chainError("duplicate_node_name")
				}
				names[node.Name] = true
			}
		case "save_source":
			if group.Kind != "subscription" {
				return "", chainError("invalid_group")
			}
			address, e := chainproxy.NormalizeURL(input.URL)
			if e != nil {
				return "", e
			}
			parsed, e := chainproxy.Parse([]byte(input.Contents), input.Filter)
			if e != nil {
				return "", e
			}
			report = &parsed.Report
			id := input.ID
			if id == "" {
				id, err = chainproxy.NewID()
				if err != nil {
					return "", err
				}
			}
			for i := range parsed.Nodes {
				digest := sha256.Sum256([]byte(id + "\x00" + parsed.Nodes[i].Name))
				parsed.Nodes[i].ID = hex.EncodeToString(digest[:16])
			}
			source := chainproxy.Source{ID: id, URL: address, Filter: strings.TrimSpace(input.Filter),
				UpdatedAt: time.Now().UTC().Format(time.RFC3339Nano), Nodes: parsed.Nodes, Report: parsed.Report}
			if input.ID == "" {
				group.Sources = append(group.Sources, source)
			} else {
				index := slices.IndexFunc(group.Sources, func(s chainproxy.Source) bool { return s.ID == input.ID })
				if index < 0 {
					return "", chainError("source_missing")
				}
				group.Sources[index] = source
			}
		case "delete_group":
			if slices.Contains(input.UsedGroups, group.ID) {
				return "", chainError("group_in_use")
			}
			library.Groups = slices.DeleteFunc(library.Groups, func(g chainproxy.Group) bool { return g.ID == input.GroupID })
		case "delete_node":
			if group.Kind != "manual" {
				return "", chainError("invalid_group")
			}
			index := slices.IndexFunc(group.Nodes, func(n chainproxy.Node) bool { return n.ID == input.ID })
			if index < 0 {
				return "", chainError("node_missing")
			}
			group.Nodes = slices.Delete(group.Nodes, index, index+1)
		case "delete_source":
			index := slices.IndexFunc(group.Sources, func(s chainproxy.Source) bool { return s.ID == input.ID })
			if index < 0 {
				return "", chainError("source_missing")
			}
			group.Sources = slices.Delete(group.Sources, index, index+1)
		default:
			return "", chainError("invalid_group")
		}
	}
	library.Revision++
	if err := chainproxy.ValidateLibrary(library); err != nil {
		return "", err
	}
	private, err := json.Marshal(library)
	if err != nil || len(private) > maxChainLibraryBytes {
		return "", chainError("input_limit")
	}
	output, err := json.Marshal(struct {
		Library json.RawMessage            `json:"library"`
		State   chainproxy.State           `json:"state"`
		Report  *subscriptionformat.Report `json:"report,omitempty"`
	}{private, chainproxy.List(library), report})
	return string(output), err
}
