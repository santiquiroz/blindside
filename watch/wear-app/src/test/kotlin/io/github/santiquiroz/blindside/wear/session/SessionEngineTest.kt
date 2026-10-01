package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.replay.SessionMode
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.scene.Warning
import io.github.santiquiroz.blindside.wear.haptics.HapticPattern
import io.github.santiquiroz.blindside.wear.haptics.LEFT_PATTERN
import io.github.santiquiroz.blindside.wear.haptics.SYSTEM_BUZZ_MS
import io.github.santiquiroz.blindside.wear.haptics.SYSTEM_PATTERN
import io.github.santiquiroz.blindside.wear.recording.RecordSink
import io.github.santiquiroz.blindside.wear.settings.ScreenMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionEngineTest {
    private val start = 1_000_000_000L

    private fun at(ms: Long): Long = start + ms * 1_000_000L

    private var nowMs = 40L
    private val pipeline = FakePipeline()
    private val records = FakeRecords()
    private val played = mutableListOf<HapticPattern>()
    private val scenes = mutableListOf<RadarScene>()
    private val errors = mutableListOf<Throwable>()
    private val deferred = mutableListOf<Pair<ContactAlert, Long>>()
    private val engine = SessionEngine(
        pipeline = pipeline,
        records = records,
        haptics = { played += it },
        scenes = { scenes += it },
        clock = { at(nowMs) },
        startNanos = start,
        deferred = { alert, atNanos -> deferred += alert to atNanos },
        onError = { errors += it },
    )

    private val leftContact = ContactAlert(displayId = 4, side = Side.LEFT, tNanos = at(40))

    private fun vibrationRecords() = records.items.filter { it.type == RecordType.VIBRATION_STARTED }

    @Test
    fun `records the raw packet and feeds it to the pipeline`() {
        engine.handle(SessionInput.Packet(byteArrayOf(9, 8), at(250)))
        assertEquals(listOf("packet:2@${at(250)}"), pipeline.calls)
        assertEquals(RecordType.BLE_PACKET, records.items.single().type)
        assertEquals(250L, records.items.single().tMsSinceStart)
    }

    @Test
    fun `a left contact alert vibrates the left rhythm and records the vibration start`() {
        pipeline.packetEvents = listOf(ContactAlert(displayId = 4, side = Side.LEFT, tNanos = at(250)))
        engine.handle(SessionInput.Packet(byteArrayOf(1), at(250)))
        assertEquals(listOf(LEFT_PATTERN), played)
        val vibration = records.items.single { it.type == RecordType.VIBRATION_STARTED }
        assertEquals(40L, vibration.tMsSinceStart)
        assertEquals(listOf<Byte>(4, 0, 0, 0, 0), vibration.payload.toList())
    }

    @Test
    fun `a system alert buzzes without a vibration record`() {
        pipeline.linkEvents = listOf(SystemAlert(kind = Warning.LINK_LOST, tNanos = at(5)))
        engine.handle(SessionInput.Link(false, at(5)))
        assertEquals(listOf(SYSTEM_PATTERN), played)
        assertEquals(listOf("link:false"), pipeline.calls)
        assertEquals(false, BsrecPayloads.readLinkChange(records.items.single().payload))
    }

    @Test
    fun `a contact in the same input as a system alert waits for the buzz to end`() {
        pipeline.packetEvents = listOf(leftContact, SystemAlert(kind = Warning.RADAR_DOWN, tNanos = at(40)))
        engine.handle(SessionInput.Packet(byteArrayOf(1), at(40)))
        assertEquals(listOf(SYSTEM_PATTERN), played)
        assertEquals(listOf(leftContact to at(40 + SYSTEM_BUZZ_MS)), deferred)
        assertTrue(vibrationRecords().isEmpty())
    }

    @Test
    fun `a contact half a second into a system buzz waits for the rest of it`() {
        pipeline.linkEvents = listOf(SystemAlert(kind = Warning.LINK_LOST, tNanos = at(40)))
        engine.handle(SessionInput.Link(false, at(40)))
        nowMs = 540L
        pipeline.packetEvents = listOf(leftContact)
        engine.handle(SessionInput.Packet(byteArrayOf(1), at(540)))
        assertEquals(listOf(leftContact to at(1_240)), deferred)
    }

    @Test
    fun `a deferred contact vibrates and is recorded when its turn comes`() {
        nowMs = 1_240L
        engine.handle(SessionInput.PlayDeferred(leftContact, at(1_240)))
        assertEquals(listOf(LEFT_PATTERN), played)
        assertEquals(1_240L, vibrationRecords().single().tMsSinceStart)
    }

    @Test
    fun `a deferred contact is dropped once the player is eliminated`() {
        engine.handle(SessionInput.ModeChanged(eliminated = true, screenMode = ScreenMode.SIGILO, nowNanos = at(100)))
        engine.handle(SessionInput.PlayDeferred(leftContact, at(1_240)))
        assertTrue(played.isEmpty())
        assertTrue(vibrationRecords().isEmpty())
    }

    @Test
    fun `a track confirmation is recorded and does not vibrate`() {
        pipeline.packetEvents = listOf(TrackConfirmed(displayId = 2, tNanos = at(120)))
        engine.handle(SessionInput.Packet(byteArrayOf(1), at(100)))
        assertTrue(played.isEmpty())
        assertEquals(120L, records.items.single { it.type == RecordType.TRACK_CONFIRMED }.tMsSinceStart)
    }

    @Test
    fun `watch and belt side inputs reach the pipeline`() {
        engine.handle(SessionInput.Gravity(0f, 0f, 9.8f, at(1)))
        engine.handle(SessionInput.Gyro(0.1f, 0f, 0f, at(2)))
        engine.handle(SessionInput.Step(at(3)))
        engine.handle(SessionInput.BeltInfo("{}", at(4)))
        assertEquals(listOf("gravity@${at(1)}", "gyro@${at(2)}", "step@${at(3)}", "info:{}"), pipeline.calls)
        assertEquals(4, records.items.size)
    }

    @Test
    fun `a mode change reaches the pipeline and is recorded`() {
        engine.handle(SessionInput.ModeChanged(eliminated = true, screenMode = ScreenMode.SIGILO, nowNanos = at(7)))
        assertEquals(listOf("eliminated:true"), pipeline.calls)
        assertEquals(SessionMode.ELIMINATED, BsrecPayloads.readModeChange(records.items.single().payload))
    }

    @Test
    fun `a tick publishes the scene for that instant without recording`() {
        engine.handle(SessionInput.Tick(at(33)))
        assertEquals(listOf("scene@${at(33)}"), pipeline.calls)
        assertEquals(listOf(pipeline.scene), scenes)
        assertTrue(records.items.isEmpty())
    }

    @Test
    fun `a flush input flushes the recorder`() {
        engine.handle(SessionInput.Flush)
        assertEquals(1, records.flushes)
    }

    @Test
    fun `keeps processing inputs after the pipeline throws`() {
        pipeline.failOnPacket = true
        engine.handle(SessionInput.Packet(byteArrayOf(1), at(10)))
        engine.handle(SessionInput.Step(at(11)))
        assertEquals(1, errors.size)
        assertTrue("step@${at(11)}" in pipeline.calls)
    }

    @Test
    fun `closing the engine closes the recorder`() {
        engine.close()
        assertTrue(records.closed)
    }
}

