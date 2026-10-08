package io.github.santiquiroz.blindside.phone

import android.content.Context
import io.github.santiquiroz.blindside.phone.bridge.PhoneBridge
import io.github.santiquiroz.blindside.phone.recordings.RecordingsRepository
import io.github.santiquiroz.blindside.phone.settings.PhonePrefsRepository
import io.github.santiquiroz.blindside.phone.settings.phonePrefsRepository
import io.github.santiquiroz.blindside.phone.update.UpdateChecker
import io.github.santiquiroz.blindside.phone.update.UpdateInstaller
import io.github.santiquiroz.blindside.shared.recording.recordingsDir
import io.github.santiquiroz.blindside.shared.settings.SettingsRepository
import io.github.santiquiroz.blindside.shared.settings.settingsRepository

class PhoneDeps(context: Context) {
    val settings: SettingsRepository = context.settingsRepository()
    val prefs: PhonePrefsRepository = context.phonePrefsRepository()
    val bridge: PhoneBridge = PhoneBridge(context)
    val recordings: RecordingsRepository = RecordingsRepository(recordingsDir(context))
    val updateChecker: UpdateChecker = UpdateChecker(context)
    val updateInstaller: UpdateInstaller = UpdateInstaller(context)
}
