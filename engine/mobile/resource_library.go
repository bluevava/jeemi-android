package mobile

import (
	"encoding/json"
	"fmt"
	"strings"
	"unicode/utf8"

	"jeemi-android/engine/internal/chainproxy"
	"jeemi-android/engine/internal/config/compose"
	"jeemi-android/engine/internal/config/fallbackoverride"
	configinspect "jeemi-android/engine/internal/config/inspect"
	"jeemi-android/engine/internal/config/resources"
	"jeemi-android/engine/internal/config/ruleprovideroverride"
	"jeemi-android/engine/internal/configtransfer"
	"jeemi-android/engine/internal/localconfig"
	"jeemi-android/engine/internal/localscript"
	"jeemi-android/engine/internal/subscriptionformat"
)

type resourceInput struct {
	ID            string `json:"id"`
	Name          string `json:"name"`
	Description   string `json:"description"`
	Kind          string `json:"kind"`
	Content       string `json:"content"`
	Strategy      string `json:"strategy"`
	FormatVersion int    `json:"formatVersion"`
}

func prepareTyped(input resourceInput) (resourceInput, error) {
	if input.FormatVersion < 0 || input.FormatVersion > 2 {
		return input, fmt.Errorf("unsupported_resource_version")
	}
	input.Name = strings.TrimSpace(input.Name)
	if utf8.RuneCountInString(input.Name) < 1 || utf8.RuneCountInString(input.Name) > 80 || utf8.RuneCountInString(input.Description) > 500 || len(input.Content) > maxBytes {
		return input, fmt.Errorf("invalid_resource")
	}
	if input.FormatVersion < 2 || input.Kind == "SCRIPT" {
		if _, err := PrepareResource(input.Kind, input.Content, input.Strategy); err != nil {
			return input, err
		}
		return input, nil
	}
	var normalized any
	switch input.Kind {
	case "CONFIG":
		var c localconfig.Config
		if json.Unmarshal([]byte(input.Content), &c) != nil {
			return input, fmt.Errorf("invalid_local_configuration")
		}
		c.Name = input.Name
		c.Description = input.Description
		prepared, err := localconfig.Prepare(c)
		if err != nil {
			return input, fmt.Errorf("invalid_local_configuration")
		}
		normalized = prepared.Config
	case "GROUPS":
		var g resources.StrategyGroup
		if json.Unmarshal([]byte(input.Content), &g) != nil {
			return input, fmt.Errorf("invalid_strategy_group")
		}
		g.ID = input.ID
		g.Name = input.Name
		g.Description = input.Description
		value, err := resources.NormalizeGroup(g)
		if err != nil {
			return input, fmt.Errorf("invalid_strategy_group")
		}
		normalized = value
	case "RULES":
		var r resources.RuleSet
		if json.Unmarshal([]byte(input.Content), &r) != nil {
			return input, fmt.Errorf("invalid_rule_set")
		}
		r.ID = input.ID
		r.Name = input.Name
		r.Description = input.Description
		value, err := resources.NormalizeRuleSet(r)
		if err != nil {
			return input, fmt.Errorf("invalid_rule_set")
		}
		normalized = value
	default:
		return input, fmt.Errorf("invalid_resource_kind")
	}
	encoded, err := json.Marshal(normalized)
	input.Content = string(encoded)
	return input, err
}
func PrepareStructuredResource(raw string) (string, error) {
	var input resourceInput
	if len(raw) > 8<<20 || json.Unmarshal([]byte(raw), &input) != nil {
		return "", fmt.Errorf("invalid_resource")
	}
	prepared, err := prepareTyped(input)
	if err != nil {
		return "", err
	}
	b, _ := json.Marshal(prepared)
	return string(b), nil
}
func resourceState(inputs []resourceInput) (resources.State, error) {
	state := resources.State{StrategyGroups: []resources.StrategyGroup{}, RuleSets: []resources.RuleSet{}}
	seen := map[string]bool{}
	for _, input := range inputs {
		if seen[input.ID] || input.ID == "" {
			return state, fmt.Errorf("duplicate_resource")
		}
		seen[input.ID] = true
		p, err := prepareTyped(input)
		if err != nil {
			return state, err
		}
		if p.FormatVersion < 2 {
			continue
		}
		switch p.Kind {
		case "GROUPS":
			var g resources.StrategyGroup
			_ = json.Unmarshal([]byte(p.Content), &g)
			state.StrategyGroups = append(state.StrategyGroups, g)
		case "RULES":
			var r resources.RuleSet
			_ = json.Unmarshal([]byte(p.Content), &r)
			state.RuleSets = append(state.RuleSets, r)
		}
	}
	if err := resources.ValidateState(state); err != nil {
		return state, fmt.Errorf("invalid_resource_references")
	}
	return state, nil
}
func ValidateResourceLibrary(raw string) error {
	var inputs []resourceInput
	if len(raw) > 32<<20 || json.Unmarshal([]byte(raw), &inputs) != nil || len(inputs) > 100 {
		return fmt.Errorf("invalid_resource_library")
	}
	state, err := resourceState(inputs)
	if err != nil {
		return err
	}
	for _, input := range inputs {
		if input.Kind != "CONFIG" || input.FormatVersion < 2 {
			continue
		}
		var c localconfig.Config
		_ = json.Unmarshal([]byte(input.Content), &c)
		if _, err := resources.ValidatePlan(c.ResourcePlan, state); err != nil {
			return fmt.Errorf("invalid_resource_references")
		}
	}
	return nil
}
func applyInput(contents []byte, input resourceInput, state resources.State) ([]byte, error) {
	if input.Kind == "SCRIPT" {
		return localscript.Execute(input.Content, contents)
	}
	if input.FormatVersion >= 2 && input.Kind == "CONFIG" {
		var c localconfig.Config
		if json.Unmarshal([]byte(input.Content), &c) != nil {
			return nil, fmt.Errorf("invalid_resource")
		}
		return localconfig.Apply(contents, c, state)
	}
	if input.FormatVersion >= 2 {
		return nil, fmt.Errorf("resource_requires_local_config")
	}
	p, err := PrepareResource(input.Kind, input.Content, input.Strategy)
	if err != nil {
		return nil, err
	}
	var prepared preparedResource
	_ = json.Unmarshal([]byte(p), &prepared)
	result, err := compose.Compose(contents, []byte(prepared.Content), prepared.Plan)
	if err != nil {
		return nil, err
	}
	return []byte(result.YAML), nil
}

