package io.github.santiquiroz.blindside.phone.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class PhonePrefsTest {
    @Test
    fun `vibration is off and the belt firmware unknown by default`() {
        assertEquals(PhonePrefs(vibrate = false, linkSupport = LinkSupport.UNKNOWN), phonePrefsFrom(emptyPreferences()))
    }

    @Test
    fun `prefs survive a write and a read`() {
        val prefs = mutablePreferencesOf()
        writePhonePrefs(prefs, PhonePrefs(vibrate = true, linkSupport = LinkSupport.DUAL_LINK))
        assertEquals(PhonePrefs(vibrate = true, linkSupport = LinkSupport.DUAL_LINK), phonePrefsFrom(prefs))
    }

    @Test
    fun `an unknown stored firmware support reads as unknown`() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("belt_link_support") to "TRIPLE_LINK")
        assertEquals(LinkSupport.UNKNOWN, phonePrefsFrom(prefs).linkSupport)
    }

    @Test
    fun `updates are visible to the next read`(@TempDir dir: File) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "phone.preferences_pb") }
        val repository = PhonePrefsRepository(store, onWriteFailed = {})
        val read = runBlocking {
            repository.update { it.copy(vibrate = true) }
            repository.current()
        }
        scope.cancel()
        assertEquals(PhonePrefs(vibrate = true), read)
    }

    @Test
    fun `a failed write is reported instead of thrown`() {
        val failures = mutableListOf<IOException>()
        val repository = PhonePrefsRepository(DiskFullStore(), onWriteFailed = { failures += it })
        assertFalse(runBlocking { repository.update { it.copy(vibrate = true) } })
        assertEquals(listOf("disk full"), failures.map { it.message })
    }

    private class DiskFullStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("disk full")
    }
}
