package io.github.tellyteam.telly.ui.setup

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.tellyteam.telly.BuildConfig
import io.github.tellyteam.telly.data.sync.SyncJoin
import kotlinx.coroutines.delay

/** One code is kept for 9 minutes, inside the relay's 10 for a box: typing on a phone takes a while. */
private const val JOIN_LIFE_MS = 9 * 60_000L

/**
 * A join QR code that's asked about every 3 seconds while [active] (and the screen showing it is
 * up). What arrives goes to [onMessage], and the next code is made; an error goes to [onError], and
 * a new code is made too. Debug builds log the link as [label], so the phone's side can be played
 * from a computer.
 */
@Composable
internal fun rememberJoinCode(
    label: String,
    active: Boolean,
    collect: suspend (SyncJoin) -> SyncJoin.Message?,
    onMessage: (SyncJoin.Message) -> Unit,
    onError: (String) -> Unit,
): SyncJoin {
    var join by remember { mutableStateOf(SyncJoin.new()) }
    val made = remember(join) { SystemClock.elapsedRealtime() }
    val message by rememberUpdatedState(onMessage)
    val error by rememberUpdatedState(onError)
    LaunchedEffect(join, active) {
        if (!active) return@LaunchedEffect
        if (BuildConfig.DEBUG) android.util.Log.d("telly", "$label QR: ${join.url}")
        while (SystemClock.elapsedRealtime() - made < JOIN_LIFE_MS) {
            delay(3_000)
            val found = try {
                collect(join)
            } catch (e: Exception) {
                error(e.message ?: "Couldn't reach the sync relay.")
                join = SyncJoin.new()
                return@LaunchedEffect
            }
            if (found != null) {
                join = SyncJoin.new()
                message(found)
                return@LaunchedEffect
            }
        }
        join = SyncJoin.new()
    }
    return join
}
