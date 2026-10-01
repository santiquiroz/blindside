package io.github.santiquiroz.blindside.shared.haptics

import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.alerts.SystemAlert

private const val NANOS_PER_MS = 1_000_000L
const val SYSTEM_BUZZ_NANOS = SYSTEM_BUZZ_MS * NANOS_PER_MS

data class HapticGate(val systemBusyUntilNanos: Long = Long.MIN_VALUE)

fun afterSystemBuzz(gate: HapticGate, nowNanos: Long): HapticGate = gate.copy(systemBusyUntilNanos = nowNanos + SYSTEM_BUZZ_NANOS)

fun contactStartNanos(gate: HapticGate, nowNanos: Long): Long = maxOf(nowNanos, gate.systemBusyUntilNanos)

fun systemFirst(events: List<PipelineEvent>): List<PipelineEvent> = events.sortedBy { it !is SystemAlert }

fun millisUntil(atNanos: Long, nowNanos: Long): Long = ((atNanos - nowNanos) / NANOS_PER_MS).coerceAtLeast(0L)
