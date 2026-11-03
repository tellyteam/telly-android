package io.github.tellyteam.telly.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tellyteam.telly.data.Provider
import io.github.tellyteam.telly.ui.home.PillButton
import io.github.tellyteam.telly.ui.theme.Telly
import io.github.tellyteam.telly.ui.theme.TellyWordmark
import io.github.tellyteam.telly.ui.theme.TvTextField
import io.github.tellyteam.telly.ui.theme.focusRing
import kotlinx.coroutines.delay

private enum class Step { KIND, SERVER, USERNAME, PASSWORD, URL, REVIEW }

/**
 * Signing in on a TV, one thing at a time, as on LG: the kind of provider, then each detail, then a
 * summary to check and connect. Back goes back a step. Changing a saved provider starts at the
 * summary, where each detail can be changed.
 */
@Composable
internal fun SetupSteps(
    existing: Provider?,
    useXtream: Boolean, onKind: (Boolean) -> Unit,
    server: String, onServer: (String) -> Unit,
    username: String, onUsername: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    url: String, onUrl: (String) -> Unit,
    allowCleartext: Boolean, onCleartext: (Boolean) -> Unit,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onForget: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onBack: (() -> Unit)?,
    onStopSync: (() -> Unit)?,
) {
    var step by rememberSaveable { mutableStateOf(if (existing != null) Step.REVIEW else Step.KIND) }
    val trail = remember { mutableStateListOf<Step>() }
    var fromReview by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(step) { runCatching { focus.requestFocus() } }

    fun go(next: Step) { trail.add(step); step = next }
    val order = if (useXtream) listOf(Step.SERVER, Step.USERNAME, Step.PASSWORD, Step.REVIEW) else listOf(Step.URL, Step.REVIEW)
    fun next() {
        if (fromReview) { fromReview = false; trail.clear(); step = Step.REVIEW; return }
        go(order[order.indexOf(step) + 1])
    }
    fun change(s: Step) { fromReview = true; go(s) }

    BackHandler {
        if (trail.isNotEmpty()) { step = trail.removeAt(trail.lastIndex); fromReview = false }
        else (onCancel ?: onBack)?.invoke()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TellyWordmark(fontSize = 40.sp)
            when (step) {
                Step.KIND -> {
                    Title("Add your provider")
                    if (onStopSync != null) Hint("This TV is linked with your other devices. Add your provider to pick up where you left off on them: the same one, signed in the same way.")
                    Hint("What did your TV provider give you?")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("A server, username and password", primary = true, onClick = { onKind(true); trail.add(step); step = Step.SERVER }, modifier = Modifier.focusRequester(focus))
                        PillButton("A playlist link (M3U)", primary = false, onClick = { onKind(false); trail.add(step); step = Step.URL })
                    }
                    Hint("Your details stay on this TV, and are sent only to your provider.")
                    if (onBack != null) PillButton("Back", primary = false, onClick = onBack)
                }
                Step.SERVER -> Field("Server address", "As your provider gave it, with its port: for example example.com:8080.", server, KeyboardType.Uri, onServer, focus, ::next)
                Step.USERNAME -> Field("Username", "Your provider's username for you.", username, KeyboardType.Text, onUsername, focus, ::next)
                Step.PASSWORD -> Field("Password", "It stays on this TV, and is sent only to your provider.", password, KeyboardType.Password, onPassword, focus, ::next)
                Step.URL -> Field("Playlist address", "The M3U link your provider gave you.", url, KeyboardType.Uri, onUrl, focus, ::next)
                Step.REVIEW -> {
                    Title(if (existing == null) "Check and connect" else "Your provider")
                    if (useXtream) {
                        Detail("Server", server) { change(Step.SERVER) }
                        Detail("Username", username) { change(Step.USERNAME) }
                        Detail("Password", "•".repeat(password.length.coerceAtMost(12))) { change(Step.PASSWORD) }
                    } else {
                        Detail("Playlist", url) { change(Step.URL) }
                    }
                    CleartextSwitch(allowCleartext, onCleartext)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Connect", primary = true, onClick = { if (canConnect) onConnect() }, modifier = Modifier.focusRequester(focus))
                        if (onForget != null) PillButton("Remove provider", primary = false, onClick = onForget)
                        if (onCancel != null) PillButton("Cancel", primary = false, onClick = onCancel)
                    }
                    if (onStopSync != null) {
                        Text("This device is linked with your other devices", fontSize = 16.sp, color = Telly.Text, modifier = Modifier.padding(top = 16.dp))
                        Hint("Add your provider to pick up where you left off on them. Use the same one, signed in the same way.")
                        PillButton("Stop syncing on this device", primary = false, onClick = onStopSync)
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Telly.Text)

@Composable
private fun Hint(text: String) = Text(text, fontSize = 15.sp, color = Telly.Muted)

@Composable
private fun Field(label: String, hint: String, value: String, type: KeyboardType, onChange: (String) -> Unit, focus: FocusRequester, onNext: () -> Unit) {
    Title(label)
    Hint(hint)
    TvTextField(label, value, type, isPassword = type == KeyboardType.Password, modifier = Modifier.fillMaxWidth(), focusRequester = focus, onChange = onChange)
    PillButton("Next", primary = true, onClick = { if (value.isNotBlank()) onNext() })
}

@Composable
private fun Detail(label: String, value: String, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = Telly.Muted)
            Text(value, fontSize = 17.sp, color = Telly.Text)
        }
        PillButton("Change", primary = false, onClick = onChange)
    }
}

/** "Allow unencrypted HTTP": turning it on takes a second press, once the warning has been on screen. */
@Composable
private fun CleartextSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(6_000); confirm = false } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Switch(
            checked = on,
            onCheckedChange = {
                when {
                    !it -> onChange(false)
                    confirm -> { onChange(true); confirm = false }
                    else -> confirm = true
                }
            },
            modifier = Modifier.focusRing(),
        )
        Column {
            Text(if (confirm) "Press again to allow unencrypted HTTP" else "Allow unencrypted HTTP", color = Telly.Text)
            Text(
                if (on || confirm) "Not encrypted. Anyone on the same network or along the way to your provider can read your username, password and what you watch."
                else "Only if your provider has no HTTPS. Your login and what you watch would be sent unencrypted.",
                fontSize = 12.sp,
                color = if (on || confirm) Telly.Warning else Telly.Muted,
            )
        }
    }
}
