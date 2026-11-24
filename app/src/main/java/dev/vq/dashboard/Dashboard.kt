package dev.vq.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/** Hide a paused/stopped track after this long. */
private const val PAUSED_HIDE_MS = 5_000L

/** After the last touch, go back to the photo frame (PC off) after this long. */
private const val FRAME_SNOOZE_MS = 10_000L

private val HOUR_FMT = DateTimeFormatter.ofPattern("HH")
private val MIN_FMT = DateTimeFormatter.ofPattern("mm")
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FMT get() = DateTimeFormatter.ofPattern(if (I18n.en) "EEEE, MMMM d" else "EEEE d MMMM", I18n.locale)
private val SHORT_DATE_FMT get() = DateTimeFormatter.ofPattern(if (I18n.en) "EEE, MMM d" else "EEE d MMM", I18n.locale)
private val DAY_FMT get() = DateTimeFormatter.ofPattern("EEE", I18n.locale)

private fun String.cap() = replaceFirstChar { it.uppercase() }

@Composable
fun Dashboard(
    prefs: Prefs,
    nowPlaying: NowPlayingSource,
    onLongPress: () -> Unit,
    setBrightness: (Float) -> Unit,
) {
    val context = LocalContext.current
    val p = LocalPalette.current

    // Minute-precision clock: the whole screen recomposes at most once a minute.
    val now by produceState(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)) {
        while (true) {
            value = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    // Cheap 2 s tick for timers; only read inside derivedStateOf, so it recomposes only when a result flips.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }
    val minuteKey = now.hour * 60 + now.minute
    val config by Remote.config.collectAsState()
    val brightnessOverride by Remote.brightnessOverride.collectAsState()
    val screenOverride by Remote.screenOverride.collectAsState()
    val night = config.isNight(minuteKey)
    val heartbeat by Remote.lastHeartbeat.collectAsState()
    val pcOnline by remember { derivedStateOf { tick; heartbeat; Remote.pcOnline() } }

    // "Screen off" = full black at minimum brightness; a tap wakes it for 30 s.
    var wakeUntil by remember { mutableLongStateOf(0L) }
    // Photo-frame mode while the PC is off; a tap shows the dashboard for 2 minutes.
    var frameSnoozeUntil by remember { mutableLongStateOf(0L) }
    /** Last finger-down anywhere: wakes the player animations for a while. */
    var lastTouch by remember { mutableLongStateOf(0L) }
    val snoozed by remember { derivedStateOf { tick; System.currentTimeMillis() < frameSnoozeUntil } }
    val awake by remember { derivedStateOf { tick; System.currentTimeMillis() < wakeUntil } }
    val off by remember(minuteKey) { derivedStateOf { tick; Remote.isScreenOff(minuteKey) && !awake } }

    LaunchedEffect(minuteKey) { Remote.expireOverrides(minuteKey) }
    val ambient by Ambient.factor.collectAsState()
    val brightness = (Remote.brightness(minuteKey) * (if (config.lightSensor) ambient else 1f)).toInt().coerceIn(1, 100)
    LaunchedEffect(brightness, off, config, brightnessOverride, screenOverride) {
        setBrightness(if (off) 0.01f else brightness / 100f)
    }

    val billableSnap by Billable.snapshot.collectAsState()
    LaunchedEffect(config.billableUrl, config.billableToken, off) {
        while (!off) {
            Billable.refresh(config.billableUrl, config.billableToken)
            delay(if (Billable.snapshot.value?.activeProj != null) 60_000 else 5 * 60_000L)
        }
    }
    val billable = remember(billableSnap, minuteKey) { billableSnap?.let { Billable.totals(it) } }

    val homeUi by GoogleHome.ui.collectAsState()
    LaunchedEffect(homeUi.signedIn, off) {
        while (homeUi.signedIn && !off) {
            GoogleHome.refresh()
            delay(30_000)
        }
    }

    val weather by produceState<Weather?>(null, prefs.city, prefs.permissionsVersion) {
        while (true) {
            WeatherRepo.load(context, prefs.city)?.let { value = it }
            delay(15 * 60 * 1000L)
        }
    }
    val agenda by produceState(emptyList<CalEvent>(), prefs.permissionsVersion, prefs.hiddenCalendars) {
        while (true) {
            value = AgendaRepo.load(context, prefs.hiddenCalendars)
            delay(5 * 60 * 1000L)
        }
    }

    val track by nowPlaying.state.collectAsState()
    val visibleTrack by remember {
        derivedStateOf {
            tick
            track?.takeIf { it.playing || System.currentTimeMillis() - it.pausedAtMs < PAUSED_HIDE_MS }
        }
    }

    // Anti burn-in: move everything by a few dp every minute.
    val shift = remember(minuteKey) { Random.nextInt(-6, 7) to Random.nextInt(-6, 7) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Any touch anywhere (even on tabs/buttons) keeps the dashboard up for FRAME_SNOOZE_MS.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        frameSnoozeUntil = System.currentTimeMillis() + FRAME_SNOOZE_MS
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) lastTouch = System.currentTimeMillis()
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = {
                        val t = System.currentTimeMillis()
                        wakeUntil = t + 30_000
                        frameSnoozeUntil = t + FRAME_SNOOZE_MS
                    },
                )
            }
    ) {
        if (off) return@Box
        val frame = pcOnline == false && !night && !snoozed
        if (frame) {
            FrameView(now, weather, visibleTrack, config.slideshowSeconds, shift)
            return@Box
        }
        Box(Modifier.fillMaxSize().offset(shift.first.dp, shift.second.dp)) {
            Crossfade(targetState = night, label = "night") { isNight ->
                if (isNight) NightView(now, visibleTrack?.takeIf { it.playing })
                else DayView(now, weather, agenda, visibleTrack, track, nowPlaying, pcOnline, config.slideshowSeconds, prefs, billable, lastTouch)
            }
        }
    }
}

