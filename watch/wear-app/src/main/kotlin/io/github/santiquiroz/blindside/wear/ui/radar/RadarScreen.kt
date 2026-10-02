package io.github.santiquiroz.blindside.wear.ui.radar

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.compass.CompassReading
import io.github.santiquiroz.blindside.shared.compass.HeadingAnimation
import io.github.santiquiroz.blindside.shared.compass.advanceHeading
import io.github.santiquiroz.blindside.shared.compass.compassColors
import io.github.santiquiroz.blindside.shared.compass.compassWarningLabel
import io.github.santiquiroz.blindside.shared.radar.DND_RADAR_WARNING
import io.github.santiquiroz.blindside.shared.radar.MAX_RANGE_M
import io.github.santiquiroz.blindside.shared.radar.PointPx
import io.github.santiquiroz.blindside.shared.radar.RangeMark
import io.github.santiquiroz.blindside.shared.radar.rangeMarks
import io.github.santiquiroz.blindside.shared.radar.rotatePoint
import io.github.santiquiroz.blindside.shared.radar.StatusItem
import io.github.santiquiroz.blindside.shared.radar.StatusMark
import io.github.santiquiroz.blindside.shared.radar.advanceSceneSpinDeg
import io.github.santiquiroz.blindside.shared.radar.centerLabel
import io.github.santiquiroz.blindside.shared.radar.drawRadar
import io.github.santiquiroz.blindside.shared.radar.fanHalfAngleFor
import io.github.santiquiroz.blindside.shared.radar.frameFraction
import io.github.santiquiroz.blindside.shared.radar.interpolatedBlips
import io.github.santiquiroz.blindside.shared.radar.radarColorsFor
import io.github.santiquiroz.blindside.shared.radar.rotatedAbout
import io.github.santiquiroz.blindside.shared.radar.screenCenter
import io.github.santiquiroz.blindside.shared.radar.showContacts
import io.github.santiquiroz.blindside.shared.radar.statusItems
import io.github.santiquiroz.blindside.shared.radar.statusMark
import io.github.santiquiroz.blindside.shared.radar.statusRows
import io.github.santiquiroz.blindside.shared.radar.toDrawModel
import io.github.santiquiroz.blindside.shared.radar.warningLabel
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.sensors.effectivePostureRotationDeg
import io.github.santiquiroz.blindside.shared.session.modeFramePeriodMs
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts
import io.github.santiquiroz.blindside.wear.ui.KeepScreenOn
import io.github.santiquiroz.blindside.wear.ui.ReportRadarVisibility
import io.github.santiquiroz.blindside.wear.ui.burnInOffset
import io.github.santiquiroz.blindside.wear.ui.keepScreenOn
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val BURN_IN_CLOCK_TICK_MS = 30_000L
private const val NANOS_PER_MS = 1_000_000L
private const val HERE_POLL_MS = 3_000L
private const val CENTER_TAP_FRACTION = 0.33f
private val CENTER_LABEL_SIZE = 26.sp
private val LINK_MESSAGE_SIZE = 14.sp
private val LINK_MESSAGE_SIDE_PADDING = 28.dp
private val STATUS_DOT_SIZE = 4.dp
private val STATUS_DOT_STROKE = 1.dp
private val COMPASS_BAND = 16.dp
private val PANEL_BOTTOM_PADDING = 22.dp

// Per-frame drawing offsets: the eased heading the ring turns by and the transient gyro spin the scene turns by.
data class RadarFrame(val azimuthDeg: Float = 0f, val spinDeg: Float = 0f)

// The last two belt scenes plus when the newest one arrived, so contacts glide from the old to the new between frames.
data class ScenePair(val prev: RadarScene? = null, val next: RadarScene? = null, val frameStartMs: Long = 0L) {
    fun advance(scene: RadarScene, nowMs: Long): ScenePair = ScenePair(prev = next, next = scene, frameStartMs = nowMs)
}

