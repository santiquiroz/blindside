package io.github.santiquiroz.blindside.phone.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.phone.PhoneDeps
import io.github.santiquiroz.blindside.phone.nav.PHONE_PERMISSIONS
import io.github.santiquiroz.blindside.phone.session.PhoneSessionCommands
import io.github.santiquiroz.blindside.phone.session.startPhoneSession
import io.github.santiquiroz.blindside.shared.permissions.bluetoothGranted
import io.github.santiquiroz.blindside.shared.permissions.shouldRequestPermissions
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import kotlinx.coroutines.launch

@Composable
fun rememberPhoneActions(deps: PhoneDeps, onBluetoothBlocked: (Boolean) -> Unit): PhoneActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startSession = rememberSessionStarter(onBluetoothBlocked)
    return remember(deps, startSession) {
        PhoneActions(
            startRadar = { startSession(SessionPurpose.GAME) },
            startDiagnostic = { startSession(SessionPurpose.DIAGNOSTIC) },
            stop = { PhoneSessionCommands.stop(context) },
            toggleEliminated = { context.startService(PhoneSessionCommands.toggleEliminatedIntent(context)) },
            retryLink = { PhoneSessionCommands.retryLink(context) },
            sendCommand = { PhoneSessionCommands.send(context, it) },
            refreshInfo = { PhoneSessionCommands.refreshInfo(context) },
            updateSettings = { transform -> scope.launch { deps.settings.update(transform) } },
            updatePrefs = { transform -> scope.launch { deps.prefs.update(transform) } },
            openAppSettings = { openAppSettings(context) },
        )
    }
}

fun grantsOf(context: Context): Map<String, Boolean> =
    PHONE_PERMISSIONS.associateWith { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

// Notifications ride along with the Bluetooth prompt; the foreground service only starts once Bluetooth is granted.
@Composable
private fun rememberSessionStarter(onBluetoothBlocked: (Boolean) -> Unit): (SessionPurpose) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf(SessionPurpose.GAME) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = bluetoothGranted(grants)
        onBluetoothBlocked(!granted)
        if (granted) startPhoneSession(context, pending.value)
    }
    return remember(launcher) {
        { purpose: SessionPurpose ->
            pending.value = purpose
            if (shouldRequestPermissions(grantsOf(context))) launcher.launch(PHONE_PERMISSIONS) else startPhoneSession(context, purpose)
        }
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}
