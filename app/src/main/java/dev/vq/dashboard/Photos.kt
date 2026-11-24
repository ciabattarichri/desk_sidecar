package dev.vq.dashboard

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Photos pushed from the PC, stored in app-private storage. */
object PhotoStore {
    val version = MutableStateFlow(0)
    private val NAME = Regex("^[A-Za-z0-9_-]{1,64}\\.jpg$")

    private fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }
    fun validName(name: String) = NAME.matches(name)
    fun names(context: Context): List<String> = dir(context).list()?.filter(::validName)?.sorted() ?: emptyList()
    fun files(context: Context): List<File> = names(context).map { File(dir(context), it) }

    fun save(context: Context, name: String, bytes: ByteArray) {
        val tmp = File(dir(context), "$name.part")
        tmp.writeBytes(bytes)
        tmp.renameTo(File(dir(context), name))
        version.value++
    }

    fun delete(context: Context, name: String) {
        File(dir(context), name).delete()
        version.value++
    }
}

/** Shuffled photos with a slow cross-fade. */
@Composable
fun Slideshow(intervalSec: Int, modifier: Modifier = Modifier, kenBurns: Boolean = false) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val version by PhotoStore.version.collectAsState()
    val files = remember(version) { PhotoStore.files(context).shuffled() }
    var current by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(files, intervalSec) {
        var i = 0
        while (files.isNotEmpty()) {
            val f = files[i % files.size]
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
            }?.let { current = it }
            i++
            delay(intervalSec * 1000L)
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        if (files.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Nessuna foto", "No photos"), style = textStyle(16, p.onSurface, 500))
                Text(tr("Arrivano dal PC con phonedash", "They come from the PC via phonedash"), style = textStyle(14, p.onSurfaceVariant, 400))
            }
        } else {
            Crossfade(current, animationSpec = tween(1500), label = "slideshow") { img ->
                if (img != null) {
                    // Ken Burns: slow zoom + drift, direction varies per photo.
                    val zoom = remember(img) { Animatable(1f) }
                    if (kenBurns) LaunchedEffect(img) {
                        zoom.animateTo(1.12f, tween(intervalSec * 1000 + 1500, easing = LinearEasing))
                    }
                    val dir = if (img.hashCode() % 2 == 0) 1f else -1f
                    Image(
                        img, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom.value
                            scaleY = zoom.value
                            translationX = (zoom.value - 1f) * size.width * 0.4f * dir
                        },
                    )
                }
            }
        }
    }
}
