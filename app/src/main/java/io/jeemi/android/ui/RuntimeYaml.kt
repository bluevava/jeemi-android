package io.jeemi.android.ui

import org.json.JSONObject

// Display serializer only; parsing, normalization and limits remain in Go.
// Android's JSON writer escapes '/', which the shared YAML reader rejects.
// Also escape YAML line-break characters so list values round-trip unchanged.
internal fun runtimeStringSequence(values: List<String>): String = values.joinToString("\n") { value ->
    "- " + JSONObject.quote(value).replace("\\/", "/")
        .replace("\u0085", "\\u0085").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}
