package io.github.santiquiroz.blindside.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material.MaterialTheme
import io.github.santiquiroz.blindside.wear.settings.settingsRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = settingsRepository()
        setContent { MaterialTheme { SpikeScreen(settings) } }
    }
}
