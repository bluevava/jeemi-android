package chainproxy

import (
	"reflect"
	"testing"
)

func TestMobileNameFilterInput(t *testing.T) {
	names := []string{"Pro.HK.gm", "Pro.HK.ev", "Pro.JP.gm"}
	for _, test := range []struct {
		query string
		want  []string
	}{
		{"hk ", names[:2]},
		{" hk ", names[:2]},
		{"\u3000hk\u3000", names[:2]},
		{"\u00a0hk\u00a0", names[:2]},
		{"\ufeffhk\ufeff", names[:2]},
		{"hk＆", names[:2]},
		{"hk & gm", names[:1]},
		{"hk＆gm", names[:1]},
		{"\u3000hk\u3000＆\u3000gm\u3000", names[:1]},
		{"\ufeffhk\ufeff & \ufeffgm\ufeff", names[:1]},
		{"hk｜jp ＆gm！ev", []string{names[0], names[2]}},
		{"！ev", []string{names[0], names[2]}},
	} {
		t.Run(test.query, func(t *testing.T) {
			matcher := ParseNameFilter(test.query)
			var got []string
			for _, name := range names {
				if matcher.Match(name) {
					got = append(got, name)
				}
			}
			if !reflect.DeepEqual(got, test.want) {
				t.Fatalf("got %v; want %v", got, test.want)
			}
		})
	}
	if !ParseNameFilter("\u3000｜ ＆ ！\ufeff").Empty() {
		t.Fatal("operators and edge whitespace should not create a keyword")
	}
	if ParseNameFilter("hk gm").Match(names[0]) || ParseNameFilter("tokyo  node").Match("Tokyo node") {
		t.Fatal("internal keyword text must remain literal")
	}
}