@Composable
fun RadarScreen(
    session: SessionUiState,
    settings: AppSettings,
    ambient: Boolean,
) {
    ReportRadarVisibility()
    KeepScreenOn(keepScreenOn(settings.screenMode))
    val elapsedMs by rememberElapsedMs()
    val shift = burnInOffset(settings.screenMode, elapsedMs)
    val contacts = showContacts(session.scene, ambient)
    val tactical by rememberTacticalPosture(active = !ambient, template = settings.postureTemplate)
    val postureDeg = effectivePostureRotationDeg(settings.posture, tactical, settings.postureTemplate)
    val fitHalfAngleDeg = remember(settings.handedness, settings.radars) { fanHalfAngleFor(settings) }
    val compassOn = settings.compass && !ambient
    val compass = rememberCompassReading(compassOn)
    val yawRate = rememberYawRate(compassOn)
    val frameState = rememberRadarFrame(compassOn, compass, yawRate)
    val frame by frameState
    val scenes = rememberScenePair(session.scene)
    val reading = compass.value.takeIf { compassOn }
    val bandPx = with(LocalDensity.current) { COMPASS_BAND.toPx() }
    val measurer = rememberTextMeasurer()
    val lastFix = rememberLastFix()
    val here by rememberHere(lastFix, compassOn)
    val wedgeColors = remember { TacticalWedgeColors(BlindsideColors.Accent, BlindsideColors.AccentDim, BlindsideColors.Warn) }
    var glanceOpen by remember { mutableStateOf(false) }
    Box(radarGestures(lastFix) { glanceOpen = !glanceOpen }) {
        // The symmetric tick band turns against the heading on the compositor, once per frame, with no recomposition.
        reading?.let { r ->
            Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = -frame.azimuthDeg }) {
                val ring = RingGeometry(screenCenter(size.width, size.height, shift), size.minDimension / 2f, bandPx)
                drawCompassTicks(ring, compassColors(settings.screenMode, r.trust))
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val margin = if (compassOn) bandPx else 0f
            val pivot = screenCenter(size.width, size.height, shift)
            val drawnScene = foldInterpolated(scenes, frameFractionNow(scenes, compassOn, modeFramePeriodMs(settings.screenMode)))
            val logical = toDrawModel(drawnScene, size.width, size.height, shift, contacts, margin, fitHalfAngleDeg)
            drawRadar(logical.rotatedAbout(pivot, postureDeg + frame.spinDeg), radarColorsFor(settings.contactColor))
            if (!ambient) drawRangeScale(rangeMarks(logical.origin, logical.radiusPx), pivot, postureDeg, measurer)
            reading?.let { r ->
                val ring = RingGeometry(pivot, size.minDimension / 2f, bandPx)
                val colors = compassColors(settings.screenMode, r.trust)
                drawCompassLetters(frame.azimuthDeg.toDouble(), ring, postureDeg, colors, measurer)
                drawFrontIndex(ring, postureDeg, colors.index)
                drawTacticalWedges(session.tacticalPoints, here, frame.azimuthDeg.toDouble(), ring, wedgeColors, measurer)
            }
        }
        RadarOverlay(session, settings, ambient, shift, postureDeg, reading, glanceOpen) { glanceOpen = false }
    }
}

private val RANGE_LABEL_SIZE = 9.sp
private const val RANGE_LABEL_INSET_PX = 12f

// The metre scale rides the radar frame (posture rotation, not the gyro washout) and stays dim so it never saturates.
private fun DrawScope.drawRangeScale(marks: List<RangeMark>, pivot: PointPx, rotationDeg: Float, measurer: TextMeasurer) {
    marks.forEach { mark ->
        val p = nudgeToward(rotatePoint(mark.at, pivot, rotationDeg), pivot, RANGE_LABEL_INSET_PX)
        val label = if (mark.meters >= MAX_RANGE_M.toInt()) "${mark.meters} m" else "${mark.meters}"
        val layout = measurer.measure(label, TextStyle(color = BlindsideColors.Text2, fontSize = RANGE_LABEL_SIZE, fontFamily = BlindsideFonts.Mono))
        drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
    }
}

