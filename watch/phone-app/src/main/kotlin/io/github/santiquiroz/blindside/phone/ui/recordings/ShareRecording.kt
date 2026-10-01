package io.github.santiquiroz.blindside.phone.ui.recordings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.santiquiroz.blindside.phone.recordings.BSREC_MIME
import java.io.File

private const val FILE_PROVIDER_SUFFIX = ".files"

fun shareRecording(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(BSREC_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Compartir grabación"))
}
