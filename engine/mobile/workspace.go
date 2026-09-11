package mobile

import (
	"encoding/json"
	"fmt"
	"slices"
	"strings"

	"gopkg.in/yaml.v3"
	"jeemi-android/engine/internal/config/compose"
	"jeemi-android/engine/internal/config/document"
	"jeemi-android/engine/internal/config/schema"
	"jeemi-android/engine/internal/localscript"
)

// FieldCatalog shares desktop field definitions; Android chooses its own presentation.
func FieldCatalog() string {
	encoded, _ := json.Marshal(schema.Current())
	return string(encoded)
}

type preparedResource struct {
	Content string         `json:"content"`
	Plan    []compose.Rule `json:"plan"`
}

// PrepareResource validates editor documents and creates an explicit sparse plan.
// CONFIG is a mapping, GROUPS a named sequence, RULES a sequence of rule strings.
func PrepareResource(kind, content, strategy string) (string, error) {
	if len(content) > maxBytes {
		return "", fmt.Errorf("size_limit")
	}
	if kind == "SCRIPT" {
		if len(content) > 256<<10 {
			return "", fmt.Errorf("size_limit")
		}
		if err := localscript.ValidateSource(content); err != nil {
			return "", fmt.Errorf("invalid_script")
		}
		result, _ := json.Marshal(preparedResource{Content: content, Plan: []compose.Rule{}})
		return string(result), nil
	}
	source := content
	if kind == "GROUPS" {
		source = "proxy-groups:\n" + indent(content)
	}
	if kind == "RULES" {
		source = "rules:\n" + indent(content)
	}
	if kind != "CONFIG" && kind != "GROUPS" && kind != "RULES" {
		return "", fmt.Errorf("invalid_resource_kind")
	}
	doc, err := document.Parse([]byte(source))
	if err != nil {
		return "", fmt.Errorf("invalid_resource_yaml")
	}
	root := document.Root(doc)
	plan := []compose.Rule{}
	for i := 0; i < len(root.Content); i += 2 {
		key, value := root.Content[i].Value, root.Content[i+1]
		selected := compose.StrategyReplace
		if strategy == "auto" {
			switch {
			case key == "rules":
				selected = compose.StrategyAppendBeforeTerminal
			case key == "proxy-groups" || key == "proxies" || key == "proxy-providers" || key == "rule-providers":
				selected = compose.StrategyMergeByName
			case value.Kind == yaml.MappingNode:
				selected = compose.StrategyMerge
			case value.Kind == yaml.SequenceNode:
				selected = compose.StrategyAppend
			}
		} else {
			selected = compose.Strategy(strategy)
		}
		if kind == "GROUPS" {
			if value.Kind != yaml.SequenceNode {
				return "", fmt.Errorf("invalid_groups")
			}
			names := map[string]bool{}
			for _, group := range value.Content {
				name, ok, _ := document.Find(group, "/name")
				typ, typed, _ := document.Find(group, "/type")
				if !ok || name.Tag != "!!str" || name.Value == "" || names[name.Value] || !typed || !slices.Contains([]string{"select", "url-test", "fallback", "load-balance", "relay"}, typ.Value) {
					return "", fmt.Errorf("invalid_groups")
				}
				names[name.Value] = true
			}
		}
		if kind == "RULES" {
			if value.Kind != yaml.SequenceNode {
				return "", fmt.Errorf("invalid_rules")
			}
			for _, rule := range value.Content {
				if rule.Kind != yaml.ScalarNode || rule.Tag != "!!str" || !strings.Contains(rule.Value, ",") {
					return "", fmt.Errorf("invalid_rules")
				}
			}
		}
		plan = append(plan, compose.Rule{Path: "/" + strings.ReplaceAll(strings.ReplaceAll(key, "~", "~0"), "/", "~1"), Strategy: selected, ConflictPolicy: compose.ConflictError})
	}
	if err := compose.ValidatePlan(root, plan); err != nil {
		return "", fmt.Errorf("invalid_resource_strategy")
	}
	normalized, err := document.Encode(doc)
	if err != nil {
		return "", fmt.Errorf("invalid_resource_yaml")
	}
	result, _ := json.Marshal(preparedResource{Content: string(normalized), Plan: plan})
	return string(result), nil
}

func indent(value string) string {
	return "  " + strings.ReplaceAll(strings.TrimSpace(value), "\n", "\n  ") + "\n"
}

