package io.github.santiquiroz.blindside.phone.ui.viewer

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

private const val VIEWER_THREAD_NAME = "blindside-viewer"

// One background-priority thread runs every viewer job, so analyses and replays never take a core from the live radar's pipeline.
val viewerDispatcher: CoroutineDispatcher by lazy { Executors.newSingleThreadExecutor(::viewerThread).asCoroutineDispatcher() }

private fun viewerThread(task: Runnable): Thread = Thread({
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    task.run()
}, VIEWER_THREAD_NAME).apply { isDaemon = true }
