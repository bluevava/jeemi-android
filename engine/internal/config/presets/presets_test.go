package presets

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
	"jeemi-android/engine/internal/config/resources"
)

func labels() map[string]string {
	result := map[string]string{}
	for _, key := range strings.Fields("general apps jeemi ai im video domestic appDirect domesticRules appDirectRules github apple aiDomains google telegramDomains telegramIP discord whatsapp line netflix spotify twitter instagram youtube") {
		result[key] = key
	}
	return result
}

func normalized(t *testing.T, p Definition) resources.State {
	t.Helper()
	state := resources.State{}
	for _, g := range p.Groups {
		value, err := resources.NormalizeGroup(g)
		if err != nil {
			t.Fatal(err)
		}
		state.StrategyGroups = append(state.StrategyGroups, value)
	}
	for _, r := range p.Rules {
		value, err := resources.NormalizeRuleSet(r)
		if err != nil {
			t.Fatal(err)
		}
		state.RuleSets = append(state.RuleSets, value)
	}
	if err := resources.ValidateState(state); err != nil {
		t.Fatal(err)
	}
	return state
}

func TestAndroidPresetsAreOfflineAndPreserveRoutingOrder(t *testing.T) {
	for _, kind := range []string{"general", "apps"} {
		t.Run(kind, func(t *testing.T) {
			p, err := Load(kind, labels())
			if err != nil {
				t.Fatal(err)
			}
			state := normalized(t, p)
			if len(state.StrategyGroups) != 5 || len(state.RuleSets) != 15 {
				t.Fatal("unexpected resource count")
			}
			input := []byte("proxies: [{name: JP, type: socks5, server: example.invalid, port: 1080}]\nproxy-providers: {remote: {type: http, url: 'https://example.invalid/nodes', path: ./nodes.yaml}}\nproxy-groups: [{name: old, type: select, proxies: [DIRECT]}]\nrules: ['MATCH,old']\n")
			input, err = resources.PrepareSource(input, p.Plan)
			if err != nil {
				t.Fatal(err)
			}
			output, err := resources.Apply(input, state, p.Plan)
			if err != nil {
				t.Fatal(err)
			}
			var config struct {
				Groups []struct {
					Name, Type, DefaultSelected string
					Hidden                      bool
					Proxies, Use                []string
				} `yaml:"proxy-groups"`
				Rules     []string `yaml:"rules"`
				Providers map[string]struct {
					Type, Behavior, URL string
					Payload             []string
				} `yaml:"rule-providers"`
			}
			if err = yaml.Unmarshal(output, &config); err != nil {
				t.Fatal(err)
			}
			if len(config.Groups) != 8 || config.Rules[len(config.Rules)-1] != "MATCH,🚀 jeemi" {
				t.Fatalf("invalid routing: %s", output)
			}
			for _, g := range config.Groups {
				if len(g.Use) != 1 || g.Use[0] != "remote" {
					t.Fatal("subscription nodes/providers lost")
				}
				if g.Type == "select" {
					if len(g.Proxies) != 2 || g.Proxies[0] != g.Name+" Auto" || g.Proxies[1] != "JP" {
						t.Fatal("missing paired automatic choice")
					}
				} else if g.Type != "url-test" || !g.Hidden || len(g.Proxies) != 1 || g.Proxies[0] != "JP" {
					t.Fatal("invalid automatic selector")
				}
			}
			for _, r := range config.Providers {
				if r.Type != "inline" || r.URL != "" || len(r.Payload) == 0 {
					t.Fatal("rules must be fixed offline payloads")
				}
			}
			for domain, want := range map[string]string{
				"www.instagram.com": "🎬 video", "www.youtube.com": "🎬 video", "r1.googlevideo.com": "🎬 video", "i.ytimg.com": "🎬 video",
				"www.netflix.com": "🎬 video", "open.spotify.com": "🎬 video", "x.com": "🎬 video", "discord-attachments-uploads-prd.storage.googleapis.com": "✈️ im",
				"api.telegram.org": "✈️ im", "discord.com": "✈️ im", "whatsapp.com": "✈️ im", "line.me": "✈️ im",
				"www.google.com": "🤖 ai", "claude.ai": "🤖 ai", "github.com": "🚀 jeemi", "www.apple.com": "🚀 jeemi",
			} {
				found := ""
				for _, rule := range config.Rules {
					parts := strings.Split(rule, ",")
					if parts[0] != "RULE-SET" {
						continue
					}
					provider := config.Providers[parts[1]]
					for _, match := range provider.Payload {
						if provider.Behavior == "classical" {
							if strings.HasPrefix(match, "DOMAIN-SUFFIX,") {
								match = "+." + strings.TrimPrefix(match, "DOMAIN-SUFFIX,")
							} else if strings.HasPrefix(match, "DOMAIN,") {
								match = strings.TrimPrefix(match, "DOMAIN,")
							} else {
								continue
							}
						}
						base := strings.TrimPrefix(match, "+.")
						if match == domain || (strings.HasPrefix(match, "+.") && (domain == base || strings.HasSuffix(domain, "."+base))) {
							found = parts[2]
							break
						}
					}
					if found != "" {
						break
					}
				}
				if found != want {
					t.Fatalf("first match of %s = %s, want %s", domain, found, want)
				}
			}
			text := string(output)
			if kind == "general" {
				if !strings.Contains(text, "GEOSITE,CN,DIRECT") || !strings.Contains(text, "GEOIP,CN,DIRECT") || strings.Contains(text, "PROCESS-NAME") {
					t.Fatal("general GEO routing changed")
				}
			} else {
				if strings.Contains(text, "GEOSITE") || strings.Contains(text, "GEOIP") || strings.Contains(text, "IP-ASN") {
					t.Fatal("app template must not route all CN directly")
				}
				last := state.RuleSets[len(state.RuleSets)-1]
				valid := regexp.MustCompile(`^PROCESS-NAME,[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`)
				for _, rule := range last.Payload {
					if !valid.MatchString(rule) {
						t.Fatalf("not an exact Android package rule: %s", rule)
					}
				}
				if len(last.Payload) < 10 || !strings.Contains(text, "PROCESS-NAME,com.tencent.mm,DIRECT") {
					t.Fatal("missing app defaults")
				}
				if !strings.HasPrefix(config.Rules[0], "PROCESS-NAME,") || !strings.HasSuffix(config.Rules[0], ",DIRECT") {
					t.Fatal("app rules must take priority")
				}
			}
		})
	}
}