private class FakePipeline : PipelinePort {
    val calls = mutableListOf<String>()
    var packetEvents: List<PipelineEvent> = emptyList()
    var linkEvents: List<PipelineEvent> = emptyList()
    var failOnPacket = false
    val scene = RadarScene(
        blips = emptyList(),
        coverage = emptyList(),
        linkUp = true,
        radars = emptyList(),
        imus = emptyList(),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = false,
    )

    override fun onBlePacket(bytes: ByteArray, arrivalNanos: Long): List<PipelineEvent> {
        calls += "packet:${bytes.size}@$arrivalNanos"
        check(!failOnPacket) { "decoder blew up" }
        return packetEvents
    }

    override fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long) {
        calls += "gravity@$eventNanos"
    }

    override fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long) {
        calls += "gyro@$eventNanos"
    }

    override fun onWatchStep(eventNanos: Long) {
        calls += "step@$eventNanos"
    }

    override fun onBeltInfo(json: String, nowNanos: Long) {
        calls += "info:$json"
    }

    override fun onLinkState(connected: Boolean, nowNanos: Long): List<PipelineEvent> {
        calls += "link:$connected"
        return linkEvents
    }

    override fun setEliminated(on: Boolean) {
        calls += "eliminated:$on"
    }

    override fun scene(nowNanos: Long): RadarScene {
        calls += "scene@$nowNanos"
        return scene
    }
}

private class FakeRecords : RecordSink {
    val items = mutableListOf<BsrecRecord>()
    var flushes = 0
    var closed = false

    override fun record(record: BsrecRecord) {
        items += record
    }

    override fun flush() {
        flushes += 1
    }

    override fun close() {
        closed = true
    }
}
