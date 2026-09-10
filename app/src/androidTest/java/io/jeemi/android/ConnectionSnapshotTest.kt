package io.jeemi.android

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.runtime.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections

class ConnectionSnapshotTest {
    // A generated stream keeps the test fixture itself out of the heap metric.
    private fun input(ids: IntProgression): InputStream {
        val chunks = sequence {
            yield("{\"connections\":[".byteInputStream())
            for ((index, id) in ids.withIndex()) {
                yield(((if (index == 0) "" else ",") + """{"id":"$id","metadata":{"host":"same.example.com","destinationIP":"203.0.113.1","destinationPort":443,"sourceIP":"172.19.0.1","sourcePort":12345,"network":"tcp","type":"TUN","process":"com.android.settings","uid":1000},"rule":"RuleSet","rulePayload":"Applications","chains":["Node","Automatic","Proxy"],"upload":${id + 1000},"download":${id + 2000},"start":"2026-09-10T00:00:00Z"}""").byteInputStream())
            }
            yield("]}".byteInputStream())
        }.iterator()
        return SequenceInputStream(object : java.util.Enumeration<InputStream> {
            override fun hasMoreElements() = chunks.hasNext()
            override fun nextElement() = chunks.next()
        })
    }
    private fun parse(text: String) = readConnectionSnapshot(text.byteInputStream())
    private fun heap(): Long {
        System.gc(); Thread.sleep(50)
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }
    @Test fun largeChurningSnapshotsKeepStableLiveRowsAndOnlyRecentHistory() {
        var feed = emptyList<ConnectionRecord>()
        val times = ArrayList<Long>()
        val memory = ArrayList<Long>()
        repeat(30) { pass ->
            val start = pass * 60
            val ids = if (pass % 2 == 0) start until start + 10000 else start + 9999 downTo start
            val before = SystemClock.elapsedRealtimeNanos()
            val snapshot = input(ids).use { readConnectionSnapshot(it, feed) }
            val merged = mergeConnections(feed, snapshot.rows, snapshot.presentTrackedIds)
            times.add((SystemClock.elapsedRealtimeNanos() - before) / 1_000_000)
            assertEquals(10000, snapshot.activeCount)
            assertEquals(ACTIVE_CONNECTION_LIMIT, snapshot.rows.size)
            assertTrue(snapshot.presentTrackedIds.size <= ACTIVE_CONNECTION_LIMIT + ENDED_CONNECTION_LIMIT)
            // Surviving tracked connections stay displayed across reversed order.
            val survivors = feed.filter { !it.ended && it.id.toInt() >= start }.mapTo(HashSet()) { it.id }
            assertTrue(snapshot.rows.map { it.id }.containsAll(survivors))
            assertTrue(merged.filter { it.ended }.all { it.id.toInt() < start })
            assertTrue(merged.size <= 2100)
            assertTrue(merged.count { it.ended } <= 100)
            feed = merged
            if (pass == 4 || pass == 14 || pass == 29) memory.add(heap())
        }
        assertEquals(100, feed.count { it.ended })
        assertEquals(1, feed.filter { it.ended }.map { it.process to it.target }.distinct().size)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = "snapshots=30\nconnections_per_snapshot=10000\nretained_active=${feed.count { !it.ended }}\nretained_ended=${feed.count { it.ended }}\nparse_merge_ms=$times\npost_gc_java_heap_bytes=$memory\n"
        File(context.getExternalFilesDir(null), "connection-stress.txt").writeText(report)
        android.util.Log.i("JeemiConnectionStress", report)
    }
    @Test fun previouslyEndedConnectionOutsideDisplayLimitIsStillRecognizedAsLive() {
        val initial = input(0 until 2000).use { readConnectionSnapshot(it).rows }
        val returned = initial.first().copy(id = "9900", ended = true)
        val previous = initial + returned
        val snapshot = input(9999 downTo 0).use { readConnectionSnapshot(it, previous) }
        assertTrue("9900" in snapshot.presentTrackedIds)
        assertFalse(snapshot.rows.any { it.id == "9900" })
        assertEquals(2000, mergeConnections(previous, snapshot.rows, snapshot.presentTrackedIds).size)
        assertEquals(initial.map { it.id }, snapshot.rows.map { it.id })
    }
    @Test fun invalidOrOversizedResponsesNeverReturnPartialSnapshots() {
        for (text in listOf("{}", "{\"connections\":[", "{\"connections\":[{\"id\":\"1\"}]}",
            "{\"connections\":[]}trailing", "{\"connections\":[],\"connections\":[]}")) {
            assertThrows(Exception::class.java) { parse(text) }
        }
        assertThrows(ConnectionSnapshotLimit::class.java) {
            parse("""{"connections":[{"id":"1","metadata":{"host":"${"a".repeat(4097)}"}}]}""")
        }
        val huge = SequenceInputStream(Collections.enumeration(listOf(
            "{\"connections\":[],\"padding\":\"".byteInputStream(),
            object : InputStream() {
                var left = CONNECTION_RESPONSE_LIMIT
                override fun read() = if (left-- > 0) 'a'.code else -1
            }, "\"}".byteInputStream())))
        assertThrows(ConnectionSnapshotLimit::class.java) { huge.use { readConnectionSnapshot(it) } }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            input(0 until 10000).use { readConnectionSnapshot(it) { if (++checks > 64) throw CancellationException() } }
        }
    }
    @Test fun metadataOrderNumericPortsAndNullEmptySnapshotsAreSupported() {
        val row = parse("""{"connections":[{"rule":"Match","download":123,"chains":null,"metadata":{"uid":-1,"process":null,"destinationPort":443},"id":"one"}]}""").rows.single()
        assertEquals("443", row.destinationPort); assertEquals(123L, row.download)
        assertEquals("", row.process); assertNull(row.uid); assertTrue(row.chains.isEmpty())
        assertEquals(0, parse("{\"connections\":null}").activeCount)
        assertTrue(parse("{\"connections\":[]}").rows.isEmpty())
        val system = parse("""{"connections":[{"id":"system","metadata":{"uid":0,"process":null}}]}""").rows.single()
        assertEquals(0L, system.uid); assertEquals("", system.process)
    }
}
