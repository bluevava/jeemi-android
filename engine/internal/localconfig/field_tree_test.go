package localconfig

import (
	"testing"

	"jeemi-android/engine/internal/config/resources"
	"jeemi-android/engine/internal/config/schema"
)

func TestEveryLockedCatalogFieldRejectsLocalInjectionAndDisabledDraft(t *testing.T) {
	for _, category := range schema.Current().Categories {
		for _, field := range category.Fields {
			if !field.Locked {
				continue
			}
			t.Run(field.Path, func(t *testing.T) {
				entry := Field{Path: field.Path, ValueYAML: "false"}
				for _, disabled := range []bool{false, true} {
					config := Config{Name: "Locked", ResourcePlan: resources.DefaultPlan()}
					if disabled {
						config.DisabledFields = []Field{entry}
					} else {
						config.Fields = []Field{entry}
					}
					want := "field_unavailable"
					if disabled {
						want = "invalid_disabled_field"
					}
					if _, err := Prepare(config); err == nil || err.Error() != want {
						t.Fatalf("locked field did not reject at the ownership boundary, disabled=%v, err=%v", disabled, err)
					}
				}
			})
		}
	}
}