type workspaceInput struct {
	Original          string                     `json:"original"`
	Configuration     string                     `json:"configuration"`
	ChainLibrary      json.RawMessage            `json:"chainLibrary"`
	ChainGroupIDs     []string                   `json:"chainGroupIds"`
	ChainRevision     int                        `json:"chainRevision"`
	Resources         []resourceInput            `json:"resources"`
	HandlerID         string                     `json:"handlerId"`
	Attached          []string                   `json:"attached"`
	Runtime           json.RawMessage            `json:"runtime"`
	Mode              string                     `json:"mode"`
	DisabledProviders []string                   `json:"disabledProviders"`
	Fallback          fallbackoverride.Selection `json:"fallback"`
	GeoMode           string                     `json:"geoMode"`
	GeoLoader         string                     `json:"geoLoader"`
	TunStack          string                     `json:"tunStack"`
	LegacyValues      map[string]string          `json:"legacyValues"`
}
type projection struct {
	YAML          string                     `json:"yaml"`
	Structure     json.RawMessage            `json:"structure"`
	Fallback      fallbackoverride.State     `json:"fallback"`
	Providers     []providerSummary          `json:"providers"`
	Chains        chainproxy.Composition     `json:"chains"`
	Normalization *subscriptionformat.Report `json:"normalization,omitempty"`
	Nodes         []nodeSummary              `json:"nodes"`
}

