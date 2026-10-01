package io.github.santiquiroz.blindside.wear.recording

import io.github.santiquiroz.blindside.core.config.pipelineConfigFromValue
import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import io.github.santiquiroz.blindside.shared.settings.toPipelineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

class RecordingHeaderTest {
    private val meta = recordingMeta(
        settings = AppSettings(screenMode = ScreenMode.VISTA),
        source = "DEMO",
        stamp = SessionClockStamp(epochMs = 1_760_000_000_000L, elapsedNanos = 42L),
        device = "SM-L310",
        appVersion = "0.1.0",
        amplitudeControl = true,
        primitives = false,
    )

    @Test
    fun `header names the source, the clocks and the screen mode`() {
        val json = headerJson(meta)
        assertTrue(json.startsWith("{") && json.endsWith("}"))
        assertTrue(json.contains(""""source":"DEMO""""))
        assertTrue(json.contains(""""started_epoch_ms":1760000000000"""))
        assertTrue(json.contains(""""started_elapsed_ns":42"""))
        assertTrue(json.contains(""""screen_mode":"VISTA""""))
        assertTrue(json.contains(""""amplitude_control":true"""))
        assertTrue(json.contains(""""primitives":false"""))
    }

    @Test
    fun `header config is the session pipeline config and reads back the way the replay reads it`() {
        val json = headerJson(meta)
        val root = MiniJson.parse(json) as Map<*, *>
        assertTrue(json.contains(""""config":{"tuning":{"""))
        assertEquals(toPipelineConfig(AppSettings(screenMode = ScreenMode.VISTA)), pipelineConfigFromValue(root["config"]))
    }

    @Test
    fun `header embeds the first belt info verbatim`() {
        val info = """{"proto":1,"boot_id":"9f3a12c4","mtu":255}"""
        assertTrue(headerJson(meta.copy(infoJson = info)).contains(""""info":$info"""))
    }

    @Test
    fun `header without belt info says so`() {
        assertTrue(headerJson(meta).contains(""""info":null"""))
    }

    @Test
    fun `belt info that is not a json object is kept as a string`() {
        assertEquals("\"garbled\"", infoField("garbled"))
    }

    @Test
    fun `strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", jsonString("a\"b\\c\n"))
    }

    @Test
    fun `file names sort by start time and name the source`() {
        assertEquals("blindside-belt-19700101-000000.bsrec", recordingFileName(0L, ZoneOffset.UTC, "BELT"))
    }
}
