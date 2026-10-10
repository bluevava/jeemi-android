package externalui

import (
	"context"
	"fmt"
	"io"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"

	"golang.org/x/net/html"
)

// Detect incomplete bundles before making them visible. Only local entrypoint
// scripts/styles are checked; no external URL is ever fetched by validation.
func validateEntrypoint(ctx context.Context, directory string, files map[string]int64) error {
	if files["index.html"] <= 0 || files["index.html"] > 1<<20 {
		return fmt.Errorf("invalid UI index size")
	}
	input, err := os.Open(filepath.Join(directory, "index.html"))
	if err != nil {
		return err
	}
	defer input.Close()
	tokens := html.NewTokenizer(io.LimitReader(contextReader{ctx, input}, (1<<20)+1))
	seenHTML := false
	for {
		switch tokens.Next() {
		case html.ErrorToken:
			if tokens.Err() != io.EOF {
				return fmt.Errorf("invalid UI index")
			}
			if !seenHTML {
				return fmt.Errorf("UI index is not HTML")
			}
			return nil
		case html.StartTagToken, html.SelfClosingTagToken:
			token := tokens.Token()
			if token.Data == "html" {
				seenHTML = true
			}
			attributes := map[string]string{}
			for _, a := range token.Attr {
				attributes[a.Key] = a.Val
			}
			address := ""
			if token.Data == "script" {
				address = attributes["src"]
			}
			if token.Data == "link" && (attributes["rel"] == "stylesheet" || attributes["rel"] == "modulepreload") {
				address = attributes["href"]
			}
			if address == "" {
				continue
			}
			u, err := url.Parse(address)
			if err != nil {
				return fmt.Errorf("invalid UI asset URL")
			}
			if u.IsAbs() || u.Host != "" {
				continue
			}
			name := u.Path
			if strings.HasPrefix(name, "/") {
				if !strings.HasPrefix(name, "/ui/") {
					return fmt.Errorf("UI asset outside /ui/")
				}
				name = strings.TrimPrefix(name, "/ui/")
			}
			name = path.Clean(name)
			if !safeName(name) || files[name] <= 0 {
				return fmt.Errorf("UI entrypoint asset is missing")
			}
		}
	}
}
