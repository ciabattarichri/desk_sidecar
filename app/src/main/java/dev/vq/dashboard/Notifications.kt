package dev.vq.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Recent PC notifications (DMS history), pushed by phonedash with each heartbeat. */
@Composable
fun NotificationsPage(modifier: Modifier) {
    val p = LocalPalette.current
    val items by Remote.notifications.collectAsState()
    if (items.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(tr("Nessuna notifica dal PC", "No notifications from the PC"), style = textStyle(15, p.onSurfaceVariant, 400))
        }
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { n ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(p.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(p.primary))
                    Spacer(Modifier.size(8.dp))
                    Text(n.app, style = textStyle(13, p.primary, 600), maxLines = 1, modifier = Modifier.weight(1f))
                    Text(ago(n.timeMs), style = textStyle(12, p.onSurfaceVariant, 400))
                }
                if (n.summary.isNotBlank()) {
                    Text(n.summary, style = textStyle(15, p.onSurface, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (n.body.isNotBlank()) {
                    Text(n.body, style = textStyle(14, p.onSurfaceVariant, 400), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun ago(ms: Long): String {
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> tr("ora", "now")
        min < 60 -> tr("$min min fa", "$min min ago")
        min < 24 * 60 -> tr("${min / 60} h fa", "${min / 60} h ago")
        else -> tr("${min / (24 * 60)} g fa", "${min / (24 * 60)} d ago")
    }
}
