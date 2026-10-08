package io.github.santiquiroz.blindside.phone.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

const val RELEASES_LATEST_URL = "https://api.github.com/repos/santiquiroz/blindside/releases/latest"

private const val CHECK_THROTTLE_MS = 12 * 3_600_000L
private const val TIMEOUT_MS = 8_000
private const val USER_AGENT = "Blindside-Android"

private val LAST_CHECK_MS = longPreferencesKey("last_check_ms")
private val DISMISSED_VERSION = stringPreferencesKey("dismissed_version")

private val Context.updatePrefs: DataStore<Preferences> by preferencesDataStore(name = "blindside_update")

class UpdateChecker(private val context: Context) {

    suspend fun check(force: Boolean = false) {
        val prefs = runCatching { context.updatePrefs.data.first() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        if (!force && now - (prefs[LAST_CHECK_MS] ?: 0L) < CHECK_THROTTLE_MS) return
        if (force) UpdateStore.set(UpdateState.Checking)
        val latest = fetchLatest()
        if (latest == null) {
            if (force) UpdateStore.set(UpdateState.Failed(null, "Sin conexión para buscar actualizaciones"))
            return
        }
        runCatching { context.updatePrefs.edit { it[LAST_CHECK_MS] = now } }
        val dismissed = prefs[DISMISSED_VERSION].orEmpty()
        if (!isOfferable(latest, dismissed, force)) {
            if (force) UpdateStore.set(UpdateState.UpToDate) else UpdateStore.set(UpdateState.Idle)
            return
        }
        UpdateStore.set(UpdateState.Available(latest))
    }

    suspend fun dismiss() {
        val current = UpdateStore.state.value as? UpdateState.Available ?: return
        UpdateStore.set(UpdateState.Idle)
        runCatching { context.updatePrefs.edit { it[DISMISSED_VERSION] = current.info.version } }
    }

    private fun isOfferable(latest: ReleaseInfo, dismissed: String, force: Boolean): Boolean {
        if (!VersionCompare.isNewer(latest.version, installedVersion())) return false
        if (!force && dismissed == latest.version) return false
        return true
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.0.0"

    private suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching { downloadReleaseJson() }.getOrNull()?.let(::parseLatestRelease)
    }

    private fun downloadReleaseJson(): String? {
        val connection = URL(RELEASES_LATEST_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }
}
