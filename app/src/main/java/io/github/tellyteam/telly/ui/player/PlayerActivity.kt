package io.github.tellyteam.telly.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.tellyteam.telly.container
import io.github.tellyteam.telly.data.Channel
import io.github.tellyteam.telly.data.PlaybackRules
import io.github.tellyteam.telly.data.PlaybackSettings
import io.github.tellyteam.telly.data.Provider
import io.github.tellyteam.telly.data.net.CleartextBlockedException
import io.github.tellyteam.telly.data.store.Aspect
import io.github.tellyteam.telly.data.store.LastWatchedStore
import io.github.tellyteam.telly.data.store.SubtitlePreference
import io.github.tellyteam.telly.data.store.WatchProgressStore
import io.github.tellyteam.telly.ui.DeviceKind
import io.github.tellyteam.telly.ui.theme.TellyTheme
import java.util.Locale

/**
 * Full-screen playback with telly's own controls (see [PlayerUi]).
 *
 * Controls hidden: OK shows them; ◀ ▶ seek in films and episodes; ▲ ▼ (or channel keys) zap live
 * channels; ☰ opens Aspect. Controls shown: buttons for play/pause, next episode, audio & subtitles
 * and aspect; ▲ reaches the scrubber in films and episodes; Back hides them, then leaves.
 *
 * Films and episodes resume where they were left and save their position as they play; in an
 * episode's last minute (the credits) a small prompt offers the next one. Aspect is remembered
 * per channel, film or series.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var playerView: PlayerView
    private val ui = PlayerUiState()
    private var provider: Provider? = null
    private lateinit var items: List<Channel>
    private var live = true
    private var heading = ""
    private var seriesId: String? = null
    private var player: ExoPlayer? = null
    private var index = 0
    private val progress get() = container.watchProgress
    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0

    /** A key whose press was handled here; its release is swallowed too (so Back doesn't also exit). */
    private var swallowKeyUp: Int? = null

    /** Last known position of the current film or episode, kept for when it changes under us. */
    /** Back hid the next-episode prompt for this item: no autoplay at its end either. */
    private var promptDismissed = false
    private val playbackSettings by lazy { PlaybackSettings(this) }

    /** Time this item has played (ms), and whether Continue watching has it yet (PLAYBACK.md). */
    private var playedMs = 0L
    private var recorded = false
    private var lastPositionMs = 0L
    private var lastDurationMs = C.TIME_UNSET

    private val actions = PlayerActions(
        playPause = { player?.let { it.playWhenReady = !it.playWhenReady }; resetHide() },
        next = { playNext() },
        seekBy = { seekBy(it) },
        openPanel = { ui.panel = it; refreshTracks() },
        chooseAspect = { chooseAspect(it) },
        interacted = { resetHide() },
        tap = {
            when {
                ui.panel != Panel.NONE -> { ui.panel = Panel.NONE; resetHide() }
                ui.overlay -> ui.overlay = false
                else -> showOverlay()
            }
        },
        seekToFraction = { f -> seekToFraction(f) },
        close = { finish() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = container.playlist
        if (items.isEmpty()) {
            // The process was recreated and the in-memory playlist is gone; go back to the list.
            finish()
            return
        }
        live = intent.getBooleanExtra(EXTRA_LIVE, true)
        heading = intent.getStringExtra(EXTRA_HEADING) ?: if (live) "Live TV" else LastWatchedStore.FILM
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID)
        index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0)).coerceIn(items.indices)
        provider = runCatching { container.activeProvider()?.provider }.getOrNull()
        ui.live = live
        ui.touch = DeviceKind.isTouch(this)
        // A phone turns sideways for the picture, whichever way it's held.
        if (DeviceKind.isPhone(this)) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        playerView = PlayerView(this).apply {
            useController = false // telly draws its own controls
            isFocusable = false
            setShutterBackgroundColor(Color.BLACK)
        }
        setContent {
            TellyTheme {
                PlayerUi(ui, provider, actions) { AndroidView({ playerView }, Modifier.fillMaxSize()) }
            }
        }
        // The picture is laid out at its own shape, then scaled to the chosen aspect; redo that whenever
        // the stream's size or the screen changes.
        val reapply = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAspect() }
        contentFrame().addOnLayoutChangeListener(reapply)
        playerView.addOnLayoutChangeListener(reapply)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::items.isInitialized || items.isEmpty()) return
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(container.http)))
            .build()
        p.trackSelectionParameters = SubtitlePreference.parameters(p.trackSelectionParameters, container.subtitles.language)
        p.addListener(listener)
        playerView.player = p
        player = p
        if (live) {
            playLive(index, announce = false)
        } else {
            // The whole season is queued so episodes follow on; resume the chosen one where it was left.
            p.setMediaItems(items.map(::mediaItem), index, progress.resumePosition(items[index].streamUrl))
            p.prepare()
            p.playWhenReady = true
            onItemChanged()
        }
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        saveProgress()
        player?.release()
        player = null
        updateKeepAwake()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    // ---- Keys ----

    // Lint's RestrictedApi here is a known false alarm: it's Activity.dispatchKeyEvent we override.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            if (code == swallowKeyUp) {
                swallowKeyUp = null
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && ::playerView.isInitialized && handleKey(code, event.repeatCount)) {
            swallowKeyUp = code
            return true
        }
        if (ui.overlay) resetHide()
        return super.dispatchKeyEvent(event)
    }

    /** Returns true when the key was handled here (rather than by the on-screen controls). */
    private fun handleKey(code: Int, repeat: Int): Boolean {
        if (ui.adjustingCustom) {
            val custom = ui.aspect as? Aspect.Custom ?: Aspect.Custom()
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> setAspect(custom.copy(width = step(custom.width, -1)))
                KeyEvent.KEYCODE_DPAD_RIGHT -> setAspect(custom.copy(width = step(custom.width, 1)))
                KeyEvent.KEYCODE_DPAD_UP -> setAspect(custom.copy(height = step(custom.height, 1)))
                KeyEvent.KEYCODE_DPAD_DOWN -> setAspect(custom.copy(height = step(custom.height, -1)))
                KeyEvent.KEYCODE_BACK, in OK_KEYS -> ui.adjustingCustom = false
                else -> return false
            }
            resetHide()
            return true
        }
        if (ui.panel != Panel.NONE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                ui.panel = Panel.NONE
                resetHide()
                return true
            }
            resetHide()
            return false
        }

        val zap = if (live) {
            when (code) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> 1
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> -1
                else -> 0
            }
        } else {
            0
        }
        if (zap != 0) {
            playLive(Math.floorMod(index + zap, items.size), announce = true)
            return true
        }

        when (code) {
            KeyEvent.KEYCODE_MENU -> {
                showOverlay()
                ui.panel = Panel.ASPECT
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                actions.playPause()
                if (!live) showOverlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (!live) {
                seekBy(30_000)
                showOverlay(scrub = true)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (!live) {
                seekBy(-10_000)
                showOverlay(scrub = true)
                return true
            }
        }

        if (!ui.overlay) {
            return when (code) {
                in OK_KEYS -> {
                    if (ui.nextPromptSecs != null) playNext() else showOverlay()
                    true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (ui.nextPromptSecs == null) return false // Back leaves the player
                    // Back hides the prompt, and this item then stops at its end.
                    promptDismissed = true
                    ui.nextPromptSecs = null
                    updateAutoAdvance()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!live) {
                        val stepMs = if (repeat > 4) 30_000L else 10_000L
                        seekBy(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -stepMs else stepMs)
                        showOverlay(scrub = true)
                    } else {
                        showOverlay()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showOverlay()
                    true
                }
                else -> false // Back leaves the player
            }
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            ui.overlay = false
            return true
        }
        return false // the on-screen controls handle it
    }

    private fun showOverlay(scrub: Boolean = false) {
        ui.focusScrubber = scrub
        ui.banner = false
        ui.overlay = true
        refreshPosition()
        resetHide()
    }

    private val hideOverlay = Runnable { if (ui.panel == Panel.NONE && !ui.adjustingCustom) ui.overlay = false }
    private val hideBanner = Runnable { ui.banner = false }
    private val clearMessage = Runnable { ui.message = null }

    private fun resetHide() {
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, OVERLAY_MS)
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: return
        p.seekTo((p.currentPosition + ms).coerceIn(0, duration))
        refreshPosition()
        resetHide()
    }

    private fun seekToFraction(f: Float) {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: return
        p.seekTo((duration * f).toLong())
        refreshPosition()
        resetHide()
    }

    // ---- Playback ----

    private fun playLive(newIndex: Int, announce: Boolean) {
        val p = player ?: return
        index = newIndex
        p.setMediaItem(mediaItem(items[index]))
        p.prepare()
        p.playWhenReady = true
        onItemChanged()
        if (announce && !ui.overlay) {
            ui.banner = true
            handler.removeCallbacks(hideBanner)
            handler.postDelayed(hideBanner, BANNER_MS)
        }
    }

    /** Titles, aspect and "pick up where you left off" for the item now playing. */
    private fun onItemChanged() {
        val ch = items[index]
        ui.channel = ch
        ui.number = index + 1
        when {
            live -> {
                ui.title = playerTitle(ch, live = true)
                ui.subtitle = null
            }
            heading == LastWatchedStore.FILM -> {
                ui.title = ch.name
                ui.subtitle = "Film"
            }
            else -> {
                ui.title = heading
                ui.subtitle = "${ch.group} · ${ch.name}"
            }
        }
        ui.nextTitle = if (!live) items.getOrNull(index + 1)?.name else null
        ui.nextPromptSecs = null
        promptDismissed = false
        updateAutoAdvance()
        // Live TV counts straight away; anything else once it has played for 30 s (the tick).
        playedMs = 0
        recorded = false
        recordIfLongEnough()
        loadAspect()
    }

    /** Continue watching follows what's playing once it counts: a brief or accidental play leaves it as it was. */
    private fun recordIfLongEnough() {
        if (recorded || !PlaybackRules.recordsWatching(playedMs, live)) return
        recorded = true
        container.lastWatched.save(heading, live, items, index, seriesId)
    }

    /** Plays on into the next item only as PLAYBACK.md says; otherwise stops on this one's last frame. */
    private fun updateAutoAdvance() {
        player?.pauseAtEndOfMediaItems = !live && !PlaybackRules.advanceAtEnd(true, playbackSettings.autoNext, promptDismissed)
    }

    private fun playNext() {
        val p = player ?: return
        if (!p.hasNextMediaItem()) return
        val duration = p.duration
        if (duration != C.TIME_UNSET) progress.markFinished(items[p.currentMediaItemIndex].streamUrl, duration)
        p.seekToNextMediaItem()
    }

    private fun saveProgress() {
        if (live) return
        val p = player ?: return
        val duration = p.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        progress.save(items[p.currentMediaItemIndex].streamUrl, p.currentPosition, duration)
    }

    private fun refreshPosition() {
        val p = player ?: return
        ui.positionMs = p.currentPosition
        ui.durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
    }

    /** Twice a second: progress, the next-episode prompt; every 10 s, save the position. */
    private val tick = object : Runnable {
        override fun run() {
            player?.let { p ->
                if (p.duration != C.TIME_UNSET) {
                    lastPositionMs = p.currentPosition
                    lastDurationMs = p.duration
                }
                if (ui.overlay) refreshPosition()
                // Time actually playing: paused, buffering or seeking doesn't count.
                if (p.isPlaying) playedMs += 500
                recordIfLongEnough()
                ui.nextPromptSecs = if (p.duration == C.TIME_UNSET || p.playbackState != Player.STATE_READY) null else PlaybackRules.promptSeconds(
                    p.duration, p.currentPosition, p.hasNextMediaItem(), playbackSettings.autoNext, promptDismissed, live,
                )
            }
            if (++ticks % 20 == 0) saveProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ui.playing = player?.playWhenReady ?: isPlaying
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            ui.playing = playWhenReady
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                // Stopped at the end instead of playing on: it's watched; the controls offer what's next.
                player?.let { p -> if (p.duration != C.TIME_UNSET) progress.markFinished(items[p.currentMediaItemIndex].streamUrl, p.duration) }
                showOverlay()
            }
            updateKeepAwake()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            ui.buffering = playbackState == Player.STATE_BUFFERING
            updateKeepAwake()
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshTracks()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            if (live || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            // Record where the previous episode got to; one that ran to the end counts as watched.
            val previous = items[index].streamUrl
            if (lastDurationMs != C.TIME_UNSET) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    progress.markFinished(previous, lastDurationMs)
                } else {
                    progress.save(previous, lastPositionMs, lastDurationMs)
                }
            }
            lastDurationMs = C.TIME_UNSET
            index = p.currentMediaItemIndex
            val resume = progress.resumePosition(items[index].streamUrl)
            if (resume > 0) p.seekTo(resume)
            onItemChanged()
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // Fell behind a live HLS window (e.g. after a stall): jump back to live.
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            val blocked = generateSequence<Throwable>(error) { it.cause }.firstOrNull { it is CleartextBlockedException }
            showMessage(blocked?.message ?: "Can't play ${items[p.currentMediaItemIndex].name} (${error.errorCodeName})")
        }
    }

    /**
     * Holds the screen on while something is playing or loading, so the TV's screensaver doesn't
     * cut in mid-episode; released when paused or finished. A window flag, because a view's
     * keepScreenOn didn't reliably hold on Fire OS.
     */
    private fun updateKeepAwake() {
        val p = player
        val active = p != null && p.playWhenReady &&
            (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun showMessage(text: String) {
        ui.message = text
        handler.removeCallbacks(clearMessage)
        handler.postDelayed(clearMessage, MESSAGE_MS)
    }

    // ---- Audio & subtitles ----

    private fun refreshTracks() {
        val p = player ?: return
        fun options(type: Int) = p.currentTracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                TrackOption(trackLabel(g.getTrackFormat(i), type), selected = g.isTrackSelected(i)) { selectTrack(g, i, type) }
            }
        }
        val audio = options(C.TRACK_TYPE_AUDIO)
        ui.audio = if (audio.size > 1) audio else emptyList()
        val subs = options(C.TRACK_TYPE_TEXT)
        // "Off" can still show forced subtitles (foreign-language lines), as on other services.
        ui.subtitles = listOf(TrackOption("Off", selected = container.subtitles.language == null) { disableSubtitles() }) + subs
    }

    private fun selectTrack(group: Tracks.Group, trackIndex: Int, type: Int) {
        val p = player ?: return
        if (type == C.TRACK_TYPE_TEXT) {
            // Remembered for every film and episode from now on.
            container.subtitles.language = group.getTrackFormat(trackIndex).language?.takeIf { it.isNotBlank() } ?: SubtitlePreference.UNDETERMINED
        }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
        refreshTracks()
        resetHide()
    }

    private fun disableSubtitles() {
        val p = player ?: return
        container.subtitles.language = null
        p.trackSelectionParameters = SubtitlePreference.parameters(p.trackSelectionParameters, null)
        refreshTracks()
        resetHide()
    }

    private fun trackLabel(format: Format, type: Int): String {
        val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale(it).getDisplayLanguage(Locale.UK).ifBlank { it } }
        val parts = mutableListOf(format.label?.takeIf { it.isNotBlank() } ?: language ?: "Track")
        if (type == C.TRACK_TYPE_AUDIO) {
            when (format.channelCount) {
                1 -> parts += "Mono"
                2 -> parts += "Stereo"
                6 -> parts += "5.1"
                8 -> parts += "7.1"
            }
            when (format.sampleMimeType) {
                MimeTypes.AUDIO_AC3 -> parts += "Dolby"
                MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> parts += "Dolby+"
            }
        }
        if (language != null && parts.first() != language) parts.add(1, language)
        return parts.distinct().joinToString(" · ")
    }

    // ---- Aspect ----

    private fun contentFrame(): View = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame)

    /** Films and channels each keep their own aspect; a series shares one across its episodes. */
    private fun aspectKey(): String {
        val key = WatchProgressStore.key(items[index].streamUrl)
        return when {
            live -> "live:$key"
            heading == LastWatchedStore.FILM -> "film:$key"
            else -> "series:$heading"
        }
    }

    private fun loadAspect() {
        ui.aspect = container.aspects.get(aspectKey())
        applyAspect()
    }

    private fun setAspect(value: Aspect) {
        ui.aspect = value
        container.aspects.save(aspectKey(), value)
        applyAspect()
    }

    /** Custom: the first pick switches to it; picking it again (or right away) lets the arrows adjust it. */
    private fun chooseAspect(a: Aspect) {
        if (a is Aspect.Custom) {
            if (ui.aspect !is Aspect.Custom) setAspect(Aspect.Custom())
            ui.adjustingCustom = true
        } else {
            setAspect(a)
        }
        resetHide()
    }

    private fun applyAspect() {
        if (!::playerView.isInitialized) return
        val frame = contentFrame()
        val scale = AspectScaler.scale(
            ui.aspect, frame.width.toFloat(), frame.height.toFloat(), playerView.width.toFloat(), playerView.height.toFloat(),
        )
        frame.scaleX = scale.x
        frame.scaleY = scale.y
    }

    private fun step(value: Float, direction: Int): Float =
        (Math.round((value + direction * ASPECT_STEP) * 100) / 100f).coerceIn(ASPECT_MIN, ASPECT_MAX)

    companion object {
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_HEADING = "heading"
        private const val EXTRA_SERIES_ID = "seriesId"
        private const val ASPECT_STEP = 0.02f
        private const val ASPECT_MIN = 0.5f
        private const val ASPECT_MAX = 2f
        private const val OVERLAY_MS = 5_000L
        private const val BANNER_MS = 4_000L
        private const val MESSAGE_MS = 4_000L
        private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

        /** [heading] labels it under "pick up where you left off": "Live TV", "Film" or the series name. */
        fun intent(context: Context, index: Int, live: Boolean, heading: String, seriesId: String? = null) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_LIVE, live)
                .putExtra(EXTRA_HEADING, heading)
                .putExtra(EXTRA_SERIES_ID, seriesId)

        private fun mediaItem(channel: Channel): MediaItem = MediaItem.Builder()
            .setUri(channel.streamUrl)
            .setMimeType(mimeTypeFor(channel.streamUrl))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
            .build()

        /** M3U entries often have no file extension, so give ExoPlayer a hint for HLS. */
        internal fun mimeTypeFor(url: String): String? =
            if (".m3u8" in url.substringBefore('?').lowercase()) MimeTypes.APPLICATION_M3U8 else null
    }
}
