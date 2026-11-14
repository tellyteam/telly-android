package io.github.tellyteam.telly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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

/**
 * Settings → Sync. Sync is part of telly Plus, so it starts one of two ways: buying telly Plus (a
 * household is made for it and linked once it's paid), or the code of a household that has it.
 */
@Composable
fun SyncSection(
    status: SyncStatus,
    onStart: suspend (code: String?) -> String?,
    onSyncNow: () -> Unit,
    onStop: () -> Unit,
    onRefreshPlus: suspend () -> Unit,
    onCheckout: suspend () -> String,
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

    fun link(withCode: String) {
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
    val plus = status.plus
    if (config != null && plus?.until != null) {
        Text(
            when {
                status.busy -> "Syncing now…"
                status.lastSyncedAt != null -> "Keeping your place in sync. Last synced ${ago(status.lastSyncedAt)}."
                else -> "Keeping your place in sync."
            },
            fontSize = 13.sp,
            color = Telly.Text,
        )
        Text("telly Plus is on for this household, until ${dayText(plus.until)}.", fontSize = 13.sp, color = Telly.Muted)
        status.error?.let { Text(it, fontSize = 13.sp, color = Telly.Faint) }
        if (showCode) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(SyncLink.url(config.code), size = 150.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(SyncCrypto.formatCode(config.code), fontSize = 26.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = Telly.Text)
                    Text(
                        "Scan the QR code with your other device's camera, or enter the code in Settings → Sync on your other devices. Keep it private: anyone with it can see what you've watched.",
                        fontSize = 12.sp,
                        color = Telly.Faint,
                        modifier = Modifier.widthIn(max = 400.dp),
                    )
                }
            }
        }
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(if (status.busy) "Syncing…" else "Sync now", primary = false, onClick = { if (!status.busy) onSyncNow() }, modifier = Modifier.focusRequester(linkedFocus))
            // Stays put and only changes its label, so focus never falls out to the menu.
            PillButton(if (showCode) "Hide code" else "Show code to link another device", primary = false, onClick = { showCode = !showCode })
            StopButton(confirmStop, onAsk = { confirmStop = true }, onStop = { confirmStop = false; onStop() })
        }
        Text(
            "Provider logins are never shared: sign in on each device.",
            fontSize = 13.sp,
            color = Telly.Muted,
            modifier = Modifier.padding(top = 16.dp).widthIn(max = 560.dp),
        )
        return
    }

    // Linked to a household without telly Plus (it ran out): its code stays hidden, and it's the
    // same two choices as a device that's never synced, plus leaving the household.
    val lapsed = config != null
    if (lapsed && plus == null) {
        Text("Checking this household's telly Plus…", fontSize = 13.sp, color = Telly.Text)
        LaunchedEffect(config) { onRefreshPlus() }
        return
    }
    // Paid on a phone from an earlier QR code: the household links as soon as the relay says so.
    LaunchedEffect(Unit) { onRefreshPlus() }
    if (lapsed) {
        Text("Sync is off: this household's telly Plus has run out.", fontSize = 13.sp, color = Telly.Text)
    } else {
        Text(
            "Pick up where you left off on your other devices. Shares progress, Continue watching and My List, end-to-end encrypted. Provider logins are never shared, and the relay keeps no logs.",
            fontSize = 13.sp,
            color = Telly.Muted,
            modifier = Modifier.widthIn(max = 560.dp),
        )
    }
    Text(
        "Sync is part of telly Plus: free for 3 days, then £4.99 a year, for every device in your household.",
        fontSize = 13.sp,
        color = Telly.Text,
        modifier = Modifier.padding(top = 6.dp).widthIn(max = 560.dp),
    )
    BuyPlus(status, onRefreshPlus, onCheckout, Modifier.focusRequester(if (lapsed) linkedFocus else unlinkedFocus))
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("I already have telly Plus", primary = false, onClick = { joining = !joining })
            if (lapsed) PillButton("Unlink this device", primary = false, onClick = { joining = false; onStop() })
        }
        if (joining) {
            Text("Enter the code from Settings → Sync on one of your other devices.", fontSize = 13.sp, color = Telly.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                TvTextField("Code, e.g. ABCD-EFGH-JKMN-PQRS", code, KeyboardType.Ascii, modifier = Modifier.widthIn(max = 380.dp)) { code = it }
                PillButton(if (linking) "Linking…" else "Link", primary = true, onClick = { if (!linking && code.isNotBlank()) link(code) })
            }
        }
        error?.let { Text(it, fontSize = 13.sp, color = Telly.Accent, modifier = Modifier.widthIn(max = 560.dp)) }
    }
}

@Composable
private fun StopButton(confirming: Boolean, onAsk: () -> Unit, onStop: () -> Unit) {
    PillButton(if (confirming) "Press again to stop" else "Stop syncing on this device", primary = false, onClick = { if (confirming) onStop() else onAsk() })
}

/**
 * Get telly Plus: a QR code opens Paddle's checkout on a phone, and the TV waits for the relay to
 * hear of the payment. Linking happens by itself once it's paid.
 */
@Composable
private fun BuyPlus(status: SyncStatus, onRefresh: suspend () -> Unit, onCheckout: suspend () -> String, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    // While the QR code shows, the relay is asked whether the payment's come through: every few
    // seconds at first, then every half minute for as long as it's up.
    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        var asked = 0
        while (true) {
            delay(if (asked++ < 120) 5_000 else 30_000)
            onRefresh()
        }
    }
    LaunchedEffect(status.plus?.until) { if (status.plus?.until != null) link = null }

    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shown = link
        if (shown != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(shown, size = 150.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Scan with your phone to get telly Plus", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Telly.Text)
                    Text(
                        "Free for 3 days, then £4.99 a year, for every device in your household. Paddle takes your card; you're not charged until the 3 days are up. Sync starts here as soon as it's done.",
                        fontSize = 12.sp,
                        color = Telly.Faint,
                        modifier = Modifier.widthIn(max = 400.dp),
                    )
                }
            }
        } else {
            PillButton(if (asking) "Getting a link…" else "Get telly Plus", primary = true, modifier = modifier, onClick = {
                if (asking) return@PillButton
                asking = true
                error = null
                scope.launch {
                    try { link = onCheckout() } catch (e: Exception) { error = e.message ?: "Couldn't get a link." }
                    asking = false
                }
            })
        }
        error?.let { Text(it, fontSize = 13.sp, color = Telly.Accent) }
    }
}

/** A day count (days since 1970, as the relay keeps it) as a date, e.g. 2 November 2027. */
// Not java.time: it isn't there before Android 8, and Fire OS 6 is Android 7.1.
private fun dayText(day: Long): String =
    java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.UK)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(day * 86_400_000L))

private fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
