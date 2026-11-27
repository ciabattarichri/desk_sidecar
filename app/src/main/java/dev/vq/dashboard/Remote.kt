package dev.vq.dashboard

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.LocalTime

/** Minutes since midnight. */
fun minuteOfDay(t: LocalTime = LocalTime.now()) = t.hour * 60 + t.minute

private fun parseHm(s: String): Int {
    val (h, m) = s.trim().split(":").map { it.toInt() }
    return h * 60 + m
}

/** "23:30-07:00" -> (1410, 420). Ranges may wrap past midnight. */
private fun parseRange(s: String): Pair<Int, Int> {
    val (a, b) = s.split("-")
    return parseHm(a) to parseHm(b)
}

private fun inRange(m: Int, r: Pair<Int, Int>) =
    if (r.first <= r.second) m >= r.first && m < r.second else m >= r.first || m < r.second

/** Behaviour driven from the PC (`~/.config/phone-dash/config.toml`). Defaults used until the PC sends one. */
data class RemoteConfig(
    /** (minute of day, brightness %) — each value holds until the next entry. */
    val brightness: List<Pair<Int, Int>> = listOf(7 * 60 to 15, 8 * 60 + 30 to 45, 19 * 60 + 30 to 20, 23 * 60 to 1),
    /** Ranges with the minimal night clock. */
    val night: List<Pair<Int, Int>> = listOf(23 * 60 to 7 * 60),
    /** Ranges with a fully black screen. */
    val screenOff: List<Pair<Int, Int>> = emptyList(),
    val slideshowSeconds: Int = 20,
    val pcOfflineAfterSec: Int = 90,
    /** Billable Hours Google Sheets backup web app (doGet) and its secret word. */
    val billableUrl: String = "",
    val billableToken: String = "",
    /** Player wave/cover animation: "off", "30s" (after track change / touch), "always". */
    val playerAnimation: String = "30s",
    /** Synced lyrics (LRCLIB) in photo-frame mode. */
    val lyrics: Boolean = true,
    /** Scale the scheduled brightness with the ambient light sensor. */
    val lightSensor: Boolean = true,
    /** PC locked: "frame" (photo frame), "off" (black screen), "none". */
    val onLock: String = "frame",
    /** Tab keys in display order; missing keys are hidden. */
    val tabs: List<String> = DEFAULT_TABS,
    val showBillable: Boolean = true,
    val showNotes: Boolean = true,
    /** "it" or "en". */
    val language: String = "it",
) {
    private val sorted = brightness.sortedBy { it.first }

    fun brightnessIndex(m: Int): Int {
        if (sorted.isEmpty()) return -1
        val i = sorted.indexOfLast { it.first <= m }
        return if (i >= 0) i else sorted.lastIndex
    }

    fun scheduledBrightness(m: Int): Int = sorted.getOrNull(brightnessIndex(m))?.second ?: 40
    fun isNight(m: Int) = night.any { inRange(m, it) }
    fun isScheduledOff(m: Int) = screenOff.any { inRange(m, it) }

    companion object {
        val DEFAULT_TABS = listOf("notifications", "agenda", "home", "photos", "weather", "pc")
        /** Every known tab ("display" = PC screen, off unless chosen in the layout). */
        val ALL_TABS = DEFAULT_TABS + "display"

        fun fromJson(j: JSONObject): RemoteConfig {
            val d = RemoteConfig()
            fun ranges(key: String, def: List<Pair<Int, Int>>): List<Pair<Int, Int>> =
                j.optJSONArray(key)?.let { a -> (0 until a.length()).map { parseRange(a.getString(it)) } } ?: def
            val bright = j.optJSONArray("brightness")?.let { a ->
                (0 until a.length()).map { val e = a.getJSONArray(it); parseHm(e.getString(0)) to e.getInt(1).coerceIn(0, 100) }
            } ?: d.brightness
            return RemoteConfig(
                brightness = bright,
                night = ranges("night", d.night),
                screenOff = ranges("screen_off", d.screenOff),
                slideshowSeconds = j.optInt("slideshow_seconds", d.slideshowSeconds).coerceAtLeast(3),
                pcOfflineAfterSec = j.optInt("pc_offline_after", d.pcOfflineAfterSec).coerceAtLeast(30),
                billableUrl = j.optString("billable_url", ""),
                billableToken = j.optString("billable_token", ""),
                playerAnimation = j.optString("player_animation", "30s"),
                lyrics = j.optBoolean("lyrics", true),
                lightSensor = j.optBoolean("light_sensor", true),
                onLock = j.optString("on_lock", "frame"),
                tabs = j.optJSONArray("tabs")?.let { a -> (0 until a.length()).map { a.getString(it) }.filter { it in ALL_TABS } }
                    ?.takeIf { it.isNotEmpty() } ?: DEFAULT_TABS,
                showBillable = j.optBoolean("show_billable", true),
                showNotes = j.optBoolean("show_notes", true),
                language = j.optString("language", "it").takeIf { it == "en" } ?: "it",
            )
        }
    }
}

/** A manual override that lasts until the schedule it overrides changes. */
data class Override<T>(val value: T, val scheduleKey: Int)

data class PcStats(
    val cpu: Int?, val ram: Int?, val ramUsed: Double?, val ramTotal: Double?,
    val cpuTemp: Int?, val diskTemp: Int?, val load: Double?,
)

data class PcNotification(val app: String, val summary: String, val body: String, val timeMs: Long)

/** Shared state between the HTTP server (PC) and the UI. */
object Remote {
    val config = MutableStateFlow(RemoteConfig())
    val palette = MutableStateFlow(Palette())
    val pcLocked = MutableStateFlow(false)
    val pcStats = MutableStateFlow<PcStats?>(null)
    /** Post-it notes written from the PC plugin. */
    val notes = MutableStateFlow<List<String>>(emptyList())

