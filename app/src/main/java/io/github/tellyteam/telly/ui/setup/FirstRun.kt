package io.github.tellyteam.telly.ui.setup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tellyteam.telly.BuildConfig
import io.github.tellyteam.telly.data.sync.SyncJoin
import io.github.tellyteam.telly.ui.DeviceKind
import io.github.tellyteam.telly.ui.components.QrCode
import io.github.tellyteam.telly.ui.home.PillButton
import io.github.tellyteam.telly.ui.theme.Telly
import io.github.tellyteam.telly.ui.theme.TellyWordmark
import io.github.tellyteam.telly.ui.theme.TvTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The first run's page: one column, the same on every device, scrolling on a phone. */
@Composable
private fun FirstRunPage(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }
}

/**
 * The first screen, the same words as on iPhone and LG: what telly is, and the two ways in.
 * Joining other devices waits with Sync for the hidden switch until telly Plus is on sale.
 */
@Composable
fun WelcomeScreen(canJoin: Boolean, onSetUp: () -> Unit, onJoin: () -> Unit) {
    val first = remember { FocusRequester() }
    FirstRunPage {
        TellyWordmark(fontSize = 48.sp)
        Text("Welcome to telly", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Telly.Text)
        Text("Watch the TV you already pay for, on all your devices.", fontSize = 18.sp, color = Telly.Muted)
        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "No ads, no accounts, no tracking.",
                "Your login stays on this device.",
                "Pick up where you left off on your other devices.",
            ).forEach { Text("•  $it", fontSize = 16.sp, color = Telly.Text) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Set up telly", primary = true, onClick = onSetUp, modifier = Modifier.focusRequester(first))
            if (canJoin) PillButton("I use telly on another device", primary = false, onClick = onJoin)
        }
        Text("telly doesn't come with any channels: you'll need a TV provider.", fontSize = 13.sp, color = Telly.Faint)
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * Joining the household's other devices: this device shows a QR code for an iPhone or iPad in the
 * household to scan (nothing to type with a remote), or takes another device's code.
 */
@Composable
fun JoinScreen(collect: suspend (SyncJoin) -> String?, onLink: suspend (String) -> String?, onSetUp: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var join by remember { mutableStateOf(SyncJoin.new()) }
    var typing by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    fun link(withCode: String) {
        linking = true
        error = null
        scope.launch {
            error = onLink(withCode)
            linking = false
        }
    }

    // While the QR code shows, the relay is asked every few seconds whether a code has been left.
    LaunchedEffect(join) {
        // Debug builds: the link, for playing the iPhone's side from a computer.
        if (BuildConfig.DEBUG) android.util.Log.d("telly", "join QR: ${join.url}")
        repeat(300) {
            delay(3_000)
            if (linking) return@repeat
            val found = try { collect(join) } catch (e: Exception) { error = e.message; join = SyncJoin.new(); return@LaunchedEffect }
            if (found != null) { link(found); return@LaunchedEffect }
        }
        join = SyncJoin.new()
    }

    FirstRunPage {
        Text("Join your other devices", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Telly.Text)
        Text("Syncing is part of telly Plus. Without it, each device keeps its own progress.", fontSize = 16.sp, color = Telly.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            QrCode(join.url, size = 170.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                Text(
                    "On your iPhone or iPad, open telly and go to Settings → Sync → Scan a new device's QR code.",
                    fontSize = 16.sp,
                    color = Telly.Muted,
                )
                Text(if (linking) "Linking…" else "Waiting for your other device…", fontSize = 14.sp, color = Telly.Faint)
            }
        }
        Text("Or use another device's code, from Settings → Sync → Show code.", fontSize = 14.sp, color = Telly.Muted, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            PillButton("I have a code", primary = false, onClick = { typing = !typing }, modifier = Modifier.focusRequester(first))
            PillButton("Set up without syncing", primary = false, onClick = onSetUp)
            PillButton("Back", primary = false, onClick = onBack)
        }
        if (typing) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                TvTextField("Code, e.g. ABCD-EFGH-JKMN-PQRS", code, KeyboardType.Ascii, modifier = Modifier.widthIn(max = 380.dp)) { code = it }
                PillButton(if (linking) "Linking…" else "Link", primary = true, onClick = { if (!linking && code.isNotBlank()) link(code) })
            }
        }
        error?.let { Text(it, fontSize = 14.sp, color = Telly.Accent) }
        Text("Provider logins are never shared: you'll sign in on this device next.", fontSize = 13.sp, color = Telly.Faint)
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * telly Plus, offered once after the first proper watch, over Home. A TV shows a QR code for the
 * checkout on a phone; a phone opens the checkout itself.
 */
@Composable
fun PlusOfferScreen(checkout: suspend () -> String, refresh: suspend () -> Unit, plusOn: Boolean, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val phone = remember { DeviceKind.isTouch(context) }
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(plusOn) { if (plusOn) onClose() }
    val done = remember { FocusRequester() }
    // While the TV's QR code shows, the relay is asked every few seconds whether the payment's come
    // through. Get telly Plus has gone, so focus moves to Done.
    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        runCatching { done.requestFocus() }
        repeat(120) { delay(5_000); refresh() }
    }

    fun get() {
        if (asking) return
        asking = true
        error = null
        scope.launch {
            try {
                val url = checkout()
                if (phone) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) else link = url
            } catch (e: Exception) {
                error = e.message ?: "Couldn't get a link."
            }
            asking = false
        }
    }

    Box(Modifier.fillMaxSize().background(Telly.Background)) {
        FirstRunPage {
            Text("telly Plus", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Telly.Text)
            Text("Pick up where you left off on your other devices: your phone, tablet and other TVs.", fontSize = 18.sp, color = Telly.Muted)
            Text("Free for 3 days, then £4.99 a year, for every device in your household. Everything else in telly stays free.", fontSize = 16.sp, color = Telly.Text)
            val shown = link
            if (shown != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCode(shown, size = 170.dp)
                    Text(
                        "Scan with your phone to get telly Plus. Paddle takes the payment; telly turns on here once it's done.",
                        fontSize = 15.sp,
                        color = Telly.Muted,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shown == null) PillButton(if (asking) "Getting a link…" else "Get telly Plus", primary = true, onClick = ::get, modifier = Modifier.focusRequester(first))
                PillButton(if (shown == null) "Not now" else "Done", primary = false, onClick = onClose, modifier = Modifier.focusRequester(done))
            }
            error?.let { Text(it, fontSize = 14.sp, color = Telly.Accent) }
            Text("You'll find it later in Settings → Sync.", fontSize = 13.sp, color = Telly.Faint)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
