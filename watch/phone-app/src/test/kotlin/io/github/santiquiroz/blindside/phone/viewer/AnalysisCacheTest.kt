package io.github.santiquiroz.blindside.phone.viewer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val WAIT_MS = 5_000L
private val FINISHED = AnalysisState.Failed("analizada")

class AnalysisCacheTest {
    @TempDir
    lateinit var dir: File

    private val owner = Job()
    private val scope = CoroutineScope(Dispatchers.Default + owner)

    private fun recording(name: String, bytes: Int = 16): File = File(dir, name).apply { writeBytes(ByteArray(bytes)) }

    @AfterEach
    fun cancelAnalyses() {
        owner.cancel()
    }

    @Test
    fun `coming back to the same recording reuses its analysis instead of starting over`() {
        runBlocking {
            val runs = AtomicInteger()
            val cache = AnalysisCache(scope) { _, _, _ -> FINISHED.also { runs.incrementAndGet() } }
            val file = recording("blindside-belt-20261001-142233.bsrec")
            val first = cache.analysisOf(file)
            assertSame(first, cache.analysisOf(file))
            assertEquals(FINISHED, withTimeout(WAIT_MS) { first.first { it !is AnalysisState.Running } })
            assertSame(first, cache.analysisOf(file))
            assertEquals(1, runs.get())
        }
    }

    @Test
    fun `opening another recording stops the running analysis at its next checkpoint`() {
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val cache = AnalysisCache(scope) { file, checkpoint, _ ->
                if (file.name.startsWith("long")) {
                    started.complete(Unit)
                    try {
                        while (true) checkpoint()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
                FINISHED
            }
            cache.analysisOf(recording("long.bsrec"))
            withTimeout(WAIT_MS) { started.await() }
            cache.analysisOf(recording("short.bsrec"))
            withTimeout(WAIT_MS) { stopped.await() }
        }
    }

    @Test
    fun `a recording downloaded again under the same name is analyzed again`() {
        val cache = AnalysisCache(scope) { _, _, _ -> FINISHED }
        val file = recording("blindside-watch-20261001-142233.bsrec", bytes = 16)
        val first = cache.analysisOf(file)
        file.writeBytes(ByteArray(32))
        assertNotSame(first, cache.analysisOf(file))
    }

    @Test
    fun `progress shows while the analysis runs`() {
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val cache = AnalysisCache(scope) { _, _, onProgress ->
                onProgress(0.5f)
                runBlocking { release.await() }
                FINISHED
            }
            val state = cache.analysisOf(recording("blindside-belt-20261001-150000.bsrec"))
            assertEquals(AnalysisState.Running(0.5f), withTimeout(WAIT_MS) { state.first { it == AnalysisState.Running(0.5f) } })
            release.complete(Unit)
            assertEquals(FINISHED, withTimeout(WAIT_MS) { state.first { it !is AnalysisState.Running } })
        }
    }
}
