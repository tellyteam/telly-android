package io.github.tellyteam.telly.ui.components

import io.github.tellyteam.telly.ui.theme.FOCUS_MS
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.tellyteam.telly.ui.live.ChannelNames
import io.github.tellyteam.telly.ui.theme.Telly
import io.github.tellyteam.telly.ui.theme.focusCard

/** The card that last had focus, so closing a detail page can put focus back on it. */
class FocusMemory {
    var last: FocusRequester? = null
}

val LocalFocusMemory = staticCompositionLocalOf { FocusMemory() }

/** The watched titles' keys (`film:<id>`, `series:<id>`), for ✓ Watched on posters (PLAYBACK.md, Watched titles). */
val LocalWatched = compositionLocalOf { emptySet<String>() }

/** Records this card as the one to return to. */
fun Modifier.rememberFocus(): Modifier = composed {
    val requester = remember { FocusRequester() }
    val memory = LocalFocusMemory.current
    focusRequester(requester).onFocusChanged { if (it.isFocused) memory.last = requester }
}

/** Side margin shared by rows and the billboard, so everything lines up. */
val PageInset = 28.dp

/**
 * Right never leaves a row: at its end nothing happens, rather than focus jumping to whichever
 * card in another row happens to be nearest (a lazy row's next card may not exist yet). Left
 * still leaves, so the first card reaches the menu.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.keepRightInRow(): Modifier = this
    .focusProperties { exit = { if (it == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default } }
    .focusGroup()

/**
 * How rows scroll as focus moves: keep about a card's width of room ahead, and glide with the same
 * timing as the card's focus animation. (The default only scrolled once a card reached the very
 * edge, by uneven nudges with a springy animation that overlapped the card growing, which read as
 * a shake.)
 */
@OptIn(ExperimentalFoundationApi::class)
internal class RowScrollSpec(private val leadingPx: Float) : BringIntoViewSpec {
    @Deprecated("Kept for older Compose versions")
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(FOCUS_MS, easing = FastOutSlowInEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val roomAhead = containerSize * 0.22f
        return when {
            offset < leadingPx -> offset - leadingPx
            offset + size > containerSize - roomAhead -> offset + size - (containerSize - roomAhead)
            else -> 0f
        }
    }
}

/** Gives the rows inside it smooth, evenly paced scrolling (see [RowScrollSpec]). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmoothRowScrolling(content: @Composable () -> Unit) {
    val leading = with(LocalDensity.current) { PageInset.toPx() }
    val spec = remember(leading) { RowScrollSpec(leading) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** A titled, horizontally scrolling row. Only the cards on screen are composed. */
@Composable
fun MediaRow(title: String, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Telly.Text,
            modifier = Modifier.padding(start = PageInset, bottom = 2.dp),
        )
        SmoothRowScrolling {
            LazyRow(
                modifier = Modifier.keepRightInRow(),
                contentPadding = PaddingValues(horizontal = PageInset, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

/** Click plays/opens; the remote's Menu button or a long press runs [onFavourite] (usually: toggle My List). */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.mediaClick(onClick: () -> Unit, onFavourite: (() -> Unit)?): Modifier = this
    .onPreviewKeyEvent { event ->
        if (onFavourite != null && event.key == Key.Menu) {
            if (event.type == KeyEventType.KeyDown) onFavourite()
            true
        } else {
            false
        }
    }
    .combinedClickable(onClick = onClick, onLongClick = onFavourite)

@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    onFavourite: (() -> Unit)? = null,
    favourite: Boolean = false,
    progress: Float? = null,
    /** Labelled ✓ Watched, with no progress bar. */
    watched: Boolean = false,
    /** In My List a watched poster is dimmed a little; the label and title stay at full strength. */
    dimmed: Boolean = false,
) {
    Column(Modifier.width(118.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(177.dp)
                .rememberFocus()
                .focusCard()
                .clip(Telly.CardShape)
                .background(Telly.SurfaceHigh)
                .mediaClick(onClick, onFavourite),
        ) {
            val fade = if (watched && dimmed) 0.5f else 1f
            if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(fade))
            } else {
                Text(title, fontSize = 13.sp, color = Telly.Text, modifier = Modifier.padding(10.dp).alpha(fade))
            }
            if (favourite) Star(Modifier.align(Alignment.TopEnd))
            if (watched) WatchedBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
            else if (progress != null) CardProgress(progress, Modifier.align(Alignment.BottomCenter))
        }
        Text(
            title,
            fontSize = 12.sp,
            color = Telly.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp),
        )
    }
}

