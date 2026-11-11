package mobile

import (
	"encoding/json"
	"fmt"
	"jeemi-android/engine/internal/config/document"
)

// A successful PUT does not imply every listener bound successfully. Verify
// the observable live ports before publishing the new configuration revision.
func ValidateReloadedConfiguration(configuration, status string) error {
	if len(configuration) > maxBytes || len(status) > 1<<20 {
		return fmt.Errorf("reload_verification_failed")
	}
	doc, err := document.Parse([]byte(configuration))
	if err != nil {
		return fmt.Errorf("reload_verification_failed")
	}
	var actual map[string]json.RawMessage
	if json.Unmarshal([]byte(status), &actual) != nil {
		return fmt.Errorf("reload_verification_failed")
	}
	root := document.Root(doc)
	for _, key := range []string{"port", "socks-port", "mixed-port"} {
		want, got := 0, -1
		node, found, err := document.Find(root, "/"+key)
		if err != nil || (found && node.Decode(&want) != nil) || json.Unmarshal(actual[key], &got) != nil || got != want {
			return fmt.Errorf("reload_verification_failed")
		}
	}
	return nil
}
