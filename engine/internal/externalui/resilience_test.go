package externalui

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestRetriesTransientResponseAndTruncatedArchiveFromZero(t *testing.T) {
	data := archiveFixture(t, "dist/index.html", "dist/assets/app.js")
	m, _ := testManager(t, data)
	transport := m.client.Transport
	api, asset := 0, 0
	m.client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		if r.URL.Host == "api.github.com" {
			api++
			if api == 1 {
				return &http.Response{StatusCode: 503, Body: io.NopCloser(strings.NewReader("temporary"))}, nil
			}
		} else {
			asset++
			if asset == 1 {
				return &http.Response{StatusCode: 200, Body: io.NopCloser(bytes.NewReader(data[:len(data)/2]))}, nil
			}
		}
		return transport.RoundTrip(r)
	})
	version, err := m.Ensure(context.Background(), "")
	if err != nil || api != 2 || asset != 2 {
		t.Fatal(version, err, api, asset)
	}
	if m.State().ReceivedBytes != int64(len(data)) {
		t.Fatal("retry counted partial bytes twice")
	}
	if _, err := m.Installed(version); err != nil {
		t.Fatal(err)
	}
}

func TestRetriesAreBoundedAndCancellationInterruptsBackoff(t *testing.T) {
	for _, cancel := range []bool{false, true} {
		m, _ := testManager(t, nil)
		ctx, stop := context.WithCancel(context.Background())
		calls := 0
		m.retryDelay = time.Hour
		if !cancel {
			m.retryDelay = 0
		}
		m.client.Transport = roundTrip(func(*http.Request) (*http.Response, error) {
			calls++
			if cancel {
				stop()
			}
			return &http.Response{StatusCode: 429, Body: io.NopCloser(strings.NewReader("rate limited"))}, nil
		})
		if _, err := m.Ensure(ctx, ""); err == nil {
			t.Fatal("request succeeded")
		}
		stop()
		expected := 3
		if cancel {
			expected = 1
		}
		if calls != expected {
			t.Fatal("unbounded retry", calls)
		}
	}
}

func TestSameSizeCorruptionRepairsOfflineAndLegacyCacheRemainsReadable(t *testing.T) {
	m, calls := testManager(t, archiveFixture(t, "dist/index.html", "dist/assets/app.js"))
	version, err := m.Ensure(context.Background(), "")
	if err != nil {
		t.Fatal(err)
	}
	asset := filepath.Join(m.root, version, "dist", "assets", "app.js")
	if err := os.WriteFile(asset, []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Installed(version); err == nil {
		t.Fatal("same-size corruption was missed")
	}
	session := t.TempDir()
	if err := m.Materialize(context.Background(), version, session); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(filepath.Join(session, filepath.FromSlash(RuntimePath(version)), "assets", "app.js"))
	if string(got) != "fixture" || calls.Load() != 2 {
		t.Fatal("offline repair failed")
	}
	metadataFile := filepath.Join(m.root, version, "metadata.json")
	data, _ := os.ReadFile(metadataFile)
	var meta map[string]any
	json.Unmarshal(data, &meta)
	delete(meta, "sha256")
	data, _ = json.Marshal(meta)
	if err := os.WriteFile(metadataFile, data, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(m.root, version, "archive.zip")); err != nil {
		t.Fatal(err)
	}
	if err := m.Materialize(context.Background(), version, t.TempDir()); err != nil {
		t.Fatal("legacy cache failed", err)
	}
	if err := os.Remove(asset); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Ensure(context.Background(), version); err != nil || calls.Load() != 4 {
		t.Fatal("legacy missing files not downloaded", err)
	}
}

func TestDamagedOfflineArchiveIsNotCopiedAndFailedReinstallKeepsGoodFiles(t *testing.T) {
	m, _ := testManager(t, archiveFixture(t, "index.html"))
	version, err := m.Ensure(context.Background(), "")
	if err != nil {
		t.Fatal(err)
	}
	installed := filepath.Join(m.root, version)
	index := filepath.Join(installed, "dist", "index.html")
	before, _ := os.ReadFile(index)
	archivePath := filepath.Join(installed, "archive.zip")
	archive, _ := os.ReadFile(archivePath)
	broken := bytes.Repeat([]byte("x"), len(archive))
	if err := os.WriteFile(archivePath, broken, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(index); err != nil {
		t.Fatal(err)
	}
	session := t.TempDir()
	if m.Materialize(context.Background(), version, session) == nil {
		t.Fatal("corrupt archive repaired files")
	}
	if _, err := os.Stat(filepath.Join(session, RuntimePath(version), "index.html")); !os.IsNotExist(err) {
		t.Fatal("bad files escaped")
	}
	if err := os.WriteFile(index, before, 0600); err != nil {
		t.Fatal(err)
	}
	transport := m.client.Transport
	m.client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		if r.URL.Host != "api.github.com" {
			return &http.Response{StatusCode: 200, Body: io.NopCloser(bytes.NewReader(broken))}, nil
		}
		return transport.RoundTrip(r)
	})
	if _, err := m.Reinstall(context.Background(), version); err == nil {
		t.Fatal("bad reinstall succeeded")
	}
	after, _ := os.ReadFile(index)
	if !bytes.Equal(before, after) {
		t.Fatal("failed reinstall replaced index")
	}
	entries, _ := os.ReadDir(m.staging)
	if len(entries) != 0 {
		t.Fatal("staging leaked")
	}
	// A failed disk write must also leave the old installation intact.
	if err := os.Remove(m.staging); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(m.staging, []byte("blocked"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Reinstall(context.Background(), version); err == nil {
		t.Fatal("unwritable staging accepted")
	}
	if _, err := m.Installed(version); err != nil {
		t.Fatal("old files damaged", err)
	}
}

func TestEntrypointRejectsMissingScriptAndAcceptsRelativeAssets(t *testing.T) {
	for _, script := range []string{"./assets/app.js", "./missing.js", "/assets/app.js"} {
		var data bytes.Buffer
		writer := zip.NewWriter(&data)
		index, _ := writer.Create("dist/index.html")
		index.Write([]byte(`<html><script type="module" src="` + script + `"></script></html>`))
		asset, _ := writer.Create("dist/assets/app.js")
		asset.Write([]byte("fixture"))
		writer.Close()
		m, _ := testManager(t, data.Bytes())
		_, err := m.Ensure(context.Background(), "")
		if (err == nil) != (script == "./assets/app.js") {
			t.Fatal(script, err)
		}
	}
}

func TestConcurrentReinstallAndSessionCopyAcrossManagers(t *testing.T) {
	m, _ := testManager(t, archiveFixture(t, "dist/index.html", "dist/assets/app.js"))
	version, err := m.Ensure(context.Background(), "")
	if err != nil {
		t.Fatal(err)
	}
	other := NewManager(Options{DataDirectory: t.TempDir()})
	other.root = m.root
	var group sync.WaitGroup
	group.Add(2)
	go func() {
		defer group.Done()
		for i := 0; i < 12; i++ {
			if _, err := m.Reinstall(context.Background(), version); err != nil {
				t.Error(err)
				return
			}
		}
	}()
	go func() {
		defer group.Done()
		for i := 0; i < 12; i++ {
			if err := other.Materialize(context.Background(), version, t.TempDir()); err != nil {
				t.Error(err)
				return
			}
		}
	}()
	group.Wait()
}
