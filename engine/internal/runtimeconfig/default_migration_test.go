package runtimeconfig

import (
	"encoding/json"
	"reflect"
	"slices"
	"strings"
	"testing"
)

func TestDefaultFakeIPFilterUpgradePreservesCustomListAndControls(t *testing.T) {
	old := []string{"+.lan", "+.local", "+.home.arpa", "+.localdomain", "+.localhost", "+.msftconnecttest.com", "+.msftncsi.com"}
	reordered := slices.Clone(old)
	reordered[2], reordered[3] = reordered[3], reordered[2]
	spaced := slices.Clone(old)
	spaced[0] += " "
	cases := []struct {
		name     string
		list     any
		enabled  bool
		strategy string
		upgrade  bool
	}{
		{"old defaults", old, true, "override", true},
		{"disabled", old, false, "override", true},
		{"append", old, true, "append", true},
		{"both controls changed", old, false, "append", true},
		{"added", append(slices.Clone(old), "custom.invalid"), true, "override", false},
		{"removed", old[:6], true, "override", false},
		{"reordered", reordered, true, "override", false},
		{"duplicate", append(slices.Clone(old), old[0]), true, "override", false},
		{"whitespace", spaced, true, "override", false},
		{"empty", []string{}, true, "override", false},
		{"null", nil, true, "override", false},
		{"current defaults", DefaultPreferences().DNSFakeIPFilter, true, "override", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			input := map[string]any{"dnsFakeIpFilter": c.list, "dnsFakeIpFilterEnabled": c.enabled,
				"dnsFakeIpFilterMerge": c.strategy, "ipv6": false, "listenPort": 7893,
				"unrelated": map[string]any{"kept": []string{"opaque", "value"}}}
			raw, _ := json.Marshal(input)
			upgraded, err := UpgradeDefaultFakeIPFilter(string(raw))
			if err != nil {
				t.Fatal(err)
			}
			var actual, expected map[string]any
			_ = json.Unmarshal([]byte(upgraded), &actual)
			_ = json.Unmarshal(raw, &expected)
			expected["dnsFakeIpFilterDefaultsVersion"] = float64(1)
			if c.upgrade {
				values, _ := json.Marshal(DefaultPreferences().DNSFakeIPFilter)
				var list any
				_ = json.Unmarshal(values, &list)
				expected["dnsFakeIpFilter"] = list
			}
			if !reflect.DeepEqual(actual, expected) {
				t.Fatal("migration changed custom/unrelated values")
			}
			// A user may later choose the exact old tuple. Checked custom
			// settings must also be protected when their controls are re-enabled.
			actual["dnsFakeIpFilter"] = old
			actual["dnsFakeIpFilterEnabled"] = true
			actual["dnsFakeIpFilterMerge"] = "override"
			again, _ := json.Marshal(actual)
			kept, err := UpgradeDefaultFakeIPFilter(string(again))
			if err != nil || kept != string(again) {
				t.Fatal("marked user choice was upgraded again", err)
			}
		})
	}
}

func TestDefaultFakeIPFilterUpgradeHonorsMissingControlsAndVersion(t *testing.T) {
	old := `["+.lan","+.local","+.home.arpa","+.localdomain","+.localhost","+.msftconnecttest.com","+.msftncsi.com"]`
	var unread Preferences
	if err := json.Unmarshal([]byte(`{"dnsFakeIpFilter":`+old+`}`), &unread); err != nil {
		t.Fatal(err)
	}
	if Normalize(unread).DNSFakeIPFilterDefaultsVersion != 0 {
		t.Fatal("normalization incorrectly marks a deferred migration as committed")
	}
	for _, marker := range []string{"", `,"dnsFakeIpFilterDefaultsVersion":0`} {
		out, err := UpgradeDefaultFakeIPFilter(`{"dnsFakeIpFilter":` + old + marker + `}`)
		if err != nil {
			t.Fatal(err)
		}
		var preferences Preferences
		if err = json.Unmarshal([]byte(out), &preferences); err != nil {
			t.Fatal(err)
		}
		if !slices.Equal(preferences.DNSFakeIPFilter, DefaultPreferences().DNSFakeIPFilter) ||
			!preferences.DNSFakeIPFilterEnabled || preferences.DNSFakeIPFilterMerge != MergeModeOverride ||
			preferences.DNSFakeIPFilterDefaultsVersion != 1 {
			t.Fatal("omitted legacy controls did not keep default semantics")
		}
	}
	current := DefaultPreferences()
	current.DNSFakeIPFilter = []string{"+.lan", "+.local", "+.home.arpa", "+.localdomain", "+.localhost", "+.msftconnecttest.com", "+.msftncsi.com"}
	raw, _ := json.Marshal(current)
	if kept, err := UpgradeDefaultFakeIPFilter(string(raw)); err != nil || kept != string(raw) {
		t.Fatal("current defaults lack an effective migration marker", err)
	}
	for _, invalid := range []string{"null", "[]", "{", strings.Repeat(" ", 256<<10+1),
		`{"dnsFakeIpFilterDefaultsVersion":-1}`, `{"dnsFakeIpFilterDefaultsVersion":null}`, `{"dnsFakeIpFilterDefaultsVersion":"1"}`} {
		if _, err := UpgradeDefaultFakeIPFilter(invalid); err == nil {
			t.Fatal("invalid migration input accepted")
		}
	}
}
