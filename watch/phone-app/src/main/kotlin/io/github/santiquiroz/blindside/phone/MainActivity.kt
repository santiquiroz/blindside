package io.github.santiquiroz.blindside.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import io.github.santiquiroz.blindside.phone.ui.theme.BlindsideTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BlindsideTheme {
                Surface(Modifier.fillMaxSize()) { Text("Blindside") }
            }
        }
    }
}
