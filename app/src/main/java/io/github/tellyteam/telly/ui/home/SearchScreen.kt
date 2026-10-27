package io.github.tellyteam.telly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.tellyteam.telly.data.Channel
import io.github.tellyteam.telly.data.ChannelGroup
import io.github.tellyteam.telly.data.Series
import io.github.tellyteam.telly.data.SeriesGroup
import io.github.tellyteam.telly.data.store.FavouritesStore
import io.github.tellyteam.telly.ui.Load
import io.github.tellyteam.telly.ui.components.ChannelTile
import io.github.tellyteam.telly.ui.components.MediaRow
import io.github.tellyteam.telly.ui.components.LocalWatched
import io.github.tellyteam.telly.ui.components.PosterCard
import io.github.tellyteam.telly.ui.theme.Telly
import io.github.tellyteam.telly.ui.theme.TvTextField

private class Results(val series: List<Series> = emptyList(), val films: List<Channel> = emptyList(), val channels: List<Channel> = emptyList())

private const val RESULT_LIMIT = 40
private val KEYS = ("abcdefghijklmnopqrstuvwxyz1234567890").map { it.toString() }

/**
 * Search everything at once with an on-screen letter grid: much quicker with a remote than the
 * system keyboard. Matching runs off the main thread, a beat after the last key press.
 */
@Composable
fun SearchScreen(
    shelves: Shelves,
    favourites: List<String>,
    actions: HomeActions,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val favouriteSet = remember(favourites) { favourites.toSet() }
    val results by produceState(Results(), query, shelves) {
        val q = query.trim()
        if (q.isEmpty()) {
            value = Results()
            return@produceState
        }
        delay(120) // let quick key presses settle before scanning the catalogue
        value = withContext(Dispatchers.Default) {
            Results(
                series = shelves.series.asSequence().filter { it.name.contains(q, ignoreCase = true) }.take(RESULT_LIMIT).toList(),
                films = shelves.films.asSequence().filter { it.name.contains(q, ignoreCase = true) }.take(RESULT_LIMIT).toList(),
                channels = shelves.channels.asSequence().filter { it.name.contains(q, ignoreCase = true) }.take(RESULT_LIMIT).toList(),
            )
        }
    }
    val field = remember { FocusRequester() }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).fillMaxHeight().padding(start = 28.dp, top = 26.dp, end = 12.dp)) {
            Text("Search", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Telly.Text)
            // A real text field, so OK opens the Fire TV keyboard and holding the remote's mic
            // dictates into it. The letter grid below is the remote-only alternative.
            TvTextField(
                label = "🎤 OK, then hold the mic to speak",
                value = query,
                placeholder = "Or use the letters below",
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                focusRequester = field,
                onChange = { query = it },
            )
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KEYS.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k ->
                            Key(k, Modifier.weight(1f)) { query += k }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Key("space", Modifier.weight(2f)) { query += " " }
                    Key("⌫", Modifier.weight(2f)) { query = query.dropLast(1) }
                    Key("clear", Modifier.weight(2f)) { query = "" }
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 26.dp, bottom = 40.dp)) {
            val q = query.trim()
            when {
                q.isEmpty() -> item { Hint("Series, films and channels all show up here as you type.") }
                results.series.isEmpty() && results.films.isEmpty() && results.channels.isEmpty() -> item { Hint("No matches for “$q”.") }
            }
            if (results.series.isNotEmpty()) item(key = "series") {
                MediaRow("Series") {
                    items(results.series, key = { it.id }) { s ->
                        PosterCard(s.name, s.coverUrl, onClick = { actions.openSeries(s) }, onFavourite = { actions.toggleFavourite(FavouritesStore.series(s)) }, favourite = FavouritesStore.series(s) in favouriteSet, watched = FavouritesStore.series(s) in LocalWatched.current)
                    }
                }
            }
            if (results.films.isNotEmpty()) item(key = "films") {
                MediaRow("Films") {
                    items(results.films, key = { it.id }) { f ->
                        PosterCard(f.name, f.logoUrl, onClick = { actions.openFilm(f) }, onFavourite = { actions.toggleFavourite(FavouritesStore.film(f)) }, favourite = FavouritesStore.film(f) in favouriteSet, watched = FavouritesStore.film(f) in LocalWatched.current)
                    }
                }
            }
            if (results.channels.isNotEmpty()) item(key = "channels") {
                MediaRow("Channels") {
                    items(results.channels.size, key = { results.channels[it].id }) { i ->
                        val c = results.channels[i]
                        ChannelTile(c.name, c.logoUrl, onClick = { actions.playChannel(results.channels, i) }, onFavourite = { actions.toggleFavourite(FavouritesStore.live(c)) })
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}

@Composable
private fun Key(label: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(38.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(Telly.RowShape)
            .background(if (focused) Telly.Accent else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = if (label.length > 1) 12.sp else 16.sp, color = if (focused) Telly.AccentInk else Color(0xFFD7DDE5))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 14.sp, color = Telly.Faint, modifier = Modifier.padding(start = 28.dp, top = 70.dp))
}
