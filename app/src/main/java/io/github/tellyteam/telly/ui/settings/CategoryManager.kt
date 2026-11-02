package io.github.tellyteam.telly.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tellyteam.telly.data.store.CategoryKind
import io.github.tellyteam.telly.data.store.CategoryPrefs
import io.github.tellyteam.telly.ui.DeviceKind
import io.github.tellyteam.telly.ui.components.PageInset
import io.github.tellyteam.telly.ui.components.mediaClick
import io.github.tellyteam.telly.ui.home.PillButton
import io.github.tellyteam.telly.ui.live.ChannelNames
import io.github.tellyteam.telly.ui.theme.Telly

/**
 * Pinning and hiding one list's categories. Rows stay in the provider's order while you work
 * (so nothing moves under the remote); the lists themselves show pinned ones first.
 */
@Composable
fun CategoryManager(
    kind: CategoryKind,
    categories: List<Pair<String, Int>>,
    prefs: CategoryPrefs,
    onTogglePinned: (String) -> Unit,
    onToggleHidden: (String) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    BackHandler(onBack = onDone)
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(start = PageInset, top = 22.dp, end = 40.dp)) {
        Text("${kind.label} categories", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Telly.Text)
        Text(
            if (DeviceKind.isTouch(LocalContext.current)) "Tap shows or hides a category. Long-press pins it to the top; pinned categories appear in the order you pin them."
            else "OK shows or hides a category.  ☰ (or hold OK) pins it to the top; pinned categories appear in the order you pin them.",
            fontSize = 13.sp,
            color = Telly.Muted,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 640.dp),
        )
        FlowRow(Modifier.padding(top = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Done", primary = true, onClick = onDone)
            if (prefs.pinned.isNotEmpty() || prefs.hidden.isNotEmpty()) {
                PillButton("Reset to the provider's order", primary = false, onClick = onReset)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
            itemsIndexed(categories, key = { _, c -> c.first }) { i, (name, count) ->
                CategoryRow(
                    name = if (kind == CategoryKind.LIVE) ChannelNames.category(name) else name,
                    count = count,
                    pinnedAt = prefs.pinned.indexOf(name).takeIf { it >= 0 }?.plus(1),
                    hidden = prefs.isHidden(name),
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onToggleHidden = { onToggleHidden(name) },
                    onTogglePinned = { onTogglePinned(name) },
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun CategoryRow(
    name: String,
    count: Int,
    pinnedAt: Int?,
    hidden: Boolean,
    modifier: Modifier,
    onToggleHidden: () -> Unit,
    onTogglePinned: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .widthIn(max = 640.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Telly.Accent else Color.Transparent, Telly.RowShape)
            .clip(Telly.RowShape)
            .background(if (focused) Telly.AccentSoft else Color.Transparent)
            .mediaClick(onToggleHidden, onTogglePinned)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            name,
            fontSize = 15.sp,
            color = when {
                hidden -> Telly.Faint
                focused -> Color.White
                else -> Telly.Text
            },
            textDecoration = if (hidden) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$count", fontSize = 12.sp, color = Telly.Faint)
        Text(
            when {
                hidden -> "Hidden"
                pinnedAt != null -> "Pinned $pinnedAt"
                else -> "Shown"
            },
            fontSize = 12.sp,
            fontWeight = if (pinnedAt != null) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                hidden -> Telly.Faint
                pinnedAt != null -> Telly.Accent
                else -> Telly.Muted
            },
            modifier = Modifier.width(70.dp),
        )
    }
}
