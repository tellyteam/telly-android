package io.github.tellyteam.telly.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import io.github.tellyteam.telly.ui.DeviceKind
import io.github.tellyteam.telly.ui.components.FocusMemory
import io.github.tellyteam.telly.ui.components.LocalFocusMemory
import io.github.tellyteam.telly.ui.theme.Telly
import io.github.tellyteam.telly.ui.theme.tellyBackground

/**
 * The rail plus the current page. Back from a page moves focus to the rail (as on Netflix);
 * Back from the rail leaves the app as usual. Pages' own Back handlers (e.g. a series page)
 * are composed later, so they take precedence.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppShell(
    destinations: List<Destination>,
    selected: Destination,
    onSelect: (Destination) -> Unit,
    /** A detail page shown over the current page (which stays alive underneath, undrawn). */
    overlay: (@Composable () -> Unit)? = null,
    refreshing: Boolean = false,
    updateAvailable: Boolean = false,
    page: @Composable () -> Unit,
) {
    // A phone gets the tab bar instead of the rail.
    if (DeviceKind.isPhone(LocalContext.current)) {
        PhoneShell(destinations, selected, onSelect, overlay, refreshing, updateAvailable, page)
        return
    }
    val railFocus = remember { FocusRequester() }
    val focusMemory = remember { FocusMemory() }
    var railHasFocus by remember { mutableStateOf(false) }
    val overlayOpen = overlay != null
    BackHandler(enabled = !railHasFocus && !overlayOpen) { runCatching { railFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().tellyBackground()) {
        // Coming back from the rail or a detail page returns focus to exactly where it was.
        // The page stays alive (undrawn) under a detail page, so focus can return to its card.
        // (Compose's focusRestorer isn't used: it redirected in-page focus moves and crashed on
        // page switches.)
        CompositionLocalProvider(LocalFocusMemory provides focusMemory) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = Telly.RailWidth)
                    .drawWithContent { if (!overlayOpen) drawContent() },
            ) { page() }
        }
        if (overlay != null) {
            // Keep D-pad focus inside the detail page; Back closes it.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = Telly.RailWidth)
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup(),
            ) { overlay() }
        }
        NavRail(
            destinations = destinations,
            selected = selected,
            onSelect = { focusMemory.last = null; onSelect(it) },
            selectedFocus = railFocus,
            onFocusChanged = { railHasFocus = it },
            refreshing = refreshing,
            updateAvailable = updateAvailable,
        )
    }

    var hadOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(overlayOpen) {
        if (!overlayOpen && hadOverlay) runCatching { focusMemory.last?.requestFocus() }
        hadOverlay = overlayOpen
    }
}
