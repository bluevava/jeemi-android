package externalui

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

func fileHash(ctx context.Context, root *os.Root, name string, size int64) (string, error) {
	input, err := root.Open(name)
	if err != nil {
		return "", err
	}
	defer input.Close()
	hash := sha256.New()
	n, err := io.Copy(hash, io.LimitReader(contextReader{ctx, input}, size+1))
	if err != nil {
		return "", err
	}
	if n != size {
		return "", fmt.Errorf("UI file changed")
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func hashFiles(ctx context.Context, directory string, files map[string]int64) (map[string]string, error) {
	root, err := os.OpenRoot(directory)
	if err != nil {
		return nil, err
	}
	defer root.Close()
	hashes := make(map[string]string, len(files))
	for name, size := range files {
		digest, err := fileHash(ctx, root, name, size)
		if err != nil {
			return nil, err
		}
		hashes[name] = digest
	}
	return hashes, nil
}

// A normal VPN start stays offline. If the extracted files are damaged, repair
// from the original archive only after rechecking its release digest and size.
func (m *Manager) ensureLocal(ctx context.Context, version string) (err error) {
	if err := ctx.Err(); err != nil {
		return err
	}
	cacheFiles.Lock()
	_, err = m.installedContext(ctx, version, true)
	cacheFiles.Unlock()
	if err == nil {
		return nil
	}
	if !ValidVersion(version) {
		return err
	}
	ctx, done, err := m.begin(ctx, "installing", version)
	if err != nil {
		return err
	}
	defer func() { done(err) }()
	root, err := os.OpenRoot(filepath.Join(m.root, version))
	if err != nil {
		return err
	}
	defer root.Close()
	input, err := root.Open("metadata.json")
	if err != nil {
		return err
	}
	raw, err := io.ReadAll(io.LimitReader(input, (1<<20)+1))
	input.Close()
	var meta metadata
	if err != nil || len(raw) > 1<<20 || json.Unmarshal(raw, &meta) != nil || meta.Release.Version != version ||
		meta.Release.Size <= 0 || meta.Release.Size > maxArchiveBytes || len(meta.Release.SHA256) != 64 {
		return fmt.Errorf("no verified archive metadata")
	}
	archive, err := root.Open("archive.zip")
	if err != nil {
		return err
	}
	defer archive.Close()
	// Close all old cache handles before replacing its directory (also Windows).
	root.Close()
	return m.stageInstall(ctx, meta.Release, func(destination string) error {
		defer archive.Close()
		output, err := os.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if err != nil {
			return err
		}
		defer output.Close()
		hash := sha256.New()
		n, err := io.Copy(io.MultiWriter(output, hash), io.LimitReader(contextReader{ctx, archive}, meta.Release.Size+1))
		if err != nil {
			return err
		}
		if n != meta.Release.Size || hex.EncodeToString(hash.Sum(nil)) != meta.Release.SHA256 {
			return fmt.Errorf("local UI archive checksum mismatch")
		}
		if err := output.Sync(); err != nil {
			return err
		}
		return output.Close()
	})
}
