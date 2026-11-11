// Package presets contains offline, editable Android routing templates.
// The fixed station lists and conversion rules originate in desktop Jeemi.
package presets

import (
	"crypto/sha256"
	"embed"
	"fmt"

	"jeemi-android/engine/internal/config/compose"
	"jeemi-android/engine/internal/config/resources"
)

//go:embed data/*
var data embed.FS

type Definition struct {
	Name   string
	Plan   resources.Plan
	Groups []resources.StrategyGroup
	Rules  []resources.RuleSet
}

// Temporary identities are remapped to fresh IDs when a copy is created.
func id(key string) string {
	sum := sha256.Sum256([]byte("jeemi-android-offline-preset-v1:" + key))
	return fmt.Sprintf("%x", sum[:16])
}

// Labels come from Android string resources; creating a copy freezes its names
// as user data. Browsing templates does not mutate or download anything.
func Load(kind string, labels map[string]string) (Definition, error) {
	if kind != "general" && kind != "apps" {
		return Definition{}, fmt.Errorf("invalid_builtin_rules")
	}
	d := Definition{Name: labels[kind], Plan: resources.DefaultPlan(), Groups: []resources.StrategyGroup{}, Rules: []resources.RuleSet{}}
	d.Plan.RuleStrategy = compose.StrategyReplace
	for _, spec := range []struct{ key, emoji string }{{"jeemi", "🚀"}, {"ai", "🤖"}, {"im", "✈️"}, {"video", "🎬"}} {
		d.Groups = append(d.Groups, resources.StrategyGroup{
			ID: id("group:" + spec.key), Name: labels[spec.key], Emoji: spec.emoji,
			Kind: resources.StrategyGroupKindSelector, Type: "select", IncludeAllProviders: true, AutoSelect: true,
			URL: "http://www.google.com/generate_204", Interval: 300, Tolerance: 50, Lazy: true,
			RuleOutput: resources.RuleOutputRuleSet, RuleSetReferences: []resources.RuleSetReference{},
			Filter: resources.NodeFilter{ProxyTypes: []string{}, NamePatterns: []string{}},
		})
	}
	for _, spec := range []struct {
		owner, key, emoji, behavior string
		sources                     []string
	}{
		{"jeemi", "github", "🚀", "domain", []string{"github"}},
		{"jeemi", "apple", "🍎", "domain", []string{"apple"}},
		{"ai", "aiDomains", "🤖", "domain", []string{"ai"}},
		{"ai", "google", "🔍", "domain", []string{"google"}},
		{"im", "telegramDomains", "✈️", "domain", []string{"telegram-domain"}},
		{"im", "telegramIP", "✈️", "ipcidr", []string{"telegram-ip"}},
		{"im", "discord", "💬", "domain", []string{"discord"}},
		{"im", "whatsapp", "💬", "domain", []string{"whatsapp"}},
		{"im", "line", "💬", "domain", []string{"line"}},
		{"video", "netflix", "🎬", "classical", []string{"netflix-domain", "netflix-ip"}},
		{"video", "spotify", "🎵", "domain", []string{"spotify"}},
		{"video", "twitter", "📱", "domain", []string{"twitter"}},
		{"video", "instagram", "📷", "domain", []string{"instagram"}},
		{"video", "youtube", "🎬", "domain", []string{"youtube"}},
	} {
		var except []string
		if spec.key == "google" {
			except = []string{"youtube"}
		}
		if err := d.addListRule(spec.owner, spec.key, labels[spec.key], spec.emoji, spec.behavior, spec.sources, except...); err != nil {
			return Definition{}, err
		}
	}
	// Dedicated IM/video destinations must precede Google's broader domains.
	d.Plan.StrategyGroupIDs = []string{id("group:jeemi"), id("group:im"), id("group:video"), id("group:ai")}
	key, emoji, payload := "domestic", "🏠", "payload:\n  - GEOSITE,CN\n  - GEOIP,CN\n"
	if kind == "apps" {
		key, emoji = "appDirect", "📱"
		contents, err := data.ReadFile("data/process-direct-android.yaml")
		if err != nil {
			return Definition{}, fmt.Errorf("invalid_builtin_rules")
		}
		payload = string(contents)
	}
	d.addDirectGroup(key, emoji)
	d.Groups[len(d.Groups)-1].Name = labels[key]
	d.addRule(key, key+"Rules", labels[key+"Rules"], emoji, "classical", payload)
	if kind == "apps" {
		// Explicit domestic app routing wins over embedded third-party domains.
		d.Plan.StrategyGroupIDs = append([]string{id("group:" + key)}, d.Plan.StrategyGroupIDs[:len(d.Plan.StrategyGroupIDs)-1]...)
	}
	d.Plan.DefaultProxySelectorID = id("group:jeemi")
	d.Plan.Match = resources.LocalMatch{Mode: "selector", SelectorID: id("group:jeemi")}
	return d, nil
}
