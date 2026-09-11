package providercache

import (
	"encoding/hex"
	"os"
	"path"
	"sort"
)

// Only the app-owned hash namespace is eligible. Session files are independent,
// so removing an old persistent entry cannot affect an active core.
func (s Store) prune() {
	root, err := os.OpenRoot(s.Root)
	if err != nil {
		return
	}
	defer root.Close()
	directory, err := root.Open(s.Path)
	if err != nil {
		return
	}
	entries, err := directory.ReadDir(-1)
	directory.Close()
	if err != nil {
		return
	}
	type cached struct {
		name           string
		size, modified int64
	}
	files := []cached{}
	for _, entry := range entries {
		if len(entry.Name()) != 64 {
			continue
		}
		if _, err := hex.DecodeString(entry.Name()); err != nil {
			continue
		}
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		files = append(files, cached{entry.Name(), info.Size(), info.ModTime().UnixNano()})
	}
	sort.Slice(files, func(i, j int) bool { return files[i].modified > files[j].modified })
	var total int64
	for index, file := range files {
		total += file.size
		if index >= 1024 || total > maxSessionBytes {
			_ = root.Remove(path.Join(s.Path, file.name))
		}
	}
}
