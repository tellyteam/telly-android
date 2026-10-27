package io.github.tellyteam.telly.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.tellyteam.telly.data.store.FavouritesStore
import io.github.tellyteam.telly.ui.components.WatchedBadge
import io.github.tellyteam.telly.ui.components.LocalWatched
import io.github.tellyteam.telly.data.Channel
import io.github.tellyteam.telly.data.Details
import io.github.tellyteam.telly.data.Season
import io.github.tellyteam.telly.data.Series
import io.github.tellyteam.telly.data.store.LastWatchedStore.LastWatched
import io.github.tellyteam.telly.data.store.WatchProgressStore
import io.github.tellyteam.telly.ui.FilmDetail
import io.github.tellyteam.telly.ui.Load
import io.github.tellyteam.telly.ui.SeriesDetail
import io.github.tellyteam.telly.ui.components.FadedBackdrop
import io.github.tellyteam.telly.ui.components.PageInset
import io.github.tellyteam.telly.ui.components.SmoothRowScrolling
import io.github.tellyteam.telly.ui.components.keepRightInRow
import io.github.tellyteam.telly.ui.home.PillButton
import io.github.tellyteam.telly.ui.theme.Telly
import io.github.tellyteam.telly.ui.theme.focusCard

@Composable
fun SeriesDetailScreen(
    detail: SeriesDetail,
    history: List<LastWatched>,
    progress: WatchProgressStore,
    progressVersion: Int,
    inMyList: Boolean,
    onToggleMyList: () -> Unit,
    onResume: (LastWatched, Int) -> Unit,
    onPlayEpisode: (Series, Season, Int) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val series = detail.series
    val data = (detail.data as? Load.Done)?.value
    val details = data?.details ?: Details(plot = series.plot)
    val seasons = data?.seasons.orEmpty()
    // Where this series was left, if it was watched before.
    val entry = remember(history, series.name) { history.firstOrNull { !it.live && !it.isFilm && it.heading == series.name } }
    val target = entry?.let { progressVersion.let { _ -> it.continueIndex(progress) } }
    val resumeEpisode = entry?.items?.getOrNull(target ?: -1)

    var seasonIndex by rememberSaveable(series.id) { mutableIntStateOf(-1) }
    // Open on the season being watched, else the first.
    val shownSeason = when {
        seasons.isEmpty() -> null
        seasonIndex in seasons.indices -> seasons[seasonIndex]
        else -> seasons.firstOrNull { s -> s.name == resumeEpisode?.group } ?: seasons.first()
    }

    Box(Modifier.fillMaxSize()) {
        FadedBackdrop(details.backdropUrl ?: series.coverUrl, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.68f).height(350.dp))
        Column(Modifier.fillMaxSize().padding(top = 30.dp)) {
            Header(
                eyebrow = "Series",
                title = series.name,
                details = details,
                primary = when {
                    entry != null && resumeEpisode != null -> {
                        val label = episodeCode(resumeEpisode.group, resumeEpisode.name)
                        val started = progress.get(resumeEpisode.streamUrl)?.let { !it.finished && it.positionMs > 0 } == true
                        (if (started) "▶  Resume $label" else "▶  Play $label") to { onResume(entry, target!!) }
                    }
                    seasons.isNotEmpty() -> "▶  Play ${episodeCode(seasons[0].name, seasons[0].episodes[0].name)}" to { onPlayEpisode(series, seasons[0], 0) }
                    else -> null
                },
                inMyList = inMyList,
                onToggleMyList = onToggleMyList,
                watched = FavouritesStore.series(series) in LocalWatched.current,
            )

            when (val d = detail.data) {
                Load.Idle, Load.Loading -> if (data == null) Box(Modifier.padding(start = PageInset, top = 24.dp)) { CircularProgressIndicator() }
                is Load.Failed -> Text(d.message, color = Telly.Muted, modifier = Modifier.padding(start = PageInset, top = 24.dp))
                is Load.Done -> Unit
            }

            if (shownSeason != null) {
                SmoothRowScrolling {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = PageInset),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 18.dp).keepRightInRow(),
                    ) {
                        itemsIndexed(seasons, key = { _, s -> s.name }) { i, s ->
                            ChoiceChip(s.name, selected = s == shownSeason) { seasonIndex = i }
                        }
                    }
                }
                val rowState = rememberLazyListState()
                LaunchedEffect(shownSeason.name) {
                    val i = shownSeason.episodes.indexOfFirst { it.streamUrl == resumeEpisode?.streamUrl }
                    rowState.scrollToItem(i.coerceAtLeast(0))
                }
                SmoothRowScrolling {
                    LazyRow(
                        state = rowState,
                        modifier = Modifier.keepRightInRow(),
                        contentPadding = PaddingValues(horizontal = PageInset, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(shownSeason.episodes, key = { _, e -> e.id }) { i, episode ->
                            val saved = progressVersion.let { progress.get(episode.streamUrl) }
                            EpisodeCard(episode, saved) { onPlayEpisode(series, shownSeason, i) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilmDetailScreen(
    detail: FilmDetail,
    progress: WatchProgressStore,
    progressVersion: Int,
    inMyList: Boolean,
    onToggleMyList: () -> Unit,
    onPlay: (Channel) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val film = detail.film
    val details = (detail.details as? Load.Done)?.value ?: Details()
    val saved = progressVersion.let { progress.get(film.streamUrl) }
    val resumable = saved != null && !saved.finished && saved.positionMs > 0

    Box(Modifier.fillMaxSize()) {
        FadedBackdrop(details.backdropUrl ?: film.logoUrl, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.68f).height(420.dp))
        Column(Modifier.fillMaxSize().padding(top = 30.dp)) {
            Header(
                eyebrow = "Film",
                title = film.name,
                details = details,
                primary = (if (resumable) "▶  Resume" else "▶  Play") to { onPlay(film) },
                inMyList = inMyList,
                onToggleMyList = onToggleMyList,
                progress = saved?.takeIf { resumable }?.let { it.fraction to "${(it.durationMs - it.positionMs) / 60_000} min left" },
                watched = FavouritesStore.film(film) in LocalWatched.current,
            )
            if (detail.details is Load.Loading) {
                Box(Modifier.padding(start = PageInset, top = 24.dp)) { CircularProgressIndicator() }
            }
            details.cast?.let {
                Text(
                    "Starring $it",
                    fontSize = 13.sp,
                    color = Telly.Faint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = PageInset, top = 22.dp).width(520.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(
    eyebrow: String,
    title: String,
    details: Details,
    primary: Pair<String, () -> Unit>?,
    inMyList: Boolean,
    onToggleMyList: () -> Unit,
    progress: Pair<Float, String>? = null,
    watched: Boolean = false,
) {
    val primaryFocus = remember { FocusRequester() }
    Column(Modifier.padding(start = PageInset).width(500.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(eyebrow.uppercase(), fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Telly.Accent)
            if (watched) WatchedBadge()
        }
        Text(
            title,
            fontSize = 34.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        val meta = listOfNotNull(details.year, details.rating?.let { "★ $it" }, details.runtime, details.genre)
        if (meta.isNotEmpty()) {
            Text(meta.joinToString("  ·  "), fontSize = 13.sp, color = Telly.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
        details.plot?.let {
            Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFC3CBD6), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        }
        if (progress != null) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(
                    progress = { progress.first },
                    color = Telly.Accent,
                    trackColor = Color.White.copy(alpha = 0.18f),
                    drawStopIndicator = {},
                    modifier = Modifier.width(200.dp).height(3.dp),
                )
                Text(progress.second, fontSize = 12.sp, color = Telly.Muted)
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (primary != null) PillButton(primary.first, primary = true, onClick = primary.second, modifier = Modifier.focusRequester(primaryFocus))
            PillButton(if (inMyList) "✓  In My List" else "＋  My List", primary = false, onClick = onToggleMyList)
        }
    }
    // Focus the main action once it exists (series wait for their episodes to load).
    LaunchedEffect(primary != null) { if (primary != null) runCatching { primaryFocus.requestFocus() } }
}

/** One of a few choices, e.g. a season or the sync relay; the chosen one is tinted. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Telly.Accent else Color.Transparent, Telly.ControlShape)
            .clip(Telly.ControlShape)
            .background(if (selected) Telly.AccentSoft else Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 14.sp, color = if (selected || focused) Color.White else Telly.Muted)
    }
}

@Composable
private fun EpisodeCard(episode: Channel, saved: WatchProgressStore.Progress?, onClick: () -> Unit) {
    Column(Modifier.width(208.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(117.dp)
                .focusCard()
                .clip(Telly.CardShape)
                .background(Telly.SurfaceHigh)
                .clickable(onClick = onClick),
        ) {
            if (episode.logoUrl != null) {
                AsyncImage(model = episode.logoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x990A0E14))))
            if (saved?.finished == true) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(Telly.ControlShape).background(Telly.Accent),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Telly.AccentInk) }
            } else if (saved != null) {
                LinearProgressIndicator(
                    progress = { saved.fraction },
                    color = Telly.Accent,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    drawStopIndicator = {},
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).height(3.dp),
                )
            }
        }
        Text(episode.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Telly.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(
            when {
                saved == null -> " "
                saved.finished -> "Watched"
                else -> "${saved.positionMs / 60_000} of ${saved.durationMs / 60_000} min"
            },
            fontSize = 12.sp,
            color = Telly.Muted,
        )
    }
}

/** "Season 4" + "E10  Being Alive" → "S4:E10". */
private fun episodeCode(seasonName: String, episodeName: String): String {
    val s = seasonName.removePrefix("Season ").trim()
    val e = episodeName.substringBefore(' ').trim()
    return if (e.startsWith("E")) "S$s:$e" else "S$s"
}
