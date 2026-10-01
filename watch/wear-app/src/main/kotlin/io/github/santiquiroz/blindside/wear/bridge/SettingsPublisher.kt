package io.github.santiquiroz.blindside.wear.bridge

import android.content.Context
import io.github.santiquiroz.blindside.shared.bridge.SETTINGS_PATH
import io.github.santiquiroz.blindside.shared.bridge.encodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.publishJson
import io.github.santiquiroz.blindside.shared.settings.SettingsRepository
import io.github.santiquiroz.blindside.shared.settings.publishableSharedSettings

suspend fun publishSharedSettings(context: Context, repository: SettingsRepository) {
    publishableSharedSettings(repository.settings)
        .collect { publishJson(context, SETTINGS_PATH, encodeSharedSettings(it)) }
}
