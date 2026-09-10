package resources

// PrepareEntry exposes the desktop's literal validation and YAML-preserving
// append operation without its desktop file store or application lifecycle.
func PrepareEntry(input RuleSetEntryInput, state State) (RuleSet, RuleSetEntryPreview, error) {
	return prepareRuleSetEntry(input, state)
}
