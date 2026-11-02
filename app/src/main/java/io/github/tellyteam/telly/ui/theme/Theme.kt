package io.github.tellyteam.telly.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import io.github.tellyteam.telly.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * telly's design tokens: blue-slate, lightly rounded, a soft blue focus glow. Agreed in the
 * browser mockup (mockup/index.html); keep new screens on these rather than ad-hoc values.
 */
object Telly {
    val Background = Color(0xFF151A22)
    val BackgroundHigh = Color(0xFF1F2838)
    val BackgroundLow = Color(0xFF12161D)
    val Surface = Color(0xFF1E2531)
    val SurfaceHigh = Color(0xFF273041)
    val Line = Color(0xFF2F3A4C)
    val Text = Color(0xFFE9EDF2)
    val Muted = Color(0xFF98A4B4)
    val Faint = Color(0xFF6C788A)
    val Accent = Color(0xFF4FC3F7)
    val AccentInk = Color(0xFF062330)
    val AccentSoft = Color(0x294FC3F7) // ~16% accent, for selected/focused fills
    val LogoTile = Color(0xFFEEF2F6) // channel logos read better on a pale tile
    val Warning = Color(0xFFFFB74D) // what an unencrypted connection exposes

    val CardShape = RoundedCornerShape(10.dp)
    val ControlShape = RoundedCornerShape(12.dp)
    val RowShape = RoundedCornerShape(8.dp)
    val SmallShape = RoundedCornerShape(6.dp)

    /** The rail's collapsed width; screens start to its right. */
    val RailWidth = 64.dp

    /** Soft light from the top right, fading to the base slate: drawn once, costs nothing per frame. */
    val BackgroundBrush = Brush.radialGradient(
        0f to BackgroundHigh,
        0.55f to Background,
        1f to BackgroundLow,
        radius = 2200f,
        center = androidx.compose.ui.geometry.Offset(1300f, -200f),
    )
}

private val colors = darkColorScheme(
    primary = Telly.Accent,
    onPrimary = Telly.AccentInk,
    background = Telly.Background,
    onBackground = Telly.Text,
    surface = Telly.Surface,
    onSurface = Telly.Text,
    surfaceVariant = Telly.SurfaceHigh,
    onSurfaceVariant = Telly.Muted,
    outline = Telly.Line,
)

private val shapes = Shapes(
    extraSmall = Telly.SmallShape,
    small = Telly.RowShape,
    medium = Telly.CardShape,
    large = Telly.ControlShape,
    extraLarge = Telly.ControlShape,
)

@Composable
fun TellyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}

/**
 * The telly wordmark: one drawing shared by every app and icon (telly-wordmark.svg in telly-brand), sized
 * like text of [fontSize] so callers keep their sizes.
 */
@Composable
fun TellyWordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 28.sp) {
    Image(
        painter = painterResource(R.drawable.telly_wordmark),
        contentDescription = "telly",
        modifier = modifier.height((fontSize.value * 0.96f).dp),
    )
}

/** The app background: soft slate gradient behind every screen. */
fun Modifier.tellyBackground(): Modifier = background(Telly.BackgroundBrush)

/** A visible ring around whatever has D-pad focus; essential on a TV. */
fun Modifier.focusRing(shape: Shape = Telly.RowShape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) Telly.Accent else Color.Transparent, shape)
}

/** How long focus effects and row scrolling take; shared so they move as one. */
const val FOCUS_MS = 200

/**
 * Card focus: a blue ring and soft glow, and full brightness (cards rest a little dimmed). Cards
 * never change size, so a row keeps exactly the same height and shape as focus moves along it;
 * scaling the focused card made rows appear to swell and settle on every move. All effects follow
 * one animated value, with the same timing as row scrolling.
 */
fun Modifier.focusCard(shape: Shape = Telly.CardShape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val t by animateFloatAsState(if (focused) 1f else 0f, tween(FOCUS_MS, easing = FastOutSlowInEasing), label = "cardFocus")
    this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            alpha = 0.82f + 0.18f * t
            shadowElevation = 14.dp.toPx() * t
            this.shape = shape
            clip = false
            ambientShadowColor = Telly.Accent
            spotShadowColor = Telly.Accent
        }
        .border(2.dp, Telly.Accent.copy(alpha = t), shape)
}
