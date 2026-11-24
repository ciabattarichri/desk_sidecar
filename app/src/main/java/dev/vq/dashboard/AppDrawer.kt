package dev.vq.dashboard

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LaunchableApp(val label: String, val pkg: String, val icon: ImageBitmap)

/** Opened with a long press: the "smelly apps" plus dashboard settings. */
@Composable
fun AppDrawer(prefs: Prefs, onClose: () -> Unit) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    val apps by produceState(emptyList<LaunchableApp>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { it.activityInfo.packageName }
                .map {
                    LaunchableApp(
                        label = it.loadLabel(pm).toString(),
                        pkg = it.activityInfo.packageName,
                        icon = it.loadIcon(pm).toBitmap(144, 144).asImageBitmap(),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    var city by remember { mutableStateOf(prefs.city) }
    var showCalendars by remember { mutableStateOf(false) }
    var showHiddenApps by remember { mutableStateOf(false) }
    val calendars by produceState(emptyList<AgendaRepo.CalendarInfo>()) { value = AgendaRepo.calendars(context) }
    val saveCity = {
        prefs.updateCity(city)
        focus.clearFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.surface.copy(alpha = 0.97f))
            // swallow touches meant for the dashboard underneath
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(Modifier.width(300.dp)) {
                MIcon(Sym.LOCATION, 20.dp, p.primary)
                BasicTextField(
                    value = city,
                    onValueChange = { city = it },
                    singleLine = true,
                    textStyle = textStyle(16, p.onSurface),
                    cursorBrush = SolidColor(p.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { saveCity() }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (city.isEmpty()) Text(tr("Città meteo", "Weather city"), style = textStyle(16, p.onSurfaceVariant, 400))
                        inner()
                    },
                )
            }
            if (city.trim() != prefs.city) {
                Pill(Modifier.clickable { saveCity() }, background = p.primary) {
                    Text(tr("Salva", "Save"), style = textStyle(15, p.onPrimary, 600))
                }
            }
            Spacer(Modifier.weight(1f))
            Pill(Modifier.clickable { showCalendars = !showCalendars }, background = if (showCalendars) p.primaryContainer else p.surfaceContainer) {
                MIcon(Sym.EVENT, 20.dp, if (showCalendars) p.onPrimaryContainer else p.primary)
                Text(tr("Calendari", "Calendars"), style = textStyle(15, if (showCalendars) p.onPrimaryContainer else p.onSurface))
            }
            Pill(Modifier.clickable {
                context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) {
                MIcon(Sym.APPS, 20.dp, p.primary)
                Text(tr("Cambia launcher", "Change launcher"), style = textStyle(15, p.onSurface))
            }
            Pill(Modifier.clickable {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) {
                MIcon(Sym.SETTINGS, 20.dp, p.primary)
                Text(tr("Impostazioni", "Settings"), style = textStyle(15, p.onSurface))
            }
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(p.surfaceContainer).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { MIcon(Sym.CLOSE, 22.dp, p.onSurface) }
        }
        Spacer(Modifier.size(16.dp))
        if (showCalendars) {
            Text(tr("Calendari mostrati in agenda (tocca per mostrare/nascondere)", "Calendars shown in the agenda (tap to show/hide)"), style = textStyle(14, p.onSurfaceVariant, 400))
            Spacer(Modifier.size(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(260.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(calendars, key = { it.id }) { cal ->
                    val on = cal.id !in prefs.hiddenCalendars
                    Pill(
                        Modifier.clickable { prefs.toggleCalendar(cal.id) },
                        background = if (on) p.surfaceContainerHigh else p.surfaceContainerLow,
                    ) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Color(cal.color).copy(alpha = if (on) 1f else 0.3f)))
                        Column(Modifier.weight(1f)) {
                            Text(cal.name, style = textStyle(15, if (on) p.onSurface else p.outline, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(cal.account, style = textStyle(12, p.onSurfaceVariant.copy(alpha = if (on) 1f else 0.5f), 400), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (on) tr("Visibile", "Visible") else tr("Nascosto", "Hidden"), style = textStyle(12, if (on) p.primary else p.outline, 600))
                    }
                }
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(apps.filter { showHiddenApps || it.pkg !in prefs.hiddenApps }, key = { it.pkg }) { app ->
                val hidden = app.pkg in prefs.hiddenApps
                Column(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(p.surfaceContainer)
                        .combinedClickable(
                            onClick = {
                                context.packageManager.getLaunchIntentForPackage(app.pkg)?.let {
                                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                                onClose()
                            },
                            // Long press hides the app (or restores it while hidden ones are shown)
                            onLongClick = { prefs.toggleApp(app.pkg) },
                        )
                        .alpha(if (hidden) 0.35f else 1f)
                        .padding(vertical = 12.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(app.icon, contentDescription = null, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        app.label, style = textStyle(13, p.onSurface, 400), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }
            if (prefs.hiddenApps.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (showHiddenApps) tr("Tieni premuto per ripristinare · nascondi", "Long-press to restore · hide") else tr("${prefs.hiddenApps.size} app nascoste · mostra", "${prefs.hiddenApps.size} hidden apps · show"),
                        style = textStyle(13, p.onSurfaceVariant, 500),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(p.surfaceContainerHigh)
                            .clickable { showHiddenApps = !showHiddenApps }.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
