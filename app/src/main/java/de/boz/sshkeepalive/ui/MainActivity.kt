package de.boz.sshkeepalive.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.boz.sshkeepalive.data.ConnectionSettings
import de.boz.sshkeepalive.data.CredentialStore
import de.boz.sshkeepalive.data.KnownHostsStore
import de.boz.sshkeepalive.service.ConnectionStateHolder
import de.boz.sshkeepalive.service.ConnectionUiState
import de.boz.sshkeepalive.service.HostKeyPrompt
import de.boz.sshkeepalive.service.KeepAliveService
import de.boz.sshkeepalive.service.Status
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var afterPermission: (() -> Unit)? = null
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Auch ohne Erlaubnis starten – der Dienst läuft dann, nur die Benachrichtigung ist versteckt.
            afterPermission?.invoke()
            afterPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            SshKeepAliveTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Bg) {
                    MainScreen(onStart = ::startWithPermission)
                }
            }
        }
    }

    private fun startWithPermission() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            afterPermission = { KeepAliveService.start(this) }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            KeepAliveService.start(this)
        }
    }
}

// =====================================================================================

@Composable
private fun MainScreen(onStart: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CredentialStore(context) }
    val knownHosts = remember { KnownHostsStore(context) }
    val initial = remember { store.loadSettings() }
    val conn by ConnectionStateHolder.state.collectAsStateWithLifecycle()

    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var user by rememberSaveable { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var wifiOnly by rememberSaveable { mutableStateOf(initial.wifiOnly) }
    var hasStoredPassword by remember { mutableStateOf(store.hasPassword()) }
    var formError by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val power = remember { context.getSystemService(PowerManager::class.java) }
    var batteryUnrestricted by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        batteryUnrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }

    val running = conn.running
    val editable = !running

    fun start() {
        val h = host.trim()
        val p = port.trim().toIntOrNull()
        formError = when {
            h.isEmpty() -> "Bitte Serveradresse eingeben."
            h.any { it.isWhitespace() } -> "Die Serveradresse darf keine Leerzeichen enthalten."
            p == null || p !in 1..65535 -> "Port muss zwischen 1 und 65535 liegen."
            user.isBlank() -> "Bitte Benutzernamen eingeben."
            password.isEmpty() && !hasStoredPassword -> "Bitte Passwort eingeben."
            else -> null
        }
        if (formError != null) return
        store.saveSettings(ConnectionSettings(h, p!!, user.trim(), wifiOnly))
        if (password.isNotEmpty()) {
            store.savePassword(password.toCharArray())
            password = ""
            hasStoredPassword = true
        }
        onStart()
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header()
        StatusCard(conn)

        // ---------------------------------------------------------------- Eingaben
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Verbindung", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                AssistChip(
                    enabled = editable,
                    onClick = { host = CredentialStore.STUDNET_HOST; port = "22"; wifiOnly = true },
                    label = { Text("StudNET Leipzig") },
                    colors = AssistChipDefaults.assistChipColors(labelColor = Palette.BlueLight),
                    border = AssistChipDefaults.assistChipBorder(enabled = editable, borderColor = Palette.Outline),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(
                    value = host, onChange = { host = it }, label = "Server (IP oder Hostname)",
                    enabled = editable, modifier = Modifier.weight(1f),
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                Field(
                    value = port, onChange = { v -> port = v.filter { it.isDigit() }.take(5) }, label = "Port",
                    enabled = editable, modifier = Modifier.width(92.dp),
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
            }
            Field(
                value = user, onChange = { user = it }, label = "Benutzername",
                supporting = "Bei StudNET: deine Mieternummer",
                enabled = editable,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            )
            Field(
                value = password, onChange = { password = it }, label = "Passwort",
                placeholder = if (hasStoredPassword) "•••••••• gespeichert" else null,
                supporting = if (hasStoredPassword) "Leer lassen, um das gespeicherte Passwort zu verwenden" else null,
                enabled = editable,
                visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                trailing = {
                    TextButton(onClick = { showPassword = !showPassword }, enabled = editable) {
                        Text(if (showPassword) "Verbergen" else "Zeigen", fontSize = 13.sp)
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Nur über WLAN verbinden", fontWeight = FontWeight.Medium)
                    Text(
                        "Empfohlen für StudNET: Der Login muss über das Wohnheim-WLAN laufen, nicht über mobile Daten.",
                        color = Palette.Muted, fontSize = 13.sp, lineHeight = 17.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = wifiOnly, onCheckedChange = { wifiOnly = it }, enabled = editable,
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Blue),
                )
            }
            formError?.let {
                Text(it, color = Palette.Red, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        // ---------------------------------------------------------------- START / STOP
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = ::start,
                enabled = !running,
                modifier = Modifier.weight(1f).height(68.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Blue,
                    disabledContainerColor = Palette.SurfaceHigh,
                    disabledContentColor = Palette.Muted,
                ),
            ) { Text("START", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
            Button(
                onClick = { KeepAliveService.stop(context) },
                enabled = running,
                modifier = Modifier.weight(1f).height(68.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Red,
                    disabledContainerColor = Palette.SurfaceHigh,
                    disabledContentColor = Palette.Muted,
                ),
            ) { Text("STOP", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
        }

        // ---------------------------------------------------------------- Hintergrundbetrieb
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, tint = Palette.BlueLight, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Hintergrundbetrieb", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Die Verbindung läuft als sichtbarer Dienst mit Benachrichtigung weiter – auch minimiert und bei " +
                    "gesperrtem Bildschirm. Keepalive alle 30 s, automatische Wiederverbindung. " +
                    "Android oder Energiesparfunktionen des Herstellers können den Dienst trotzdem beenden; " +
                    "eine garantierte 24/7-Laufzeit gibt es nicht.",
                color = Palette.Muted, fontSize = 14.sp, lineHeight = 19.sp,
            )
            if (!batteryUnrestricted) {
                BatteryButton()
            } else {
                Text("✓ Akku-Optimierung ist für diese App deaktiviert.", color = Palette.Green, fontSize = 14.sp)
            }
        }

        TextButton(
            onClick = { confirmClear = true },
            enabled = editable,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Gespeicherte Daten löschen")
        }
        Row(
            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lock, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Zugangsdaten nur lokal, verschlüsselt im Android Keystore.",
                color = Palette.Muted, fontSize = 12.sp,
            )
        }
    }

    // ---------------------------------------------------------------- Dialoge
    conn.hostKeyPrompt?.let { HostKeyDialog(it) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Gespeicherte Daten löschen?") },
            text = { Text("Benutzername, Passwort und bestätigte Hostschlüssel werden von diesem Gerät gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    store.clearAll(); knownHosts.clearAll()
                    host = CredentialStore.STUDNET_HOST; port = "22"; user = ""; password = ""
                    wifiOnly = true; hasStoredPassword = false; confirmClear = false
                }) { Text("Löschen", color = Palette.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
            containerColor = Palette.Surface,
        )
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh)
                .border(1.dp, Palette.Outline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(">_", color = Palette.BlueLight, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("SSH KeepAlive", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Text("Hält deine SSH-Anmeldung offen", color = Palette.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun StatusCard(conn: ConnectionUiState) {
    val target = when (conn.status) {
        Status.CONNECTED -> Palette.Green
        Status.CONNECTING -> Palette.Amber
        Status.ERROR -> Palette.Red
        Status.DISCONNECTED -> Palette.Muted
    }
    val color by animateColorAsState(target, label = "statusColor")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = if (conn.status == Status.CONNECTING) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha",
    )

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(conn.connectedSince, conn.nextRetryAt) {
        while (conn.connectedSince != null || conn.nextRetryAt != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.Surface)
            .border(1.5.dp, color.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).alpha(alpha).clip(CircleShape).background(color.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(12.dp).clip(CircleShape).background(color)) }
                Spacer(Modifier.width(12.dp))
                Text(conn.status.label, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
                Spacer(Modifier.weight(1f))
                conn.networkLabel?.takeIf { conn.running }?.let {
                    Text(
                        it, color = Palette.BlueLight, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(conn.detail, color = Palette.Text, fontSize = 15.sp, lineHeight = 20.sp)
            conn.connectedSince?.let {
                Text("Verbunden seit ${formatDuration(now - it)}", color = Palette.Green, fontSize = 14.sp)
            }
            conn.nextRetryAt?.let {
                val secs = ((it - now) / 1000).coerceAtLeast(0)
                Text("Neuer Versuch in ${secs} s · Versuch ${conn.attempt + 1}", color = Palette.Amber, fontSize = 14.sp)
            }
            conn.serverText?.takeIf { it.isNotBlank() && conn.running }?.let {
                Text("Servermeldung", color = Palette.Muted, fontSize = 12.sp)
                Text(
                    it.lines().take(6).joinToString("\n"),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Palette.Text,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Palette.Bg).padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun HostKeyDialog(prompt: HostKeyPrompt) {
    val changed = prompt is HostKeyPrompt.Changed
    AlertDialog(
        onDismissRequest = { /* bewusste Entscheidung erzwingen */ },
        icon = { Icon(Icons.Filled.Warning, null, tint = if (changed) Palette.Red else Palette.Amber) },
        title = { Text(if (changed) "Serveridentität hat sich geändert!" else "Unbekannte Serveridentität") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (changed) {
                    Text(
                        "Der Server meldet einen ANDEREN Schlüssel als beim letzten Mal. Das kann ein Angriff " +
                            "(Man-in-the-Middle) sein – oder der Server wurde neu eingerichtet. Nur akzeptieren, " +
                            "wenn du sicher bist.",
                        color = Palette.Red,
                    )
                    Text("Bisher bekannt:", color = Palette.Muted, fontSize = 13.sp)
                    Mono((prompt as HostKeyPrompt.Changed).trustedFingerprint)
                } else {
                    Text("Du verbindest dich zum ersten Mal mit diesem Server. Prüfe den Fingerprint, bevor du ihm vertraust.")
                }
                Text("Server: ${prompt.info.hostId}", color = Palette.Muted, fontSize = 13.sp)
                Text("Neuer Schlüssel (${prompt.info.keyType}):", color = Palette.Muted, fontSize = 13.sp)
                Mono(prompt.info.sha256)
            }
        },
        confirmButton = {
            TextButton(onClick = { ConnectionStateHolder.resolveHostKey(true) }) {
                Text(if (changed) "Trotzdem vertrauen" else "Vertrauen & verbinden", color = if (changed) Palette.Red else Palette.Green)
            }
        },
        dismissButton = {
            TextButton(onClick = { ConnectionStateHolder.resolveHostKey(false) }) { Text("Abbrechen") }
        },
        containerColor = Palette.Surface,
    )
}

@Composable
private fun Mono(text: String) {
    Text(
        text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Palette.Text,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Bg).padding(8.dp),
    )
}

@SuppressLint("BatteryLife")
@Composable
private fun BatteryButton() {
    val context = LocalContext.current
    Button(
        onClick = {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(direct) }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHigh, contentColor = Palette.BlueLight),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Akku-Optimierung deaktivieren (empfohlen)") }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null,
    supporting: String? = null,
    visual: VisualTransformation = VisualTransformation.None,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Palette.Muted) } },
        supportingText = supporting?.let { { Text(it, color = Palette.Muted) } },
        enabled = enabled,
        singleLine = true,
        visualTransformation = visual,
        keyboardOptions = keyboard,
        trailingIcon = trailing,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Blue,
            unfocusedBorderColor = Palette.Outline,
            focusedLabelColor = Palette.BlueLight,
            cursorColor = Palette.Blue,
            disabledTextColor = Palette.Text.copy(alpha = 0.7f),
            disabledBorderColor = Palette.Outline.copy(alpha = 0.5f),
            disabledLabelColor = Palette.Muted,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d h".format(h, m, s) else "%d:%02d min".format(m, s)
}
