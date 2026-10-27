package io.github.tellyteam.telly.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tellyteam.telly.R
import io.github.tellyteam.telly.container
import io.github.tellyteam.telly.data.nameInSentence
import io.github.tellyteam.telly.data.Channel
import io.github.tellyteam.telly.data.Provider
import io.github.tellyteam.telly.ui.components.LocalWatched
import io.github.tellyteam.telly.ui.live.LiveScreen
import io.github.tellyteam.telly.ui.settings.SettingsScreen
import io.github.tellyteam.telly.data.store.LastWatchedStore
import io.github.tellyteam.telly.data.Series
import io.github.tellyteam.telly.data.store.CategoryKind
import io.github.tellyteam.telly.data.store.FavouritesStore
import io.github.tellyteam.telly.ui.catalog.CatalogScreen
import io.github.tellyteam.telly.ui.catalog.PosterSpec
import io.github.tellyteam.telly.ui.detail.FilmDetailScreen
import io.github.tellyteam.telly.ui.detail.SeriesDetailScreen
import io.github.tellyteam.telly.ui.home.HomeActions
import io.github.tellyteam.telly.ui.home.HomeScreen
import io.github.tellyteam.telly.ui.home.MyListScreen
import io.github.tellyteam.telly.ui.home.SearchScreen
import io.github.tellyteam.telly.ui.home.rememberShelves
import io.github.tellyteam.telly.ui.player.PlayerActivity
import io.github.tellyteam.telly.ui.shell.AppShell
import io.github.tellyteam.telly.ui.shell.Destination
import io.github.tellyteam.telly.ui.setup.SetupScreen
import io.github.tellyteam.telly.ui.theme.TellyTheme
import io.github.tellyteam.telly.ui.theme.TellyWordmark
import io.github.tellyteam.telly.ui.theme.focusRing

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch theme shows the telly wordmark while starting; swap back before drawing the UI.
        setTheme(R.style.Theme_Telly)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.color.background)
        setContent {
            TellyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: MainViewModel = viewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    // Back from the player (or the TV waking up): a finished series may have new episodes.
                    LifecycleResumeEffect(vm) {
                        vm.checkForNewEpisodes()
                        vm.syncNow()
                        onPauseOrDispose {}
                    }
                    when (val s = state) {
                        is UiState.Setup -> {
                            val sync by vm.sync.collectAsStateWithLifecycle()
                            SetupScreen(
                                existing = s.existing,
                                onConnect = vm::submitProvider,
                                onForget = if (s.existing != null) vm::forgetAttempt else null,
                                onStopSync = if (sync.config != null) vm::stopSync else null,
                            )
                        }
                        is UiState.Loading -> Centered {
                            TellyWordmark(fontSize = 64.sp)
                            CircularProgressIndicator()
                            Text("Loading channels from ${s.provider.nameInSentence}…")
                        }
                        is UiState.Failed -> FailedScreen(
                            message = s.message,
                            onRetry = vm::retry,
                            onEdit = { vm.editProvider(s.provider) },
                        )
                        is UiState.Ready -> {
                            val form by vm.form.collectAsStateWithLifecycle()
                            val open = form
                            if (open != null) {
                                SetupScreen(
                                    existing = open.editing?.provider,
                                    onConnect = vm::submitProvider,
                                    onForget = open.editing?.let { e -> { vm.removeProvider(e.id) } },
                                    onCancel = vm::closeForm,
                                )
                            } else {
                                Browse(vm, s)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Browse(vm: MainViewModel, s: UiState.Ready) {
        val films by vm.films.collectAsStateWithLifecycle()
        val series by vm.series.collectAsStateWithLifecycle()
        val seriesDetail by vm.seriesDetail.collectAsStateWithLifecycle()
        val filmDetail by vm.filmDetail.collectAsStateWithLifecycle()
        val updating by vm.updating.collectAsStateWithLifecycle()
        val update by vm.update.collectAsStateWithLifecycle()
        val favourites by container.favourites.keys.collectAsStateWithLifecycle()
        val watchedMarks by container.watched.marks.collectAsStateWithLifecycle()
        val watched = remember(watchedMarks) { watchedMarks.filterValues { it.on }.keys }
        val history by container.lastWatched.history.collectAsStateWithLifecycle()
        val progressVersion by container.watchProgress.changes.collectAsStateWithLifecycle()
        val arrangement by container.categories.state.collectAsStateWithLifecycle()
        val account by vm.account.collectAsStateWithLifecycle()
        val sync by vm.sync.collectAsStateWithLifecycle()
        val syncUnlocked by vm.syncUnlocked.collectAsStateWithLifecycle()
        val providers by vm.providers.collectAsStateWithLifecycle()
        val activeId by vm.activeId.collectAsStateWithLifecycle()
        val progress = container.watchProgress
        val hasVod = s.provider is Provider.Xtream
        val destinations = remember(hasVod) {
            listOfNotNull(
                Destination.HOME,
                Destination.SEARCH,
                Destination.LIVE,
                Destination.SERIES.takeIf { hasVod },
                Destination.FILMS.takeIf { hasVod },
                Destination.MY_LIST,
                Destination.REFRESH,
                Destination.SETTINGS,
            )
        }
        var selected by rememberSaveable { mutableStateOf(Destination.HOME) }
        // Indexes over the whole catalogue, built once off the main thread and shared by pages.
        val shelves = rememberShelves(s.live, films, series, arrangement)
        val actions = remember {
            HomeActions(
                resume = { entry, index -> play(entry.channels(), index, entry.live, entry.heading, entry.seriesId) },
                openSeries = vm::openSeries,
                openFilm = vm::openFilm,
                playFilm = { film -> play(listOf(film), 0, live = false, heading = LastWatchedStore.FILM) },
                playChannel = { channels, index -> play(channels, index, live = true, heading = "Live TV") },
                toggleFavourite = vm::toggleFavourite,
                removeFromContinue = { entry ->
                    vm.removeFromContinue(entry.slot)
                    Toast.makeText(this@MainActivity, "Removed from Continue watching", Toast.LENGTH_SHORT).show()
                },
            )
        }
        val favouriteSet = remember(favourites) { favourites.toSet() }

        val overlay: (@Composable () -> Unit)? = when {
            filmDetail != null -> {
                {
                    val d = filmDetail!!
                    FilmDetailScreen(
                        detail = d,
                        progress = progress,
                        progressVersion = progressVersion,
                        inMyList = FavouritesStore.film(d.film) in favouriteSet,
                        onToggleMyList = { vm.toggleFavourite(FavouritesStore.film(d.film)) },
                        onPlay = actions.playFilm,
                        onBack = vm::closeFilm,
                    )
                }
            }
            seriesDetail != null -> {
                {
                    val d = seriesDetail!!
                    SeriesDetailScreen(
                        detail = d,
                        history = history,
                        progress = progress,
                        progressVersion = progressVersion,
                        inMyList = FavouritesStore.series(d.series) in favouriteSet,
                        onToggleMyList = { vm.toggleFavourite(FavouritesStore.series(d.series)) },
                        onResume = actions.resume,
                        onPlayEpisode = { show, season, index -> play(season.episodes, index, live = false, heading = show.name, seriesId = show.id) },
                        onBack = vm::closeSeries,
                    )
                }
            }
            else -> null
        }

        // ✓ Watched on every poster, from the record of watched titles.
        CompositionLocalProvider(LocalWatched provides watched) {
            AppShell(
                destinations = destinations,
                selected = selected,
                onSelect = { d -> if (d == Destination.REFRESH) vm.refreshEverything() else selected = d },
                refreshing = updating > 0,
                updateAvailable = update is UpdateState.Available || update is UpdateState.NeedsPermission,
                overlay = overlay,
            ) {
                when (selected) {
                    Destination.HOME -> HomeScreen(history, progress, progressVersion, shelves, favourites, actions)
                    Destination.SEARCH -> SearchScreen(shelves, favourites, actions)
                    Destination.MY_LIST -> MyListScreen(shelves, favourites, actions)
                    Destination.SETTINGS -> SettingsScreen(
                        provider = s.provider,
                        providers = providers,
                        activeId = activeId,
                        onSwitchProvider = vm::switchProvider,
                        onAddProvider = { vm.openForm() },
                        onEditProvider = { vm.openForm(it) },
                        onRemoveProvider = { activeId?.let(vm::removeProvider) },
                        updating = updating > 0,
                        onClearSavedData = vm::clearSavedData,
                        update = update,
                        onCheckForUpdates = { vm.checkForUpdates(manual = true) },
                        onInstallUpdate = vm::installUpdate,
                        account = account,
                        onLoadAccount = vm::loadAccount,
                        categories = remember(s.live, films, series) {
                            mapOf(
                                CategoryKind.LIVE to s.live.map { it.name to it.channels.size },
                                CategoryKind.SERIES to (series as? Load.Done)?.value.orEmpty().map { it.name to it.series.size },
                                CategoryKind.FILMS to (films as? Load.Done)?.value.orEmpty().map { it.name to it.channels.size },
                            )
                        },
                        categoryPrefs = arrangement,
                        onTogglePinned = container.categories::togglePinned,
                        onToggleHidden = container.categories::toggleHidden,
                        onResetCategories = container.categories::reset,
                        sync = sync,
                        syncUnlocked = syncUnlocked,
                        onToggleSyncUnlocked = {
                            val shown = vm.toggleSyncUnlocked()
                            Toast.makeText(this@MainActivity, if (shown) "Sync is now in Settings" else "Sync hidden", Toast.LENGTH_SHORT).show()
                        },
                        onStartSync = vm::startSync,
                        onSyncNow = { vm.syncNow(force = true) },
                        onStopSync = vm::stopSync,
                        onClearProgress = {
                            vm.clearProgress()
                            Toast.makeText(this@MainActivity, "Watch progress cleared", Toast.LENGTH_SHORT).show()
                        },
                    )
                    Destination.SERIES -> {
                        val groups = remember(series, arrangement) {
                            series.map { list -> container.categories[CategoryKind.SERIES].arrange(list) { it.name }.map { it.name to it.series } }
                        }
                        CatalogScreen(
                            title = "Series",
                            groups = groups,
                            spec = remember { PosterSpec<Series>({ it.id }, { it.name }, { it.coverUrl }, FavouritesStore::series) },
                            favourites = favourites,
                            updating = updating > 0,
                            onOpen = vm::openSeries,
                            onToggleFavourite = container.favourites::toggle,
                            onRetry = { vm.loadSeries(force = true) },
                        )
                    }
                    Destination.FILMS -> {
                        val groups = remember(films, arrangement) {
                            films.map { list -> container.categories[CategoryKind.FILMS].arrange(list) { it.name }.map { it.name to it.channels } }
                        }
                        CatalogScreen(
                            title = "Films",
                            groups = groups,
                            spec = PosterSpec(
                                { it.id }, { it.name }, { it.logoUrl }, FavouritesStore::film,
                                progress = { film -> progressVersion.let { progress.get(film.streamUrl)?.takeIf { !it.finished }?.fraction } },
                            ),
                            favourites = favourites,
                            updating = updating > 0,
                            onOpen = vm::openFilm,
                            onToggleFavourite = container.favourites::toggle,
                            onRetry = { vm.loadFilms(force = true) },
                        )
                    }
                    else -> LiveScreen(
                        provider = s.provider,
                        live = remember(s.live, arrangement) { container.categories[CategoryKind.LIVE].arrange(s.live) { it.name } },
                        allLive = s.live,
                        favourites = favourites,
                        updating = updating > 0,
                        onPlay = actions.playChannel,
                        onToggleFavourite = container.favourites::toggle,
                    )
                }
            }
        }
    }

    private fun play(playlist: List<Channel>, index: Int, live: Boolean, heading: String, seriesId: String? = null) {
        container.playlist = playlist
        startActivity(PlayerActivity.intent(this, index, live, heading, seriesId))
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun FailedScreen(message: String, onRetry: () -> Unit, onEdit: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    Centered {
        Text("Couldn't load channels", style = MaterialTheme.typography.headlineSmall)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus).focusRing()) { Text("Retry") }
            OutlinedButton(onClick = onEdit, modifier = Modifier.focusRing()) { Text("Edit provider") }
        }
    }
    LaunchedEffect(Unit) { retryFocus.requestFocus() }
}
