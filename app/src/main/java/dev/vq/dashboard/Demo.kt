package dev.vq.dashboard

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Layout demo: `adb shell am start -n dev.vq.dashboard/.MainActivity --ez demo true` cycles through
 * every combination (music playing/paused/none × billable normal/huge/none), 10 s each, with fake data.
 */
object Demo {
    data class Case(val label: String, val music: String, val billable: String)

    val cases = listOf(
        Case("1/7 · normale, niente musica", "none", "real"),
        Case("2/7 · musica in pausa → controlli", "paused", "real"),
        Case("3/7 · musica in riproduzione", "playing", "real"),
        Case("4/7 · 300:59 nel mese, timer attivo", "none", "huge"),
        Case("5/7 · 300:59 + musica", "playing", "huge"),
        Case("6/7 · senza Billable", "none", "none"),
        Case("7/7 · senza Billable + musica", "playing", "none"),
    )
    val current = MutableStateFlow<Case?>(null)
    private var job: Job? = null

    fun start(scope: CoroutineScope, seconds: Long = 10) {
        job?.cancel()
        job = scope.launch {
            for (c in cases) {
                current.value = c
                delay(seconds * 1000)
            }
            current.value = null
        }
    }

    fun track(playing: Boolean) = NowPlaying(
        title = "For the Greater Good", appName = "Spotify", artist = "Organ Freeman", album = "Organ Freeman",
        art = null, playing = playing, durationMs = 357_000, positionMs = 94_000,
        positionAtElapsed = SystemClock.elapsedRealtime(), speed = 1f,
        pausedAtMs = if (playing) 0 else 1, // paused long ago → full player hidden, mini controls shown
    )

    val huge = Billable.Totals(
        todaySec = 12 * 3600 + 34 * 60, todayAmount = 987.65, monthSec = 300 * 3600 + 59 * 60, monthAmount = 12345.67,
        running = "Demo", hasRates = true, currency = "€",
    )
}

@Composable
fun DemoLabel(c: Demo.Case, modifier: Modifier) {
    Text(
        "DEMO ${c.label}",
        style = textStyle(13, Color.Black, 700),
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFD54F)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
