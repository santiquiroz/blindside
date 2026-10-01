package io.github.santiquiroz.blindside.shared.session

import android.content.Context
import android.os.PowerManager

class SessionWakeLock(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
        .apply { setReferenceCounted(false) }

    fun acquireOrRenew() = lock.acquire(TIMEOUT_MS)

    fun release() {
        if (lock.isHeld) lock.release()
    }

    companion object {
        private const val TAG = "blindside:session"
        private const val TIMEOUT_MS = 10 * 60 * 1_000L
        const val RENEW_EVERY_MS = 9 * 60 * 1_000L
    }
}
