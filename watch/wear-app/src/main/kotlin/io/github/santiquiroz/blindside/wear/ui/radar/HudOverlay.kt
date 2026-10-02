package io.github.santiquiroz.blindside.wear.ui.radar

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.shared.compass.headingText
import io.github.santiquiroz.blindside.shared.hud.AlertKind
import io.github.santiquiroz.blindside.shared.hud.AlertQueueState
import io.github.santiquiroz.blindside.shared.hud.BezelField
import io.github.santiquiroz.blindside.shared.hud.GlanceData
import io.github.santiquiroz.blindside.shared.hud.GlanceRow
import io.github.santiquiroz.blindside.shared.hud.bezelFieldAt
import io.github.santiquiroz.blindside.shared.hud.enqueueAlert
import io.github.santiquiroz.blindside.shared.hud.gameClockText
import io.github.santiquiroz.blindside.shared.hud.gameRemainingMs
import io.github.santiquiroz.blindside.shared.hud.glanceRows
import io.github.santiquiroz.blindside.shared.hud.glanceVisible
import io.github.santiquiroz.blindside.shared.hud.hydrationTick
import io.github.santiquiroz.blindside.shared.hud.stepAlertQueue
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.hud.toggledPin
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts
import io.github.santiquiroz.blindside.wear.ui.alertText
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val HUD_TICK_MS = 1_000L
private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val HUD_SURFACE = RoundedCornerShape(6.dp)

// The rear-half and bezel HUD: never covers contacts, numbers in Mono, and drives no vibration (the service owns buzzes).
@Composable
fun HudOverlay(
    frontHeadingDeg: Double?,
    gameStartElapsedMs: Long?,
    gameDurationMs: Long,
    beltLinkDown: Boolean,
    hydrationBaselineMs: Long?,
    tapAtMs: Long?,
    modifier: Modifier,
) {
    val nowMs by rememberHudClock()
    val alert by rememberAlert(beltLinkDown, hydrationBaselineMs)
    var pinned by remember { mutableStateOf<BezelField?>(null) }
    val field = bezelFieldAt(nowMs, pinned)
    Box(modifier) {
        alert?.let { AlertLine(alertText(it), Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp)) }
        BezelWindow(
            text = bezelText(field, frontHeadingDeg, gameStartElapsedMs, gameDurationMs, nowMs),
            pinned = pinned != null,
            onTogglePin = { pinned = toggledPin(pinned, field) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (glanceVisible(tapAtMs, nowMs)) {
            GlancePanel(glanceData(gameStartElapsedMs, gameDurationMs, nowMs), Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun BezelWindow(text: String, pinned: Boolean, onTogglePin: () -> Unit, modifier: Modifier) {
    Text(
        text,
        modifier
            .padding(bottom = 4.dp)
            .background(BlindsideColors.Bg, HUD_SURFACE)
            .clickable(onClick = onTogglePin)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = if (pinned) BlindsideColors.Accent else BlindsideColors.Text,
        fontFamily = BlindsideFonts.Mono,
        fontSize = 11.sp,
        maxLines = 1,
    )
}

@Composable
private fun AlertLine(text: String, modifier: Modifier) {
    Text(
        text,
        modifier.background(BlindsideColors.Surface, HUD_SURFACE).padding(horizontal = 6.dp, vertical = 2.dp),
        color = BlindsideColors.Warn,
        fontFamily = BlindsideFonts.Mono,
        fontSize = 11.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun GlancePanel(data: GlanceData, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(0.72f).background(BlindsideColors.Surface, HUD_SURFACE).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        glanceRows(data).forEach { GlanceRowView(it) }
    }
}

@Composable
private fun GlanceRowView(row: GlanceRow) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(row.label, color = BlindsideColors.Text2, fontSize = 10.sp, maxLines = 1)
        Text(row.value, color = BlindsideColors.Text, fontFamily = BlindsideFonts.Mono, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun rememberHudClock(): State<Long> = produceState(SystemClock.elapsedRealtime()) {
    while (true) {
        value = SystemClock.elapsedRealtime()
        delay(HUD_TICK_MS)
    }
}

// The alert line shows one kind at a time; it is fed by the signals wired today (belt link down, hydration timer).
@Composable
private fun rememberAlert(beltLinkDown: Boolean, hydrationBaselineMs: Long?): State<AlertKind?> {
    val linkDown by rememberUpdatedState(beltLinkDown)
    val showing = remember { mutableStateOf<AlertKind?>(null) }
    StepAlertQueue(linkDownProvider = { linkDown }, hydrationBaselineMs = hydrationBaselineMs, showing = showing)
    return showing
}

@Composable
private fun StepAlertQueue(linkDownProvider: () -> Boolean, hydrationBaselineMs: Long?, showing: MutableState<AlertKind?>) {
    // The baseline is read once per effect start and persisted back to the session, so screen wakes resume the cadence.
    LaunchedEffect(Unit) {
        var state = AlertQueueState()
        var baseline = hydrationBaselineMs
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (linkDownProvider()) state = enqueueAlert(state, AlertKind.BELT_LINK_DOWN)
            val tick = hydrationTick(baseline, now)
            if (tick.baselineMs != baseline) {
                baseline = tick.baselineMs
                SessionStore.markHydrationBaseline(baseline)
            }
            if (tick.remind) state = enqueueAlert(state, AlertKind.HYDRATION)
            state = stepAlertQueue(state, now)
            showing.value = state.showing
            delay(HUD_TICK_MS)
        }
    }
}

private fun bezelText(field: BezelField, frontHeadingDeg: Double?, startMs: Long?, durationMs: Long, nowMs: Long): String =
    when (field) {
        BezelField.HEADING -> frontHeadingDeg?.let(::headingText) ?: wallClockText()
        BezelField.CLOCK -> wallClockText()
        BezelField.GAME_TIME -> gameTimeText(startMs, nowMs, durationMs)
    }

private fun glanceData(startMs: Long?, durationMs: Long, nowMs: Long): GlanceData = GlanceData(
    clockText = wallClockText(),
    gameTimeText = gameTimeText(startMs, nowMs, durationMs),
    heartRate = null,
    steps = null,
    distanceM = null,
    watchBattery = null,
    phoneBattery = null,
    beltBattery = null,
)

private fun wallClockText(): String = LocalTime.now().format(CLOCK_FORMAT)

// Sin límite (duration 0) shows elapsed time counting up; a bounded game shows the remaining countdown.
private fun gameTimeText(startMs: Long?, nowMs: Long, durationMs: Long): String {
    if (startMs == null) return "--:--"
    val ms = if (durationMs == 0L) nowMs - startMs else gameRemainingMs(startMs, nowMs, durationMs)
    return gameClockText(ms.coerceAtLeast(0L))
}