// SetConfigurationField inserts one typed catalog value into an editor draft.
func SetConfigurationField(configuration, path, value string) (string, error) {
	field, found := schema.FindField(path)
	if !found || field.Locked || field.Hidden || field.Scope != "" {
		return "", fmt.Errorf("field_unavailable")
	}
	root, err := document.Parse([]byte(configuration))
	if err != nil {
		return "", fmt.Errorf("invalid_configuration")
	}
	node, err := document.ParseValue(value)
	if err != nil {
		return "", fmt.Errorf("invalid_field_value")
	}
	valid := true
	switch field.Editor {
	case schema.EditorBoolean:
		valid = node.Tag == "!!bool"
	case schema.EditorNumber:
		valid = node.Tag == "!!int" || node.Tag == "!!float"
	case schema.EditorString:
		valid = node.Kind == yaml.ScalarNode && node.Tag == "!!str"
	case schema.EditorEnum:
		valid = slices.Contains(field.Options, node.Value)
	}
	switch field.Kind {
	case schema.KindMapping, schema.KindNamedMapping:
		valid = valid && node.Kind == yaml.MappingNode
	case schema.KindSequence, schema.KindNamedSequence, schema.KindRules:
		valid = valid && node.Kind == yaml.SequenceNode
	}
	if !valid {
		return "", fmt.Errorf("invalid_field_type")
	}
	if err := document.SetMappingPath(document.Root(root), path, node); err != nil {
		return "", fmt.Errorf("invalid_field_path")
	}
	result, err := document.Encode(root)
	if err != nil {
		return "", fmt.Errorf("invalid_configuration")
	}
	return string(result), nil
}

func ExecuteScript(configuration, source string) (string, error) {
	if len(source) > 256<<10 || len(configuration) > maxBytes {
		return "", fmt.Errorf("size_limit")
	}
	output, err := localscript.Execute(source, []byte(configuration))
	if err != nil {
		return "", fmt.Errorf("script_execution_failed")
	}
	return string(output), nil
}

type groupSummary struct {
	Name            string   `json:"name"`
	Type            string   `json:"type"`
	Icon            string   `json:"icon"`
	Members         []string `json:"members"`
	Providers       []string `json:"providers"`
	Hidden          bool     `json:"hidden"`
	DefaultSelected string   `json:"defaultSelected"`
}
type providerSummary struct {
	Name     string `json:"name"`
	Type     string `json:"type"`
	Behavior string `json:"behavior"`
}

// InspectSubscription provides offline structure only, never a selected node or latency.
func InspectSubscription(configuration string) (string, error) {
	doc, err := document.Parse([]byte(configuration))
	if err != nil {
		return "", fmt.Errorf("invalid_configuration")
	}
	groups := []groupSummary{}
	providers := []providerSummary{}
	if values, ok, _ := document.Find(document.Root(doc), "/proxy-groups"); ok && values.Kind == yaml.SequenceNode {
		for _, item := range values.Content {
			group := groupSummary{Members: []string{}, Providers: []string{}}
			if value, found, _ := document.Find(item, "/name"); found {
				group.Name = value.Value
			}
			if value, found, _ := document.Find(item, "/type"); found {
				group.Type = value.Value
			}
			if value, found, _ := document.Find(item, "/icon"); found && value.Kind == yaml.ScalarNode && value.Tag == "!!str" {
				group.Icon = value.Value
			}
			if value, found, _ := document.Find(item, "/default-selected"); found {
				group.DefaultSelected = value.Value
			}
			if value, found, _ := document.Find(item, "/hidden"); found {
				group.Hidden = value.Value == "true"
			}
			if value, found, _ := document.Find(item, "/proxies"); found && value.Kind == yaml.SequenceNode {
				for _, n := range value.Content {
					group.Members = append(group.Members, n.Value)
				}
			}
			if value, found, _ := document.Find(item, "/use"); found && value.Kind == yaml.SequenceNode {
				for _, n := range value.Content {
					group.Providers = append(group.Providers, n.Value)
				}
			}
			groups = append(groups, group)
		}
	}
	if mode, found, _ := document.Find(document.Root(doc), "/mode"); found && mode.Value == "global" &&
		!slices.ContainsFunc(groups, func(group groupSummary) bool { return group.Name == "GLOBAL" }) {
		// mihomo creates this selector at runtime. Project its known members for
		// the offline UI without injecting a second group into the candidate.
		global := groupSummary{Name: "GLOBAL", Type: "select", Members: []string{"DIRECT", "REJECT"}, Providers: []string{}}
		if proxies, ok, _ := document.Find(document.Root(doc), "/proxies"); ok && proxies.Kind == yaml.SequenceNode {
			for _, proxy := range proxies.Content {
				kind, _, _ := document.Find(proxy, "/type")
				if kind != nil && (kind.Value == "pass" || kind.Value == "pass-rule") {
					continue
				}
				if name, ok, _ := document.Find(proxy, "/name"); ok && name.Value != "" {
					global.Members = append(global.Members, name.Value)
				}
			}
		}
		for _, group := range groups {
			global.Members = append(global.Members, group.Name)
		}
		groups = append([]groupSummary{global}, groups...)
	}
	if values, ok, _ := document.Find(document.Root(doc), "/rule-providers"); ok && values.Kind == yaml.MappingNode {
		for i := 0; i < len(values.Content); i += 2 {
			item := providerSummary{Name: values.Content[i].Value}
			if v, ok, _ := document.Find(values.Content[i+1], "/type"); ok {
				item.Type = v.Value
			}
			if v, ok, _ := document.Find(values.Content[i+1], "/behavior"); ok {
				item.Behavior = v.Value
			}
			providers = append(providers, item)
		}
	}
	encoded, _ := json.Marshal(struct {
		Groups    []groupSummary    `json:"groups"`
		Providers []providerSummary `json:"providers"`
	}{groups, providers})
	return string(encoded), nil
}
