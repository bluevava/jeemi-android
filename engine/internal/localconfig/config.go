// Package localconfig adapts the desktop field plan to Android-owned storage.
package localconfig

import (
	"fmt"
	"slices"
	"sort"

	"gopkg.in/yaml.v3"
	"jeemi-android/engine/internal/config/compose"
	"jeemi-android/engine/internal/config/document"
	"jeemi-android/engine/internal/config/proxyoverride"
	"jeemi-android/engine/internal/config/resources"
	"jeemi-android/engine/internal/config/schema"
)

type Field struct {
	Path           string                 `json:"path"`
	ValueYAML      string                 `json:"valueYaml"`
	Strategy       compose.Strategy       `json:"strategy"`
	ConflictPolicy compose.ConflictPolicy `json:"conflictPolicy,omitempty"`
}
type Config struct {
	Name           string         `json:"name"`
	Description    string         `json:"description"`
	Fields         []Field        `json:"fields"`
	DisabledFields []Field        `json:"disabledFields,omitempty"`
	ResourcePlan   resources.Plan `json:"resourcePlan"`
}
type Prepared struct {
	Config     Config
	Overlay    []byte
	Rules      []compose.Rule
	Transforms []proxyoverride.Override
}

func Prepare(input Config) (Prepared, error) {
	result := Prepared{Config: input, Rules: []compose.Rule{}, Transforms: []proxyoverride.Override{}}
	if len(input.Fields)+len(input.DisabledFields) > 512 {
		return result, fmt.Errorf("field_limit")
	}
	plan, err := resources.NormalizePlan(input.ResourcePlan)
	if err != nil {
		return result, err
	}
	result.Config.ResourcePlan = plan
	result.Config.Fields = append([]Field{}, input.Fields...)
	sort.Slice(result.Config.Fields, func(i, j int) bool { return result.Config.Fields[i].Path < result.Config.Fields[j].Path })
	overlay := document.New()
	seen := map[string]bool{}
	for i := range result.Config.Fields {
		field := &result.Config.Fields[i]
		definition, ok := schema.FindField(field.Path)
		if !ok || definition.Locked || seen[field.Path] {
			return result, fmt.Errorf("field_unavailable")
		}
		seen[field.Path] = true
		value, err := ValidateValue(definition, field.ValueYAML)
		if err != nil {
			return result, err
		}
		if field.Strategy == "" {
			field.Strategy = definition.DefaultStrategy
		}
		if !slices.Contains(definition.Strategies, field.Strategy) {
			return result, fmt.Errorf("invalid_field_strategy")
		}
		if field.Strategy == compose.StrategyMergeByName {
			if field.ConflictPolicy == "" {
				field.ConflictPolicy = definition.DefaultConflict
			}
			if field.ConflictPolicy != compose.ConflictError && field.ConflictPolicy != compose.ConflictUseLocal {
				return result, fmt.Errorf("invalid_conflict_policy")
			}
		} else {
			field.ConflictPolicy = ""
		}
		field.ValueYAML, err = document.EncodeValue(value)
		if err != nil {
			return result, err
		}
		if definition.Scope == schema.ScopeAllProxies {
			result.Transforms = append(result.Transforms, proxyoverride.Override{Path: field.Path, ValueYAML: field.ValueYAML, TargetPath: definition.TargetPath, ApplicableTypes: definition.ApplicableTypes})
			continue
		}
		if err = document.SetMappingPath(document.Root(overlay), field.Path, value); err != nil {
			return result, err
		}
		result.Rules = append(result.Rules, compose.Rule{Path: field.Path, Strategy: field.Strategy, ConflictPolicy: field.ConflictPolicy})
	}
	// Disabled values are editor drafts. They never become YAML or transforms.
	for _, field := range input.DisabledFields {
		definition, ok := schema.FindField(field.Path)
		if !ok || definition.Locked || seen[field.Path] || len(field.ValueYAML) > 1<<20 {
			return result, fmt.Errorf("invalid_disabled_field")
		}
		seen[field.Path] = true
	}
	if err = compose.ValidatePlan(document.Root(overlay), result.Rules); err != nil {
		return result, err
	}
	result.Overlay, err = document.Encode(overlay)
	return result, err
}

func ValidateValue(field schema.Field, valueYAML string) (*yaml.Node, error) {
	if len(valueYAML) > 1<<20 {
		return nil, fmt.Errorf("field_limit")
	}
	value, err := document.ParseValue(valueYAML)
	if err != nil {
		return nil, fmt.Errorf("invalid_field_yaml")
	}
	valid := true
	switch field.Kind {
	case schema.KindScalar:
		valid = value.Kind == yaml.ScalarNode
	case schema.KindMapping, schema.KindNamedMapping:
		valid = value.Kind == yaml.MappingNode
	case schema.KindSequence, schema.KindNamedSequence, schema.KindRules:
		valid = value.Kind == yaml.SequenceNode
	}
	switch field.Editor {
	case schema.EditorBoolean:
		valid = valid && value.Tag == "!!bool"
	case schema.EditorNumber:
		valid = valid && (value.Tag == "!!int" || value.Tag == "!!float")
	case schema.EditorString:
		valid = valid && value.Tag == "!!str"
	case schema.EditorEnum:
		valid = valid && slices.Contains(field.Options, value.Value)
	}
	if !valid {
		return nil, fmt.Errorf("invalid_field_type")
	}
	return value, nil
}

func Apply(contents []byte, input Config, state resources.State) ([]byte, error) {
	prepared, err := Prepare(input)
	if err != nil {
		return nil, err
	}
	source, err := resources.PrepareSource(contents, prepared.Config.ResourcePlan)
	if err != nil {
		return nil, err
	}
	composed, err := compose.Compose(source, prepared.Overlay, prepared.Rules)
	if err != nil {
		return nil, err
	}
	source, err = proxyoverride.Apply([]byte(composed.YAML), prepared.Transforms)
	if err != nil {
		return nil, err
	}
	return resources.Apply(source, state, prepared.Config.ResourcePlan)
}
