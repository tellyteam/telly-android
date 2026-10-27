package io.github.tellyteam.telly.ui.live

import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.tellyteam.telly.data.Channel
import io.github.tellyteam.telly.data.ChannelGroup
import io.github.tellyteam.telly.data.Provider
import io.github.tellyteam.telly.data.store.FavouritesStore
import io.github.tellyteam.telly.ui.components.mediaClick
import io.github.tellyteam.telly.ui.theme.Telly
import io.github.tellyteam.telly.ui.theme.TvTextField

private const val FAVOURITES = "★ Favourites"

/**
 * Live TV as a dense list (agreed in the mockup): categories, then channels, then a preview of
 * the focused channel. Built for fast channel-surfing: only the preview redraws as focus moves.
 */
@Composable
fun LiveScreen(
    provider: Provider,
    /** Categories as the user arranged them (pinned first, hidden left out). */
    live: List<ChannelGroup>,
    /** Every channel, so favourites from hidden categories still show. */
    allLive: List<ChannelGroup>,
    favourites: List<String>,
    updating: Boolean,
    onPlay: (List<Channel>, Int) -> Unit,
    onToggleFavourite: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val favouriteSet = remember(favourites) { favourites.toSet() }
    val searchFocus = remember { FocusRequester() }

    // Favourites always first, even when empty, newest first (PLAYBACK.md, Lists); rebuilt only when either changes.
    val groups = remember(live, allLive, favourites) {
        val byKey = allLive.asSequence().flatMap { it.channels }.associateBy(FavouritesStore::live)
        val favs = favourites.asReversed().mapNotNull { byKey[it] }
        listOf(ChannelGroup(FAVOURITES, favs)) + live
    }
    // Opens on Favourites when there are some, otherwise on the first category.
    val selected = groups.firstOrNull { it.name == selectedName }
        ?: groups.firstOrNull { it.channels.isNotEmpty() } ?: groups.firstOrNull()
    val q = query.trim()
    val results by produceState<List<Channel>?>(null, q, live) {
        if (q.isEmpty()) {
            value = null
            return@produceState
        }
        delay(120)
        value = withContext(Dispatchers.Default) {
            live.asSequence().flatMap { it.channels }.distinctBy { it.id }.filter { it.name.contains(q, ignoreCase = true) }.toList()
        }
    }
    val channels = results ?: selected?.channels.orEmpty()
    var preview by remember { mutableStateOf<Channel?>(null) }
    // The live picture only plays while a channel has focus, not while browsing categories.
    var listFocused by remember { mutableStateOf(false) }
    val previewPlayer = rememberPreviewPlayer()

    val shown = preview?.takeIf { it in channels } ?: channels.firstOrNull()

    Row(Modifier.fillMaxSize()) {
        // Categories
        Column(Modifier.width(236.dp).fillMaxHeight().padding(start = 24.dp, top = 22.dp, end = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live TV", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Telly.Text)
                Spacer(Modifier.weight(1f))
                if (updating) Text("Updating…", fontSize = 11.sp, color = Telly.Muted)
            }
            TvTextField(
                label = "🎤 Search channels",
                value = query,
                placeholder = "OK to type or speak",
                modifier = Modifier.padding(top = 12.dp, bottom = 10.dp).fillMaxWidth(),
                focusRequester = searchFocus,
                onChange = { query = it },
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(groups, key = { _, g -> g.name }) { _, g ->
                    val isSelected = results == null && g == selected
                    CategoryItem(
                        modifier = Modifier,
                        name = if (g.name == FAVOURITES) g.name else ChannelNames.category(g.name),
                        count = g.channels.size,
                        selected = isSelected,
                        onFocus = { selectedName = g.name },
                        onClick = {
                            selectedName = g.name
                            query = "" // choosing a category leaves search
                        },
                    )
                }
            }
        }

        // Channels
        val listState = rememberLazyListState()
        LaunchedEffect(selected?.name, q) { listState.scrollToItem(0) }
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 22.dp)) {
            Text(
                when {
                    results != null && channels.isEmpty() -> "No channels match “$q”"
                    results != null -> "${channels.size} channels match “$q”"
                    selected?.name == FAVOURITES && channels.isEmpty() -> "No favourites yet: hold OK or press ☰ Menu on a channel to add it"
                    else -> selected?.let { if (it.name == FAVOURITES) it.name else ChannelNames.category(it.name) }.orEmpty()
                },
                fontSize = 13.sp,
                color = Telly.Muted,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            )
            LazyColumn(state = listState, modifier = Modifier.onFocusChanged { listFocused = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                    ChannelRow(
                        modifier = Modifier,
                        number = index + 1,
                        channel = channel,
                        provider = provider,
                        favourite = FavouritesStore.live(channel) in favouriteSet,
                        onFocus = { preview = channel },
                        onClick = {
                            // Most providers allow one stream at a time: free the preview's first.
                            previewPlayer.stop()
                            onPlay(channels, index)
                        },
                        onFavourite = { onToggleFavourite(FavouritesStore.live(channel)) },
                    )
                }
            }
        }

        // Preview of the focused channel
        Preview(shown, listFocused, previewPlayer, provider, shown?.let { channels.indexOf(it) + 1 } ?: 0, Modifier.width(290.dp).fillMaxHeight().padding(top = 30.dp, end = 34.dp, bottom = 24.dp))
    }
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
}

