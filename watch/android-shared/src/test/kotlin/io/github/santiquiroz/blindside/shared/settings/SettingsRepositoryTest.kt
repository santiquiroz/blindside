package io.github.santiquiroz.blindside.shared.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class SettingsRepositoryTest {
    @Test
    fun `updates are visible to the next read`(@TempDir dir: File) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
        val repository = SettingsRepository(store, onWriteFailed = {})
        val (written, read) = runBlocking {
            val written = repository.update { it.copy(screenMode = ScreenMode.VISTA, eliminated = true) }
            written to repository.current()
        }
        scope.cancel()
        assertTrue(written)
        assertEquals(ScreenMode.VISTA, read.screenMode)
        assertTrue(read.eliminated)
    }

    @Test
    fun `a failed write is reported instead of thrown`() {
        val failures = mutableListOf<IOException>()
        val repository = SettingsRepository(DiskFullStore(), onWriteFailed = { failures += it })
        val written = runBlocking { repository.update { it.copy(eliminated = true) } }
        assertFalse(written)
        assertEquals(listOf("disk full"), failures.map { it.message })
    }

    @Test
    fun `shared edits are stamped with the repository clock`(@TempDir dir: File) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
        val repository = SettingsRepository(store, onWriteFailed = {}, clock = { 1_234L })
        val read = runBlocking {
            repository.update { it.copy(posture = WatchPosture.TACTICAL_LEFT) }
            repository.current()
        }
        scope.cancel()
        assertEquals(1_234L, read.sharedUpdatedMs)
    }

    private class DiskFullStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("disk full")
    }
}
