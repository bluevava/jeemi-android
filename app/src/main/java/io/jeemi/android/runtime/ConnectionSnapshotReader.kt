package io.jeemi.android.runtime

import android.util.JsonReader
import android.util.JsonToken
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

internal const val CONNECTION_RESPONSE_LIMIT = 8 * 1024 * 1024
internal class ConnectionSnapshotLimit : IOException()

// Stream directly from HTTP: no whole-response byte array, String, JSONObject
// tree or list containing all core connections exists at any point.
internal fun readConnectionSnapshot(
    input: InputStream,
    previous: List<ConnectionRecord> = emptyList(),
    checkActive: () -> Unit = {},
): ConnectionSnapshot {
    val tracked = previous.mapTo(HashSet()) { it.id }
    val preferred = previous.asSequence().filterNot { it.ended }.map { it.id }.toHashSet()
    val present = HashSet<String>()
    val retained = LinkedHashMap<String, ConnectionRecord>()
    val candidates = LinkedHashMap<String, ConnectionRecord>()
    var count = 0
    var found = false
    JsonReader(ConnectionInput(input, checkActive).reader(Charsets.UTF_8)).use { reader ->
        reader.beginObject()
        while (reader.hasNext()) {
            checkActive()
            if (reader.nextName() != "connections") { reader.skipValue(); continue }
            check(!found); found = true
            if (reader.peek() == JsonToken.NULL) { reader.nextNull(); continue }
            reader.beginArray()
            while (reader.hasNext()) {
                checkActive()
                if (++count > 100_000) throw ConnectionSnapshotLimit()
                val row = reader.connection()
                if (row.id in tracked) present.add(row.id)
                if (row.id in preferred) {
                    retained[row.id] = row
                    if (retained.size + candidates.size > ACTIVE_CONNECTION_LIMIT) {
                        val iterator = candidates.entries.iterator()
                        check(iterator.hasNext()); iterator.next(); iterator.remove()
                    }
                } else if (retained.size + candidates.size < ACTIVE_CONNECTION_LIMIT) candidates[row.id] = row
            }
            reader.endArray()
        }
        reader.endObject()
        // A missing/truncated array must never turn all prior connections into
        // ended history. Only a completely read, valid snapshot is published.
        check(found && reader.peek() == JsonToken.END_DOCUMENT)
    }
    // Keep the surviving display set in its previous order even if the core's
    // map iteration order changes. Fill free slots from this complete snapshot.
    val rows = ArrayList<ConnectionRecord>(retained.size + candidates.size)
    for (row in previous) retained.remove(row.id)?.let(rows::add)
    rows.addAll(candidates.values)
    return ConnectionSnapshot(rows, count, present)
}

private class ConnectionInput(input: InputStream, private val checkActive: () -> Unit) : FilterInputStream(input) {
    private var remaining = CONNECTION_RESPONSE_LIMIT
    override fun read(): Int {
        checkActive()
        val value = `in`.read()
        if (value >= 0 && --remaining < 0) throw ConnectionSnapshotLimit()
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkActive()
        val size = `in`.read(buffer, offset, minOf(length, remaining + 1))
        if (size > 0) { remaining -= size; if (remaining < 0) throw ConnectionSnapshotLimit() }
        return size
    }
}

private fun JsonReader.text(): String {
    if (peek() == JsonToken.NULL) { nextNull(); return "" }
    return nextString().also { if (it.length > 4096) throw ConnectionSnapshotLimit() }
}

private fun JsonReader.counter(fallback: Long = 0): Long {
    if (peek() == JsonToken.NULL) { nextNull(); return fallback }
    return nextLong()
}

private fun JsonReader.connection(): ConnectionRecord {
    var id = ""; var host = ""; var destinationIP = ""; var destinationPort = ""
    var sourceIP = ""; var sourcePort = ""; var network = ""; var inbound = ""
    var process = ""; var uid: Long? = null; var rule = ""; var rulePayload = ""
    var start = ""; var upload = 0L; var download = 0L; var metadata = false
    val chains = ArrayList<String>()
    beginObject()
    while (hasNext()) when (nextName()) {
        "id" -> id = text()
        "metadata" -> {
            metadata = true; beginObject()
            while (hasNext()) when (nextName()) {
                "host" -> host = text()
                "destinationIP" -> destinationIP = text()
                "destinationPort" -> destinationPort = text()
                "sourceIP" -> sourceIP = text()
                "sourcePort" -> sourcePort = text()
                "network" -> network = text()
                "type" -> inbound = text()
                "process" -> process = text()
                "uid" -> uid = counter(-1).takeIf { it >= 0 }
                else -> skipValue()
            }
            endObject()
        }
        "chains" -> if (peek() == JsonToken.NULL) nextNull() else {
            beginArray()
            while (hasNext()) {
                if (chains.size == 64) throw ConnectionSnapshotLimit()
                chains.add(text())
            }
            endArray()
        }
        "rule" -> rule = text()
        "rulePayload" -> rulePayload = text()
        "upload" -> upload = counter()
        "download" -> download = counter()
        "start" -> start = text()
        else -> skipValue()
    }
    endObject()
    check(id.isNotBlank() && metadata)
    return ConnectionRecord(id, host, destinationIP, destinationPort, sourceIP, sourcePort, network, inbound,
        process, uid, rule, rulePayload, chains, upload, download, start)
}
