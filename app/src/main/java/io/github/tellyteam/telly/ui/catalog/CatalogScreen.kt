package io.github.tellyteam.telly.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.tellyteam.telly.ui.Load
import io.github.tellyteam.telly.ui.components.MediaRow
import io.github.tellyteam.telly.ui.components.PageInset
import io.github.tellyteam.telly.ui.components.LocalWatched
import io.github.tellyteam.telly.ui.components.PosterCard
import io.github.tellyteam.telly.ui.components.SeeAllCard
import io.github.tellyteam.telly.ui.theme.Telly
import io.github.tellyteam.telly.ui.theme.TvTextField
import io.github.tellyteam.telly.ui.theme.focusRing

/** How a catalogue item (a film or a series) is shown and opened. */
class PosterSpec<T>(
    val key: (T) -> String,
    val title: (T) -> String,
    val image: (T) -> String?,
    val favouriteKey: (T) -> String,
    val progress: (T) -> Float? = { null },
)

private const val ROW_LIMIT = 30

/**
 * The Series or Films page: search (voice-capable) at the top, then My List, then one row per
 * category ending in "See all", which opens that category as a full grid.
 */
@Composable
fun <T> CatalogScreen(
    title: String,
    groups: Load<List<Pair<String, List<T>>>>,
    spec: PosterSpec<T>,
    favourites: List<String>,
    updating: Boolean,
    onOpen: (T) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    var seeAll by rememberSaveable(title) { mutableStateOf<String?>(null) }
    BackHandler(enabled = seeAll != null) { seeAll = null }
    val favouriteSet = remember(favourites) { favourites.toSet() }
    val searchFocus = remember { FocusRequester() }
    val loaded = (groups as? Load.Done)?.value.orEmpty()

    Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
        Row(Modifier.padding(start = PageInset, end = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(seeAll?.let { "$title · $it" } ?: title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Telly.Text, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (updating) Text("Updating…", fontSize = 12.sp, color = Telly.Muted)
            TvTextField(
                label = "🎤 Search ${title.lowercase()}",
                value = query,
                placeholder = "OK to type or speak",
                modifier = Modifier.width(280.dp),
                focusRequester = searchFocus,
                onChange = { query = it },
            )
        }

        when (groups) {
            Load.Idle, Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Load.Failed -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(groups.message, color = Telly.Muted)
                Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp).focusRing()) { Text("Retry") }
            }
            is Load.Done -> when {
                query.isNotBlank() -> {
                    val q = query.trim()
                    val results by produceState(emptyList<T>(), q, loaded) {
                        delay(120)
                        value = withContext(Dispatchers.Default) {
                            loaded.asSequence().flatMap { it.second }.distinctBy(spec.key).filter { spec.title(it).contains(q, ignoreCase = true) }.take(200).toList()
                        }
                    }
                    Grid(if (results.isEmpty()) "No matches for “$q”" else "${results.size} matches", results, spec, favouriteSet, onOpen, onToggleFavourite)
                }
                seeAll != null -> {
                    val items = loaded.firstOrNull { it.first == seeAll }?.second.orEmpty()
                    Grid("${items.size} titles · Back to return", items, spec, favouriteSet, onOpen, onToggleFavourite)
                }
                else -> Rows(loaded, spec, favourites, favouriteSet, onOpen, onToggleFavourite) { seeAll = it }
            }
        }
    }
    // Search is the first thing focused, so typing or speaking is one press away.
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
}

@Composable
private fun <T> Rows(
    groups: List<Pair<String, List<T>>>,
    spec: PosterSpec<T>,
    favourites: List<String>,
    favouriteSet: Set<String>,
    onOpen: (T) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onSeeAll: (String) -> Unit,
) {
    // My List for this page, most recently added first; worked out off the main thread.
    val myList by produceState(emptyList<T>(), groups, favourites) {
        value = withContext(Dispatchers.Default) {
            val byKey = groups.asSequence().flatMap { it.second }.associateBy(spec.favouriteKey)
            favourites.asReversed().mapNotNull { byKey[it] }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 14.dp, bottom = 40.dp)) {
        if (myList.isNotEmpty()) {
            item(key = "mylist") {
                MediaRow("My List") {
                    items(myList, key = spec.key) { poster(it, spec, favouriteSet, onOpen, onToggleFavourite) }
                }
            }
        }
        items(groups, key = { it.first }) { (name, items) ->
            MediaRow(name) {
                items(items.take(ROW_LIMIT), key = spec.key) { poster(it, spec, favouriteSet, onOpen, onToggleFavourite) }
                if (items.size > ROW_LIMIT) item(key = "seeall") { SeeAllCard(items.size) { onSeeAll(name) } }
            }
        }
    }
}

@Composable
private fun <T> Grid(
    caption: String,
    items: List<T>,
    spec: PosterSpec<T>,
    favouriteSet: Set<String>,
    onOpen: (T) -> Unit,
    onToggleFavourite: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(caption, fontSize = 13.sp, color = Telly.Muted, modifier = Modifier.padding(start = PageInset, top = 12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(122.dp),
            contentPadding = PaddingValues(start = PageInset, end = 40.dp, top = 14.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = spec.key) { poster(it, spec, favouriteSet, onOpen, onToggleFavourite) }
        }
    }
}

@Composable
private fun <T> poster(item: T, spec: PosterSpec<T>, favouriteSet: Set<String>, onOpen: (T) -> Unit, onToggleFavourite: (String) -> Unit) {
    val favKey = spec.favouriteKey(item)
    PosterCard(
        title = spec.title(item),
        imageUrl = spec.image(item),
        onClick = { onOpen(item) },
        onFavourite = { onToggleFavourite(favKey) },
        favourite = favKey in favouriteSet,
        progress = spec.progress(item),
        watched = favKey in LocalWatched.current,
    )
}