private fun nudgeToward(from: PointPx, target: PointPx, px: Float): PointPx {
    val dx = target.x - from.x
    val dy = target.y - from.y
    val distance = hypot(dx, dy)
    if (distance < 1e-3f) return from
    return PointPx(from.x + dx / distance * px, from.y + dy / distance * px)
}

@Composable
private fun radarGestures(lastFix: () -> GeoPoint?, onCenterTap: () -> Unit): Modifier =
    Modifier.fillMaxSize().background(BlindsideColors.Bg).pointerInput(Unit) {
        detectTapGestures(
            onLongPress = { lastFix()?.let(SessionStore::markTactical) },
            onTap = { offset -> if (isCenterTap(offset, size)) onCenterTap() },
        )
    }

private fun isCenterTap(offset: Offset, size: IntSize): Boolean {
    val dx = offset.x - size.width / 2f
    val dy = offset.y - size.height / 2f
    return hypot(dx, dy) < size.width.coerceAtMost(size.height) * CENTER_TAP_FRACTION
}

// The current fix drives the wedges; it is polled slowly because a mark and its bearing only need a coarse fix.
@Composable
private fun rememberHere(lastFix: () -> GeoPoint?, active: Boolean): State<GeoPoint?> {
    val here = remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            here.value = lastFix()
            delay(HERE_POLL_MS)
        }
    }
    return here
}

// Spec §8.3: belt link down is the one alert that must still vibrate in Sigilo; it rides the engine's system-buzz path.
private fun beltLinkDown(session: SessionUiState): Boolean =
    session.source == SessionSource.BELT && session.ble in setOf(BleStatus.RECONNECTING, BleStatus.BOND_LOST)

// One frame loop drives both the heading ease and the gyro spin washout; idle (0,0) whenever the compass is off.
@Composable
private fun rememberRadarFrame(active: Boolean, compass: State<CompassReading?>, yawRate: State<Float>): State<RadarFrame> {
    val frame = remember { mutableStateOf(RadarFrame()) }
    LaunchedEffect(active) {
        if (!active) {
            frame.value = RadarFrame()
            return@LaunchedEffect
        }
        var anim: HeadingAnimation? = null
        var spin = 0.0
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                val dtMs = frameDeltaMs(lastNanos, now)
                lastNanos = now
                val next = advanceHeading(anim, compass.value?.azimuthDeg ?: anim?.currentDeg ?: 0.0, now)
                anim = next
                spin = advanceSceneSpinDeg(spin, yawRate.value.toDouble(), dtMs)
                frame.value = RadarFrame(next.currentDeg.toFloat(), spin.toFloat())
            }
        }
    }
    return frame
}

// A new belt scene becomes the target to glide toward; the one before it stays as the start of the glide.
@Composable
private fun rememberScenePair(scene: RadarScene?): ScenePair {
    val holder = remember { mutableStateOf(ScenePair()) }
    LaunchedEffect(scene) {
        if (scene != null) holder.value = holder.value.advance(scene, SystemClock.elapsedRealtime())
    }
    return holder.value
}

private fun frameDeltaMs(lastNanos: Long, nowNanos: Long): Long =
    if (lastNanos == 0L) 0L else (nowNanos - lastNanos) / NANOS_PER_MS

// The glide spans exactly one publication period, which the watch sets by screen mode (Vista 33 ms, Sigilo 100 ms).
private fun frameFractionNow(scenes: ScenePair, animating: Boolean, periodMs: Long): Float {
    if (!animating) return 1f
    return frameFraction(SystemClock.elapsedRealtime() - scenes.frameStartMs, periodMs)
}

