package io.github.tellyteam.telly.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tellyteam.telly.data.Channel
import io.github.tellyteam.telly.data.Provider
import io.github.tellyteam.telly.data.store.Aspect
import io.github.tellyteam.telly.ui.live.ChannelNames
import io.github.tellyteam.telly.ui.live.clock
import io.github.tellyteam.telly.ui.live.rememberNowNext
import io.github.tellyteam.telly.ui.theme.Telly

enum class Panel { NONE, TRACKS, ASPECT }

/** One audio or subtitle choice; [select] applies it. */
class TrackOption(val label: String, val selected: Boolean, val select: () -> Unit)

/** Everything the overlay shows; the activity owns playback and writes these. */
class PlayerUiState {
    var overlay by mutableStateOf(false)
    var focusScrubber by mutableStateOf(false)
    var panel by mutableStateOf(Panel.NONE)
    var playing by mutableStateOf(true)
    var buffering by mutableStateOf(true)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var live by mutableStateOf(true)
    var channel by mutableStateOf<Channel?>(null)
    var number by mutableIntStateOf(0)
    var title by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var nextTitle by mutableStateOf<String?>(null)
    var nextPromptSecs by mutableStateOf<Int?>(null)
    var aspect by mutableStateOf<Aspect>(Aspect.Original)
    var adjustingCustom by mutableStateOf(false)
    var audio by mutableStateOf(emptyList<TrackOption>())
    var subtitles by mutableStateOf(emptyList<TrackOption>())
    var banner by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    /** A phone or tablet: the controls also answer to touch, and there's a close button. */
    var touch by mutableStateOf(false)
}

class PlayerActions(
    val playPause: () -> Unit,
    val next: () -> Unit,
    val seekBy: (Long) -> Unit,
    val openPanel: (Panel) -> Unit,
    val chooseAspect: (Aspect) -> Unit,
    val interacted: () -> Unit,
    /** A tap on the picture: shows the controls, or hides them (and any panel). */
    val tap: () -> Unit = {},
    /** Jumps to a point in a film or episode, 0..1 along it. */
    val seekToFraction: (Float) -> Unit = {},
    val close: () -> Unit = {},
)

