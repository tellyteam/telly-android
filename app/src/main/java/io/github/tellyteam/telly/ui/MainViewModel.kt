package io.github.tellyteam.telly.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import coil.imageLoader
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.tellyteam.telly.BuildConfig
import io.github.tellyteam.telly.data.update.Release
import io.github.tellyteam.telly.data.update.ReleaseParser
import io.github.tellyteam.telly.container
import io.github.tellyteam.telly.data.Channel
import io.github.tellyteam.telly.data.ChannelGroup
import io.github.tellyteam.telly.data.AccountText
import io.github.tellyteam.telly.data.Details
import io.github.tellyteam.telly.data.NewEpisodes
import io.github.tellyteam.telly.data.PlaybackRules
import io.github.tellyteam.telly.data.SeriesData
import io.github.tellyteam.telly.data.Provider
import io.github.tellyteam.telly.data.Season
import io.github.tellyteam.telly.data.Series
import io.github.tellyteam.telly.data.SeriesGroup
import io.github.tellyteam.telly.data.Source
import io.github.tellyteam.telly.data.xtream.XtreamParser
import io.github.tellyteam.telly.data.store.FavouritesStore
import io.github.tellyteam.telly.data.store.LastWatchedStore
import io.github.tellyteam.telly.data.store.ProviderStore
import io.github.tellyteam.telly.data.store.SavedProvider
import io.github.tellyteam.telly.data.store.Scoped
import io.github.tellyteam.telly.data.store.WatchProgressStore
import io.github.tellyteam.telly.data.store.WatchedStore
import io.github.tellyteam.telly.data.syncIdentity
import io.github.tellyteam.telly.data.withAllowCleartext
import io.github.tellyteam.telly.data.sync.SyncBundle
import io.github.tellyteam.telly.data.sync.SyncConfig
import io.github.tellyteam.telly.data.sync.SyncCrypto
import io.github.tellyteam.telly.data.sync.SyncSettings
import io.github.tellyteam.telly.data.sync.SyncEntry
import io.github.tellyteam.telly.data.sync.SyncException
import io.github.tellyteam.telly.data.sync.SyncLocal
import io.github.tellyteam.telly.data.sync.SyncWatched
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

sealed interface UiState {
    /** No provider yet, or the user chose to edit it (then [existing] pre-fills the form). */
    data class Setup(val existing: Provider? = null) : UiState
    data class Loading(val provider: Provider) : UiState
    data class Ready(val provider: Provider, val live: List<ChannelGroup>) : UiState
    data class Failed(val provider: Provider, val message: String) : UiState
}

/** A section that loads on demand (films, series, a series' seasons). */
sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

fun <T, R> Load<T>.map(transform: (T) -> R): Load<R> = when (this) {
    is Load.Done -> Load.Done(transform(value))
    is Load.Failed -> this
    Load.Idle -> Load.Idle
    Load.Loading -> Load.Loading
}

data class SeriesDetail(val series: Series, val data: Load<SeriesData>) {
    val seasons: Load<List<Season>>
        get() = when (data) {
            is Load.Done -> Load.Done(data.value.seasons)
            is Load.Failed -> data
            Load.Idle -> Load.Idle
            Load.Loading -> Load.Loading
        }
}

data class FilmDetail(val film: Channel, val details: Load<Details>)

/** The provider form over the browse screens: adding one ([editing] null) or changing one. */
data class ProviderForm(val editing: SavedProvider?)

/** Sync for Settings: [config] is null when this device isn't linked. */
data class SyncStatus(
    val config: SyncConfig? = null,
    val busy: Boolean = false,
    val lastSyncedAt: Long? = null,
    val error: String? = null,
)

