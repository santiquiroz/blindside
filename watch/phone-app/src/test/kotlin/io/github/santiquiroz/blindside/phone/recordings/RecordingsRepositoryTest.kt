package io.github.santiquiroz.blindside.phone.recordings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RecordingsRepositoryTest {
    private val a = "blindside-belt-20261001-100000.bsrec"
    private val b = "blindside-belt-20261001-110000.bsrec"

    @Test
    fun `lists recordings and ignores parts, folders and other files`(@TempDir dir: File) {
        File(dir, a).writeText("x")
        File(dir, "$b.part").writeText("x")
        File(dir, "c.txt").writeText("x")
        File(dir, "blindside-belt-20261001-120000.bsrec").mkdirs()
        val repository = RecordingsRepository(dir)
        assertEquals(listOf(a), repository.list().map { it.name })
        assertEquals(setOf(a), repository.names())
    }

    @Test
    fun `an unsafe or missing name never resolves to a file`(@TempDir dir: File) {
        File(dir, a).writeText("x")
        val repository = RecordingsRepository(File(dir, "sub").apply { mkdirs() })
        assertNull(repository.file("../$a"))
        assertNull(repository.file(b))
    }

    @Test
    fun `deleting removes only the named recording`(@TempDir dir: File) {
        File(dir, a).writeText("x")
        File(dir, b).writeText("x")
        val repository = RecordingsRepository(dir)
        assertTrue(repository.delete(a))
        assertFalse(repository.delete(a))
        assertEquals(setOf(b), repository.names())
    }

    @Test
    fun `a missing folder lists nothing`(@TempDir dir: File) {
        assertEquals(emptyList<LocalRecording>(), RecordingsRepository(File(dir, "nope")).list())
    }
}