@Composable
private fun CategoryItem(modifier: Modifier, name: String, count: Int, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .border(2.dp, if (focused) Telly.Accent else Color.Transparent, Telly.RowShape)
            .clip(Telly.RowShape)
            .background(
                when {
                    focused -> Telly.AccentSoft
                    selected -> Color.White.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            )
            .mediaClick(onClick, null)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 14.sp,
            color = if (focused || selected) Color.White else Telly.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$count", fontSize = 12.sp, color = Telly.Faint)
    }
}

@Composable
private fun ChannelRow(
    modifier: Modifier,
    number: Int,
    channel: Channel,
    provider: Provider,
    favourite: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onFavourite: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .border(2.dp, if (focused) Telly.Accent else Color.Transparent, Telly.RowShape)
            .clip(Telly.RowShape)
            .background(if (focused) Telly.AccentSoft else Color.Transparent)
            .mediaClick(onClick, onFavourite)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("$number", fontSize = 13.sp, color = Telly.Faint, modifier = Modifier.width(30.dp))
        Box(Modifier.size(58.dp, 33.dp).clip(Telly.SmallShape).background(Telly.LogoTile), contentAlignment = Alignment.Center) {
            if (channel.logoUrl != null) {
                AsyncImage(model = channel.logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize().padding(3.dp))
            }
        }
        // Rows only ask for the guide once they've been on screen briefly, so fast scrolling stays cheap.
        val nowNext = rememberNowNext(channel, provider, settleMs = 350)
        Column(Modifier.weight(1f)) {
            Text(
                ChannelNames.channel(channel.name),
                fontSize = 15.sp,
                color = if (focused) Color.White else Telly.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            nowNext?.now?.let {
                Text("Now: ${it.title}", fontSize = 11.sp, color = Telly.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (favourite) Text("★", fontSize = 14.sp, color = Telly.Accent)
    }
}

@Composable
private fun Preview(channel: Channel?, live: Boolean, player: ExoPlayer, provider: Provider, number: Int, modifier: Modifier) {
    Column(
        modifier
            .clip(Telly.ControlShape)
            .background(Color.White.copy(alpha = 0.04f))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (channel == null) return@Column
        // 16:9, so the live picture fills it; the logo shows until the picture arrives.
        LivePicture(
            channel = if (live) channel else null,
            player = player,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Telly.CardShape).background(Color.Black),
        ) {
            Box(Modifier.fillMaxSize().background(Telly.LogoTile), contentAlignment = Alignment.Center) {
                if (channel.logoUrl != null) {
                    AsyncImage(model = channel.logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize().padding(22.dp))
                } else {
                    Text(ChannelNames.channel(channel.name), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B2433))
                }
            }
        }
        Text("$number · ${ChannelNames.channel(channel.name)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(ChannelNames.category(channel.group), fontSize = 13.sp, color = Telly.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val nowNext = rememberNowNext(channel, provider)
        nowNext?.now?.let { now ->
            Text(
                "Now  ${clock(now.startMs)}–${clock(now.endMs)}",
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
                color = Telly.Accent,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(now.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(
                progress = { now.progress(System.currentTimeMillis()) },
                color = Telly.Accent,
                trackColor = Color.White.copy(alpha = 0.15f),
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            now.description?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = Telly.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        nowNext?.next?.let { next ->
            Text("Next  ${clock(next.startMs)}  ${next.title}", fontSize = 12.sp, color = Telly.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        Text("OK to watch  ·  ☰ to add to My List", fontSize = 12.sp, color = Telly.Muted)
        if (nowNext == null) Text("Looking up the programme guide…", fontSize = 11.sp, color = Telly.Faint)
    }
}
