package externalui

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestAndroidSessionsReuseInstalledDashboardWithoutNetworkOrWriteback(t *testing.T) {
	manager, calls := testManager(t, archiveFixture(t, "dist/index.html", "dist/assets/app.js"))
	version, err := manager.Ensure(context.Background(), "")
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		session := t.TempDir()
		if err := manager.Materialize(context.Background(), version, session); err != nil {
			t.Fatal(err)
		}
		name := filepath.Join(session, filepath.FromSlash(RuntimePath(version)), "index.html")
		if content, err := os.ReadFile(name); err != nil || string(content) != "fixture" {
			t.Fatal("session has no valid UI", err)
		}
		if err := os.WriteFile(name, []byte("session modification"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	if calls.Load() != 2 {
		t.Fatal("session preparation downloaded UI")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if manager.Materialize(ctx, version, t.TempDir()) == nil {
		t.Fatal("cancelled UI copy succeeded")
	}
}