@Composable
fun PlayerUi(state: PlayerUiState, provider: Provider?, actions: PlayerActions, video: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        video()
        // Touch: a tap shows or hides the controls; a double tap on either half skips 10 seconds.
        // Under the controls, so their buttons take their own taps.
        if (state.touch) {
            Box(
                Modifier.fillMaxSize().pointerInput(state.live) {
                    detectTapGestures(
                        onTap = { actions.tap() },
                        onDoubleTap = { at -> if (!state.live) actions.seekBy(if (at.x < size.width / 2) -10_000L else 10_000L) },
                    )
                },
            )
        }

        if (state.buffering && !state.overlay) {
            CircularProgressIndicator(color = Telly.Accent, modifier = Modifier.align(Alignment.Center))
        }

        // The small credits prompt (overlay hidden); the overlay has its own Next button.
        val secs = state.nextPromptSecs
        if (secs != null && !state.overlay && state.panel == Panel.NONE) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .graphicsLayer { alpha = 0.9f }
                    .clip(Telly.RowShape)
                    .background(Color(0x99101418))
                    .border(1.dp, Telly.Accent, Telly.RowShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .width(220.dp),
            ) {
                Text("▶ Next in ${secs}s  ·  OK", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                state.nextTitle?.let { Text(it, fontSize = 10.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }

        // Live zapping: a small channel banner instead of the full controls.
        AnimatedVisibility(state.banner && !state.overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
            ChannelBanner(state, provider)
        }

        AnimatedVisibility(state.overlay, enter = fadeIn(), exit = fadeOut()) {
            Overlay(state, provider, actions)
        }

        AnimatedVisibility(
            state.panel != Panel.NONE,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            SidePanel(state, actions)
        }

        state.message?.let {
            Text(
                it,
                fontSize = 14.sp,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clip(Telly.ControlShape)
                    .background(Color(0xCC101418)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Overlay(state: PlayerUiState, provider: Provider?, actions: PlayerActions) {
    val playFocus = remember { FocusRequester() }
    val scrubFocus = remember { FocusRequester() }
    val nowNext = if (state.live) rememberNowNext(state.channel, provider) else null

    Box(Modifier.fillMaxSize()) {
        // Top: what's playing.
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color(0xCC070A0F), 1f to Color.Transparent))
                .padding(start = 40.dp, top = 28.dp, end = 40.dp, bottom = 50.dp),
        ) {
            if (state.touch) {
                Text(
                    "✕",
                    fontSize = 22.sp,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 10.dp).clip(Telly.ControlShape).clickable(onClick = actions.close)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                if (state.live && state.number > 0) "${state.number}  ${state.title}" else state.title,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = if (state.live) nowNext?.now?.title?.let { "Now: $it" } else state.subtitle
            sub?.let { Text(it, fontSize = 15.sp, color = Telly.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
        }

        // Bottom: progress and controls.
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xE6070A0F)))
                .padding(start = 40.dp, end = 40.dp, top = 60.dp, bottom = 30.dp),
        ) {
            if (state.live) {
                val now = nowNext?.now
                if (now != null) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Text("${clock(now.startMs)}–${clock(now.endMs)}", fontSize = 13.sp, color = Telly.Muted)
                        Spacer(Modifier.weight(1f))
                        nowNext.next?.let { Text("Next ${clock(it.startMs)}  ${it.title}", fontSize = 13.sp, color = Telly.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Bar(now.progress(System.currentTimeMillis()), focused = false)
                }
            } else {
                var scrubFocused by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(scrubFocus)
                        .onFocusChanged { scrubFocused = it.isFocused }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            // Holding the button speeds up: 10 s steps, then 30 s.
                            val step = if (e.nativeKeyEvent.repeatCount > 4) 30_000L else 10_000L
                            when (e.key) {
                                Key.DirectionLeft -> { actions.seekBy(-step); true }
                                Key.DirectionRight -> { actions.seekBy(step); true }
                                // Always Play/Pause, rather than whichever button sits below the current position.
                                Key.DirectionDown -> { runCatching { playFocus.requestFocus() }; true }
                                else -> false
                            }
                        }
                        // Touch: tap or drag along the bar to move there. (OK on a remote still plays or pauses.)
                        .pointerInput(Unit) {
                            detectTapGestures { at -> actions.seekToFraction((at.x / size.width).coerceIn(0f, 1f)) }
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ -> actions.seekToFraction((change.position.x / size.width).coerceIn(0f, 1f)) }
                        }
                        .clickable { actions.playPause() }
                        .padding(vertical = 8.dp),
                ) {
                    Bar(if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f, focused = scrubFocused)
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(time(state.positionMs), fontSize = 13.sp, color = Telly.Muted)
                    Spacer(Modifier.weight(1f))
                    Text(if (state.durationMs > 0) "−${time(state.durationMs - state.positionMs)}" else "", fontSize = 13.sp, color = Telly.Muted)
                }
            }

            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ControlButton(if (state.playing) "❚❚  Pause" else "▶  Play", Modifier.focusRequester(playFocus), actions.playPause)
                if (state.nextTitle != null) ControlButton("⏭  Next episode", Modifier, actions.next)
                ControlButton("Audio & subtitles", Modifier) { actions.openPanel(Panel.TRACKS) }
                ControlButton("Aspect · ${state.aspect.label.substringBefore(" (")}", Modifier) { actions.openPanel(Panel.ASPECT) }
            }
        }
    }
    LaunchedEffect(state.focusScrubber) {
        runCatching { if (state.focusScrubber && !state.live) scrubFocus.requestFocus() else playFocus.requestFocus() }
    }
}

@Composable
private fun ChannelBanner(state: PlayerUiState, provider: Provider?) {
    val nowNext = rememberNowNext(state.channel, provider)
    Column(
        Modifier.padding(28.dp).width(420.dp).clip(Telly.ControlShape).background(Color(0xD9101418)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${state.number}  ${state.title}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        nowNext?.now?.let { now ->
            Text("Now  ${clock(now.startMs)}  ${now.title}", fontSize = 13.sp, color = Telly.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Bar(now.progress(System.currentTimeMillis()), focused = false)
        }
        nowNext?.next?.let { Text("Next  ${clock(it.startMs)}  ${it.title}", fontSize = 12.sp, color = Telly.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun SidePanel(state: PlayerUiState, actions: PlayerActions) {
    val first = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxHeight()
            .padding(20.dp)
            .width(300.dp)
            .clip(Telly.ControlShape)
            .background(Color(0xE6141A24))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (state.panel) {
            Panel.TRACKS -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { PanelTitle("Audio") }
                if (state.audio.isEmpty()) item { Text("Only one audio track", fontSize = 13.sp, color = Telly.Faint) }
                items(state.audio.size) { i ->
                    val o = state.audio[i]
                    Option(o.label, o.selected, if (i == 0) Modifier.focusRequester(first) else Modifier, o.select)
                }
                item { PanelTitle("Subtitles", Modifier.padding(top = 14.dp)) }
                items(state.subtitles.size) { i ->
                    val o = state.subtitles[i]
                    Option(o.label, o.selected, if (state.audio.isEmpty() && i == 0) Modifier.focusRequester(first) else Modifier, o.select)
                }
            }
            Panel.ASPECT -> {
                PanelTitle("Aspect ratio")
                Aspect.cycle.forEachIndexed { i, a ->
                    Option(
                        a.label,
                        selected = a::class == state.aspect::class,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    ) { actions.chooseAspect(a) }
                }
                Spacer(Modifier.weight(1f))
                val a = state.aspect
                if (a is Aspect.Custom) {
                    Text(
                        "Width ${(a.width * 100).toInt()}%  ·  Height ${(a.height * 100).toInt()}%",
                        fontSize = 13.sp,
                        color = if (state.adjustingCustom) Telly.Accent else Telly.Muted,
                    )
                    Text(
                        if (state.adjustingCustom) "◀ ▶ width   ▲ ▼ height   OK when done" else "Select Custom again to adjust",
                        fontSize = 12.sp,
                        color = Telly.Faint,
                    )
                }
                Text("Remembered for this ${if (state.live) "channel" else "title"}", fontSize = 12.sp, color = Telly.Faint)
            }
            Panel.NONE -> Unit
        }
    }
    LaunchedEffect(state.panel) { runCatching { first.requestFocus() } }
}

@Composable
private fun PanelTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = modifier.padding(bottom = 6.dp))
}

@Composable
private fun Option(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Telly.Accent else Color.Transparent, Telly.RowShape)
            .clip(Telly.RowShape)
            .background(if (focused) Telly.AccentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = if (focused) Color.White else Color(0xFFD3DAE3), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (selected) Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Telly.Accent)
    }
}

@Composable
private fun ControlButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                val s = if (focused) 1.05f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(Telly.ControlShape)
            .background(if (focused) Color(0xFFF2F5F8) else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Color(0xFF111722) else Color.White)
    }
}

@Composable
private fun Bar(fraction: Float, focused: Boolean) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        color = Telly.Accent,
        trackColor = Color.White.copy(alpha = 0.2f),
        drawStopIndicator = {},
        modifier = Modifier.fillMaxWidth().height(if (focused) 6.dp else 4.dp),
    )
}

private fun time(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** For display: tidy live channel names the same way as the Live TV list. */
fun playerTitle(channel: Channel, live: Boolean): String = if (live) ChannelNames.channel(channel.name) else channel.name
