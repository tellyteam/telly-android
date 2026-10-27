package io.github.tellyteam.telly.ui.home

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.tellyteam.telly.data.Channel
import io.github.tellyteam.telly.data.ChannelGroup
import io.github.tellyteam.telly.data.PlaybackRules
import io.github.tellyteam.telly.data.Series
import io.github.tellyteam.telly.data.SeriesGroup
import io.github.tellyteam.telly.data.store.FavouritesStore
import io.github.tellyteam.telly.data.store.CategoryKind
import io.github.tellyteam.telly.data.store.CategoryPrefs
import io.github.tellyteam.telly.data.store.LastWatchedStore.LastWatched
import io.github.tellyteam.telly.data.store.WatchProgressStore
import io.github.tellyteam.telly.ui.Load
import io.github.tellyteam.telly.ui.components.ChannelTile
import io.github.tellyteam.telly.ui.components.EmptyHint
import io.github.tellyteam.telly.ui.components.FadedBackdrop
import io.github.tellyteam.telly.ui.components.LandscapeCard
import io.github.tellyteam.telly.ui.components.MediaRow
import io.github.tellyteam.telly.ui.components.PageInset
import io.github.tellyteam.telly.ui.components.LocalWatched
import io.github.tellyteam.telly.ui.components.PosterCard
import io.github.tellyteam.telly.ui.theme.Telly

/** Everything Home and My List can open or play. */
class HomeActions(
    val resume: (LastWatched, index: Int) -> Unit,
    val openSeries: (Series) -> Unit,
    val openFilm: (Channel) -> Unit,
    val playFilm: (Channel) -> Unit,
    val playChannel: (List<Channel>, Int) -> Unit,
    val toggleFavourite: (String) -> Unit,
    /** The remote's Menu button (or holding OK) on a Continue watching card. */
    val removeFromContinue: (LastWatched) -> Unit,
)

/**
 * Catalogue-derived rows and indexes, worked out once off the main thread (films can number in the
 * tens of thousands) and shared by Home, My List and Search.
 */
class Shelves(
    val films: List<Channel> = emptyList(),
    val series: List<Series> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val recentFilms: List<Channel> = emptyList(),
    val recentSeries: List<Series> = emptyList(),
    val filmRows: List<ChannelGroup> = emptyList(),
    val seriesRows: List<SeriesGroup> = emptyList(),
    val seriesByName: Map<String, Series> = emptyMap(),
    // Keyed like FavouritesStore, so My List lookups cost only as much as the list itself.
    val seriesByKey: Map<String, Series> = emptyMap(),
    val filmsByKey: Map<String, Channel> = emptyMap(),
    val channelsByKey: Map<String, Channel> = emptyMap(),
)

@Composable
fun rememberShelves(
    live: List<ChannelGroup>,
    films: Load<List<ChannelGroup>>,
    series: Load<List<SeriesGroup>>,
    arrangement: Map<CategoryKind, CategoryPrefs>,
): Shelves {
    val filmGroups = (films as? Load.Done)?.value.orEmpty()
    val seriesGroups = (series as? Load.Done)?.value.orEmpty()
    val shelves by produceState(Shelves(), live, filmGroups, seriesGroups, arrangement) {
        value = withContext(Dispatchers.Default) {
            fun prefs(kind: CategoryKind) = arrangement[kind] ?: CategoryPrefs()
            // Hidden categories stay out of rows, "Recently added" and Search…
            val shownFilmGroups = prefs(CategoryKind.FILMS).arrange(filmGroups) { it.name }
            val shownSeriesGroups = prefs(CategoryKind.SERIES).arrange(seriesGroups) { it.name }
            val shownLive = prefs(CategoryKind.LIVE).arrange(live) { it.name }
            val films = shownFilmGroups.asSequence().flatMap { it.channels }.distinctBy { it.id }.toList()
            val shows = shownSeriesGroups.asSequence().flatMap { it.series }.distinctBy { it.id }.toList()
            val channels = shownLive.asSequence().flatMap { it.channels }.distinctBy { it.id }.toList()
            // …but My List and history still find everything.
            val allFilms = filmGroups.asSequence().flatMap { it.channels }.distinctBy { it.id }.toList()
            val allSeries = seriesGroups.asSequence().flatMap { it.series }.distinctBy { it.id }.toList()
            val allChannels = live.asSequence().flatMap { it.channels }.distinctBy { it.id }.toList()
            Shelves(
                films = films,
                series = shows,
                channels = channels,
                seriesByKey = allSeries.associateBy(FavouritesStore::series),
                filmsByKey = allFilms.associateBy(FavouritesStore::film),
                channelsByKey = allChannels.associateBy(FavouritesStore::live),
                recentFilms = films.filter { it.added > 0 }.sortedByDescending { it.added }.take(ROW_LIMIT),
                recentSeries = shows.filter { it.added > 0 }.sortedByDescending { it.added }.take(ROW_LIMIT),
                filmRows = shownFilmGroups.take(3).map { ChannelGroup(it.name, it.channels.take(ROW_LIMIT)) },
                seriesRows = shownSeriesGroups.take(3).map { SeriesGroup(it.name, it.series.take(ROW_LIMIT)) },
                seriesByName = allSeries.associateBy { it.name },
            )
        }
    }
    return shelves
}

