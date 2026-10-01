package io.github.santiquiroz.blindside.core.alerts

import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.scene.Warning

sealed interface PipelineEvent {
    val tNanos: Long
}

data class ContactAlert(val displayId: Int, val side: Side, override val tNanos: Long) : PipelineEvent

data class TrackConfirmed(val displayId: Int, override val tNanos: Long) : PipelineEvent

data class SystemAlert(val kind: Warning, override val tNanos: Long) : PipelineEvent
