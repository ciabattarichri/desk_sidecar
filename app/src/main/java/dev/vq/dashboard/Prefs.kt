package dev.vq.dashboard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Small observable wrapper over SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dashboard", Context.MODE_PRIVATE)

    /** City for the weather; blank = use the phone's last known location. */
    var city by mutableStateOf(sp.getString("city", DEFAULT_CITY) ?: DEFAULT_CITY)
        private set

    /** Bumped when runtime permissions change, so data loaders retry. */
    var permissionsVersion by mutableIntStateOf(0)
        private set

    /** Calendar ids hidden from the agenda. */
    var hiddenCalendars by mutableStateOf(sp.getStringSet("hidden_calendars", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet())
        private set

    /** Google Home device ids hidden from the Casa tab. */
    var hiddenDevices by mutableStateOf(sp.getStringSet("hidden_devices", emptySet())!!.toSet())
        private set

    /** Package names hidden from the app drawer. */
    var hiddenApps by mutableStateOf(sp.getStringSet("hidden_apps", emptySet())!!.toSet())
        private set

    fun toggleApp(pkg: String) {
        hiddenApps = if (pkg in hiddenApps) hiddenApps - pkg else hiddenApps + pkg
        sp.edit().putStringSet("hidden_apps", hiddenApps).apply()
    }

    fun toggleCalendar(id: Long) {
        hiddenCalendars = if (id in hiddenCalendars) hiddenCalendars - id else hiddenCalendars + id
        sp.edit().putStringSet("hidden_calendars", hiddenCalendars.map { it.toString() }.toSet()).apply()
    }

    fun toggleDevice(id: String) {
        hiddenDevices = if (id in hiddenDevices) hiddenDevices - id else hiddenDevices + id
        sp.edit().putStringSet("hidden_devices", hiddenDevices).apply()
    }

    fun updateCity(value: String) {
        city = value.trim()
        sp.edit().putString("city", city).apply()
    }

    fun bumpPermissions() {
        permissionsVersion++
    }

    companion object {
        const val DEFAULT_CITY = "Torino"
    }
}
