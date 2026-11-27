package dev.vq.dashboard

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

/** Every tab the dashboard knows; order and visibility come from the PC config (RemoteConfig.tabs). */
private val TAB_INFO = mapOf(
    "notifications" to (Sym.NOTIFICATIONS to "Notifiche|Notifications"),
    "agenda" to (Sym.EVENT to "Agenda"),
    "home" to (Sym.HOME to "Casa|Home"),
    "photos" to (Sym.PHOTO to "Foto|Photos"),
    "weather" to (Sym.SUNNY to "Meteo|Weather"),
    "pc" to (Sym.COMPUTER to "PC"),
    "display" to (Sym.CAST to "Schermo|Screen"),
)

/** Tabs card. Follows the PC state (agenda / photos) but can always be swiped by hand. */
@Composable
fun TabsCard(agenda: List<CalEvent>, weather: Weather?, pcOnline: Boolean?, slideshowSec: Int, prefs: Prefs, modifier: Modifier) {
    val p = LocalPalette.current
    val config by Remote.config.collectAsState()
    val tabs = config.tabs
    val pager = rememberPagerState(initialPage = tabs.indexOf("agenda").coerceAtLeast(0)) { tabs.size }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pcOnline, tabs) {
        if (pcOnline != null) {
            val target = tabs.indexOf(if (pcOnline) "agenda" else "photos")
            if (target >= 0) pager.animateScrollToPage(target)
        }
    }

    // `phonedash mirror tab`: jump to the Screen tab.
    val showTab by Mirror.showTab.collectAsState()
    LaunchedEffect(showTab) { if (showTab > 0) tabs.indexOf("display").takeIf { it >= 0 }?.let { pager.animateScrollToPage(it) } }
    val fullMirror by Mirror.fullscreen.collectAsState()

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(p.surfaceContainer)
    ) {
        // Scrolls sideways if the tabs don't fit (7 tabs + the selected label is close to the card width).
        Row(
            Modifier.padding(top = 12.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEachIndexed { i, key ->
                val (icon, label) = TAB_INFO.getValue(key)
                val selected = pager.currentPage == i
                val fg = if (selected) p.onPrimaryContainer else p.onSurfaceVariant
                Pill(
                    Modifier.clickable { scope.launch { pager.animateScrollToPage(i) } },
                    background = if (selected) p.primaryContainer else p.surfaceContainerHigh,
                    contentPadding = PaddingValues(horizontal = if (selected) 12.dp else 10.dp, vertical = 4.dp),
                ) {
                    MIcon(icon, 18.dp, fg, filled = selected)
                    // Only the selected tab shows its label (M3 nav style), so they all fit.
                    if (selected) Text(label.split("|").let { if (I18n.en) it.last() else it.first() }, style = textStyle(14, fg, 600), maxLines = 1, softWrap = false)
                }
            }
        }
        // On the Screen tab swipes go to the PC, not to the pager (tabs still switch with the pills).
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { tabs.getOrElse(it) { "" } },
            userScrollEnabled = tabs.getOrNull(pager.currentPage) != "display") { page ->
            when (tabs.getOrNull(page)) {
                "agenda" -> AgendaPage(agenda, Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp))
                "notifications" -> NotificationsPage(Modifier.fillMaxSize().padding(12.dp))
                "home" -> HomePage(prefs, Modifier.fillMaxSize().padding(12.dp))
                "weather" -> WeatherTab(weather, Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp))
                "pc" -> PcPage(Modifier.fillMaxSize().padding(12.dp))
                "display" -> if (fullMirror) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(tr("A schermo intero", "Full screen"), style = textStyle(15, p.onSurfaceVariant, 500))
                } else MirrorView(Modifier.fillMaxSize().padding(12.dp), full = false)
                "photos" -> Slideshow(
                    slideshowSec,
                    Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}

@Composable
private fun HomePage(prefs: Prefs, modifier: Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by GoogleHome.ui.collectAsState()
    var showHidden by remember { mutableStateOf(false) }

    if (!ui.signedIn) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Pill(
                Modifier.clickable { context.startActivity(Intent(context, LoginActivity::class.java)) },
                background = p.primary,
            ) {
                MIcon(Sym.HOME, 20.dp, p.onPrimary, filled = true)
                Text(tr("Collega Google Home", "Connect Google Home"), style = textStyle(15, p.onPrimary, 600))
            }
        }
        return
    }
    if (ui.devices.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(ui.error ?: tr("Caricamento dispositivi…", "Loading devices…"), style = textStyle(15, p.onSurfaceVariant, 400))
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val hidden = prefs.hiddenDevices
        items(ui.devices.filter { showHidden || it.id !in hidden }, key = { it.id }) { d ->
            DevicePill(
                d, isHidden = d.id in hidden,
                onToggle = { scope.launch { GoogleHome.toggle(d) } },
                onLongPress = { prefs.toggleDevice(d.id) },
            )
        }
        if (hidden.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (showHidden) tr("Tieni premuto per ripristinare · nascondi", "Long-press to restore · hide") else tr("${hidden.size} nascosti · mostra", "${hidden.size} hidden · show"),
                    style = textStyle(13, p.onSurfaceVariant, 500),
                    modifier = Modifier.clip(CircleShape).background(p.surfaceContainerHigh)
                        .clickable { showHidden = !showHidden }.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DevicePill(d: HomeDevice, isHidden: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit) {
    val p = LocalPalette.current
    val s = d.state
    val on = s?.online == true && (s.on == true || (d.isThermostat && s.mode != null && s.mode != "off"))
    val bg = if (on) p.primaryContainer else p.surfaceContainerHigh
    val fg = if (on) p.onPrimaryContainer else p.onSurface
    val icon = when {
        d.isThermostat -> Sym.THERMOSTAT
        "LIGHT" in d.type -> Sym.LIGHTBULB
        "OUTLET" in d.type || "SWITCH" in d.type -> Sym.OUTLET
        else -> Sym.POWER
    }
    val detail = when {
        s == null -> ""
        !s.online -> "Offline"
        d.isThermostat -> listOfNotNull(
            s.ambientC?.let { "%.1f°".format(Locale.ITALIAN, it) },
            s.setpointC?.let { "→ %.1f°".format(Locale.ITALIAN, it) },
        ).joinToString(" ")
        s.on == true && s.brightness != null -> "${s.brightness}%"
        s.on == true -> tr("Acceso", "On")
        else -> tr("Spento", "Off")
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .combinedClickable(
                onClick = { if (d.canOnOff && s?.online != false) onToggle() },
                onLongClick = onLongPress,
            )
            .alpha(if (isHidden) 0.35f else if (s?.online == false) 0.45f else 1f)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (on) p.primary else p.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { MIcon(icon, 20.dp, if (on) p.onPrimary else p.onSurfaceVariant, filled = on) }
        Column(Modifier.padding(start = 10.dp)) {
            Text(d.name, style = textStyle(14, fg, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = textStyle(12, if (on) fg else p.onSurfaceVariant, 400), maxLines = 1)
        }
    }
}