private const val ROW_LIMIT = 24

@Composable
fun HomeScreen(
    history: List<LastWatched>,
    progress: WatchProgressStore,
    progressVersion: Int,
    shelves: Shelves,
    favourites: List<String>,
    actions: HomeActions,
) {
    val favouriteSet = remember(favourites) { favourites.toSet() }
    val continueItems = remember(history, progressVersion) {
        history.filter { !it.live }.mapNotNull { entry ->
            val target = entry.continueIndex(progress)
            val saved = progress.get(entry.items[target].streamUrl)
            // A finished film (or a finished last episode) no longer needs continuing.
            if (saved?.finished == true && target == entry.index) null else entry to target
        }
            // What you actually watched comes first, most recent first; series that came back
            // with new episodes follow after them. (sortedBy is stable, so each group keeps its order.)
            .sortedBy { (entry, _) -> entry.newEpisodes }
    }
    // Something still to watch, else the last channel: a finished film or series never leads Home.
    val hero = continueItems.firstOrNull()?.first ?: history.firstOrNull { it.live }

    var bannerFocused by rememberSaveable { mutableStateOf(false) }
    val watched = LocalWatched.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "billboard") {
            if (hero != null) {
                val target = hero.continueIndex(progress)
                val item = hero.items[target]
                val saved = if (hero.live) null else progressVersion.let { progress.get(item.streamUrl) }
                Billboard(
                    eyebrow = when {
                        hero.live -> "Last watched"
                        hero.newEpisodes -> "New episodes"
                        target != hero.index -> "Up next"
                        else -> "Continue watching"
                    },
                    title = if (hero.isFilm || hero.live) item.name else hero.heading,
                    subtitle = when {
                        hero.live -> "Live TV"
                        hero.isFilm -> "Film"
                        else -> "${item.group} · ${item.name}"
                    },
                    imageUrl = item.logoUrl,
                    progress = saved?.takeIf { !it.finished }?.fraction,
                    primaryLabel = if (saved != null && !saved.finished && saved.positionMs > 0) "▶  Resume" else "▶  Play",
                    onPrimary = { actions.resume(hero, target) },
                    secondaryLabel = shelves.seriesByName[hero.heading]?.let { "Episodes" },
                    onSecondary = { shelves.seriesByName[hero.heading]?.let(actions.openSeries) },
                    takeFocus = !bannerFocused,
                    onTookFocus = { bannerFocused = true },
                )
            } else {
                val featured = shelves.recentFilms.firstOrNull() ?: shelves.films.firstOrNull()
                Billboard(
                    eyebrow = "Welcome to telly",
                    title = featured?.name ?: "Live TV, films and series",
                    subtitle = featured?.let { "Recently added film" } ?: "Pick something below to get started",
                    imageUrl = featured?.logoUrl,
                    progress = null,
                    primaryLabel = if (featured != null) "▶  Play" else null,
                    onPrimary = { featured?.let(actions.playFilm) },
                    secondaryLabel = null,
                    onSecondary = {},
                    takeFocus = !bannerFocused,
                    onTookFocus = { bannerFocused = true },
                )
            }
        }

        if (continueItems.isNotEmpty()) {
            item(key = "continue") {
                MediaRow("Continue watching") {
                    items(continueItems, key = { it.first.slot }) { (entry, target) ->
                        val item = entry.items[target]
                        LandscapeCard(
                            title = if (entry.isFilm) item.name else entry.heading,
                            subtitle = if (entry.isFilm) "Film" else item.name,
                            imageUrl = item.logoUrl,
                            badge = when {
                                entry.isFilm -> "Film"
                                entry.newEpisodes -> "New episodes"
                                target != entry.index -> "Up next"
                                else -> item.group
                            },
                            progress = progressVersion.let { progress.get(item.streamUrl)?.takeIf { !it.finished }?.fraction },
                            onClick = { actions.resume(entry, target) },
                            onMenu = { actions.removeFromContinue(entry) },
                        )
                    }
                }
            }
        }

        favouriteRows(shelves, favourites, watched, actions, showEmpty = false)

        if (shelves.recentFilms.isNotEmpty()) {
            item(key = "recentFilms") {
                MediaRow("Recently added films") {
                    items(shelves.recentFilms, key = { it.id }) { film ->
                        PosterCard(
                            film.name, film.logoUrl, onClick = { actions.openFilm(film) },
                            onFavourite = { actions.toggleFavourite(FavouritesStore.film(film)) },
                            favourite = FavouritesStore.film(film) in favouriteSet,
                            watched = FavouritesStore.film(film) in LocalWatched.current,
                        )
                    }
                }
            }
        }
        if (shelves.recentSeries.isNotEmpty()) {
            item(key = "recentSeries") {
                MediaRow("Recently added series") {
                    items(shelves.recentSeries, key = { it.id }) { s ->
                        PosterCard(
                            s.name, s.coverUrl, onClick = { actions.openSeries(s) },
                            onFavourite = { actions.toggleFavourite(FavouritesStore.series(s)) },
                            favourite = FavouritesStore.series(s) in favouriteSet,
                            watched = FavouritesStore.series(s) in LocalWatched.current,
                        )
                    }
                }
            }
        }
        // The provider's own leading categories ("Recommended", "Top 10"…), a few of each.
        shelves.seriesRows.forEach { group ->
            item(key = "series:${group.name}") {
                MediaRow(group.name) {
                    items(group.series, key = { it.id }) { s ->
                        PosterCard(
                            s.name, s.coverUrl, onClick = { actions.openSeries(s) },
                            onFavourite = { actions.toggleFavourite(FavouritesStore.series(s)) },
                            favourite = FavouritesStore.series(s) in favouriteSet,
                            watched = FavouritesStore.series(s) in LocalWatched.current,
                        )
                    }
                }
            }
        }
        shelves.filmRows.forEach { group ->
            item(key = "films:${group.name}") {
                MediaRow(group.name) {
                    items(group.channels, key = { it.id }) { film ->
                        PosterCard(
                            film.name, film.logoUrl, onClick = { actions.openFilm(film) },
                            onFavourite = { actions.toggleFavourite(FavouritesStore.film(film)) },
                            favourite = FavouritesStore.film(film) in favouriteSet,
                            watched = FavouritesStore.film(film) in LocalWatched.current,
                        )
                    }
                }
            }
        }
    }
}

