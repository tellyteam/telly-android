package io.github.tellyteam.telly.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import io.github.tellyteam.telly.BuildConfig
import io.github.tellyteam.telly.ui.SyncStatus
import io.github.tellyteam.telly.ui.UpdateState
import io.github.tellyteam.telly.data.AccountText
import io.github.tellyteam.telly.data.PlaybackSettings
import io.github.tellyteam.telly.data.Provider
import io.github.tellyteam.telly.data.store.CategoryKind
import io.github.tellyteam.telly.data.store.CategoryPrefs
import io.github.tellyteam.telly.data.store.SavedProvider
import io.github.tellyteam.telly.data.xtream.XtreamParser
import io.github.tellyteam.telly.ui.Load
import io.github.tellyteam.telly.ui.components.PageInset
import io.github.tellyteam.telly.ui.home.PillButton
import io.github.tellyteam.telly.ui.theme.Telly

/** Downloader (AFTVnews) code that always fetches the latest release. */
private const val DOWNLOADER_CODE = "3327748"

@Composable
fun SettingsScreen(
    provider: Provider,
    providers: List<SavedProvider>,
    activeId: String?,
    onSwitchProvider: (String) -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (SavedProvider) -> Unit,
    /** Removes the provider in use. */
    onRemoveProvider: () -> Unit,
    updating: Boolean,
    onClearSavedData: () -> Unit,
    update: UpdateState,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    account: Load<XtreamParser.Account>,
    onLoadAccount: () -> Unit,
    /** Each list's categories (name and item count), in the provider's order. */
    categories: Map<CategoryKind, List<Pair<String, Int>>>,
    categoryPrefs: Map<CategoryKind, CategoryPrefs>,
    onTogglePinned: (CategoryKind, String) -> Unit,
    onToggleHidden: (CategoryKind, String) -> Unit,
    onResetCategories: (CategoryKind) -> Unit,
    sync: SyncStatus,
    syncUnlocked: Boolean,
    /** Seven presses on the version show (or hide) Sync. */
    onToggleSyncUnlocked: () -> Unit,
    onStartSync: suspend (code: String?, relay: String) -> String?,
    onSyncNow: () -> Unit,
    onStopSync: () -> Unit,
    onClearProgress: () -> Unit,
) {
    var managing by rememberSaveable { mutableStateOf<CategoryKind?>(null) }
    managing?.let { kind ->
        CategoryManager(
            kind = kind,
            categories = categories[kind].orEmpty(),
            prefs = categoryPrefs[kind] ?: CategoryPrefs(),
            onTogglePinned = { onTogglePinned(kind, it) },
            onToggleHidden = { onToggleHidden(kind, it) },
            onReset = { onResetCategories(kind) },
            onDone = { managing = null },
        )
        return
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(provider) { if (provider is Provider.Xtream) onLoadAccount() }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmClearProgress by remember { mutableStateOf(false) }
    LaunchedEffect(confirmClearProgress) { if (confirmClearProgress) { delay(4_000); confirmClearProgress = false } }
    // A second press within a few seconds confirms; otherwise the button resets.
    LaunchedEffect(confirmClear) { if (confirmClear) { delay(4_000); confirmClear = false } }
    LaunchedEffect(confirmRemove) { if (confirmRemove) { delay(4_000); confirmRemove = false } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = PageInset, top = 22.dp, end = 40.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Telly.Text)

        if (providers.size > 1) {
            Section("Providers") {
                Text(
                    "Each provider keeps its own My List, progress and Continue watching.",
                    fontSize = 13.sp,
                    color = Telly.Muted,
                    modifier = Modifier.width(560.dp),
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    providers.forEach { saved ->
                        val inUse = saved.id == activeId
                        PillButton(
                            if (inUse) "✓ ${saved.provider.name}" else saved.provider.name,
                            primary = inUse,
                            onClick = { if (!inUse) onSwitchProvider(saved.id) },
                        )
                    }
                }
            }
        }

        Section(if (providers.size > 1) "In use" else "Your provider") {
            Text(provider.name, fontSize = 17.sp, color = Telly.Text)
            Text(
                when (provider) {
                    is Provider.Xtream -> "Xtream Codes · ${provider.server}"
                    is Provider.M3u -> "M3U playlist"
                },
                fontSize = 13.sp,
                color = Telly.Muted,
            )
            if (provider is Provider.Xtream) {
                when (account) {
                    is Load.Done -> {
                        Text("Subscription: ${AccountText.summary(account.value, System.currentTimeMillis())}", fontSize = 13.sp, color = Telly.Text, modifier = Modifier.padding(top = 6.dp))
                        AccountText.connections(account.value)?.let { Text(it, fontSize = 13.sp, color = Telly.Muted) }
                    }
                    is Load.Failed -> Text(account.message, fontSize = 13.sp, color = Telly.Faint, modifier = Modifier.padding(top = 6.dp))
                    else -> Text("Checking your subscription…", fontSize = 13.sp, color = Telly.Faint, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(
                    "Edit provider",
                    primary = false,
                    onClick = { providers.firstOrNull { it.id == activeId }?.let(onEditProvider) },
                    modifier = Modifier.focusRequester(firstFocus),
                )
                PillButton(
                    if (confirmRemove) "Press again to remove" else "Remove provider",
                    primary = false,
                    onClick = { if (confirmRemove) onRemoveProvider() else confirmRemove = true },
                )
                PillButton("Add another provider", primary = false, onClick = onAddProvider)
            }
        }

        Section("Playback") {
            val context = LocalContext.current
            val playback = remember { PlaybackSettings(context) }
            var autoNext by remember { mutableStateOf(playback.autoNext) }
            Text(
                "In the last minute of an episode, a small prompt counts down to the next one: OK plays it now, Back cancels it.",
                fontSize = 13.sp,
                color = Telly.Muted,
                modifier = Modifier.width(560.dp),
            )
            Row(Modifier.padding(top = 12.dp)) {
                PillButton(
                    if (autoNext) "Play the next episode automatically: On" else "Play the next episode automatically: Off",
                    primary = false,
                    onClick = { autoNext = !autoNext; playback.autoNext = autoNext },
                )
            }
        }

        Section("Categories") {
            Text(
                "Pin the categories you use most to the top, and hide the ones you never watch. Hidden categories also stay out of Home and Search.",
                fontSize = 13.sp,
                color = Telly.Muted,
                modifier = Modifier.width(560.dp),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryKind.entries.filter { categories[it].orEmpty().isNotEmpty() }.forEach { kind ->
                    val p = categoryPrefs[kind] ?: CategoryPrefs()
                    val extra = listOfNotNull(
                        p.pinned.size.takeIf { it > 0 }?.let { "$it pinned" },
                        p.hidden.size.takeIf { it > 0 }?.let { "$it hidden" },
                    ).joinToString(", ")
                    PillButton(if (extra.isEmpty()) kind.label else "${kind.label} · $extra", primary = false, onClick = { managing = kind })
                }
            }
        }

        if (syncUnlocked) {
            Section("Sync") {
                SyncSection(sync, onStartSync, onSyncNow, onStopSync)
            }
        }

        Section("Watch progress") {
            Text(
                "Forget where you got to in every film and episode, and empty Continue watching. My List is kept." +
                    if (sync.config != null) " Your linked devices are cleared too." else "",
                fontSize = 13.sp,
                color = Telly.Muted,
                modifier = Modifier.width(560.dp),
            )
            Row(Modifier.padding(top = 12.dp)) {
                PillButton(
                    if (confirmClearProgress) "Press again to clear" else "Clear watch progress",
                    primary = false,
                    onClick = {
                        if (confirmClearProgress) {
                            confirmClearProgress = false
                            onClearProgress()
                        } else {
                            confirmClearProgress = true
                        }
                    },
                )
            }
        }

        Section("Channels, films and series") {
            Text(
                "telly saves your lists so they open instantly, and refreshes them in the background each time it starts. Use Refresh in the menu to update them now.",
                fontSize = 13.sp,
                color = Telly.Muted,
                modifier = Modifier.width(560.dp),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    if (confirmClear) "Press again to clear" else "Clear saved data",
                    primary = false,
                    onClick = {
                        if (confirmClear) {
                            confirmClear = false
                            onClearSavedData()
                        } else {
                            confirmClear = true
                        }
                    },
                )
                if (updating) Text("Updating…", fontSize = 13.sp, color = Telly.Accent)
            }
            Text(
                "Clearing removes saved lists and artwork, then downloads them again. Your My List, watch progress and history are kept.",
                fontSize = 12.sp,
                color = Telly.Faint,
                modifier = Modifier.padding(top = 8.dp).width(560.dp),
            )
        }

        Section("Updates") {
            Text("You have telly ${BuildConfig.VERSION_NAME}. telly checks for a new version once a week.", fontSize = 13.sp, color = Telly.Muted)
            val release = when (update) {
                is UpdateState.Available -> update.release
                is UpdateState.Downloading -> update.release
                is UpdateState.NeedsPermission -> update.release
                is UpdateState.Failed -> update.release
                else -> null
            }
            val status = when (update) {
                UpdateState.Idle -> null
                UpdateState.Checking -> "Checking…"
                UpdateState.UpToDate -> "You're up to date."
                is UpdateState.Available -> "telly ${update.release.version} is available."
                is UpdateState.Downloading -> "Downloading telly ${update.release.version}… ${(update.progress * 100).toInt()}%"
                is UpdateState.NeedsPermission -> "Allow telly to install apps (on the screen that opened), then come back and choose Install again."
                is UpdateState.Failed -> update.message
            }
            status?.let { Text(it, fontSize = 13.sp, color = Telly.Text, modifier = Modifier.padding(top = 4.dp)) }
            (update as? UpdateState.Available)?.release?.notes?.let {
                Text(it, fontSize = 12.sp, color = Telly.Faint, maxLines = 4, modifier = Modifier.padding(top = 4.dp).width(560.dp))
            }
            if (release != null) {
                Text(
                    "Android then asks you to confirm: choose Install, then Open. Everything you've set up is kept.",
                    fontSize = 13.sp,
                    color = Telly.Accent,
                    modifier = Modifier.padding(top = 4.dp).width(560.dp),
                )
            }
            // One button that stays put and only changes its label, so the remote's focus never
            // falls out to the menu while checking or downloading.
            Row(Modifier.padding(top = 12.dp)) {
                val (label, action) = when {
                    update is UpdateState.Checking -> "Checking…" to {}
                    update is UpdateState.Downloading -> "Downloading… ${(update.progress * 100).toInt()}%" to {}
                    release != null -> "Download and install ${release.version}" to onInstallUpdate
                    else -> "Check for updates" to onCheckForUpdates
                }
                PillButton(label, primary = release != null, onClick = action)
            }
        }

        Section("About") {
            VersionLine(onSevenPresses = onToggleSyncUnlocked)
            Text(
                "Plays your own IPTV subscription. Talks only to your provider and to GitHub to check for updates" +
                    if (syncUnlocked) ", and to the sync relay if you turn on sync." else ".",
                fontSize = 13.sp,
                color = Telly.Muted,
            )
            Text("To update: open Downloader and enter code $DOWNLOADER_CODE", fontSize = 13.sp, color = Telly.Muted)
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

/** The version, quietly focusable: seven presses in a row toggle the hidden Sync section. */
@Composable
private fun VersionLine(onSevenPresses: () -> Unit) {
    var presses by remember { mutableStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(presses) { if (presses > 0) { delay(3_000); presses = 0 } }
    Text(
        "telly ${BuildConfig.VERSION_NAME}",
        fontSize = 15.sp,
        color = if (focused) Color.White else Telly.Text,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                presses++
                if (presses >= 7) {
                    presses = 0
                    onSevenPresses()
                }
            },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Telly.Accent, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}
