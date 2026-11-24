package dev.vq.dashboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

// ---- Ambient light ------------------------------------------------------------------------------

/** Ambient light sensor, bucketed so brightness only changes on meaningful steps. */
object Ambient : SensorEventListener {
    /** Brightness multiplier for the current room light. */
    val factor = MutableStateFlow(1f)
    private var registered = false

    fun start(context: Context) {
        if (registered) return
        val sm = context.getSystemService(SensorManager::class.java)
        val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        registered = sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop(context: Context) {
        if (!registered) return
        context.getSystemService(SensorManager::class.java).unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val lux = e.values[0]
        factor.value = when {
            lux < 2f -> 0.35f   // dark room
            lux < 15f -> 0.6f
            lux < 120f -> 0.85f
            lux < 500f -> 1f
            else -> 1.4f        // sunlight on the desk
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// ---- PC stats tab -------------------------------------------------------------------------------

@Composable
fun PcPage(modifier: Modifier) {
    val p = LocalPalette.current
    val st by Remote.pcStats.collectAsState()
    val s = st
    if (s == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(tr("Nessun dato dal PC", "No data from the PC"), style = textStyle(15, p.onSurfaceVariant, 400))
        }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Meter(Sym.SPEED, "CPU", s.cpu?.let { "$it%" } ?: "–", (s.cpu ?: 0) / 100f, s.load?.let { tr("carico $it", "load $it") })
        Meter(
            Sym.MEMORY, "RAM", s.ram?.let { "$it%" } ?: "–", (s.ram ?: 0) / 100f,
            if (s.ramUsed != null && s.ramTotal != null) "%.1f / %.1f GB".format(s.ramUsed, s.ramTotal) else null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TempTile("CPU", s.cpuTemp, Modifier.weight(1f))
            TempTile(tr("Disco", "Disk"), s.diskTemp, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Meter(icon: Char, label: String, value: String, fraction: Float, detail: String?) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MIcon(icon, 20.dp, p.primary)
            Text(label, style = textStyle(14, p.onSurfaceVariant, 600))
            Spacer(Modifier.weight(1f))
            detail?.let { Text(it, style = textStyle(12, p.onSurfaceVariant, 400)) }
            Text(value, style = textStyle(18, p.onSurface, 700))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(p.onSurface.copy(alpha = 0.1f))) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape)
                    .background(if (fraction > 0.85f) p.error else p.primary)
            )
        }
    }
}

@Composable
private fun TempTile(label: String, temp: Int?, modifier: Modifier) {
    val p = LocalPalette.current
    val hot = (temp ?: 0) >= 80
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(p.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MIcon(Sym.THERMOMETER, 20.dp, if (hot) p.error else p.primary)
        Text(label, style = textStyle(14, p.onSurfaceVariant, 600))
        Spacer(Modifier.weight(1f))
        Text(temp?.let { "$it°" } ?: "–", style = textStyle(18, if (hot) p.error else p.onSurface, 700))
    }
}

// ---- Post-it ------------------------------------------------------------------------------------

/** Notes written from the PC plugin, stuck on the dashboard like post-its. Tap to fold/unfold. */
@Composable
fun PostIts(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val notes by Remote.notes.collectAsState()
    if (notes.isEmpty()) return
    var folded by remember { mutableStateOf(false) }
    Column(modifier.clickable { folded = !folded }, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy((-6).dp)) {
        if (folded) {
            Row(
                Modifier.rotate(-3f).clip(RoundedCornerShape(12.dp)).background(p.tertiaryContainer.copy(alpha = 0.92f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MIcon(Sym.STICKY, 18.dp, p.onTertiaryContainer, filled = true)
                Text("${notes.size}", style = textStyle(14, p.onTertiaryContainer, 700))
            }
        } else {
            notes.forEachIndexed { i, n ->
                Text(
                    n,
                    style = textStyle(15, p.onTertiaryContainer, 600),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .rotate(if (i % 2 == 0) -2.5f else 2f)
                        .widthIn(max = 200.dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 4.dp))
                        .background(p.tertiaryContainer.copy(alpha = 0.94f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}
