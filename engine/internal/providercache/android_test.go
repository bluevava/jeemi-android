package providercache

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestAndroidCachesProxyProvidersAndExpandsSharedAnchors(t *testing.T) {
	source := testConfiguration + "\nproxy-providers:\n  one: &source {type: http, url: 'https://example.test/nodes?secret=one', interval: 86400}\n  two: *source\n"
	_, entries, err := rewrite([]byte(source))
	if err != nil || len(entries) != 3 {
		t.Fatal("provider alias expansion failed", err)
	}
	seen := map[string]bool{}
	store := Store{Root: t.TempDir(), Path: "providers"}
	first, _ := sessionFixture(t, store, source)
	for _, entry := range entries {
		if seen[entry.key] || len(entry.key) != 64 {
			t.Fatal("provider namespaces collided")
		}
		seen[entry.key] = true
		writeProvider(t, first.workspace, sessionDirectory+"/"+entry.key, "proxies: []\n", time.Now().Add(-time.Hour).Truncate(time.Second))
	}
	if err := first.Save(context.Background()); err != nil {
		t.Fatal(err)
	}
	second, _ := sessionFixture(t, store, strings.ReplaceAll(source, "interval: 86400", "interval: 3600"))
	for key := range seen {
		if content, err := os.ReadFile(filepath.Join(second.workspace, sessionDirectory, key)); err != nil || string(content) != "proxies: []\n" {
			t.Fatal("provider cache not restored", err)
		}
	}
	changed := strings.ReplaceAll(source, "secret=one", "secret=two")
	_, altered, err := rewrite([]byte(changed))
	if err != nil || seen[altered[1].key] || seen[altered[2].key] {
		t.Fatal("different credentials shared a cache")
	}
}

func TestAndroidNeverSeedsFromPrivateSessionConfiguration(t *testing.T) {
	workspace := t.TempDir()
	source := strings.ReplaceAll(testConfiguration, "./rules/google.yaml", "config.yaml")
	os.WriteFile(filepath.Join(workspace, "config.yaml"), []byte(source), 0600)
	session := NewSession(workspace, Store{Root: t.TempDir(), Path: "providers"})
	if err := session.Prepare("config.yaml"); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(filepath.Join(workspace, sessionDirectory))
	if err != nil || len(entries) != 0 {
		t.Fatal("private YAML seeded a provider cache", err)
	}
}
