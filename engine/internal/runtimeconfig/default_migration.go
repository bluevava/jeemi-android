package runtimeconfig

import (
	"bytes"
	"encoding/json"
	"fmt"
	"slices"
)

// UpgradeDefaultFakeIPFilter is a storage-boundary migration, not an editor
// normalization. Mark both migrated defaults and preserved custom settings so
// a later deliberate edit back to the old values cannot trigger it again.
func UpgradeDefaultFakeIPFilter(raw string) (string, error) {
	var fields map[string]json.RawMessage
	if len(raw) > 256<<10 || json.Unmarshal([]byte(raw), &fields) != nil || fields == nil {
		return "", fmt.Errorf("invalid_runtime_preferences")
	}
	const marker = "dnsFakeIpFilterDefaultsVersion"
	if value, found := fields[marker]; found {
		var version int
		if bytes.Equal(bytes.TrimSpace(value), []byte("null")) || json.Unmarshal(value, &version) != nil || version < 0 {
			return "", fmt.Errorf("invalid_runtime_preferences")
		}
		if version >= 1 {
			return raw, nil
		}
	}
	legacy := []string{"+.lan", "+.local", "+.home.arpa", "+.localdomain", "+.localhost", "+.msftconnecttest.com", "+.msftncsi.com"}
	var filter []string
	listMatches := json.Unmarshal(fields["dnsFakeIpFilter"], &filter) == nil && slices.Equal(filter, legacy)
	if listMatches {
		fields["dnsFakeIpFilter"], _ = json.Marshal(defaultDNSFakeIPFilter)
	}
	fields[marker] = json.RawMessage("1")
	result, err := json.Marshal(fields)
	return string(result), err
}