func ComposeWorkspace(raw string) (string, error) {
	var input workspaceInput
	if len(raw) > 64<<20 || json.Unmarshal([]byte(raw), &input) != nil || len(input.Configuration) > maxBytes || len(input.Original) > maxBytes || input.ChainRevision < 0 {
		return "", fmt.Errorf("invalid_workspace")
	}
	var normalization *subscriptionformat.Report
	if input.Original != "" {
		parsed, err := subscriptionformat.Normalize([]byte(input.Original))
		if err != nil {
			return "", err
		}
		if err := parsed.CheckCore(bundledProtocolVersion); err != nil {
			return "", err
		}
		input.Configuration = string(parsed.Contents)
		normalization = &parsed.Report
	}
	state, err := resourceState(input.Resources)
	if err != nil {
		return "", err
	}
	available := map[string]resourceInput{}
	for _, r := range input.Resources {
		available[r.ID] = r
	}
	candidate := []byte(input.Configuration)
	if input.HandlerID != "" {
		resource, ok := available[input.HandlerID]
		if !ok || (resource.Kind != "CONFIG" && resource.Kind != "SCRIPT") {
			return "", fmt.Errorf("missing_handler")
		}
		candidate, err = applyInput(candidate, resource, state)
		if err != nil {
			return "", fmt.Errorf("local_processing_failed")
		}
	}
	for _, id := range input.Attached {
		resource, ok := available[id]
		if !ok {
			return "", fmt.Errorf("missing_resource")
		}
		candidate, err = applyInput(candidate, resource, state)
		if err != nil {
			return "", fmt.Errorf("local_processing_failed")
		}
	}
	providers, err := configinspect.RuleProviders(candidate)
	if err != nil {
		return "", fmt.Errorf("invalid_rule_providers")
	}
	candidate, err = ruleprovideroverride.Apply(candidate, input.DisabledProviders)
	if err != nil {
		return "", fmt.Errorf("provider_override_failed")
	}
	candidate, fallback, err := fallbackoverride.Apply(candidate, input.Fallback)
	if err != nil {
		return "", fmt.Errorf("fallback_override_failed")
	}
	chainJSON := string(input.ChainLibrary)
	if chainJSON == "null" {
		chainJSON = ""
	}
	library, err := readChainLibrary(chainJSON)
	if err != nil {
		return "", err
	}
	groups, err := chainproxy.SelectedGroups(library, input.ChainGroupIDs)
	if err != nil {
		return "", err
	}
	candidate, chains, err := chainproxy.Apply(candidate, groups, input.ChainRevision, bundledProtocolVersion)
	if err != nil {
		return "", err
	}
	// Only non-Home fields from the old editor retain a compatibility override.
	extras := map[string]string{}
	for _, path := range []string{"/tcp-concurrent", "/unified-delay", "/dns/default-nameserver", "/dns/fallback"} {
		if v, ok := input.LegacyValues[path]; ok {
			extras[path] = v
		}
	}
	if len(extras) > 0 {
		encoded, _ := json.Marshal(extras)
		text, e := ApplyRuntimeValues(string(candidate), string(encoded))
		if e != nil {
			return "", e
		}
		candidate = []byte(text)
	}
	candidate, err = applyHome(candidate, string(input.Runtime), input.Mode, input.GeoMode, input.GeoLoader, input.TunStack)
	if err != nil {
		return "", err
	}
	if err = configinspect.ValidateReferences(candidate); err != nil {
		return "", fmt.Errorf("invalid_candidate_references")
	}
	structure, err := InspectSubscription(string(candidate))
	if err != nil {
		return "", err
	}
	providerRows := make([]providerSummary, 0, len(providers))
	for _, p := range providers {
		providerRows = append(providerRows, providerSummary{Name: p.Name, Type: p.Type, Behavior: p.Behavior})
	}
	// Reuse the node summary boundary so generated B nodes have offline protocol
	// labels as well. The raw subscription and its stored format stay untouched.
	nodeText, err := NormalizeSubscription(string(candidate))
	if err != nil {
		return "", err
	}
	var nodeResult normalized
	_ = json.Unmarshal([]byte(nodeText), &nodeResult)
	encoded, err := json.Marshal(projection{string(candidate), json.RawMessage(structure), fallback, providerRows, chains, normalization, nodeResult.Nodes})
	return string(encoded), err
}

