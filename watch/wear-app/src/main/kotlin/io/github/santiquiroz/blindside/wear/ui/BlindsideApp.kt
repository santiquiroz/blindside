package io.github.santiquiroz.blindside.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.github.santiquiroz.blindside.wear.SpikeScreen
import io.github.santiquiroz.blindside.wear.session.SessionStore
import io.github.santiquiroz.blindside.wear.session.toggleEliminated
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.SettingsRepository
import io.github.santiquiroz.blindside.wear.settings.SettingsTransform
import io.github.santiquiroz.blindside.wear.ui.radar.RadarScreen
import kotlinx.coroutines.launch

@Composable
fun BlindsideApp(settingsRepository: SettingsRepository) {
    val session by SessionStore.state.collectAsStateWithLifecycle()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val ambient by SessionStore.ambient.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val navController = rememberSwipeDismissableNavController()
    val update: (SettingsTransform) -> Unit = { transform -> scope.launch { settingsRepository.update(transform) } }
    val navigate: (String) -> Unit = { route -> navController.navigate(route) }
    val onToggleEliminated: () -> Unit = { toggleEliminated(scope, settingsRepository) }
    MaterialTheme {
        SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_HOME) {
            composable(ROUTE_HOME) { HomeScreen(session, settings, navigate, onToggleEliminated) }
            composable(ROUTE_RADAR) { RadarScreen(session, settings, ambient, onToggleEliminated) }
            composable(ROUTE_SETTINGS) { SettingsScreen(settings, update, onOpenSpikes = { navigate(ROUTE_SPIKES) }) }
            composable(ROUTE_PRACTICE) {
                PracticeScreen(settings, onQuizPassed = { update { it.copy(quizPassedAtEpochMs = System.currentTimeMillis()) } })
            }
            composable(ROUTE_SPIKES) { SpikeScreen(settingsRepository) }
        }
    }
}
