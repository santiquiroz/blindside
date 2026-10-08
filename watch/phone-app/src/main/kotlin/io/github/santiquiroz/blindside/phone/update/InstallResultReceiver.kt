package io.github.santiquiroz.blindside.phone.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = userActionIntent(intent) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateStore.set(UpdateState.Idle)
            else -> UpdateStore.set(UpdateState.Failed(currentInfo(), statusMessage(intent)))
        }
    }

    // minSdk is 33, so the typed getParcelableExtra is always available.
    private fun userActionIntent(intent: Intent): Intent? =
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)

    private fun statusMessage(intent: Intent): String =
        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "La instalación falló"

    private fun currentInfo(): ReleaseInfo? =
        when (val state = UpdateStore.state.value) {
            is UpdateState.Installing -> state.info
            is UpdateState.Downloading -> state.info
            is UpdateState.Available -> state.info
            is UpdateState.Failed -> state.info
            else -> null
        }
}
