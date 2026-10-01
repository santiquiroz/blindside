package io.github.santiquiroz.blindside.wear.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsRepositoryTest {
    @Test
    fun `updates are visible to the next read`(@TempDir dir: File) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
        val repository = SettingsRepository(store)
        val read = runBlocking {
            repository.update { it.copy(screenMode = ScreenMode.VISTA, eliminated = true) }
            repository.current()
        }
        scope.cancel()
        assertEquals(ScreenMode.VISTA, read.screenMode)
        assertTrue(read.eliminated)
    }
}
