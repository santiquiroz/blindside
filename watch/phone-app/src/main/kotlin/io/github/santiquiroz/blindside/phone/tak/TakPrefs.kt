package io.github.santiquiroz.blindside.phone.tak

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "TakPrefs"

data class TakPrefs(
    val callsign: String = "",
    val publishContacts: Boolean = true,
    val deviceId: String = "",
    val packageSummary: String? = null,
)

private object TakKeys {
    val CALLSIGN = stringPreferencesKey("tak_callsign")
    val PUBLISH_CONTACTS = booleanPreferencesKey("tak_publish_contacts")
    val DEVICE_ID = stringPreferencesKey("tak_device_id")
    val PACKAGE_SUMMARY = stringPreferencesKey("tak_package_summary")
}

private val Context.takPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "blindside_tak")

fun Context.takPrefsRepository(): TakPrefsRepository =
    TakPrefsRepository(applicationContext.takPrefsStore, onWriteFailed = { Log.w(TAG, "tak prefs write failed", it) })

fun takPrefsFrom(prefs: Preferences): TakPrefs = TakPrefs(
    callsign = prefs[TakKeys.CALLSIGN] ?: "",
    publishContacts = prefs[TakKeys.PUBLISH_CONTACTS] ?: true,
    deviceId = prefs[TakKeys.DEVICE_ID] ?: "",
    packageSummary = prefs[TakKeys.PACKAGE_SUMMARY],
)

fun writeTakPrefs(prefs: MutablePreferences, value: TakPrefs) {
    prefs[TakKeys.CALLSIGN] = value.callsign
    prefs[TakKeys.PUBLISH_CONTACTS] = value.publishContacts
    prefs[TakKeys.DEVICE_ID] = value.deviceId
    if (value.packageSummary == null) prefs.remove(TakKeys.PACKAGE_SUMMARY) else prefs[TakKeys.PACKAGE_SUMMARY] = value.packageSummary
}

class TakPrefsRepository(
    private val store: DataStore<Preferences>,
    private val onWriteFailed: (IOException) -> Unit,
) {
    val prefs: Flow<TakPrefs> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::takPrefsFrom)

    suspend fun current(): TakPrefs = prefs.first()

    suspend fun update(transform: (TakPrefs) -> TakPrefs): Boolean = try {
        store.edit { writeTakPrefs(it, transform(takPrefsFrom(it))) }
        true
    } catch (error: IOException) {
        onWriteFailed(error)
        false
    }
}

suspend fun importTakPackage(context: Context, zip: ByteArray): PackageResult {
    val result = readTakPackage(zip)
    val pkg = (result as? PackageResult.Ok)?.pkg ?: return result
    val app = context.applicationContext
    withContext(Dispatchers.IO) {
        val file = packageFile(app)
        file.parentFile?.mkdirs()
        file.writeBytes(zip)
    }
    app.takPrefsRepository().update { current ->
        current.copy(
            callsign = current.callsign.ifBlank { pkg.clientName },
            deviceId = deviceIdOf(pkg.clientName),
            packageSummary = "${pkg.host}:${pkg.port} · ${pkg.clientName}",
        )
    }
    return result
}

fun loadTakPackage(context: Context): TakPackage? {
    val file = packageFile(context.applicationContext)
    if (!file.isFile) return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    return (readTakPackage(bytes) as? PackageResult.Ok)?.pkg
}

private fun packageFile(context: Context): File = File(context.filesDir, "tak/package.zip")
