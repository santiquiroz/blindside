package io.github.santiquiroz.blindside.wear.recording

import android.content.Context
import java.io.File

private const val RECORDINGS_DIR = "recordings"

fun recordingsDir(context: Context): File =
    context.getExternalFilesDir(RECORDINGS_DIR) ?: File(context.filesDir, RECORDINGS_DIR)
