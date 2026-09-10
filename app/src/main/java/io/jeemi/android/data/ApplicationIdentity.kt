package io.jeemi.android.data

private val applicationPackagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")

// Keep the exact identifier. Trimming a process name, stripping :service or
// converting an executable path could assign the connection to another app.
internal fun isApplicationPackage(value: String): Boolean =
    value.length in 1..255 && applicationPackagePattern.matches(value)

internal fun unambiguousApplicationPackage(packages: Array<out String?>?): String {
    val first = packages?.firstOrNull() ?: return ""
    return if (isApplicationPackage(first) && packages.all { it == first }) first else ""
}

// Application labels are optional presentation data, possibly styled. Read a
// bounded prefix without converting an arbitrary CharSequence to a huge String.
internal fun safeApplicationLabel(value: CharSequence?, limit: Int = 160): String {
    if (value == null) return ""
    val result = StringBuilder(minOf(limit, 160))
    val end = minOf(value.length, 1024)
    var index = 0
    while (index < end && result.length < limit) {
        val char = value[index++]
        when {
            char == '\u0000' || char in '\u202a'..'\u202e' || char in '\u2066'..'\u2069' ||
                char == '\u200e' || char == '\u200f' || char == '\u061c' -> Unit
            char.isWhitespace() || char.isISOControl() -> if (result.isNotEmpty() && result.last() != ' ') result.append(' ')
            char.isHighSurrogate() -> {
                if (index < end && value[index].isLowSurrogate()) {
                    if (result.length + 2 > limit) break
                    result.append(char).append(value[index++])
                }
            }
            char.isLowSurrogate() -> Unit
            else -> result.append(char)
        }
    }
    return result.toString().trim()
}
