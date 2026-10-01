package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// The running session reads the in-memory flag, so a failed DataStore write can never keep a hit player in play.
fun toggleEliminated(scope: CoroutineScope, settings: SettingsRepository) {
    val eliminated = SessionStore.toggleEliminated()
    scope.launch { settings.update { it.copy(eliminated = eliminated) } }
}
