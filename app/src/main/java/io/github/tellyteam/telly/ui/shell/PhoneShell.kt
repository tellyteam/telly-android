package io.github.tellyteam.telly.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tellyteam.telly.R
import io.github.tellyteam.telly.ui.theme.Telly
import io.github.tellyteam.telly.ui.theme.tellyBackground

/** The phone's tabs, as on iPhone: Browse holds Live TV, Films and Series behind a switcher. */
private enum class Tab(val label: String, val icon: Int) {
    HOME("Home", R.drawable.ic_nav_home),
    BROWSE("Browse", R.drawable.ic_nav_live),
    MY_LIST("My List", R.drawable.ic_nav_mylist),
    SEARCH("Search", R.drawable.ic_nav_search),
    SETTINGS("Settings", R.drawable.ic_nav_settings),
}

private val browsePages = listOf(Destination.LIVE, Destination.FILMS, Destination.SERIES)

private fun tabOf(d: Destination) = when (d) {
    Destination.HOME, Destination.REFRESH -> Tab.HOME
    Destination.LIVE, Destination.FILMS, Destination.SERIES -> Tab.BROWSE
    Destination.MY_LIST -> Tab.MY_LIST
    Destination.SEARCH -> Tab.SEARCH
    Destination.SETTINGS -> Tab.SETTINGS
}

/**
 * The phone layout: the page, a tab bar along the bottom, and on Browse a switcher at the top
 * (just "Live TV" for a playlist, which has no films or series). Refresh sits at the top of Home.
 * A detail page covers the page and keeps the tab bar.
 */
@Composable
fun PhoneShell(
    destinations: List<Destination>,
    selected: Destination,
    onSelect: (Destination) -> Unit,
    overlay: (@Composable () -> Unit)?,
    refreshing: Boolean,
    updateAvailable: Boolean,
    page: @Composable () -> Unit,
) {
    val browse = browsePages.filter { it in destinations }
    // Browse comes back to the page last used there.
    var lastBrowse by rememberSaveable { mutableStateOf(Destination.LIVE) }
    if (selected in browse) lastBrowse = selected
    val overlayOpen = overlay != null

    Column(Modifier.fillMaxSize().tellyBackground().statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().drawWithContent { if (!overlayOpen) drawContent() }) {
                if (selected in browse && browse.size > 1) Switcher(browse, selected, onSelect)
                Box(Modifier.weight(1f).fillMaxWidth()) { page() }
            }
            // Refresh, pinned at the top of Home over the page.
            if (selected == Destination.HOME && Destination.REFRESH in destinations && !overlayOpen) {
                Icon(
                    painterResource(R.drawable.ic_nav_refresh),
                    contentDescription = if (refreshing) "Refreshing" else "Refresh",
                    tint = if (refreshing) Telly.Accent else Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp).size(40.dp).clip(Telly.ControlShape)
                        .background(Telly.BackgroundLow.copy(alpha = 0.7f)).clickable { onSelect(Destination.REFRESH) }.padding(10.dp),
                )
            }
            if (overlay != null) Box(Modifier.fillMaxSize()) { overlay() }
        }
        TabBar(selected, updateAvailable, browseLabel = if (browse.size > 1) Tab.BROWSE.label else Destination.LIVE.label) { tab ->
            onSelect(
                when (tab) {
                    Tab.HOME -> Destination.HOME
                    Tab.BROWSE -> lastBrowse.takeIf { it in browse } ?: Destination.LIVE
                    Tab.MY_LIST -> Destination.MY_LIST
                    Tab.SEARCH -> Destination.SEARCH
                    Tab.SETTINGS -> Destination.SETTINGS
                },
            )
        }
    }
}

@Composable
private fun Switcher(pages: List<Destination>, selected: Destination, onSelect: (Destination) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(Telly.ControlShape).background(Telly.Surface).padding(3.dp),
    ) {
        pages.forEach { d ->
            val on = d == selected
            Text(
                d.label,
                fontSize = 14.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) Color.White else Telly.Muted,
                modifier = Modifier
                    .weight(1f)
                    .clip(Telly.ControlShape)
                    .background(if (on) Telly.SurfaceHigh else Color.Transparent)
                    .clickable { onSelect(d) }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TabBar(selected: Destination, updateAvailable: Boolean, browseLabel: String, onTab: (Tab) -> Unit) {
    val current = tabOf(selected)
    Row(
        Modifier.fillMaxWidth().background(Telly.BackgroundLow).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { tab ->
            val on = tab == current
            Column(
                Modifier.weight(1f).clip(Telly.ControlShape).clickable { onTab(tab) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Icon(painterResource(tab.icon), contentDescription = null, tint = if (on) Telly.Accent else Telly.Muted, modifier = Modifier.size(22.dp))
                    if (tab == Tab.SETTINGS && updateAvailable) {
                        Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(Telly.ControlShape).background(Telly.Accent))
                    }
                }
                Text(
                    if (tab == Tab.BROWSE) browseLabel else tab.label,
                    fontSize = 11.sp,
                    color = if (on) Telly.Accent else Telly.Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
