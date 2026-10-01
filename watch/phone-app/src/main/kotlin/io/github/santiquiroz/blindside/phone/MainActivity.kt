package io.github.santiquiroz.blindside.phone

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.santiquiroz.blindside.phone.ui.PhoneApp
import io.github.santiquiroz.blindside.phone.ui.theme.BlindsideTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        val deps = PhoneDeps(applicationContext)
        setContent { BlindsideTheme { PhoneApp(deps) } }
    }
}
