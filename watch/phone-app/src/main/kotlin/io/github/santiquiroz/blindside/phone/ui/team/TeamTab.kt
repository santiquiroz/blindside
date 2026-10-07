package io.github.santiquiroz.blindside.phone.ui.team

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.santiquiroz.blindside.phone.tak.PackageResult
import io.github.santiquiroz.blindside.phone.tak.TakLinkService
import io.github.santiquiroz.blindside.phone.tak.TakPrefs
import io.github.santiquiroz.blindside.phone.tak.TakPrefsRepository
import io.github.santiquiroz.blindside.phone.tak.TakStore
import io.github.santiquiroz.blindside.phone.tak.importTakPackage
import io.github.santiquiroz.blindside.phone.tak.takPrefsRepository
import io.github.santiquiroz.blindside.phone.tak.takStatusText
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.SectionCard
import io.github.santiquiroz.blindside.phone.ui.common.SwitchRow
import io.github.santiquiroz.blindside.phone.ui.common.rememberNowMs
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
private val IMPORT_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

@Composable
fun TeamTab() {
    val context = LocalContext.current
    val repo = remember(context) { context.takPrefsRepository() }
    val prefs by repo.prefs.collectAsStateWithLifecycle(initialValue = TakPrefs())
    val takState by TakStore.state.collectAsStateWithLifecycle()
    val nowMs by rememberNowMs()
    var importMessage by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Equipo", style = MaterialTheme.typography.headlineSmall)
        Text(takStatusText(takState, nowMs), style = MaterialTheme.typography.bodyLarge)
        SectionCard("Servidor TAK") {
            ImportSection(prefs, importMessage, { importMessage = it })
            CallsignField(repo, prefs.callsign)
            val scope = rememberCoroutineScope()
            SwitchRow("Publicar contactos del radar", prefs.publishContacts) {
                scope.launch { repo.update { it.copy(publishContacts = !it.publishContacts) } }
            }
            ConnectButton(prefs.packageSummary != null, takState.running)
            Text(
                "Tu posición la publica ATAK. Blindside solo publica los contactos del radar y tus puntos tácticos.",
                style = MaterialTheme.typography.bodySmall,
                color = Text2Color,
            )
        }
    }
}

@Composable
private fun ImportSection(prefs: TakPrefs, importMessage: String?, onMessage: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = readImportBytes(context.contentResolver, uri)
            val result = bytes?.let { importTakPackage(context, it) }
            onMessage(
                when (result) {
                    is PackageResult.Ok -> null
                    is PackageResult.Invalid -> result.reason
                    null -> "No se pudo leer el archivo"
                },
            )
        }
    }
    OutlinedButton(
        onClick = { launcher.launch(IMPORT_MIME_TYPES) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH),
    ) { Text("Importar paquete (.zip)") }
    Text(prefs.packageSummary ?: "Sin paquete importado", style = MaterialTheme.typography.bodyMedium, color = Text2Color)
    importMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun CallsignField(repo: TakPrefsRepository, callsign: String) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var draft by remember(callsign) { mutableStateOf(callsign) }
    fun save() {
        if (draft == callsign) return
        scope.launch { repo.update { it.copy(callsign = draft.trim()) } }
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Tu callsign") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save(); focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) save() },
    )
}

@Composable
private fun ConnectButton(hasPackage: Boolean, running: Boolean) {
    val context = LocalContext.current
    // The result only lists what was asked, so an already granted location permission is read from the system.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val located = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (located) TakLinkService.start(context)
    }
    Button(
        onClick = {
            if (running) {
                TakLinkService.stop(context)
                return@Button
            }
            val missing = takPermissions().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) TakLinkService.start(context) else launcher.launch(missing.toTypedArray())
        },
        enabled = hasPackage || running,
        modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH),
    ) { Text(if (running) "Desconectar" else "Conectar al servidor") }
}

private fun takPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun readImportBytes(resolver: ContentResolver, uri: Uri): ByteArray? {
    resolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) return out.toByteArray()
            total += read
            if (total > MAX_IMPORT_BYTES) return null
            out.write(chunk, 0, read)
        }
    }
    return null
}