@Composable
private fun DayView(
    now: LocalDateTime,
    weather: Weather?,
    agenda: List<CalEvent>,
    track: NowPlaying?,
    /** Any media session, even paused: drives the mini controls pill. */
    session: NowPlaying?,
    nowPlaying: NowPlayingSource,
    pcOnline: Boolean?,
    slideshowSec: Int,
    prefs: Prefs,
    billable: Billable.Totals?,
    lastTouch: Long,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Left: clock, date, weather
        Column(Modifier.weight(1.05f).fillMaxHeight()) {
            val playing = track != null
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(now.format(HOUR_FMT), style = textStyle(104, p.onSurface, 800).copy(fontFamily = ClockFont, lineHeight = 104.sp()))
                        Text(":", style = textStyle(104, p.primary, 800).copy(fontFamily = ClockFont, lineHeight = 104.sp()))
                        Text(now.format(MIN_FMT), style = textStyle(104, p.primary, 800).copy(fontFamily = ClockFont, lineHeight = 104.sp()))
                    }
                    // With music: short date + today's weather tuck under the clock, beside the hours card.
                    if (playing) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill {
                                MIcon(Sym.CALENDAR, 20.dp, p.primary)
                                Text(now.format(SHORT_DATE_FMT).split(" ").joinToString(" ") { it.cap() }.replace(".", ""), style = textStyle(17, p.onSurface), maxLines = 1)
                            }
                            weather?.let { CurrentWeatherPill(it, compact = true) }
                        }
                    }
                }
                billable?.takeIf { Remote.config.value.showBillable }?.let {
                    Spacer(Modifier.width(16.dp))
                    BillableCard(it, compact = false, modifier = Modifier.weight(1f))
                }
            }
            if (!playing) {
                Spacer(Modifier.height(8.dp))
                Pill {
                    MIcon(Sym.CALENDAR, 20.dp, p.primary)
                    Text(now.format(DATE_FMT).cap(), style = textStyle(17, p.onSurface), maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.weight(1f))
            Crossfade(targetState = track != null, label = "left-bottom") { playing ->
                if (playing) {
                    track?.let { MediaCard(it, nowPlaying, lastTouch) }
                } else {
                    weather?.let {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CurrentWeatherPill(it, compact = false)
                                if (session != null) MiniControls(session, nowPlaying)
                            }
                            ForecastPills(it)
                        }
                    }
                }
            }
        }
        // Right: agenda / home / photos
        Box(Modifier.weight(1f).fillMaxHeight()) {
            TabsCard(agenda, weather, pcOnline, slideshowSec, prefs, Modifier.fillMaxSize())
            if (Remote.config.collectAsState().value.showNotes) {
                PostIts(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 14.dp))
            }
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

