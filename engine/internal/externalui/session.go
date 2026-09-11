package externalui

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
)

// Materialize uses only installed resources. The core can update its disposable
// copy without modifying the persistent, verified dashboard.
func (m *Manager) Materialize(ctx context.Context, version, workspace string) error {
	if _, err := m.Installed(version); err != nil {
		return err
	}
	source, err := os.OpenRoot(filepath.Join(m.root, version))
	if err != nil {
		return err
	}
	defer source.Close()
	raw, err := source.ReadFile("metadata.json")
	var meta metadata
	if err != nil || len(raw) > 1<<20 || json.Unmarshal(raw, &meta) != nil {
		return fmt.Errorf("invalid UI metadata")
	}
	target, err := os.OpenRoot(workspace)
	if err != nil {
		return err
	}
	defer target.Close()
	for name, size := range meta.Files {
		if err := ctx.Err(); err != nil {
			return err
		}
		if !safeName(name) {
			return fmt.Errorf("invalid UI file")
		}
		destination := RuntimePath(version) + "/" + name
		if err := target.MkdirAll(path.Dir(destination), 0700); err != nil {
			return err
		}
		input, err := source.Open("dist/" + name)
		if err != nil {
			return err
		}
		output, err := target.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if err != nil {
			input.Close()
			return err
		}
		n, copyErr := io.Copy(output, io.LimitReader(input, size+1))
		input.Close()
		closeErr := output.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
		if n != size {
			return fmt.Errorf("UI file changed")
		}
	}
	return ctx.Err()
}