    fun applyExtras(j: JSONObject) {
        if (j.has("pc_locked")) pcLocked.value = j.optBoolean("pc_locked")
        j.optJSONArray("notes")?.let { a -> notes.value = (0 until a.length()).map { a.getString(it) } }
        j.optJSONObject("pc_stats")?.let { o ->
            fun i(k: String) = if (o.isNull(k)) null else o.optInt(k)
            fun d(k: String) = if (o.isNull(k)) null else o.optDouble(k)
            pcStats.value = PcStats(i("cpu"), i("ram"), d("ram_used"), d("ram_total"), i("cpu_temp"), i("disk_temp"), d("load"))
        }
    }

    /** Recent desktop notifications (DMS history), newest first. */
    val notifications = MutableStateFlow<List<PcNotification>>(emptyList())

    fun applyNotifications(a: org.json.JSONArray) {
        notifications.value = (0 until a.length()).map { i ->
            val n = a.getJSONObject(i)
            PcNotification(n.optString("app"), n.optString("summary"), n.optString("body"), n.optLong("ts"))
        }
    }
    /** elapsedRealtime of the last request from the PC, 0 = never. */
    val lastHeartbeat = MutableStateFlow(0L)
    val brightnessOverride = MutableStateFlow<Override<Int>?>(null)
    /** true = forced on, false = forced off. */
    val screenOverride = MutableStateFlow<Override<Boolean>?>(null)

    private val startedAt = SystemClock.elapsedRealtime()
    private lateinit var app: Context
    private lateinit var configFile: File
    lateinit var token: String
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        configFile = File(app.filesDir, "remote-config.json")
        runCatching { config.value = RemoteConfig.fromJson(JSONObject(configFile.readText())) }
        palette.value = Palette.load(app)
        val tokenFile = File(app.filesDir, "token")
        token = tokenFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length >= 32 } ?: run {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            bytes.joinToString("") { "%02x".format(it) }.also { tokenFile.writeText(it) }
        }
        // Also expose it in the app's external files dir so `phonedash pair` can read it over adb
        // (release builds are not debuggable, so `run-as` is unavailable). Other apps can't read it.
        runCatching { app.getExternalFilesDir(null)?.let { File(it, "token").writeText(token) } }
    }

    /** Set when the PC says goodbye (service stopped / shutdown); cleared by the next heartbeat. */
    @Volatile private var pcSaidGoodbye = false

    fun heartbeat() {
        pcSaidGoodbye = false
        lastHeartbeat.value = SystemClock.elapsedRealtime()
    }

    fun pcGoodbye() {
        pcSaidGoodbye = true
        lastHeartbeat.value = SystemClock.elapsedRealtime() // emit so the UI recomposes
    }

    /** null while unknown (just started), then whether the PC has been heard from recently. */
    fun pcOnline(): Boolean? {
        if (pcSaidGoodbye) return false
        if (pcLocked.value && config.value.onLock == "frame") return false
        val limit = config.value.pcOfflineAfterSec * 1000L
        val now = SystemClock.elapsedRealtime()
        val hb = lastHeartbeat.value
        if (hb == 0L) return if (now - startedAt < limit) null else false
        return now - hb < limit
    }

    fun applyConfig(j: JSONObject) {
        val c = RemoteConfig.fromJson(j)
        if (c != config.value) {
            config.value = c
            configFile.writeText(j.toString())
        }
    }

    fun applyColors(dark: JSONObject) {
        val f = Palette.file(app) ?: return
        val content = JSONObject().put("colors", JSONObject().put("dark", dark)).toString()
        if (f.isFile && f.readText() == content) return
        f.parentFile?.mkdirs()
        f.writeText(content)
        palette.value = Palette.load(app)
    }

    fun setBrightness(percent: Int?) {
        val m = minuteOfDay()
        brightnessOverride.value = percent?.let { Override(it.coerceIn(0, 100), config.value.brightnessIndex(m)) }
    }

    fun setScreen(on: Boolean?) {
        val m = minuteOfDay()
        screenOverride.value = on?.let { Override(it, if (config.value.isScheduledOff(m)) 1 else 0) }
    }

    /** Drops overrides whose schedule slot has changed. Call once a minute. */
    fun expireOverrides(m: Int) {
        val c = config.value
        brightnessOverride.value?.let { if (it.scheduleKey != c.brightnessIndex(m)) brightnessOverride.value = null }
        screenOverride.value?.let { if (it.scheduleKey != (if (c.isScheduledOff(m)) 1 else 0)) screenOverride.value = null }
    }

    fun isScreenOff(m: Int): Boolean = screenOverride.value?.let { !it.value }
        ?: (config.value.isScheduledOff(m) || (pcLocked.value && config.value.onLock == "off"))

    fun brightness(m: Int): Int = brightnessOverride.value?.value ?: config.value.scheduledBrightness(m)

    fun status(): JSONObject = JSONObject()
        .put("brightness", brightness(minuteOfDay()))
        .put("brightness_override", brightnessOverride.value?.value ?: JSONObject.NULL)
        .put("screen_off", isScreenOff(minuteOfDay()))
        .put("screen_override", screenOverride.value?.value ?: JSONObject.NULL)
        .put("photos", PhotoStore.names(app).size)
        .put("google_home", GoogleHome.ui.value.signedIn)
        .put("mirror", if (Mirror.fullscreen.value) "full" else "off")
        .put("pc_online", pcOnline() ?: JSONObject.NULL)
        .put("heartbeat_age_s", if (lastHeartbeat.value == 0L) -1 else (SystemClock.elapsedRealtime() - lastHeartbeat.value) / 1000)

    fun jsonArrayOf(items: List<String>) = JSONArray(items)
}
