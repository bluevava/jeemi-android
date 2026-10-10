package mobile

import (
	"encoding/json"
	"fmt"
	"jeemi-android/engine/internal/config/resources"
)

// EditAppRuleSet maps a local classical YAML draft to application rows and
// applies explicit draft operations. No library writes or installed-app access.
func EditAppRuleSet(payload, request string) (string, error) {
	var edit resources.AppRuleEdit
	if len(payload) > 2<<20 || len(request) > 2<<20 || json.Unmarshal([]byte(request), &edit) != nil {
		return "", fmt.Errorf("invalid_app_rules")
	}
	view, err := resources.EditAppRules(payload, edit)
	if err != nil {
		return "", fmt.Errorf("invalid_app_rules")
	}
	result, err := json.Marshal(view)
	return string(result), err
}