@Composable
fun LandscapeCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    badge: String? = null,
    progress: Float? = null,
    /** The remote's Menu button, or holding OK. */
    onMenu: (() -> Unit)? = null,
) {
    Column(Modifier.width(208.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(117.dp)
                .rememberFocus()
                .focusCard()
                .clip(Telly.CardShape)
                .background(Telly.SurfaceHigh)
                .mediaClick(onClick, onMenu),
        ) {
            if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            // Keeps the badge and title readable over any artwork.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC0A0E14))))
            if (badge != null) {
                Text(
                    badge,
                    fontSize = 10.sp,
                    color = Telly.Text,
                    modifier = Modifier.padding(8.dp).background(Color(0xB30A0E14), Telly.SmallShape).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = if (progress != null) 14.dp else 9.dp),
            )
            if (progress != null) CardProgress(progress, Modifier.align(Alignment.BottomCenter))
        }
        // Always takes its line, so every card in a row is the same height.
        Text(
            subtitle ?: " ",
            fontSize = 12.sp,
            color = Telly.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp),
        )
    }
}

/** A channel logo on a pale tile; logos are designed for light backgrounds. */
@Composable
fun ChannelTile(rawName: String, logoUrl: String?, onClick: () -> Unit, onFavourite: (() -> Unit)? = null) {
    val name = ChannelNames.channel(rawName)
    Column(Modifier.width(150.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .rememberFocus()
                .focusCard()
                .clip(Telly.CardShape)
                .background(Telly.LogoTile)
                .mediaClick(onClick, onFavourite),
            contentAlignment = Alignment.Center,
        ) {
            if (logoUrl != null) {
                AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize().padding(14.dp))
            } else {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B2433), maxLines = 2, modifier = Modifier.padding(8.dp))
            }
        }
        Text(name, fontSize = 12.sp, color = Telly.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
    }
}

@Composable
private fun CardProgress(fraction: Float, modifier: Modifier) {
    LinearProgressIndicator(
        progress = { fraction },
        color = Telly.Accent,
        trackColor = Color.White.copy(alpha = 0.2f),
        drawStopIndicator = {},
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp).height(3.dp),
    )
}

/** "✓ Watched", on a poster or beside a page's title. */
@Composable
fun WatchedBadge(modifier: Modifier = Modifier) {
    Text(
        "✓ Watched",
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = modifier.background(Color(0xB30A0E14), Telly.SmallShape).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun Star(modifier: Modifier) {
    Text(
        "★",
        color = Telly.Accent,
        fontSize = 13.sp,
        modifier = modifier.padding(6.dp).background(Color(0xB30A0E14), Telly.SmallShape).padding(horizontal = 5.dp),
    )
}

/** A small square used where a row would otherwise be empty. */
@Composable
fun EmptyHint(text: String) {
    Box(Modifier.size(width = 260.dp, height = 60.dp).padding(start = PageInset), contentAlignment = Alignment.CenterStart) {
        Text(text, fontSize = 13.sp, color = Telly.Faint)
    }
}

/**
 * Artwork that melts into the page on its left and bottom. True alpha fades (not colour
 * overlays), so it blends with the gradient background without an edge. One offscreen layer.
 */
@Composable
fun FadedBackdrop(url: String?, modifier: Modifier) {
    if (url == null) return
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.2f to Color.Black.copy(alpha = 0.25f), 0.62f to Color.Black), blendMode = BlendMode.DstIn)
                drawRect(Brush.verticalGradient(0.4f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
    ) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** The last card in a category row: opens the full grid. */
@Composable
fun SeeAllCard(count: Int, onClick: () -> Unit) {
    Column(Modifier.width(118.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(177.dp)
                .rememberFocus()
                .focusCard()
                .clip(Telly.CardShape)
                .background(Color.White.copy(alpha = 0.05f))
                .mediaClick(onClick, null),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("See all ›", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Telly.Text)
                Text("$count", fontSize = 12.sp, color = Telly.Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
