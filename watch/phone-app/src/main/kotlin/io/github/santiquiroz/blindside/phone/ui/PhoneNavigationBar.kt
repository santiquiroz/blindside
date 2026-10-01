package io.github.santiquiroz.blindside.phone.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.santiquiroz.blindside.phone.nav.PhoneTab
import io.github.santiquiroz.blindside.phone.nav.tabLabel
import io.github.santiquiroz.blindside.phone.ui.theme.AccentColor
import io.github.santiquiroz.blindside.phone.ui.theme.Surface2Color
import io.github.santiquiroz.blindside.phone.ui.theme.SurfaceColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color

@Composable
fun PhoneNavigationBar(selected: PhoneTab, onSelect: (PhoneTab) -> Unit) {
    NavigationBar(containerColor = SurfaceColor) {
        PhoneTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tabIcon(tab), contentDescription = null) },
                label = { Text(tabLabel(tab)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentColor,
                    selectedTextColor = AccentColor,
                    indicatorColor = Surface2Color,
                    unselectedIconColor = Text2Color,
                    unselectedTextColor = Text2Color,
                ),
            )
        }
    }
}

private fun tabIcon(tab: PhoneTab): ImageVector = when (tab) {
    PhoneTab.RADAR -> Icons.Filled.Radar
    PhoneTab.RECORDINGS -> Icons.Filled.FolderOpen
    PhoneTab.VIEWER -> Icons.Filled.Insights
    PhoneTab.BELT -> Icons.Filled.SettingsInputAntenna
}
