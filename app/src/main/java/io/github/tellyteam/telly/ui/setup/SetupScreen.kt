package io.github.tellyteam.telly.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tellyteam.telly.data.DEFAULT_PLAYLIST_NAME
import io.github.tellyteam.telly.data.DEFAULT_PROVIDER_NAME
import io.github.tellyteam.telly.data.Provider
import io.github.tellyteam.telly.ui.DeviceKind
import androidx.compose.ui.platform.LocalContext
import io.github.tellyteam.telly.ui.theme.Telly
import io.github.tellyteam.telly.ui.theme.TellyWordmark
import io.github.tellyteam.telly.ui.theme.TvTextField
import io.github.tellyteam.telly.ui.theme.focusRing
import kotlinx.coroutines.delay

@Composable
fun SetupScreen(
    existing: Provider?,
    onConnect: (Provider) -> Unit,
    onForget: (() -> Unit)?,
    /** Set when opened from Settings: Back returns without changing anything. */
    onCancel: (() -> Unit)? = null,
    /** Set while this device is still linked for sync with no provider: a way to unlink. */
    onStopSync: (() -> Unit)? = null,
    /** On the first run, back to Welcome. */
    onBack: (() -> Unit)? = null,
) {
    BackHandler(enabled = onCancel != null || onBack != null) { (onCancel ?: onBack)?.invoke() }
    val xtream = existing as? Provider.Xtream
    val m3u = existing as? Provider.M3u
    var useXtream by rememberSaveable { mutableStateOf(m3u == null) }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var server by rememberSaveable { mutableStateOf(xtream?.server ?: "") }
    var username by rememberSaveable { mutableStateOf(xtream?.username ?: "") }
    var password by rememberSaveable { mutableStateOf(xtream?.password ?: "") }
    var url by rememberSaveable { mutableStateOf(m3u?.url ?: "") }
    var allowCleartext by rememberSaveable { mutableStateOf(existing?.allowCleartext ?: false) }
    var confirmCleartext by remember { mutableStateOf(false) }
    LaunchedEffect(confirmCleartext) { if (confirmCleartext) { delay(6_000); confirmCleartext = false } }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    val canConnect = if (useXtream) server.isNotBlank() && username.isNotBlank() && password.isNotBlank() else url.isNotBlank()

    fun provider(): Provider {
        val label = name.trim()
        return if (useXtream) {
            Provider.Xtream(label.ifEmpty { DEFAULT_PROVIDER_NAME }, server.trim(), username.trim(), password, allowCleartext)
        } else {
            Provider.M3u(label.ifEmpty { DEFAULT_PLAYLIST_NAME }, url.trim(), allowCleartext)
        }
    }

    // A TV asks one thing at a time, as on LG: the TV's keyboard stays up for one field, and OK goes on.
    val context = LocalContext.current
    if (remember { DeviceKind.isTv(context) }) {
        SetupSteps(
            existing = existing,
            useXtream = useXtream, onKind = { useXtream = it },
            server = server, onServer = { server = it },
            username = username, onUsername = { username = it },
            password = password, onPassword = { password = it },
            url = url, onUrl = { url = it },
            allowCleartext = allowCleartext, onCleartext = { allowCleartext = it },
            canConnect = canConnect,
            onConnect = { onConnect(provider()) },
            onForget = onForget,
            onCancel = onCancel,
            onBack = onBack,
            onStopSync = onStopSync.takeIf { existing == null && onCancel == null },
        )
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TellyWordmark(fontSize = 48.sp)
            Text(if (existing == null) "Add your provider" else "Your provider", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Sign in with the details your TV provider gave you. They're stored encrypted on this device and sent only to your provider.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = useXtream, onClick = { useXtream = true }, label = { Text("Username and password") }, modifier = Modifier.focusRing())
                FilterChip(selected = !useXtream, onClick = { useXtream = false }, label = { Text("Playlist link") }, modifier = Modifier.focusRing())
            }

            TvTextField("Name (optional)", name, modifier = Modifier.fillMaxWidth()) { name = it }
            if (useXtream) {
                TvTextField("Server, e.g. https://example.com:8080", server, KeyboardType.Uri, modifier = Modifier.fillMaxWidth()) { server = it }
                TvTextField("Username", username, modifier = Modifier.fillMaxWidth()) { username = it }
                TvTextField("Password", password, KeyboardType.Password, isPassword = !showPassword, modifier = Modifier.fillMaxWidth()) { password = it }
                FilterChip(
                    selected = showPassword,
                    onClick = { showPassword = !showPassword },
                    label = { Text(if (showPassword) "Hide password" else "Show password") },
                    modifier = Modifier.focusRing(),
                )
            } else {
                TvTextField("Playlist URL", url, KeyboardType.Uri, modifier = Modifier.fillMaxWidth()) { url = it }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Turning it on takes a second press, once the warning has been on screen.
                Switch(
                    checked = allowCleartext,
                    onCheckedChange = {
                        when {
                            !it -> allowCleartext = false
                            confirmCleartext -> { allowCleartext = true; confirmCleartext = false }
                            else -> confirmCleartext = true
                        }
                    },
                    modifier = Modifier.focusRing(),
                )
                Column {
                    Text(if (confirmCleartext) "Press again to allow unencrypted HTTP" else "Allow unencrypted HTTP")
                    Text(
                        when {
                            allowCleartext || confirmCleartext -> "Not encrypted. Anyone on the same network or along the way to your provider can read your username, password and what you watch."
                            else -> "Only if your provider has no HTTPS. Your login and what you watch would be sent unencrypted."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (allowCleartext || confirmCleartext) Telly.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    enabled = canConnect,
                    modifier = Modifier.focusRing(),
                    onClick = { onConnect(provider()) },
                ) { Text("Connect") }
                if (onBack != null) OutlinedButton(onClick = onBack, modifier = Modifier.focusRing()) { Text("Back") }
                if (onForget != null) {
                    OutlinedButton(onClick = onForget, modifier = Modifier.focusRing()) { Text("Remove provider") }
                }
            }
            if (onStopSync != null && existing == null && onCancel == null) {
                Text("This device is linked with your other devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
                Text(
                    "Add your provider to pick up where you left off on them. Use the same one, signed in the same way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onStopSync, modifier = Modifier.focusRing()) { Text("Stop syncing on this device") }
            }
        }
    }
}