private fun foldInterpolated(scenes: ScenePair, t: Float): RadarScene? {
    val next = scenes.next ?: return null
    val prev = scenes.prev ?: return next
    return next.copy(blips = interpolatedBlips(prev.blips, next.blips, t))
}

@Composable
private fun RadarOverlay(
    session: SessionUiState,
    settings: AppSettings,
    ambient: Boolean,
    shift: PointPx,
    postureDeg: Float,
    reading: CompassReading?,
    glanceOpen: Boolean,
    onDismissGlance: () -> Unit,
) {
    val link = radarMessage(session)
    val placement = Modifier.fillMaxSize()
        .offset { IntOffset(shift.x.roundToInt(), shift.y.roundToInt()) }
        // Turns after the shift, about the shifted center like the drawing, so the panel stays over the rear and off the flanks.
        .graphicsLayer { rotationZ = postureDeg }
    Box(placement) {
        centerLabel(session.scene, ambient, link)?.let { label ->
            CenterLabel(label, isLinkMessage = label == link, Modifier.align(Alignment.Center))
        }
        // Spec §5.4: the dimmed screen keeps ≤ 15 % lit pixels, so ambient shows only the fan and "--".
        if (!ambient) BottomPanel(session, compassWarningLabel(reading), Modifier.align(Alignment.BottomCenter))
        if (!ambient) {
            HudOverlay(
                beltLinkDown = beltLinkDown(session),
                hydrationBaselineMs = session.hydrationBaselineMs,
                glanceOpen = glanceOpen,
                onDismissGlance = onDismissGlance,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CenterLabel(label: String, isLinkMessage: Boolean, modifier: Modifier) {
    Text(
        label,
        modifier.padding(horizontal = LINK_MESSAGE_SIDE_PADDING),
        color = BlindsideColors.Text2,
        fontSize = if (isLinkMessage) LINK_MESSAGE_SIZE else CENTER_LABEL_SIZE,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BottomPanel(session: SessionUiState, compassWarning: String?, modifier: Modifier) {
    Column(modifier = modifier.padding(bottom = PANEL_BOTTOM_PADDING), horizontalAlignment = Alignment.CenterHorizontally) {
        compassWarning?.let { Text(it, color = BlindsideColors.Warn, fontSize = 11.sp, maxLines = 1) }
        if (session.dndMaySilenceAlerts) Text(DND_RADAR_WARNING, color = BlindsideColors.Warn, fontSize = 11.sp, maxLines = 1)
        warningLabel(session.scene?.warnings.orEmpty())?.let { Text(it, color = BlindsideColors.Warn, fontSize = 11.sp) }
        statusRows(statusItems(session.scene, session.watchSteps)).forEach { StatusRow(it) }
    }
}

@Composable
private fun StatusRow(items: List<StatusItem>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { StatusChip(it) }
    }
}

@Composable
private fun StatusChip(item: StatusItem) {
    val tint = if (item.ok) BlindsideColors.Accent else BlindsideColors.AlertRed
    Row(
        Modifier.background(BlindsideColors.Surface, CircleShape).padding(horizontal = 3.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StatusDot(statusMark(item), tint)
        Text(item.label, color = tint, fontSize = 9.sp, fontFamily = BlindsideFonts.Mono, maxLines = 1)
    }
}

@Composable
private fun StatusDot(mark: StatusMark, tint: Color) {
    Canvas(Modifier.size(STATUS_DOT_SIZE)) {
        when (mark) {
            StatusMark.FILLED -> drawCircle(tint)
            StatusMark.HOLLOW -> drawCircle(tint, style = Stroke(width = STATUS_DOT_STROKE.toPx()))
        }
    }
}

@Composable
private fun rememberElapsedMs(): State<Long> = produceState(SystemClock.elapsedRealtime()) {
    while (true) {
        delay(BURN_IN_CLOCK_TICK_MS)
        value = SystemClock.elapsedRealtime()
    }
}
