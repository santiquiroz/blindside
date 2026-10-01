package io.github.santiquiroz.blindside.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.santiquiroz.blindside.wear.session.SessionStore
import io.github.santiquiroz.blindside.wear.settings.settingsRepository
import io.github.santiquiroz.blindside.wear.ui.BlindsideApp
import io.github.santiquiroz.blindside.wear.ui.createAmbientObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A previous activity may have died in ambient; a new one always starts interactive.
        SessionStore.setAmbient(false)
        lifecycle.addObserver(createAmbientObserver(this))
        val settings = settingsRepository()
        setContent { BlindsideApp(settings) }
    }
}