func ExportResourcePackage(resourceJSON, libraryJSON string) (string, error) {
	var input resourceInput
	var all []resourceInput
	if json.Unmarshal([]byte(resourceJSON), &input) != nil || len(libraryJSON) > 32<<20 || json.Unmarshal([]byte(libraryJSON), &all) != nil {
		return "", fmt.Errorf("invalid_resource")
	}
	p, err := prepareTyped(input)
	if err != nil {
		return "", err
	}
	if p.Kind == "SCRIPT" {
		pkg := configtransfer.FromScript(localscript.Script{Summary: localscript.Summary{Name: p.Name, Description: p.Description}, Contents: p.Content})
		b, e := configtransfer.Encode(pkg)
		return string(b), e
	}
	if p.Kind != "CONFIG" || p.FormatVersion < 2 {
		return "", fmt.Errorf("requires_structured_configuration")
	}
	state, err := resourceState(all)
	if err != nil {
		return "", err
	}
	var config localconfig.Config
	_ = json.Unmarshal([]byte(p.Content), &config)
	pkg, err := configtransfer.FromConfig(config, state)
	if err != nil {
		return "", fmt.Errorf("invalid_package_references")
	}
	b, err := configtransfer.Encode(pkg)
	return string(b), err
}

// PrepareResourceImport returns a detached library. The Android owner confirms
// its impact, validates every affected subscription and atomically commits it.
func PrepareResourceImport(packageJSON, targetJSON, libraryJSON string) (string, error) {
	var target resourceInput
	var all []resourceInput
	if json.Unmarshal([]byte(targetJSON), &target) != nil || len(libraryJSON) > 32<<20 || json.Unmarshal([]byte(libraryJSON), &all) != nil {
		return "", fmt.Errorf("invalid_resource")
	}
	kind := configtransfer.ConfigKind
	if target.Kind == "SCRIPT" {
		kind = configtransfer.ScriptKind
	}
	pkg, err := configtransfer.Decode([]byte(packageJSON), kind)
	if err != nil {
		return "", fmt.Errorf("invalid_resource_package")
	}
	overwritten, added := []string{}, []string{}
	previousName := ""
	for _, existing := range all {
		if existing.ID == target.ID {
			previousName = existing.Name
			break
		}
	}
	if pkg.Script != nil {
		target.Name = pkg.Script.Name
		target.Description = pkg.Script.Description
		target.Content = pkg.Script.Contents
		target.FormatVersion = 2
	} else {
		state, err := resourceState(all)
		if err != nil {
			return "", err
		}
		c := pkg.Config
		imported, err := resources.PrepareImport(state, c.ResourcePlan, c.StrategyGroups, c.RuleSets)
		if err != nil {
			return "", fmt.Errorf("resource_import_conflict")
		}
		overwritten = append(imported.OverwrittenGroups, imported.OverwrittenRuleSets...)
		added = append(imported.AddedGroups, imported.AddedRuleSets...)
		for _, g := range imported.State.StrategyGroups {
			b, _ := json.Marshal(g)
			all = replaceResource(all, resourceInput{ID: g.ID, Name: g.Name, Description: g.Description, Kind: "GROUPS", Content: string(b), Strategy: "auto", FormatVersion: 2})
		}
		for _, r := range imported.State.RuleSets {
			b, _ := json.Marshal(r)
			all = replaceResource(all, resourceInput{ID: r.ID, Name: r.Name, Description: r.Description, Kind: "RULES", Content: string(b), Strategy: "auto", FormatVersion: 2})
		}
		content, _ := json.Marshal(localconfig.Config{Name: c.Name, Description: c.Description, Fields: c.Fields, ResourcePlan: imported.Plan})
		target.Name = c.Name
		target.Description = c.Description
		target.Content = string(content)
		target.FormatVersion = 2
	}
	target, err = prepareTyped(target)
	if err != nil {
		return "", err
	}
	if previousName != "" {
		overwritten = append(overwritten, previousName)
	} else {
		added = append(added, target.Name)
	}
	all = replaceResource(all, target)
	encoded, _ := json.Marshal(all)
	if err = ValidateResourceLibrary(string(encoded)); err != nil {
		return "", err
	}
	result, err := json.Marshal(struct {
		Resources   []resourceInput `json:"resources"`
		Target      resourceInput   `json:"target"`
		Overwritten []string        `json:"overwritten"`
		Added       []string        `json:"added"`
	}{all, target, overwritten, added})
	return string(result), err
}
func replaceResource(all []resourceInput, item resourceInput) []resourceInput {
	for i, r := range all {
		if r.ID == item.ID {
			all[i] = item
			return all
		}
	}
	return append(all, item)
}
