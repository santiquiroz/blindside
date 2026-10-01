package io.github.santiquiroz.blindside.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text

@Composable
fun NavChip(label: String, onClick: () -> Unit) {
    Chip(
        label = { Text(label, maxLines = 2) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SettingChip(title: String, value: String, onClick: () -> Unit) {
    Chip(
        label = { Text(title, maxLines = 2) },
        secondaryLabel = { Text(value, maxLines = 1) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun Notice(text: String) {
    Text(text, color = LABEL_GRAY, fontSize = 12.sp, textAlign = TextAlign.Center)
}