@Composable
private fun CurrentWeatherPill(w: Weather, compact: Boolean) {
    val p = LocalPalette.current
    val (icon, desc) = WeatherRepo.describe(w.code, w.isDay)
    val pad = if (compact) PaddingValues(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)
    else PaddingValues(start = 10.dp, end = 18.dp, top = 6.dp, bottom = 6.dp)
    Pill(contentPadding = pad) {
        Box(
            Modifier.size(if (compact) 32.dp else 40.dp).clip(CircleShape).background(p.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { MIcon(icon, if (compact) 20.dp else 26.dp, p.onPrimaryContainer, filled = true) }
        Text("${w.temperature.roundToInt()}°", style = textStyle(if (compact) 18 else 26, p.onSurface, 500))
        // Compact (next to the date while music plays): icon + temperature only.
        if (!compact) Text(
            listOf(desc, w.place).filter { it.isNotBlank() }.joinToString(" · "),
            style = textStyle(16, p.onSurfaceVariant),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Billable Hours summary (watchapp Summary: today / this month) as a tertiary stat tile next to the clock. */
@Composable
private fun BillableCard(t: Billable.Totals, compact: Boolean, modifier: Modifier) {
    val p = LocalPalette.current
    val fg = p.onSecondaryContainer
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(p.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp),
    ) {
        @Composable
        fun block(label: String, icon: Char, filled: Boolean, sec: Long, amount: Double) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MIcon(icon, 16.dp, p.primary, filled = filled)
                    Text(label, style = textStyle(13, fg, 600))
                }
                Text(Billable.hm(sec), style = if (compact) textStyle(24, p.primary, 600).copy(lineHeight = 26.sp()) else textStyle(30, p.primary, 600).copy(lineHeight = 32.sp()))
                if (t.hasRates && !compact) Text(Billable.money(amount, t.currency), style = textStyle(14, fg, 500), maxLines = 1)
            }
        }
        block(if (t.running != null) tr("In corso", "Running") else tr("Oggi", "Today"), Sym.TIMER, t.running != null, t.todaySec, t.todayAmount)
        Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = 0.15f)))
        block(tr("Mese", "Month"), Sym.WORK, false, t.monthSec, t.monthAmount)
    }
}

/** Small always-available transport pill, shown while the full player is hidden. */
@Composable
private fun MiniControls(t: NowPlaying, source: NowPlayingSource) {
    val p = LocalPalette.current
    Pill(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
        listOf(
            Triple(if (t.playing) Sym.PAUSE else Sym.PLAY, true, source::togglePlayPause),
            Triple(Sym.SKIP_NEXT, false, source::next),
        ).forEach { (sym, main, action) ->
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (main) p.primaryContainer else p.surfaceContainerHigh)
                    .clickable { action() },
                contentAlignment = Alignment.Center,
            ) { MIcon(sym, 22.dp, if (main) p.onPrimaryContainer else p.onSurface, filled = true) }
        }
    }
}

@Composable
private fun ForecastPills(w: Weather) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        w.days.drop(1).take(3).forEach { d ->
            Pill(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text(d.date.format(DAY_FMT).cap().removeSuffix("."), style = textStyle(14, p.onSurfaceVariant))
                MIcon(WeatherRepo.describe(d.code).first, 20.dp, p.primary)
                Text("${d.tMax.roundToInt()}°", style = textStyle(15, p.onSurface))
                Text("${d.tMin.roundToInt()}°", style = textStyle(15, p.onSurfaceVariant, 400))
            }
        }
    }
}

private sealed interface AgendaRow {
    data class Header(val day: LocalDate) : AgendaRow
    data class Item(val event: CalEvent, val today: Boolean) : AgendaRow
}

