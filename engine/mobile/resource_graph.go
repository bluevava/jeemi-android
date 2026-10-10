package mobile

import (
	"encoding/json"
	"fmt"

	"jeemi-android/engine/internal/config/resources"
	"jeemi-android/engine/internal/localconfig"
)

type graphSubscription struct {
	ID            string   `json:"id"`
	Name          string   `json:"name"`
	HandlerID     string   `json:"handlerId"`
	ResourceIDs   []string `json:"resourceIds"`
	ChainGroupIDs []string `json:"chainGroupIds"`
}
type graphChain struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}
type resourceGraphNode struct {
	Key           string   `json:"key"`
	ID            string   `json:"id"`
	Kind          string   `json:"kind"`
	Name          string   `json:"name"`
	Missing       bool     `json:"missing"`
	Subscriptions []string `json:"subscriptions"`
}
type resourceGraphEdge struct {
	From    string `json:"from"`
	To      string `json:"to"`
	Role    string `json:"role"`
	Enabled bool   `json:"enabled"`
}
type resourceGraph struct {
	Nodes []resourceGraphNode `json:"nodes"`
	Edges []resourceGraphEdge `json:"edges"`
}

// InspectResourceGraph reads stable IDs, never arbitrary strings in source code,
// YAML, URLs or descriptions. It is also usable on detached editor drafts:
// missing references and disabled relationships remain visible for repair.
// Names and IDs are the only source data returned; no documents or credentials.
func InspectResourceGraph(resourcesJSON, subscriptionsJSON, chainsJSON string) (string, error) {
	var inputs []resourceInput
	var subscriptions []graphSubscription
	var chains []graphChain
	if len(resourcesJSON) > 32<<20 || len(subscriptionsJSON) > 1<<20 || len(chainsJSON) > 1<<20 ||
		json.Unmarshal([]byte(resourcesJSON), &inputs) != nil || len(inputs) > 100 ||
		json.Unmarshal([]byte(subscriptionsJSON), &subscriptions) != nil || len(subscriptions) > 50 ||
		json.Unmarshal([]byte(chainsJSON), &chains) != nil || len(chains) > 128 {
		return "", fmt.Errorf("invalid_resource_graph")
	}
	graph := resourceGraph{Nodes: []resourceGraphNode{}, Edges: []resourceGraphEdge{}}
	known := map[string]bool{}
	addNode := func(prefix, id, name, kind string) error {
		key := prefix + id
		if id == "" || known[key] {
			return fmt.Errorf("invalid_resource_graph")
		}
		known[key] = true
		graph.Nodes = append(graph.Nodes, resourceGraphNode{Key: key, ID: id, Name: name, Kind: kind, Subscriptions: []string{}})
		return nil
	}
	addEdge := func(from, to, role string, enabled bool) {
		graph.Edges = append(graph.Edges, resourceGraphEdge{from, to, role, enabled})
	}
	for _, r := range inputs {
		if err := addNode("resource:", r.ID, r.Name, r.Kind); err != nil {
			return "", err
		}
		if r.FormatVersion < 2 {
			continue
		}
		switch r.Kind {
		case "CONFIG":
			var config localconfig.Config
			if json.Unmarshal([]byte(r.Content), &config) != nil {
				return "", fmt.Errorf("invalid_local_configuration")
			}
			plan := config.ResourcePlan
			for _, id := range plan.StrategyGroupIDs {
				addEdge("resource:"+r.ID, "resource:"+id, "group", plan.GroupEnabled(id))
			}
			if plan.DefaultProxySelectorID != "" {
				addEdge("resource:"+r.ID, "resource:"+plan.DefaultProxySelectorID, "proxy", plan.GroupEnabled(plan.DefaultProxySelectorID))
			}
			if plan.Match.Mode == "selector" && plan.Match.SelectorID != "" {
				addEdge("resource:"+r.ID, "resource:"+plan.Match.SelectorID, "match", plan.GroupEnabled(plan.Match.SelectorID))
			}
		case "GROUPS":
			var group resources.StrategyGroup
			if json.Unmarshal([]byte(r.Content), &group) != nil {
				return "", fmt.Errorf("invalid_strategy_group")
			}
			for _, ref := range group.RuleSetReferences {
				addEdge("resource:"+r.ID, "resource:"+ref.RuleSetID, "rules", true)
			}
		}
	}
	for _, chain := range chains {
		if err := addNode("chain:", chain.ID, chain.Name, "CHAIN"); err != nil {
			return "", err
		}
	}
	for _, sub := range subscriptions {
		if err := addNode("subscription:", sub.ID, sub.Name, "SUBSCRIPTION"); err != nil {
			return "", err
		}
		if sub.HandlerID != "" {
			addEdge("subscription:"+sub.ID, "resource:"+sub.HandlerID, "handler", true)
		}
		for _, id := range sub.ResourceIDs {
			addEdge("subscription:"+sub.ID, "resource:"+id, "extra", true)
		}
		for _, id := range sub.ChainGroupIDs {
			addEdge("subscription:"+sub.ID, "chain:"+id, "chain", true)
		}
	}
	if len(graph.Edges) > 20000 {
		return "", fmt.Errorf("invalid_resource_graph")
	}
	for _, edge := range graph.Edges {
		if !known[edge.To] {
			known[edge.To] = true
			graph.Nodes = append(graph.Nodes, resourceGraphNode{Key: edge.To, Kind: "MISSING", Missing: true, Subscriptions: []string{}})
		}
	}
	children := map[string][]string{}
	for _, edge := range graph.Edges {
		children[edge.From] = append(children[edge.From], edge.To)
	}
	// Count each subscription once, including disabled references. Such references
	// are still retained in plans/packages and must remain visible to an editor.
	for _, sub := range subscriptions {
		visited := map[string]bool{}
		queue := []string{"subscription:" + sub.ID}
		for len(queue) > 0 {
			key := queue[0]
			queue = queue[1:]
			if visited[key] {
				continue
			}
			visited[key] = true
			queue = append(queue, children[key]...)
		}
		for i := range graph.Nodes {
			if visited[graph.Nodes[i].Key] {
				graph.Nodes[i].Subscriptions = append(graph.Nodes[i].Subscriptions, sub.ID)
			}
		}
	}
	encoded, err := json.Marshal(graph)
	return string(encoded), err
}
