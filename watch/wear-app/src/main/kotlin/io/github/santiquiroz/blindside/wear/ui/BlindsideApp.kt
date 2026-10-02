package io.github.santiquiroz.blindside.wear.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.github.santiquiroz.blindside.wear.SpikeScreen
import io.github.santiquiroz.blindside.wear.bridge.publishSharedSettings
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.SettingsRepository
import io.github.santiquiroz.blindside.shared.settings.SettingsTransform
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import io.github.santiquiroz.blindside.wear.session.WearSessionCommands
import io.github.santiquiroz.blindside.wear.ui.radar.RadarScreen
import io.github.santiquiroz.blindside.wear.ui.theme.BlindsideWearTheme
import kotlinx.coroutines.launch

@Composable
fun BlindsideApp(settingsRepository: SettingsRepository) {
    val session by SessionStore.state.collectAsStateWithLifecycle()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val ambient by SessionStore.ambient.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberSwipeDismissableNavController()
    val update: (SettingsTransform) -> Unit = { transform -> scope.launch { settingsRepository.update(transform) } }
    val navigate: (String) -> Unit = { route -> navController.navigate(route) }
    val showRadar: () -> Unit = { navigateToRadar(navController) }
    val startDemo: () -> Unit = { startRadar(context, SessionSource.DEMO, showRadar) }
    val onPairPhone: () -> Unit = { WearSessionCommands.openPairing(context) }
    LaunchRadarOnOpen(settingsRepository, showRadar)
    LaunchedEffect(settingsRepository) { publishSharedSettings(context, settingsRepository) }
    BlindsideWearTheme {
        SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_HOME, modifier = Modifier.background(BlindsideColors.Bg)) {
            composable(ROUTE_HOME) { HomeScreen(session, navigate, showRadar) }
            composable(ROUTE_RADAR) { RadarScreen(session, settings, ambient) }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(settings, update, navigate, startDemo.takeUnless { session.running }, rememberPhonePairing(session), onPairPhone)
            }
            composable(ROUTE_PRACTICE) { PracticeScreen(settings) }
            composable(ROUTE_CALIBRATE_POSTURE) { CalibratePostureScreen(settings, update) { navController.popBackStack() } }
            composable(ROUTE_SPIKES) { SpikeScreen(settingsRepository) }
        }
    }
}

// Swiping back from the radar always lands on the home, wherever the radar was opened from.
private fun navigateToRadar(navController: NavHostController) {
    navController.navigate(ROUTE_RADAR) {
        popUpTo(ROUTE_HOME)
        launchSingleTop = true
    }
}
