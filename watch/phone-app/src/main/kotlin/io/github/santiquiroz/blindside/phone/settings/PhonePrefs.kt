package io.github.santiquiroz.blindside.phone.settings

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
import io.github.santiquiroz.blindside.shared.settings.enumOrDefault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val TAG = "PhonePrefs"

data class PhonePrefs(val vibrate: Boolean = false, val linkSupport: LinkSupport = LinkSupport.UNKNOWN)

private object PhoneKeys {
    val VIBRATE = booleanPreferencesKey("phone_vibrate")
    val LINK_SUPPORT = stringPreferencesKey("belt_link_support")
}

private val Context.phonePrefsStore: DataStore<Preferences> by preferencesDataStore(name = "blindside_phone")

fun Context.phonePrefsRepository(): PhonePrefsRepository =
    PhonePrefsRepository(applicationContext.phonePrefsStore, onWriteFailed = { Log.w(TAG, "phone prefs write failed", it) })

fun phonePrefsFrom(prefs: Preferences): PhonePrefs = PhonePrefs(
    vibrate = prefs[PhoneKeys.VIBRATE] ?: false,
    linkSupport = enumOrDefault(prefs[PhoneKeys.LINK_SUPPORT], LinkSupport.UNKNOWN),
)

fun writePhonePrefs(prefs: MutablePreferences, value: PhonePrefs) {
    prefs[PhoneKeys.VIBRATE] = value.vibrate
    prefs[PhoneKeys.LINK_SUPPORT] = value.linkSupport.name
}

class PhonePrefsRepository(
    private val store: DataStore<Preferences>,
    private val onWriteFailed: (IOException) -> Unit,
) {
    val prefs: Flow<PhonePrefs> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::phonePrefsFrom)

    suspend fun current(): PhonePrefs = prefs.first()

    suspend fun update(transform: (PhonePrefs) -> PhonePrefs): Boolean = try {
        store.edit { writePhonePrefs(it, transform(phonePrefsFrom(it))) }
        true
    } catch (error: IOException) {
        onWriteFailed(error)
        false
    }
}
