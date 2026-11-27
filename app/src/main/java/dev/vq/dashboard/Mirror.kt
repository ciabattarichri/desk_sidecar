package dev.vq.dashboard

// "Screen" tab: a PC monitor (niri output) streamed by phonedash as H.264 over USB (`adb reverse`), decoded
// in hardware into a SurfaceView. Touches go back to the PC as a virtual touchscreen (they don't move the
// mouse). Streams only while a view is on screen: the surface's lifetime is the connection's lifetime.

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

object Mirror {
    private const val PORT = 8767

    /** Full-screen view (button, BACK, or `phonedash mirror full|tab`). */
    val fullscreen = MutableStateFlow(false)
    /** Bumped by `phonedash mirror tab`: the tabs card scrolls to the Screen tab. */
    val showTab = MutableStateFlow(0)
    /** Message instead of the picture (null = streaming). */
    val status = MutableStateFlow<String?>("")
    /** Stream size, for the aspect ratio and the touch mapping. */
    val size = MutableStateFlow(16 to 9)

    private val sender = Executors.newSingleThreadExecutor()
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var surface: Surface? = null
    private var thread: Thread? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    /** Decoder: lives with the surface; the connection outlives it for a few seconds (tab <-> full screen). */
    private var codec: MediaCodec? = null
    @Volatile private var needKey = true
    private var lastSize: Pair<Int, Int>? = null

    @Synchronized
    fun attach(s: Surface) {
        surface = s
        releaseCodec()
        lastSize?.let { (w, h) -> codec = newCodec(w, h, s) }
        requestKey()
        if (thread?.isAlive != true) thread = Thread(::loop, "mirror").also { it.start() }
    }

    @Synchronized
    fun detach(s: Surface) {
        if (surface !== s) return
        surface = null
        releaseCodec()
        // Keep the stream a moment: switching tab <-> full screen re-attaches right away.
        timer.schedule({ synchronized(this) { if (surface == null) runCatching { socket?.close() } } }, 3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun releaseCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
    }

    private fun newCodec(w: Int, h: Int, sf: Surface) = runCatching {
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            configure(f, sf, null, 0)
            start()
        }
    }.getOrNull()

    private fun requestKey() {
        needKey = true
        send(JSONObject().put("t", "key"))
    }

    /** Touch on the picture, [x]/[y] in 0..1. */
    fun touch(kind: String, id: Long, x: Float, y: Float) =
        send(JSONObject().put("t", kind).put("i", id % 1_000_000).put("x", x.toDouble()).put("y", y.toDouble()))

    private fun send(j: JSONObject) {
        val o = out ?: return
        sender.execute { runCatching { o.write((j.toString() + "\n").toByteArray()); o.flush() } }
    }

    private fun loop() {
        while (synchronized(this) { surface != null }) {
            var started = false
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", PORT), 2_000)
                    s.tcpNoDelay = true
                    socket = s
                    out = s.getOutputStream()
                    started = true
                    if (status.value != null) status.value = tr("Collegamento al PC…", "Connecting to the PC…")
                    send(JSONObject().put("token", Remote.token))
                    needKey = true
                    stream(DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16)))
                }
            } catch (e: Exception) {
                // Closed by us (view gone): no error, no wait.
                if (synchronized(this) { surface == null }) break
                if (!started) {
                    status.value = tr("Collega il telefono al PC con il cavo USB (phonedash attivo)", "Connect the phone to the PC over USB (phonedash running)")
                    Thread.sleep(2_000)
                } else Thread.sleep(300)
            } finally {
                out = null; socket = null
            }
        }
        status.value = tr("Collegamento al PC…", "Connecting to the PC…")
    }

    private fun stream(input: DataInputStream) {
        var pts = 0L
        val info = MediaCodec.BufferInfo()
        while (true) {
            val type = input.readUnsignedByte()
            val len = input.readInt()
            if (len !in 0..(8 shl 20)) error("bad frame")
            val data = ByteArray(len).also { input.readFully(it) }
            when (type) {
                1 -> {
                    val j = JSONObject(String(data))
                    val w = j.getInt("w"); val h = j.getInt("h")
                    size.value = w to h
                    synchronized(this) {
                        lastSize = w to h
                        releaseCodec()
                        surface?.let { codec = newCodec(w, h, it) }
                    }
                    requestKey()
                }
                2 -> synchronized(this) {
                    val c = codec ?: return@synchronized
                    if (needKey && !hasKeyframe(data)) return@synchronized
                    needKey = false
                    try {
                        val i = c.dequeueInputBuffer(100_000)
                        if (i < 0) { requestKey(); return@synchronized } // decoder stuck: lost frame -> start again from a keyframe
                        c.getInputBuffer(i)!!.apply { clear(); put(data) }
                        pts += 33_333
                        c.queueInputBuffer(i, 0, data.size, pts, 0)
                        while (true) {
                            val o = c.dequeueOutputBuffer(info, 0)
                            if (o >= 0) { c.releaseOutputBuffer(o, true); if (status.value != null) status.value = null }
                            else if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        }
                    } catch (e: IllegalStateException) {
                        releaseCodec()
                        val sz = lastSize; val sf = surface
                        if (sz != null && sf != null) codec = newCodec(sz.first, sz.second, sf)
                        requestKey()
                    }
                }
            }
        }
    }

    /** Annex-B access unit with an SPS (7) or IDR slice (5). */
    private fun hasKeyframe(d: ByteArray): Boolean {
        var i = 0
        while (i + 3 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                val t = d[i + 3].toInt() and 0x1f
                if (t == 7 || t == 5) return true
                i += 3
            } else i++
        }
        return false
    }
}

/**
 * The PC screen. In the tab the rounded card takes the stream's aspect ratio (no black bands), centred;
 * full screen it fits the display on black. TextureView (not SurfaceView) so the rounded clip applies.
 */
@Composable
fun MirrorView(modifier: Modifier, full: Boolean) {
    val p = LocalPalette.current
    val (w, h) = Mirror.size.collectAsState().value
    val status by Mirror.status.collectAsState()
    Box(modifier.then(if (full) Modifier.background(Color.Black) else Modifier), contentAlignment = Alignment.Center) {
        Box(
            Modifier.aspectRatio(w.toFloat() / h)
                .then(if (full) Modifier else Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    android.view.TextureView(ctx).apply {
                        surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                            var sf: Surface? = null
                            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                sf = Surface(st).also { Mirror.attach(it) }
                            }
                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                sf?.let { Mirror.detach(it); it.release() }
                                sf = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            e.changes.forEach { c ->
                                val x = c.position.x / size.width
                                val y = c.position.y / size.height
                                when {
                                    c.changedToDown() -> Mirror.touch("d", c.id.value, x, y)
                                    c.changedToUp() -> Mirror.touch("u", c.id.value, x, y)
                                    c.pressed && c.positionChanged() -> Mirror.touch("m", c.id.value, x, y)
                                }
                                c.consume()
                            }
                        }
                    }
                },
            )
            status?.takeIf { it.isNotEmpty() }?.let { Text(it, style = textStyle(16, p.onSurfaceVariant, 500), textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)) }
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)).clickable { Mirror.fullscreen.value = !full },
                contentAlignment = Alignment.Center,
            ) { MIcon(if (full) Sym.FULLSCREEN_EXIT else Sym.FULLSCREEN, 24.dp, Color.White) }
        }
    }
}