/** Where the app-update check has got to. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** Telly needs "install unknown apps" permission first; the settings screen has been opened. */
    data class NeedsPermission(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * Every catalogue is shown from the copy saved last time (instant), then refreshed from the
 * provider in the background. Films and series preload as soon as live TV is up.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val repo = container.channels
    private val _state = MutableStateFlow<UiState>(UiState.Setup())
    val state: StateFlow<UiState> = _state
    private var loadJob: Job? = null

    private val _films = MutableStateFlow<Load<List<ChannelGroup>>>(Load.Idle)
    val films: StateFlow<Load<List<ChannelGroup>>> = _films
    private val _series = MutableStateFlow<Load<List<SeriesGroup>>>(Load.Idle)
    val series: StateFlow<Load<List<SeriesGroup>>> = _series
    private val _seriesDetail = MutableStateFlow<SeriesDetail?>(null)
    val seriesDetail: StateFlow<SeriesDetail?> = _seriesDetail
    private val _filmDetail = MutableStateFlow<FilmDetail?>(null)
    val filmDetail: StateFlow<FilmDetail?> = _filmDetail

    /** The provider form, open over the browse screens so closing it needs no reload. */
    private val _form = MutableStateFlow<ProviderForm?>(null)
    val form: StateFlow<ProviderForm?> = _form

    /** Every saved provider, and the one in use. */
    private val _providers = MutableStateFlow(container.providerStore.loadAll())
    val providers: StateFlow<List<SavedProvider>> = _providers
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId

    /** What [retry] tries again: the provider last connected to, and whether it was being saved (and in place of which). */
    private var attempt: Triple<Provider, Boolean, String?>? = null

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update

    /** The subscription's end date and connections, shown in Settings (Xtream providers only). */
    private val _account = MutableStateFlow<Load<XtreamParser.Account>>(Load.Idle)
    val account: StateFlow<Load<XtreamParser.Account>> = _account

    /** Number of background refreshes running, for the "Updating…" hint. */
    private val _updating = MutableStateFlow(0)
    val updating: StateFlow<Int> = _updating

    /** Large catalogues are downloaded and parsed one at a time to stay within a TV stick's memory. */
    private val heavy = Mutex()

    /** Saved copies are read one at a time too, but separately, so they never wait behind a download. */
    private val savedCopies = Mutex()

    /** Bumped whenever the provider changes, so late results from the old one are dropped. */
    private var generation = 0

    /** One new-episode check at a time. */
    private val episodeCheck = Mutex()

    private val _sync = MutableStateFlow(SyncStatus(container.syncSettings.load()))
    val sync: StateFlow<SyncStatus> = _sync

    /** Sync is a hidden feature for now: Settings shows it once unlocked, or while this device is linked. */
    private val _syncUnlocked = MutableStateFlow(container.syncSettings.unlocked || _sync.value.config != null)
    val syncUnlocked: StateFlow<Boolean> = _syncUnlocked

    private val syncing = Mutex()
    private var syncSoonJob: Job? = null

    init {
        // Weekly, quietly: only says anything if there is a newer version.
        if (container.updates.dueForCheck()) checkForUpdates(manual = false)
        viewModelScope.launch {
            _series.collect {
                if (it !is Load.Done) return@collect
                checkForNewEpisodes()
                // Everything a shared entry might point at is loaded now.
                syncNow()
            }
        }
        val saved = _providers.value.let { list -> list.firstOrNull { it.id == container.providerStore.activeId } ?: list.firstOrNull() }
        if (saved != null) activate(saved) else devProvider()?.let { _state.value = UiState.Setup(it) }
    }

    /** Opens the provider form: to add one, or to change [editing]. */
    fun openForm(editing: SavedProvider? = null) {
        _form.value = ProviderForm(editing)
    }

    fun closeForm() {
        _form.value = null
    }

    /** Makes [saved] the provider in use: its records straight away, then its lists. */
    private fun activate(saved: SavedProvider) {
        container.useProvider(saved.id)
        _activeId.value = saved.id
        connect(saved.provider, save = false)
    }

    /** Switches to another saved provider. */
    fun switchProvider(id: String) {
        if (id == _activeId.value) return
        _providers.value.firstOrNull { it.id == id }?.let(::activate)
    }

    /**
     * Connects to [provider]. With [save], it's checked first (its channel list has to download)
     * and only then saved: as a new provider that comes into use, or in place of [editing].
     */
    fun connect(provider: Provider, save: Boolean = true, editing: String? = null) {
        _form.value = null
        attempt = Triple(provider, save, editing)
        if (save) {
            // The same provider twice would only split its records: use the one already saved.
            _providers.value.firstOrNull { it.id != editing && it.provider.syncIdentity == provider.syncIdentity }?.let { same ->
                Toast.makeText(getApplication(), "You've already added ${same.provider.name}.", Toast.LENGTH_LONG).show()
                if (same.id != _activeId.value) activate(same)
                return
            }
        }
        val previous = _providers.value.firstOrNull { it.id == _activeId.value }
        // A new provider starts with empty records under its own id (kept only if it works).
        val id = if (save) editing ?: ProviderStore.newId() else _activeId.value
        if (save && id != _activeId.value) {
            container.useProvider(id)
            _activeId.value = id
        }
        container.networkPolicy.allowCleartext = provider.allowCleartext
        resetCatalogues()
        val gen = generation
        loadJob?.cancel()
        _state.value = UiState.Loading(provider)
        loadJob = viewModelScope.launch {
            val cached = if (save) null else runCatching { savedCopies.withLock { repo.liveGroups(provider, Source.CACHE) } }.getOrNull()
            if (cached != null) {
                _state.value = UiState.Ready(provider, cached)
                // Saved films and series show straight away too, instead of after the channel download.
                loadFilms()
                loadSeries()
            }
            refreshing {
                try {
                    val fresh = heavy.withLock { repo.liveGroups(provider, Source.NETWORK)!! }
                    // Only remember a provider once it has actually worked.
                    if (save && id != null) remember(SavedProvider(id, provider, System.currentTimeMillis()), editing)
                    if (gen == generation) _state.value = UiState.Ready(provider, fresh)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message ?: e.javaClass.simpleName
                    when {
                        // With a saved list on screen, a failed refresh just keeps it.
                        cached != null -> {}
                        // A new provider that doesn't work: back to the one in use, with the reason.
                        save && editing == null && previous != null -> {
                            Toast.makeText(getApplication(), "Couldn't add ${provider.name}: $message", Toast.LENGTH_LONG).show()
                            activate(previous)
                        }
                        else -> _state.value = UiState.Failed(provider, message)
                    }
                }
            }
            if (_state.value is UiState.Ready) {
                loadFilms()
                loadSeries()
                remindIfExpiring()
            }
        }
    }

    /** Re-downloads everything now; the saved lists stay on screen until the new ones arrive. */
    fun refreshEverything() {
        val provider = (_state.value as? UiState.Ready)?.provider ?: return
        connect(provider, save = false)
    }

    /** Deletes saved catalogues and cached artwork, then downloads afresh. Favourites, progress and history are kept. */
    fun clearSavedData() {
        val provider = (_state.value as? UiState.Ready)?.provider ?: return
        viewModelScope.launch {
            container.channels.clearSavedCatalogues()
            clearImageCaches()
            connect(provider, save = false)
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private suspend fun clearImageCaches() {
        val loader = getApplication<Application>().imageLoader
        loader.memoryCache?.clear()
        withContext(Dispatchers.IO) { loader.diskCache?.clear() }
    }

    fun checkForUpdates(manual: Boolean = true) {
        if (_update.value is UpdateState.Checking || _update.value is UpdateState.Downloading) return
        if (manual) _update.value = UpdateState.Checking
        viewModelScope.launch {
            _update.value = try {
                val latest = container.updates.latest()
                when {
                    latest != null && ReleaseParser.isNewer(latest.version, BuildConfig.VERSION_NAME) -> {
                        if (!manual) {
                            Toast.makeText(getApplication(), "telly ${latest.version} is available. Go to Settings to update.", Toast.LENGTH_LONG).show()
                        }
                        UpdateState.Available(latest)
                    }
                    manual -> UpdateState.UpToDate
                    else -> UpdateState.Idle
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) UpdateState.Failed("Couldn't check for updates. Try again later.") else UpdateState.Idle
            }
        }
    }

    /** Downloads the release and hands it to Android's installer, which asks the user to confirm. */
    fun installUpdate() {
        val release = when (val u = _update.value) {
            is UpdateState.Available -> u.release
            is UpdateState.NeedsPermission -> u.release
            is UpdateState.Failed -> u.release
            else -> null
        } ?: return
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            _update.value = UpdateState.NeedsPermission(release)
            return
        }
        _update.value = UpdateState.Downloading(release, 0f)
        viewModelScope.launch {
            try {
                val apk = container.updates.download(release) { p -> _update.value = UpdateState.Downloading(release, p) }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
                app.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                _update.value = UpdateState.Available(release)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.value = UpdateState.Failed("The download didn't finish. Check your connection and try again.", release)
            }
        }
    }

    fun retry() {
        val (provider, save, editing) = attempt ?: return
        if (_state.value is UiState.Failed) connect(provider, save, editing)
    }

    /** From the failure screen: the form, filled in with what didn't work. */
    fun editProvider(current: Provider?) {
        loadJob?.cancel()
        _state.value = UiState.Setup(current)
    }

    /** "Remove provider" on the sign-in screen: the provider that failed, if it was saved; otherwise just a clean form. */
    fun forgetAttempt() {
        val id = _activeId.value
        if (id != null && _providers.value.any { it.id == id }) removeProvider(id) else _state.value = UiState.Setup()
    }

    /** What the form's Connect does: saves in place of the provider being changed, or adds a new one. */
    fun submitProvider(provider: Provider) {
        // Over the browse screens, the form says; on the sign-in screen, it's whatever was last tried.
        val form = _form.value
        val editing = if (form != null) form.editing?.id else attempt?.third.takeIf { _state.value is UiState.Setup }
        connect(provider, save = true, editing = editing)
    }

    /** Saves [saved] (new, or in place of [editing]) and makes it the provider in use. */
    private fun remember(saved: SavedProvider, editing: String?) {
        val list = _providers.value
        val old = list.firstOrNull { it.id == saved.id }
        if (old != null && old.provider.syncIdentity != saved.provider.syncIdentity) {
            // A new server or login: the old one's lists go.
            viewModelScope.launch { repo.clearSavedCatalogues(old.provider) }
        }
        saveProviders(if (old != null) list.map { if (it.id == saved.id) saved else it } else list + saved)
        container.providerStore.activeId = saved.id
        _activeId.value = saved.id
    }

    private fun saveProviders(list: List<SavedProvider>) {
        container.providerStore.saveAll(list)
        _providers.value = list
    }

    /** Removes a provider from this device, with its lists and everything watched with it. */
    fun removeProvider(id: String) {
        val gone = _providers.value.firstOrNull { it.id == id } ?: return
        saveProviders(_providers.value.filter { it.id != id })
        // Its history holds stream URLs, which carry the login.
        Scoped.delete(getApplication(), id)
        viewModelScope.launch { repo.clearSavedCatalogues(gone.provider) }
        if (id != _activeId.value) return
        _form.value = null
        loadJob?.cancel()
        container.playlist = emptyList()
        resetCatalogues()
        val next = _providers.value.firstOrNull()
        if (next != null) {
            activate(next)
        } else {
            container.useProvider(null)
            _activeId.value = null
            _state.value = UiState.Setup()
        }
    }

    /** Lets a provider's "Allow unencrypted HTTP" be changed without the whole form. */
    fun setAllowCleartext(allow: Boolean) {
        val id = _activeId.value ?: return
        val list = _providers.value
        val saved = list.firstOrNull { it.id == id } ?: return
        val updated = saved.copy(provider = saved.provider.withAllowCleartext(allow), changedAt = System.currentTimeMillis())
        saveProviders(list.map { if (it.id == id) updated else it })
        container.networkPolicy.allowCleartext = allow
        (_state.value as? UiState.Ready)?.let { _state.value = it.copy(provider = updated.provider) }
    }

    fun loadFilms(force: Boolean = false) = loadSection(_films, force) { p, source -> repo.filmGroups(p, source) }

    fun loadSeries(force: Boolean = false) = loadSection(_series, force) { p, source -> repo.seriesGroups(p, source) }

    fun openSeries(series: Series) {
        val provider = xtream() ?: return
        _filmDetail.value = null
        _seriesDetail.value = SeriesDetail(series, Load.Loading)
        loadDetail(
            cache = { repo.seriesData(provider, series, Source.CACHE) },
            network = { repo.seriesData(provider, series, Source.NETWORK) },
            // Ignore results if the user has since opened something else or gone back.
            publish = { data -> if (_seriesDetail.value?.series == series) _seriesDetail.value = SeriesDetail(series, data) },
        )
    }

    fun openFilm(film: Channel) {
        val provider = xtream() ?: return
        _seriesDetail.value = null
        _filmDetail.value = FilmDetail(film, Load.Loading)
        loadDetail(
            cache = { repo.filmDetails(provider, film, Source.CACHE) },
            network = { repo.filmDetails(provider, film, Source.NETWORK) },
            publish = { data -> if (_filmDetail.value?.film == film) _filmDetail.value = FilmDetail(film, data) },
        )
    }

    fun closeFilm() {
        _filmDetail.value = null
    }

    /** Shows the saved copy at once (if any), then the provider's latest. */
    private fun <T> loadDetail(cache: suspend () -> T?, network: suspend () -> T?, publish: (Load<T>) -> Unit) {
        val gen = generation
        viewModelScope.launch {
            val cached = runCatching { cache() }.getOrNull()
            if (cached != null && gen == generation) publish(Load.Done(cached))
            refreshing {
                try {
                    val fresh = network()!!
                    if (gen == generation) publish(Load.Done(fresh))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (cached == null && gen == generation) publish(Load.Failed(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /**
     * Brings back series that were watched to the end of what they had, once the provider adds more:
     * the series returns to Continue watching at the next episode ("New episodes" when they arrived
     * after the user watched). Runs as the series list loads and when the player closes; each series
     * is only fetched again after the provider has changed it, or to record it as watched
     * (PLAYBACK.md, Watched titles).
     */
    fun checkForNewEpisodes() {
        val provider = xtream() ?: return
        recordWatchedFilms()
        val groups = (_series.value as? Load.Done)?.value ?: return
        viewModelScope.launch {
            if (!episodeCheck.tryLock()) return@launch
            try {
                val history = container.lastWatched.history.value
                val progress = container.watchProgress
                val watched = container.watched
                val candidates = history.filter { it.caughtUp(progress) }
                // Watched series that Continue watching's short history has let go are checked too.
                val letGo = watched.marks.value.filter { (k, m) ->
                    m.on && k.startsWith("series:") && history.none { it.seriesId == k.removePrefix("series:") }
                }
                if (candidates.isEmpty() && letGo.isEmpty()) return@launch
                val all = withContext(Dispatchers.Default) { groups.flatMap { it.series } }
                val byId = all.associateBy { it.id }
                val byName = all.associateBy { it.name }
                val checks = container.newEpisodeChecks
                suspend fun fetch(show: Series) = try {
                    repo.seriesData(provider, show, Source.NETWORK)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                for (entry in candidates) {
                    val show = entry.seriesId?.let(byId::get) ?: byName[entry.heading] ?: continue
                    val key = FavouritesStore.series(show)
                    val mark = watched.marks.value[key]
                    val recorded = mark?.on == true && mark.episode == entry.current.id
                    if (recorded && !NewEpisodes.due(show, checks.get(show.id), System.currentTimeMillis())) continue
                    val data = fetch(show) ?: continue
                    checks.mark(show.id, show.added)
                    val next = NewEpisodes.after(data.seasons, entry.current.id)
                    if (next == null) {
                        if (!recorded) watched.set(key, true, entry.current.id)
                        continue
                    }
                    if (mark?.on == true) watched.set(key, false)
                    val (season, index) = next
                    val fresh = NewEpisodes.isNew(show, entry.savedAt)
                    container.lastWatched.save(
                        entry.heading, live = false, season.episodes, index,
                        // Never moved to the front: Continue watching leads with what you last watched.
                        seriesId = show.id, newEpisodes = fresh, toFront = false,
                        // Still "when it was last watched", which orders Continue watching on every synced device.
                        now = entry.savedAt,
                    )
                    if (fresh) Toast.makeText(getApplication(), "New episodes of ${entry.heading}", Toast.LENGTH_LONG).show()
                }
                for ((key, mark) in letGo) {
                    val show = byId[key.removePrefix("series:")] ?: continue
                    if (!NewEpisodes.due(show, checks.get(show.id), System.currentTimeMillis())) continue
                    val data = fetch(show) ?: continue
                    checks.mark(show.id, show.added)
                    val (season, index) = NewEpisodes.after(data.seasons, mark.episode) ?: continue
                    watched.set(key, false)
                    val fresh = NewEpisodes.isNew(show, mark.at)
                    container.lastWatched.save(
                        show.name, live = false, season.episodes, index,
                        seriesId = show.id, newEpisodes = fresh, toFront = false, now = mark.at,
                    )
                    if (fresh) Toast.makeText(getApplication(), "New episodes of ${show.name}", Toast.LENGTH_LONG).show()
                }
                syncSoon()
            } finally {
                episodeCheck.unlock()
            }
        }
    }

    // ---- My List, Continue watching and progress (changes here are shared when sync is on) ----

    fun toggleFavourite(key: String) {
        container.favourites.toggle(key)
        syncSoon()
    }

    /**
     * Records films watched since last time, and takes them out of My List (PLAYBACK.md, Watched
     * titles). The first time, films watched before there was a record go in without leaving it.
     */
    private fun recordWatchedFilms() {
        val watched = container.watched
        val leave = watched.seeded
        for ((k, p) in container.watchProgress.all()) {
            val id = WatchedStore.filmId(k) ?: continue
            val key = "film:$id"
            if (!p.finished || watched.marks.value[key] != null) continue
            watched.set(key, true, now = if (leave) System.currentTimeMillis() else p.savedAt)
            if (leave && PlaybackRules.leavesMyList("film") && key in container.favourites.keys.value) container.favourites.toggle(key)
        }
        watched.seeded = true
    }

    fun removeFromContinue(slot: String) {
        container.lastWatched.remove(slot)
        syncSoon()
    }

    /** Forgets every position and empties Continue watching (My List stays); linked devices follow. */
    fun clearProgress() {
        container.watchProgress.clear()
        container.lastWatched.clearProgress()
        container.watched.clear()
        syncNow(force = true)
    }

    // ---- Sync ----

    /** Starts a new household with a fresh code, or joins one with [code]. Returns an error message, or null. */
    suspend fun startSync(code: String?): String? {
        val normal = if (code == null) SyncCrypto.newCode() else SyncCrypto.normaliseCode(code)
            ?: return "That code isn't right: it's 16 letters and numbers, like ABCD-EFGH-JKMN-PQRS."
        val config = SyncConfig(normal, _sync.value.config?.device ?: SyncCrypto.newDeviceId(), SyncSettings.RELAY)
        container.syncSettings.save(config)
        _sync.value = SyncStatus(config)
        val error = runSync(config)
        if (error != null) {
            container.syncSettings.clear()
            _sync.value = SyncStatus(error = error)
        }
        return error
    }

    /** Shows or hides Settings → Sync. Returns whether it's now shown. Hiding never unlinks a linked device. */
    fun toggleSyncUnlocked(): Boolean {
        val shown = !_syncUnlocked.value || _sync.value.config != null
        container.syncSettings.unlocked = shown
        _syncUnlocked.value = shown
        return shown
    }

    /** Takes this device out of sync: its copy leaves the relay; the other devices keep theirs. */
    fun stopSync() {
        val config = _sync.value.config ?: return
        container.syncSettings.clear()
        _sync.value = SyncStatus()
        viewModelScope.launch {
            runCatching { container.syncClient.leave(config.relay, SyncCrypto.deriveKeys(config.code), config.device) }
        }
    }

    /** After small changes (My List, removals): soon, but once for a burst of them. */
    private fun syncSoon() {
        syncSoonJob?.cancel()
        syncSoonJob = viewModelScope.launch {
            delay(3_000)
            syncNow(force = true)
        }
    }

    /**
     * Fetches the other devices' changes, takes in what's newer and shares the result. Runs on
     * start, when telly comes back to the front (including from the player) and after changes;
     * never during playback, which happens in another activity.
     */
    fun syncNow(force: Boolean = false) {
        val status = _sync.value
        val config = status.config ?: return
        if (!force && System.currentTimeMillis() - (status.lastSyncedAt ?: 0) < SYNC_GAP_MS) return
        viewModelScope.launch { runSync(config) }
    }

    /** One provider's records, for sync: the provider in use's live ones, any other's as saved. */
    private class Records(val id: String, val progress: WatchProgressStore, val history: LastWatchedStore, val favourites: FavouritesStore, val watched: WatchedStore) {
        fun local() = SyncLocal.Local(
            progress.all(), history.history.value, favourites.keys.value, favourites.changes, history.removed, history.cleared,
            watched.marks.value.mapValues { (_, m) -> SyncWatched(m.at, m.on, m.episode) },
        )
    }

    /**
     * Each saved provider's records travel in their own section, matched to other devices' by
     * provider tag. The provider in use takes in everything; the others take in progress, My List
     * and removals now, and Continue watching when they're next in use (resolving it needs their
     * lists). Provider logins never travel: each device signs in to its providers itself.
     */
    private suspend fun runSync(config: SyncConfig): String? {
        if (!syncing.tryLock()) return null
        try {
            val providers = _providers.value
            // Without a provider there's nothing to sync.
            if (providers.isEmpty()) return null
            _sync.value = _sync.value.copy(busy = true, error = null)
            val app = getApplication<Application>()
            val keys = SyncCrypto.deriveKeys(config.code)
            val active = _activeId.value
            val records = providers.associate { saved ->
                SyncCrypto.providerTag(keys, saved.provider.syncIdentity) to
                    if (saved.id == active) Records(saved.id, container.watchProgress, container.lastWatched, container.favourites, container.watched)
                    else Records(saved.id, WatchProgressStore(app, saved.id), LastWatchedStore(app, saved.id), FavouritesStore(app, saved.id), WatchedStore(app, saved.id))
            }
            val locals = records.mapValues { it.value.local() }
            // A version 1 parcel doesn't say whose it is, so it's read as this device's provider only
            // when there's just the one. With several, it's ignored: taken as the provider in use, an
            // older device's My List would turn up under every provider switched to.
            val current = records.entries.singleOrNull()?.key
            val now = System.currentTimeMillis()
            val own = SyncBundle(now, locals.mapValues { SyncLocal.toParcel(it.value, now) })
            val merged = container.syncClient.syncOnce(config.relay, keys, config.device, own, current, now)
            if (_sync.value.config != config) return null
            for ((tag, r) in records) {
                val section = merged.sections[tag] ?: continue
                val isActive = r.id == _activeId.value
                val next = SyncLocal.apply(locals.getValue(tag), section, MAX_HISTORY) { if (isActive) resolve(it) else null }
                // A switch of provider meanwhile: these are no longer the records in use.
                if (r.id == active && !isActive) continue
                if (_providers.value.none { it.id == r.id }) continue
                r.progress.replace(next.progress)
                r.history.replace(next.history, next.removed, next.cleared)
                r.favourites.replace(next.favourites, next.listChanges)
                r.watched.replace(next.watched.mapValues { (_, w) -> WatchedStore.Mark(w.at, w.on, w.episode) })
            }
            _sync.value = _sync.value.copy(busy = false, lastSyncedAt = System.currentTimeMillis())
            return null
        } catch (e: CancellationException) {
            _sync.value = _sync.value.copy(busy = false)
            throw e
        } catch (e: Exception) {
            val message = (e as? SyncException)?.message ?: "Couldn't sync: ${e.javaClass.simpleName}"
            _sync.value = _sync.value.copy(busy = false, error = message)
            return message
        } finally {
            syncing.unlock()
        }
    }

    /** A shared history entry as one this device can play, from its own lists and login. */
    private suspend fun resolve(e: SyncEntry): LastWatchedStore.LastWatched? {
        fun entry(heading: String, live: Boolean, playlist: List<Channel>, index: Int, seriesId: String? = null) =
            LastWatchedStore.LastWatched(
                heading, live, index,
                playlist.map { LastWatchedStore.Item(it.id, it.name, it.group, it.logoUrl, it.streamUrl) },
                seriesId, e.at,
            )
        return when (e.kind) {
            SyncEntry.Kind.FILM -> {
                val films = (_films.value as? Load.Done)?.value ?: return null
                films.firstNotNullOfOrNull { g -> g.channels.firstOrNull { it.id == e.id } }
                    ?.let { entry(LastWatchedStore.FILM, false, listOf(it), 0) }
            }
            SyncEntry.Kind.LIVE -> {
                val live = (_state.value as? UiState.Ready)?.live ?: return null
                val group = live.firstOrNull { g -> g.channels.any { it.id == e.id } } ?: return null
                entry("Live TV", true, group.channels, group.channels.indexOfFirst { it.id == e.id })
            }
            SyncEntry.Kind.SERIES -> {
                val provider = xtream() ?: return null
                val show = (_series.value as? Load.Done)?.value?.firstNotNullOfOrNull { g -> g.series.firstOrNull { it.id == e.series } }
                    ?: return null
                val data = runCatching { repo.seriesData(provider, show, Source.CACHE) }.getOrNull()
                    ?: runCatching { repo.seriesData(provider, show, Source.NETWORK) }.getOrNull()
                    ?: return null
                data.seasons.firstNotNullOfOrNull { season ->
                    season.episodes.indexOfFirst { it.id == e.id }.takeIf { it >= 0 }?.let { entry(show.name, false, season.episodes, it, show.id) }
                }
            }
        }
    }

    /** Fetches the subscription's details afresh (Settings shows them; cheap: one small request). */
    fun loadAccount() {
        val provider = xtream() ?: return
        if (_account.value !is Load.Done) _account.value = Load.Loading
        viewModelScope.launch {
            _account.value = try {
                Load.Done(repo.account(provider))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (_account.value as? Load.Done) ?: Load.Failed("Couldn't reach your provider just now.")
            }
        }
    }

    /** Once a day, in the last week of a subscription: "Your subscription ends in 3 days". */
    private fun remindIfExpiring() {
        val provider = xtream() ?: return
        val prefs = getApplication<Application>().getSharedPreferences("account", Context.MODE_PRIVATE)
        val today = System.currentTimeMillis() / DAY_MS
        if (prefs.getLong("remindedDay", -1) == today) return
        viewModelScope.launch {
            val account = runCatching { repo.account(provider) }.getOrNull() ?: return@launch
            _account.value = Load.Done(account)
            val days = account.expiresAtMs?.let { AccountText.daysLeft(it, System.currentTimeMillis()) } ?: return@launch
            if (days !in 0..7) return@launch
            prefs.edit().putLong("remindedDay", today).apply()
            Toast.makeText(getApplication(), AccountText.reminder(days), Toast.LENGTH_LONG).show()
        }
    }

    fun closeSeries() {
        _seriesDetail.value = null
    }

    private fun <T> loadSection(
        target: MutableStateFlow<Load<T>>,
        force: Boolean,
        block: suspend (Provider.Xtream, Source) -> T?,
    ) {
        val provider = xtream() ?: return
        if (!force && (target.value is Load.Loading || target.value is Load.Done)) return
        val gen = generation
        target.value = Load.Loading
        viewModelScope.launch {
            // The saved copy first (quick), then the provider's latest after any other download.
            val cached = savedCopies.withLock { if (gen == generation) runCatching { block(provider, Source.CACHE) }.getOrNull() else null }
            if (cached != null && gen == generation) target.value = Load.Done(cached)
            heavy.withLock {
                if (gen != generation) return@withLock
                refreshing {
                    val result = try {
                        Load.Done(block(provider, Source.NETWORK)!!)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (cached == null) Load.Failed(e.message ?: e.javaClass.simpleName) else null
                    }
                    if (result != null && gen == generation) target.value = result
                }
            }
        }
    }

    private inline fun refreshing(block: () -> Unit) {
        _updating.value++
        try {
            block()
        } finally {
            _updating.value--
        }
    }

    private fun xtream(): Provider.Xtream? = (_state.value as? UiState.Ready)?.provider as? Provider.Xtream

    private fun resetCatalogues() {
        generation++
        _account.value = Load.Idle
        _films.value = Load.Idle
        _series.value = Load.Idle
        _seriesDetail.value = null
        _filmDetail.value = null
    }

    /** Debug builds only: a test provider from provider.local.properties, shown pre-filled but not auto-connected. */
    private fun devProvider(): Provider? {
        if (!BuildConfig.DEBUG) return null
        val name = BuildConfig.DEV_PROVIDER_NAME.ifBlank { "Test provider" }
        val cleartext = BuildConfig.DEV_PROVIDER_ALLOWCLEARTEXT.toBoolean()
        return when (BuildConfig.DEV_PROVIDER_TYPE) {
            "xtream" -> Provider.Xtream(
                name, BuildConfig.DEV_PROVIDER_SERVER, BuildConfig.DEV_PROVIDER_USERNAME, BuildConfig.DEV_PROVIDER_PASSWORD, cleartext,
            ).takeIf { it.server.isNotBlank() }
            "m3u" -> Provider.M3u(name, BuildConfig.DEV_PROVIDER_URL, cleartext).takeIf { it.url.isNotBlank() }
            else -> null
        }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Coming back to telly syncs at most this often. */
private const val SYNC_GAP_MS = 60_000L

/** Continue watching keeps this many entries (as LastWatchedStore does). */
private const val MAX_HISTORY = 20