@Composable
fun AgendaPage(events: List<CalEvent>, modifier: Modifier) {
    val p = LocalPalette.current
    val today = LocalDate.now()
    val rows = remember(events, today) {
        buildList {
            var last: LocalDate? = null
            events.forEach { e ->
                val day = maxOf(e.start.toLocalDate(), today)
                if (day != last) { add(AgendaRow.Header(day)); last = day }
                add(AgendaRow.Item(e, day == today))
            }
        }
    }
    Column(modifier) {
        NextEventBanner(events)
        if (rows.isEmpty()) {
            Text(tr("Nessun impegno nei prossimi giorni", "Nothing planned in the next days"), style = textStyle(15, p.onSurfaceVariant, 400))
            return@Column
        }
        // Scrollable through the next 7 days; snaps back to the top after 20 s untouched.
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(list.isScrollInProgress, list.firstVisibleItemIndex) {
            if (!list.isScrollInProgress && (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)) {
                delay(20_000)
                list.animateScrollToItem(0)
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows.size) { i ->
                when (val r = rows[i]) {
                    is AgendaRow.Header -> Text(
                        AgendaRepo.dayLabel(r.day),
                        style = textStyle(13, p.primary, 600),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    is AgendaRow.Item -> EventRow(r.event, r.today)
                }
            }
        }
    }
}

/** "Tra 25 min · Christian 1:1" for the next timed event today; highlighted in the last 10 minutes. */
@Composable
private fun NextEventBanner(events: List<CalEvent>) {
    val p = LocalPalette.current
    val now = LocalDateTime.now()
    val next = events.firstOrNull { !it.allDay && it.start.isAfter(now) && it.start.toLocalDate() == now.toLocalDate() } ?: return
    val min = java.time.Duration.between(now, next.start).toMinutes() + 1
    val soon = min <= 10
    val whenText = if (min < 60) tr("tra $min min", "in $min min") else tr("tra ${min / 60} h ${min % 60} min", "in ${min / 60} h ${min % 60} min")
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(if (soon) p.primaryContainer else p.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MIcon(Sym.TIMER, 18.dp, if (soon) p.onPrimaryContainer else p.primary, filled = soon)
        Text(whenText, style = textStyle(14, if (soon) p.onPrimaryContainer else p.primary, 700))
        Text(next.title, style = textStyle(14, if (soon) p.onPrimaryContainer else p.onSurface, 500), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EventRow(e: CalEvent, today: Boolean) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(64.dp)
                .clip(CircleShape)
                .background(if (today) p.secondaryContainer else p.surfaceContainerHigh)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (e.allDay) tr("Giorno", "All day") else e.start.format(TIME_FMT),
                style = textStyle(13, if (today) p.onSecondaryContainer else p.onSurfaceVariant),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(e.title, style = textStyle(16, p.onSurface, if (today) 500 else 400), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Column that only places the children that fully fit, and never ends with a dangling header.
 */
@Composable
private fun FitColumn(isHeader: List<Boolean>, spacing: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, c ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map { it.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
        var y = 0
        var count = 0
        for (pl in placeables) {
            val h = pl.height + if (count > 0) gap else 0
            if (y + h > c.maxHeight) break
            y += h
            count++
        }
        while (count > 0 && isHeader.getOrElse(count - 1) { false }) count--
        layout(c.maxWidth, c.maxHeight) {
            var py = 0
            for (i in 0 until count) {
                placeables[i].place(0, py)
                py += placeables[i].height + gap
            }
        }
    }
}

@Composable
private fun NightView(now: LocalDateTime, track: NowPlaying?) {
    val p = LocalPalette.current
    val dim = p.onSurface.copy(alpha = 0.30f)
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(now.format(TIME_FMT), style = textStyle(140, dim, 400).copy(fontFamily = ClockFont))
        track?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MIcon(Sym.MUSIC_NOTE, 18.dp, dim)
                Text("${it.title} · ${it.artist}", style = textStyle(17, dim, 400), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Full-screen photo frame with a small translucent clock/weather pill. */
@Composable
private fun FrameView(now: LocalDateTime, weather: Weather?, track: NowPlaying?, seconds: Int, shift: Pair<Int, Int>) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize()) {
        Slideshow(seconds, Modifier.fillMaxSize(), kenBurns = true)
        if (Remote.config.collectAsState().value.lyrics) {
            track?.takeIf { it.playing }?.let {
                // Fixed-size card pinned top-left (like the DMS lyrics widget): it never jumps around.
                LyricsView(
                    it,
                    Modifier.align(Alignment.TopStart).offset(shift.first.dp, shift.second.dp).padding(28.dp)
                        .width(460.dp).height(124.dp)
                        .clip(RoundedCornerShape(28.dp)).background(p.surface.copy(alpha = 0.6f))
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).offset(shift.first.dp, shift.second.dp).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(background = p.surface.copy(alpha = 0.6f), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
                Text(now.format(TIME_FMT), style = textStyle(64, p.onSurface, 800).copy(fontFamily = ClockFont, lineHeight = 68.sp()))
                weather?.let {
                    Spacer(Modifier.width(4.dp))
                    MIcon(WeatherRepo.describe(it.code, it.isDay).first, 40.dp, p.primary, filled = true)
                    Text("${it.temperature.roundToInt()}°", style = textStyle(32, p.onSurface, 600))
                }
            }
        }
        // Now playing: bottom-right
        track?.takeIf { it.playing }?.let {
            Pill(
                Modifier.align(Alignment.BottomEnd).offset(shift.first.dp, shift.second.dp).padding(28.dp).widthIn(max = 420.dp),
                background = p.surface.copy(alpha = 0.6f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            ) {
                MIcon(Sym.MUSIC_NOTE, 28.dp, p.primary, filled = true)
                Column(Modifier.weight(1f, fill = false)) {
                    Text(it.title, style = textStyle(22, p.onSurface, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(it.artist, style = textStyle(16, p.onSurfaceVariant, 500), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
