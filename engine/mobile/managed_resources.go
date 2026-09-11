package mobile

import (
	"context"
	"fmt"
	"path/filepath"
	"time"

	"jeemi-android/engine/internal/config/document"
	"jeemi-android/engine/internal/externalui"
	"jeemi-android/engine/internal/localscript"
	"jeemi-android/engine/internal/providercache"
)

// Android passes only app-owned directories to these lifecycle bridges.
type ProviderCacheSession struct{ session *providercache.Session }

func NewProviderCacheSession(home, privateRoot string) *ProviderCacheSession {
	return &ProviderCacheSession{providercache.NewSession(home, providercache.Store{Root: privateRoot, Path: "provider-cache"})}
}
func (s *ProviderCacheSession) Prepare() error {
	if s.session.Prepare("config.yaml") != nil {
		return fmt.Errorf("provider_cache_prepare_failed")
	}
	return nil
}
func (s *ProviderCacheSession) Save() {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = s.session.Save(ctx)
}

// Create cancellation before dispatching IO, so a fast cancellation cannot race
// a request that has not entered the download method yet.
type ResourceDownload struct {
	ctx    context.Context
	cancel context.CancelFunc
}

func NewResourceDownload() *ResourceDownload {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	return &ResourceDownload{ctx, cancel}
}
func (r *ResourceDownload) Cancel() { r.cancel() }
func (r *ResourceDownload) Script(address string) (string, error) {
	content, err := (&localscript.HTTPFetcher{}).Fetch(r.ctx, address)
	if err != nil {
		return "", fmt.Errorf("script_download_failed")
	}
	return content, nil
}
func NormalizeScriptURL(address string) (string, error) {
	url, err := localscript.NormalizeSourceURL(address)
	if err != nil {
		return "", fmt.Errorf("invalid_script_url")
	}
	return url, nil
}
func (r *ResourceDownload) Dashboard(privateRoot, version string) (string, error) {
	if !filepath.IsAbs(privateRoot) {
		return "", fmt.Errorf("invalid_resource_directory")
	}
	result, err := externalui.NewManager(externalui.Options{DataDirectory: privateRoot}).Ensure(r.ctx, version)
	if err != nil {
		return "", fmt.Errorf("dashboard_download_failed")
	}
	return result, nil
}
func MaterializeDashboard(privateRoot, version, home string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if externalui.NewManager(externalui.Options{DataDirectory: privateRoot}).Materialize(ctx, version, home) != nil {
		return fmt.Errorf("dashboard_resources_unavailable")
	}
	return nil
}

// Both candidate and session must discard untrusted source dashboard settings.
// The session first applies AndroidSessionConfiguration, then this final layer.
func AndroidDashboardConfiguration(configuration, version string) (string, error) {
	if version != "" && !externalui.ValidVersion(version) {
		return "", fmt.Errorf("invalid_dashboard_version")
	}
	doc, err := document.Parse([]byte(configuration))
	if err != nil {
		return "", fmt.Errorf("invalid_dashboard_configuration")
	}
	root := document.Root(doc)
	for _, key := range []string{"external-ui", "external-ui-url", "external-ui-name", "external-controller-cors"} {
		if err := document.DeleteMappingPath(root, "/"+key); err != nil {
			return "", err
		}
	}
	if version != "" {
		for _, pair := range [][2]string{{"external-ui", externalui.RuntimePath(version)}, {"external-ui-url", externalui.ArchiveURL(version)}} {
			node, _ := document.ParseValue(fmt.Sprintf("%q", pair[1]))
			if err := document.SetMappingPath(root, "/"+pair[0], node); err != nil {
				return "", err
			}
		}
	}
	result, err := document.Encode(doc)
	return string(result), err
}