func TestBundledListChecksums(t *testing.T) {
	raw, err := data.ReadFile("data/sources.json")
	if err != nil {
		t.Fatal(err)
	}
	var manifest struct {
		Files []struct {
			File, SHA256 string
			Bytes        int
		}
	}
	if json.Unmarshal(raw, &manifest) != nil || len(manifest.Files) != 15 {
		t.Fatal("invalid snapshot manifest")
	}
	for _, item := range manifest.Files {
		body, err := data.ReadFile("data/" + item.File)
		if err != nil || len(body) != item.Bytes || fmt.Sprintf("%x", sha256.Sum256(body)) != item.SHA256 {
			t.Fatalf("snapshot changed: %s", item.File)
		}
	}
}

func TestDisplayEmojiDoesNotChangeRulesOrLogicalGroupRouting(t *testing.T) {
	p, err := Load("apps", labels())
	if err != nil {
		t.Fatal(err)
	}
	input := []byte("proxies: [{name: JP, type: socks5, server: example.invalid, port: 1080}]\nrules: ['MATCH,DIRECT']\n")
	before, err := resources.Apply(input, normalized(t, p), p.Plan)
	if err != nil {
		t.Fatal(err)
	}
	for i := range p.Rules {
		p.Rules[i].Emoji = "🐱"
	}
	for i := range p.Groups {
		if p.Groups[i].Kind == resources.StrategyGroupKindRule {
			p.Groups[i].Emoji = "🐱"
		}
	}
	after, err := resources.Apply(input, normalized(t, p), p.Plan)
	if err != nil {
		t.Fatal(err)
	}
	if string(before) != string(after) {
		t.Fatal("display-only Emoji changed the runtime configuration")
	}
}
