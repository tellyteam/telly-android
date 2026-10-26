package io.github.tellyteam.telly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.tellyteam.telly.data.sync.SyncCrypto
import io.github.tellyteam.telly.data.sync.SyncLink
import io.github.tellyteam.telly.ui.components.QrCode
import io.github.tellyteam.telly.ui.SyncStatus
import io.github.tellyteam.telly.ui.home.PillButton
import io.github.tellyteam.telly.ui.theme.Telly
import io.github.tellyteam.telly.ui.theme.TvTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings → Sync: link this device with a code, see when it last synced, or stop. */
@Composable
fun SyncSection(
    status: SyncStatus,
    onStart: suspend (code: String?) -> String?,
    onSyncNow: () -> Unit,
    onStop: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var joining by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    LaunchedEffect(confirmStop) { if (confirmStop) { delay(4_000); confirmStop = false } }

    // Linking or stopping swaps the buttons; put focus on the new ones rather than letting it
    // fall out to the menu. Not on first showing, when Settings chooses its own first focus.
    val linkedFocus = remember { FocusRequester() }
    val unlinkedFocus = remember { FocusRequester() }
    val linked = status.config != null
    var wasLinked by remember { mutableStateOf(linked) }
    LaunchedEffect(linked) {
        if (linked != wasLinked) {
            wasLinked = linked
            runCatching { (if (linked) linkedFocus else unlinkedFocus).requestFocus() }
        }
    }

    fun link(withCode: String?) {
        linking = true
        error = null
        scope.launch {
            error = onStart(withCode)
            linking = false
            if (error == null) {
                joining = false
                code = ""
                showCode = true
            }
        }
    }

    val config = status.config
    if (config != null) {
        Text(
            when {
                status.busy -> "Syncing now…"
                status.lastSyncedAt != null -> "Keeping your place in sync. Last synced ${ago(status.lastSyncedAt)}."
                else -> "Keeping your place in sync."
            },
            fontSize = 13.sp,
            color = Telly.Text,
        )
        status.error?.let { Text(it, fontSize = 13.sp, color = Telly.Faint) }
        if (showCode) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(SyncLink.url(config.code), size = 150.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(SyncCrypto.formatCode(config.code), fontSize = 26.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = Telly.Text)
                    Text(
                        "Scan the QR code with your iPad's camera, or enter the code in Settings → Sync on your other devices. Keep it private: anyone with it can see what you've watched.",
                        fontSize = 12.sp,
                        color = Telly.Faint,
                        modifier = Modifier.width(400.dp),
                    )
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(if (status.busy) "Syncing…" else "Sync now", primary = false, onClick = { if (!status.busy) onSyncNow() }, modifier = Modifier.focusRequester(linkedFocus))
            // Stays put and only changes its label, so focus never falls out to the menu.
            PillButton(if (showCode) "Hide code" else "Show code to link another device", primary = false, onClick = { showCode = !showCode })
            PillButton(
                if (confirmStop) "Press again to stop" else "Stop syncing on this device",
                primary = false,
                onClick = { if (confirmStop) { confirmStop = false; onStop() } else confirmStop = true },
            )
        }
        Text(
            "Provider logins are never shared: sign in on each device.",
            fontSize = 13.sp,
            color = Telly.Muted,
            modifier = Modifier.padding(top = 16.dp).width(560.dp),
        )
        return
    }

    Text(
        "Pick up where you left off on your other devices. Shares progress, Continue watching and My List, end-to-end encrypted. Provider logins are never shared.",
        fontSize = 13.sp,
        color = Telly.Muted,
        modifier = Modifier.width(560.dp),
    )
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(if (linking && !joining) "Starting…" else "Start syncing", primary = true, onClick = { if (!linking) link(null) }, modifier = Modifier.focusRequester(unlinkedFocus))
            PillButton("I have a code", primary = false, onClick = { joining = !joining })
        }
        if (joining) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvTextField("Code, e.g. ABCD-EFGH-JKMN-PQRS", code, KeyboardType.Ascii, modifier = Modifier.width(380.dp)) { code = it }
                PillButton(if (linking) "Linking…" else "Link", primary = true, onClick = { if (!linking && code.isNotBlank()) link(code) })
            }
        }
        error?.let { Text(it, fontSize = 13.sp, color = Telly.Accent, modifier = Modifier.width(560.dp)) }
    }
}

private fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
