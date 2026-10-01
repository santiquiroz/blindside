package io.github.santiquiroz.blindside.shared.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val TAG = "SettingsRepository"

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "blindside_settings")

fun Context.settingsRepository(): SettingsRepository =
    SettingsRepository(applicationContext.settingsDataStore, onWriteFailed = ::logWriteFailure)

private fun logWriteFailure(error: IOException) {
    Log.w(TAG, "settings write failed", error)
}

class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val onWriteFailed: (IOException) -> Unit,
) {
    val settings: Flow<AppSettings> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::settingsFrom)

    suspend fun current(): AppSettings = settings.first()

    // DataStore throws IOException (CorruptionException included) on a full or broken disk; callers run without a handler.
    suspend fun update(transform: SettingsTransform): Boolean = try {
        store.edit { prefs -> writeSettings(prefs, transform(settingsFrom(prefs))) }
        true
    } catch (error: IOException) {
        onWriteFailed(error)
        false
    }
}
