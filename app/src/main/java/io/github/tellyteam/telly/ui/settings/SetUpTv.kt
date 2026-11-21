package io.github.tellyteam.telly.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tellyteam.telly.data.Provider
import io.github.tellyteam.telly.data.store.SavedProvider
import io.github.tellyteam.telly.data.sync.SyncJoin
import io.github.tellyteam.telly.ui.components.QrScanner
import io.github.tellyteam.telly.ui.theme.Telly
import kotlinx.coroutines.launch

/**
 * Settings → Set up a TV, on a phone or tablet: scan the QR code a TV shows, then choose what to
 * send it: this household's link code ("Link it for sync", under today's rules) and/or a provider
 * (off every time). Only a code telly's own scanner read is acted on, never a link opened elsewhere.
 */
@Composable
fun SetUpTvScreen(
    /** This device can link another today: Sync shown, linked, and the household has telly Plus. */
    canLink: Boolean,
    /** Shown by name, address and username; the password is read only when Send is pressed. */
    providers: List<SavedProvider>,
    /** Sends what was chosen; throws with a readable message. */
    onSend: suspend (join: SyncJoin, link: Boolean, providerId: String?) -> Unit,
    onClose: () -> Unit,
) {
    var join by remember { mutableStateOf<SyncJoin?>(null) }
    var unknown by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(true) }
    var sendProvider by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(providers.singleOrNull()?.id) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler { if (join != null && !sent) { join = null; error = null } else onClose() }

    Column(
        Modifier.fillMaxSize().background(Telly.Background).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Set up a TV", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Telly.Text)
            val scanned = join
            when {
                sent -> {
                    Text("Sent. Check the details on your TV and connect.", fontSize = 16.sp, color = Telly.Text)
                    Button(onClick = onClose) { Text("Done") }
                }
                scanned == null -> {
                    Text("On the TV, open telly and start setting up. Then scan the QR code it shows.", fontSize = 16.sp, color = Telly.Muted)
                    QrScanner(
                        onText = { text ->
                            val parsed = SyncJoin.parse(text)
                            unknown = parsed == null
                            if (parsed != null) {
                                join = parsed
                                // Every time: linking on by default where it's offered, a provider off.
                                link = canLink
                                sendProvider = false
                                chosen = providers.singleOrNull()?.id
                                error = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Telly.Surface),
                    )
                    if (unknown) Text("That isn't a telly code. Scan the QR code on the TV's setup screen.", fontSize = 14.sp, color = Telly.Accent)
                    Text(
                        "No telly here yet? The TV's QR code also opens a web page to type your details on.",
                        fontSize = 13.sp,
                        color = Telly.Faint,
                    )
                    OutlinedButton(onClick = onClose) { Text("Cancel") }
                }
                else -> {
                    val offerLink = canLink
                    val offerProvider = scanned.takesProvider && providers.isNotEmpty()
                    if (!offerLink && !offerProvider) {
                        Text(
                            if (!scanned.takesProvider) "That TV runs an older telly, which only takes a sync link. Update telly on the TV, or link it from a device with telly Plus."
                            else "Add a provider to telly here first, to send it to the TV.",
                            fontSize = 16.sp,
                            color = Telly.Muted,
                        )
                    }
                    if (offerLink) {
                        Choice("Link it for sync", "Picks up where you left off on your other devices.", link) { link = it }
                    }
                    if (offerProvider) {
                        Choice("Send a provider", "Its login, so there's nothing to type on the TV.", sendProvider) { sendProvider = it }
                        if (sendProvider) {
                            providers.forEach { p ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { chosen = p.id }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (providers.size > 1) RadioButton(selected = chosen == p.id, onClick = { chosen = p.id })
                                    Column(Modifier.weight(1f)) {
                                        Text(p.provider.name, fontSize = 16.sp, color = Telly.Text)
                                        summary(p.provider).forEach { Text(it, fontSize = 13.sp, color = Telly.Muted) }
                                    }
                                }
                            }
                            Text(
                                "It's sealed so only the TV showing the code can read it; telly's relay can't. The password is sent too, but never shown here.",
                                fontSize = 13.sp,
                                color = Telly.Faint,
                            )
                        }
                    }
                    Text(
                        "Only do this for a TV of your own that's showing the code in front of you.",
                        fontSize = 13.sp,
                        color = Telly.Warning,
                    )
                    error?.let { Text(it, fontSize = 14.sp, color = Telly.Accent) }
                    val providerId = chosen.takeIf { sendProvider && offerProvider }
                    val ready = (link && offerLink) || providerId != null
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = ready && !sending,
                            onClick = {
                                sending = true
                                error = null
                                scope.launch {
                                    try {
                                        onSend(scanned, link && offerLink, providerId)
                                        sent = true
                                    } catch (e: Exception) {
                                        error = e.message ?: "Couldn't send."
                                    }
                                    sending = false
                                }
                            },
                        ) { Text(if (sending) "Sending…" else "Send") }
                        OutlinedButton(onClick = { join = null; error = null }) { Text("Scan again") }
                    }
                }
            }
        }
    }
}

/** What will be sent, as shown: the address and username, never the password. */
private fun summary(p: Provider): List<String> = when (p) {
    is Provider.Xtream -> listOf(p.server, "Username: ${p.username}")
    is Provider.M3u -> listOf(p.url)
}

@Composable
private fun Choice(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Telly.Text)
            Text(detail, fontSize = 13.sp, color = Telly.Muted)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