/** Everything the user has added with Menu / hold OK, in the order they added it. */
@Composable
fun MyListScreen(
    shelves: Shelves,
    favourites: List<String>,
    actions: HomeActions,
) {
    val watched = LocalWatched.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 22.dp, bottom = 40.dp)) {
        item(key = "title") {
            Row(Modifier.padding(start = PageInset, bottom = 14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("My List", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Telly.Text)
                Text("☰ Menu or hold OK on anything to add or remove it", fontSize = 12.sp, color = Telly.Faint)
            }
        }
        favouriteRows(shelves, favourites, watched, actions, showEmpty = true)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.favouriteRows(
    shelves: Shelves,
    favourites: List<String>,
    watched: Set<String>,
    actions: HomeActions,
    showEmpty: Boolean,
) {
    // Most recently added first, the watched after the rest (PLAYBACK.md, Watched titles).
    val keys = PlaybackRules.myListOrder(favourites.asReversed()) { it in watched }
    val favSeries = keys.mapNotNull { shelves.seriesByKey[it] }
    val favFilms = keys.mapNotNull { shelves.filmsByKey[it] }
    val favChannels = keys.mapNotNull { shelves.channelsByKey[it] }

    if (favSeries.isNotEmpty() || favFilms.isNotEmpty()) {
        item(key = "mylist") {
            MediaRow("My List") {
                items(favSeries, key = { "s" + it.id }) { s ->
                    PosterCard(s.name, s.coverUrl, onClick = { actions.openSeries(s) }, onFavourite = { actions.toggleFavourite(FavouritesStore.series(s)) }, favourite = true, watched = FavouritesStore.series(s) in watched, dimmed = true)
                }
                items(favFilms, key = { "f" + it.id }) { film ->
                    PosterCard(film.name, film.logoUrl, onClick = { actions.openFilm(film) }, onFavourite = { actions.toggleFavourite(FavouritesStore.film(film)) }, favourite = true, watched = FavouritesStore.film(film) in watched, dimmed = true)
                }
            }
        }
    } else if (showEmpty) {
        item(key = "mylistEmpty") { EmptyHint("No series or films yet.") }
    }
    if (favChannels.isNotEmpty()) {
        item(key = "favChannels") {
            MediaRow("Favourite channels") {
                items(favChannels.size, key = { favChannels[it].id }) { i ->
                    val c = favChannels[i]
                    ChannelTile(c.name, c.logoUrl, onClick = { actions.playChannel(favChannels, i) }, onFavourite = { actions.toggleFavourite(FavouritesStore.live(c)) })
                }
            }
        }
    } else if (showEmpty) {
        item(key = "channelsEmpty") { EmptyHint("No favourite channels yet.") }
    }
}

/** The big "what to watch" panel: artwork fading softly into the page on the left and bottom. */
@Composable
private fun Billboard(
    eyebrow: String,
    title: String,
    subtitle: String,
    imageUrl: String?,
    progress: Float?,
    primaryLabel: String?,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    onSecondary: () -> Unit,
    /** True only the first time Home shows: the banner takes focus then, never again. */
    takeFocus: Boolean,
    onTookFocus: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxWidth().height(286.dp)) {
        FadedBackdrop(imageUrl, Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.66f))
        Column(Modifier.padding(start = PageInset, top = 40.dp).width(430.dp)) {
            Text(eyebrow.uppercase(), fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Telly.Accent)
            Text(
                title,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(subtitle, fontSize = 14.sp, color = Telly.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = Telly.Accent,
                    trackColor = Color.White.copy(alpha = 0.18f),
                    drawStopIndicator = {},
                    modifier = Modifier.padding(top = 14.dp).width(240.dp).height(3.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (primaryLabel != null) PillButton(primaryLabel, primary = true, onClick = onPrimary, modifier = Modifier.focusRequester(primaryFocus))
                if (secondaryLabel != null) PillButton(secondaryLabel, primary = false, onClick = onSecondary)
            }
        }
    }
    // Home is a lazy list, so the banner is rebuilt whenever it scrolls back into view. Taking
    // focus every time pulled focus up out of the rows below (e.g. while moving along My List).
    if (primaryLabel != null && takeFocus) {
        LaunchedEffect(Unit) {
            runCatching { primaryFocus.requestFocus() }
            onTookFocus()
        }
    }
}

@Composable
fun PillButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                val s = if (focused) 1.05f else 1f
                scaleX = s
                scaleY = s
            }
            .border(2.dp, if (focused) Telly.Accent else Color.Transparent, Telly.ControlShape)
            .clip(Telly.ControlShape)
            .background(
                when {
                    primary -> Color(0xFFF2F5F8)
                    focused -> Telly.AccentSoft
                    else -> Color.White.copy(alpha = 0.08f)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (primary) Color(0xFF111722) else Color.White)
    }
}
