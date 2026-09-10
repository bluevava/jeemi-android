package resources

import (
	"fmt"
	"time"
)

func validateState(groups []StrategyGroup, ruleSets []RuleSet) error {
	seen := make(map[string]struct{}, len(groups)+len(ruleSets))
	for index := range groups {
		item := groups[index]
		if !resourceIDPattern.MatchString(item.ID) {
			return fmt.Errorf("stored strategy group id is invalid")
		}
		if _, duplicate := seen["group-id:"+item.ID]; duplicate {
			return fmt.Errorf("stored strategy group id is duplicated")
		}
		seen["group-id:"+item.ID] = struct{}{}
		displayName := strategyGroupDisplayName(item)
		if _, duplicate := seen["group-name:"+displayName]; duplicate {
			return fmt.Errorf("stored strategy group name is duplicated")
		}
		seen["group-name:"+displayName] = struct{}{}
		updatedAt, updatedErr := time.Parse(time.RFC3339Nano, item.UpdatedAt)
		if updatedErr != nil {
			return fmt.Errorf("stored strategy group %q update time is invalid", item.Name)
		}
		if _, createdErr := time.Parse(time.RFC3339Nano, item.CreatedAt); createdErr != nil {
			return fmt.Errorf("stored strategy group %q creation time is invalid", item.Name)
		}
		normalized, err := normalizeStrategyGroup(item, updatedAt)
		if err != nil {
			return fmt.Errorf("stored strategy group %q is invalid: %w", item.Name, err)
		}
		groups[index] = normalized
	}
	for index := range ruleSets {
		item := ruleSets[index]
		if !resourceIDPattern.MatchString(item.ID) {
			return fmt.Errorf("stored rule set id is invalid")
		}
		if _, duplicate := seen["rule-id:"+item.ID]; duplicate {
			return fmt.Errorf("stored rule set id is duplicated")
		}
		seen["rule-id:"+item.ID] = struct{}{}
		if _, duplicate := seen["rule-name:"+item.Name]; duplicate {
			return fmt.Errorf("stored rule set name is duplicated")
		}
		seen["rule-name:"+item.Name] = struct{}{}
		updatedAt, updatedErr := time.Parse(time.RFC3339Nano, item.UpdatedAt)
		if updatedErr != nil {
			return fmt.Errorf("stored rule set %q update time is invalid", item.Name)
		}
		if _, createdErr := time.Parse(time.RFC3339Nano, item.CreatedAt); createdErr != nil {
			return fmt.Errorf("stored rule set %q creation time is invalid", item.Name)
		}
		normalized, err := normalizeRuleSet(item, updatedAt)
		if err != nil {
			return fmt.Errorf("stored rule set %q is invalid: %w", item.Name, err)
		}
		ruleSets[index] = normalized
	}
	state := State{StrategyGroups: groups, RuleSets: ruleSets}
	ruleSetsByID := make(map[string]RuleSet, len(ruleSets))
	for _, ruleSet := range ruleSets {
		ruleSetsByID[ruleSet.ID] = ruleSet
	}
	ruleSetOwners := make(map[string]string, len(ruleSets))
	groupNames := make(map[string]string, len(groups))
	for _, group := range groups {
		groupNames[group.ID] = strategyGroupDisplayName(group)
	}
	for _, group := range groups {
		if err := validateStrategyGroupReferences(group, state); err != nil {
			return fmt.Errorf("stored strategy group %q has invalid references: %w", group.Name, err)
		}
		for _, reference := range group.RuleSetReferences {
			if owner, exists := ruleSetOwners[reference.RuleSetID]; exists && owner != group.ID {
				return fmt.Errorf(
					"rule set %q can only be referenced by one strategy group; it already belongs to %q",
					ruleSetsByID[reference.RuleSetID].Name,
					groupNames[owner],
				)
			}
			ruleSetOwners[reference.RuleSetID] = group.ID
		}
	}
	return nil
}

func ValidateState(state State) error { return validateState(state.StrategyGroups, state.RuleSets) }
func NormalizeGroup(group StrategyGroup) (StrategyGroup, error) {
	return normalizeStrategyGroup(group, time.Now().UTC())
}
func NormalizeRuleSet(rule RuleSet) (RuleSet, error) { return normalizeRuleSet(rule, time.Now().UTC()) }
