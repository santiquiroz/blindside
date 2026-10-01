package io.github.santiquiroz.blindside.phone.session

import android.content.pm.ServiceInfo
import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.ble.BeltRole
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionTraits

const val PHONE_RECORDING_TAG = "PHONE"
const val PHONE_FRAME_MS = 33L
const val PHONE_FOREGROUND_TYPES = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

// Spec §8 leaves the phone's own IMU out, so the pipeline gets belt data only; a diagnostic link records nothing and marks no session.
fun phoneTraits(purpose: SessionPurpose, vibrate: Boolean): SessionTraits {
    val game = purpose == SessionPurpose.GAME
    return SessionTraits(
        link = BeltLinkProfile(BeltRole.PHONE, activatesSession = game),
        records = game,
        readsDeviceSensors = false,
        vibrates = game && vibrate,
        framePeriodMs = PHONE_FRAME_MS,
        recordingTag = PHONE_RECORDING_TAG,
    )
}
