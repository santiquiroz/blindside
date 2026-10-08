package io.github.santiquiroz.blindside.phone.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateInstaller(private val context: Context) {

    suspend fun downloadAndInstall(info: ReleaseInfo) {
        val apkUrl = info.apkUrl
        if (apkUrl == null || !isTrustedDownloadUrl(apkUrl)) {
            UpdateStore.set(UpdateState.Failed(info, "Enlace de descarga no confiable"))
            return
        }
        val file = try {
            withContext(Dispatchers.IO) { downloadApk(info, apkUrl) }
        } catch (failure: UpdateFailure) {
            UpdateStore.set(UpdateState.Failed(info, failure.message ?: "Descarga incompleta"))
            return
        } catch (_: Exception) {
            UpdateStore.set(UpdateState.Failed(info, "Sin conexión para descargar la actualización"))
            return
        }
        UpdateStore.set(UpdateState.Installing(info))
        try {
            withContext(Dispatchers.IO) { installApk(file) }
        } catch (_: Exception) {
            UpdateStore.set(UpdateState.Failed(info, "No se pudo instalar la actualización"))
        }
    }

    private fun downloadApk(info: ReleaseInfo, apkUrl: String): File {
        val dir = File(context.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "blindside-celular-${info.version}.apk")
        val connection = URL(apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw UpdateFailure("Descarga incompleta")
            if (!isTrustedDownloadUrl(connection.url.toString())) throw UpdateFailure("Enlace de descarga no confiable")
            writeBody(info, connection, file)
        } finally {
            connection.disconnect()
        }
        if (info.apkBytes != null && file.length() != info.apkBytes) {
            file.delete()
            throw UpdateFailure("Descarga incompleta")
        }
        return file
    }

    private fun writeBody(info: ReleaseInfo, connection: HttpURLConnection, file: File) {
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.apkBytes
        UpdateStore.set(UpdateState.Downloading(info, 0f))
        var lastEmitted = 0f
        var downloaded = 0L
        connection.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    lastEmitted = emitProgress(info, total, downloaded, lastEmitted)
                }
            }
        }
    }

    private fun emitProgress(info: ReleaseInfo, total: Long?, downloaded: Long, lastEmitted: Float): Float {
        if (total == null || total <= 0) return lastEmitted
        val progress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
        if (progress - lastEmitted < PROGRESS_STEP && progress < 1f) return lastEmitted
        UpdateStore.set(UpdateState.Downloading(info, progress))
        return progress
    }

    private fun installApk(file: File) {
        val installer = context.packageManager.packageInstaller
        val sessionId = installer.createSession(SessionParamsFactory.fullInstall())
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite(SESSION_APK_NAME, 0, file.length()).use { output ->
                    input.copyTo(output)
                }
            }
            session.commit(statusReceiver(sessionId).intentSender)
        }
    }

    private fun statusReceiver(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java)
        // Mutable so PackageInstaller can attach its result extras.
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }
}

private class UpdateFailure(message: String) : Exception(message)

private object SessionParamsFactory {
    fun fullInstall(): PackageInstaller.SessionParams =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
}

private const val TIMEOUT_MS = 8_000
private const val USER_AGENT = "Blindside-Android"
private const val BUFFER_SIZE = 8_192
private const val PROGRESS_STEP = 0.05f
private const val SESSION_APK_NAME = "blindside"
