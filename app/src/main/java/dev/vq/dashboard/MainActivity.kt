package dev.vq.dashboard

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private lateinit var nowPlaying: NowPlayingSource
    private lateinit var prefs: Prefs
    private val drawerOpen = mutableStateOf(false)
    private lateinit var server: HttpServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        prefs = Prefs(this)
        Remote.init(this)
        GoogleHome.init(this)
        server = HttpServer(this).also { it.start() }
        nowPlaying = NowPlayingSource(this)

        setContent {
            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { prefs.bumpPermissions() }
            LaunchedEffect(Unit) {
                permLauncher.launch(
                    arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }

            // This is the home screen: BACK must never leave it.
            BackHandler { drawerOpen.value = false }

            val palette by Remote.palette.collectAsState()
            // Language switch: restart so every string and date formatter is rebuilt.
            val language = Remote.config.collectAsState().value.language
            val startLanguage = androidx.compose.runtime.remember { language }
            LaunchedEffect(language) { if (language != startLanguage) recreate() }

            CompositionLocalProvider(LocalPalette provides palette) {
                Dashboard(
                    prefs = prefs,
                    nowPlaying = nowPlaying,
                    onLongPress = { drawerOpen.value = true },
                    setBrightness = ::setBrightness,
                )
                if (drawerOpen.value) {
                    AppDrawer(prefs = prefs, onClose = { drawerOpen.value = false })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        nowPlaying.start() // no-op until notification access is granted
        Ambient.start(this)
    }

    override fun onPause() {
        Ambient.stop(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed while already on the dashboard: close the drawer.
        drawerOpen.value = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        nowPlaying.stop()
        server.stop()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
    }
}
