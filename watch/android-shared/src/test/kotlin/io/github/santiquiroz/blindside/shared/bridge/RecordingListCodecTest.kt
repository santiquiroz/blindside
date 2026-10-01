package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecordingListCodecTest {
    private val entries = listOf(
        RecordingEntry("blindside-belt-20261001-142233.bsrec", 53_000_000L, 1_727_790_153_000L),
        RecordingEntry("blindside-demo-20261001-150000.bsrec", 1_024L, 1_727_792_400_000L),
    )

    @Test
    fun `a list survives encode and decode`() {
        assertEquals(entries, decodeRecordingList(encodeRecordingList(entries)))
    }

    @Test
    fun `the json uses the spec field names`() {
        assertEquals(
            """[{"nombre":"blindside-belt-20261001-142233.bsrec","bytes":53000000,"inicio":1727790153000}]""",
            encodeRecordingList(entries.take(1)),
        )
    }

    @Test
    fun `malformed or unsafe entries are dropped`() {
        val json = """[{"nombre":"../etc/passwd","bytes":1,"inicio":2},""" +
            """{"nombre":"blindside-belt-20261001-142233.bsrec","bytes":"x","inicio":2},{"bytes":1},7,""" +
            """{"nombre":"blindside-belt-20261001-142233.bsrec","bytes":3,"inicio":4}]"""
        assertEquals(listOf(RecordingEntry("blindside-belt-20261001-142233.bsrec", 3L, 4L)), decodeRecordingList(json))
    }

    @Test
    fun `text that is not a json list decodes to no recordings`() {
        assertEquals(emptyList<RecordingEntry>(), decodeRecordingList("not json"))
        assertEquals(emptyList<RecordingEntry>(), decodeRecordingList("""{"nombre":"x"}"""))
    }
}
