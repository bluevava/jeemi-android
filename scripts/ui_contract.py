"""Check bilingual resources and the mandatory Jeemi UI contract without a device."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"


def resources(folder):
    result = {}
    for path in sorted((RES / folder).glob("*.xml")):
        for item in ET.parse(path).getroot():
            if item.tag not in ("string", "plurals", "string-array"):
                continue
            key = (item.tag, item.attrib["name"])
            if key in result:
                raise ValueError(f"Duplicate resource: {folder}/{key}")
            values = ["".join(child.itertext()) for child in item] if len(item) else ["".join(item.itertext())]
            if not values or any(not value.strip() for value in values):
                raise ValueError(f"Empty resource: {folder}/{key}")
            result[key] = values
    return result


def main():
    english, chinese = resources("values"), resources("values-zh-rCN")
    if english.keys() != chinese.keys():
        raise ValueError(f"Language keys differ: {english.keys() ^ chinese.keys()}")
    placeholders = re.compile(r"%(?:\d+\$)?[dsf]")
    for key, values in english.items():
        if {tuple(sorted(placeholders.findall(v))) for v in values} != {
            tuple(sorted(placeholders.findall(v))) for v in chinese[key]
        }:
            raise ValueError(f"Incompatible format arguments: {key}")
    # A topic must translate as a whole; never mix a specific purpose with generic notes.
    names = {key[1] for key in english}
    for name in names:
        if name.endswith("_purpose"):
            prefix = name.removesuffix("_purpose")
            if not {prefix + "_scenarios", prefix + "_cautions"} <= names:
                raise ValueError(f"Incomplete three-section help: {prefix}")
    sources = ROOT / "app/src/main/java/io/jeemi/android/ui"
    for path in sources.rglob("*.kt"):
        code = path.read_text(encoding="utf-8-sig")
        if re.search(r'\bText\(\s*"[^"\n]*[A-Za-z\u4e00-\u9fff]', code):
            raise ValueError(f"Hardcoded product text in {path.relative_to(ROOT)}")
        # Explicitly rejected old UI elements cannot silently return during migration.
        if re.search(r'\bNavigationRail(?:Item)?\(', code) or re.search(r'R\.string\.(traffic|upload|download)\b', code):
            raise ValueError(f"Desktop navigation or traffic statistics in {path.relative_to(ROOT)}")
        inline = set(re.findall(r'Text\(stringResource\(R\.string\.(\w*(?:_note|_intro|_pending))\b', code))
        # Destructive-action confirmations are not functional annotations.
        if inline - {"delete_resource_note", "chain_delete_note"}:
            raise ValueError(f"Inline feature explanation in {path.relative_to(ROOT)}; use FeatureHelp")
    ns = "{http://schemas.android.com/apk/res/android}"
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
    activity = next(a for a in manifest.findall("application/activity") if a.get(ns + "name") == ".MainActivity")
    if activity.get(ns + "screenOrientation") != "portrait":
        raise ValueError("MainActivity must request portrait")
    if not {"locale", "layoutDirection"} <= set(activity.get(ns + "configChanges", "").split("|")):
        raise ValueError("Compose must handle locale and layoutDirection changes in place")
    if not any(p.get(ns + "name") == "android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY" and p.get(ns + "value") == "true" for p in activity.findall("property")):
        raise ValueError("Missing API 36 portrait compatibility property")
    # Locale switching uses AppCompatActivity, including when Android selects night resources.
    for theme_file in RES.glob("values*/themes.xml"):
        for theme in ET.parse(theme_file).getroot().findall("style"):
            if theme.get("name") == "Theme.Jeemi" and not theme.get("parent", "").startswith("Theme.AppCompat."):
                raise ValueError(f"Activity theme is incompatible with app languages: {theme_file}")
    print(f"UI contract passed: {len(english)} bilingual resources; complete help, portrait, no traffic statistics.")


if __name__ == "__main__":
    main()
