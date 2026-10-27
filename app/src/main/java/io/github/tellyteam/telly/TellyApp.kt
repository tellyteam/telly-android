package io.github.tellyteam.telly

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.github.tellyteam.telly.data.Channel
import io.github.tellyteam.telly.data.ChannelRepository
import io.github.tellyteam.telly.data.NewEpisodeChecks
import io.github.tellyteam.telly.data.guide.GuideRepository
import io.github.tellyteam.telly.data.net.Http
import io.github.tellyteam.telly.data.update.UpdateRepository
import io.github.tellyteam.telly.data.net.NetworkPolicy
import io.github.tellyteam.telly.data.store.AspectStore
import io.github.tellyteam.telly.data.store.CatalogCache
import io.github.tellyteam.telly.data.store.CategoryStore
import io.github.tellyteam.telly.data.store.SubtitlePreference
import io.github.tellyteam.telly.data.store.FavouritesStore
import io.github.tellyteam.telly.data.store.LastWatchedStore
import io.github.tellyteam.telly.data.store.ProviderStore
import io.github.tellyteam.telly.data.store.SavedProvider
import io.github.tellyteam.telly.data.store.Scoped
import io.github.tellyteam.telly.data.store.WatchProgressStore
import io.github.tellyteam.telly.data.store.WatchedStore
import io.github.tellyteam.telly.data.sync.SyncClient
import io.github.tellyteam.telly.data.sync.SyncSettings
import java.io.File

class TellyApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    // Channel logos go through the same client, so they obey the same cleartext policy.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.http)
        .crossfade(true)
        .build()
}

/** Hand-wired dependencies; small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val networkPolicy = NetworkPolicy()
    val http = Http.client(networkPolicy)
    val providerStore = ProviderStore(context).also { store ->
        // The one provider of earlier versions becomes the first saved one, with its records.
        store.migrateSingle()?.let { Scoped.move(context, null, it) }
    }
    val channels = ChannelRepository(http, CatalogCache(File(context.noBackupFilesDir, "catalog")))

    // The provider in use's records; [useProvider] points them at another's.
    val watchProgress = WatchProgressStore(context, providerStore.activeId)
    val favourites = FavouritesStore(context, providerStore.activeId)
    val lastWatched = LastWatchedStore(context, providerStore.activeId)
    val watched = WatchedStore(context, providerStore.activeId)
    val aspects = AspectStore(context)
    val guide = GuideRepository(http)
    val updates = UpdateRepository(context, http)
    val newEpisodeChecks = NewEpisodeChecks(context)
    val categories = CategoryStore(context, providerStore.activeId)
    val subtitles = SubtitlePreference(context)
    val syncSettings = SyncSettings(context)
    val syncClient = SyncClient(http)

    /** Makes [id] the provider in use: its My List, progress, history, watched titles and categories. */
    fun useProvider(id: String?) {
        providerStore.activeId = id
        watchProgress.use(id)
        favourites.use(id)
        lastWatched.use(id)
        watched.use(id)
        categories.use(id)
    }

    /** The provider in use, as saved. */
    fun activeProvider(): SavedProvider? = providerStore.activeId?.let { id -> providerStore.loadAll().firstOrNull { it.id == id } }

    /** What the player plays: a live group (for zapping), one film, or a season's episodes (in order). */
    @Volatile
    var playlist: List<Channel> = emptyList()
}

val Context.container: AppContainer get() = (applicationContext as TellyApp).container
