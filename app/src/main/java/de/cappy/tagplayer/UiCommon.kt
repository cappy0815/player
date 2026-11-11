package de.cappy.tagplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Läuft unabhängig von einzelnen Seiten (Abgleich, Speichern) */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * Cover direkt aus der Datei (eingebettetes Bild). Der MediaStore-Vorschaucache wird nach
 * Tag-Änderungen nicht zuverlässig erneuert, deshalb nur als Rückfallebene.
 */
object CoverCache {
    private val cache = LruCache<String, Bitmap>(120)
    private val none = HashSet<String>()
    suspend fun get(ctx: android.content.Context, s: Song, px: Int): Bitmap? {
        val k = "${s.path}:${java.io.File(s.path).lastModified()}:$px"
        cache.get(k)?.let { return it }
        if (k in none) return null
        val b = withContext(Dispatchers.IO) { embedded(s.path, px) }
        if (b != null) cache.put(k, b) else none += k
        return b
    }

    private fun embedded(path: String, px: Int): Bitmap? = try {
        val r = android.media.MediaMetadataRetriever()
        val data = try { r.setDataSource(path); r.embeddedPicture } finally { r.release() }
        if (data == null) null else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Exception) { null }
}

@Composable
fun Cover(song: Song?, size: Dp, corner: Dp = 10.dp, px: Int = 200, bytes: ByteArray? = null) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, song?.path, song?.modified, Library.version, bytes) {
        value = when {
            bytes != null -> withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            song != null -> CoverCache.get(ctx, song, px)
            else -> null
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(Surface2),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = TextDim, modifier = Modifier.size(size / 2.5f))
    }
}

@Composable
fun Pill(text: String, filled: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        color = when { !enabled -> TextDim; filled -> Color.Black; else -> Accent },
        fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(50))
            .background(if (filled && enabled) Accent else Color.Transparent)
            .border(1.dp, (if (enabled) Accent else TextDim).copy(alpha = if (filled) 1f else .5f), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    )
}

@Composable
fun SectionTitle(t: String) {
    Spacer(Modifier.height(18.dp))
    Text(t.uppercase(), color = Accent, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
}

@Composable
fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(16.dp),
        content = content
    )
}

@Composable
fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 15.sp)
            if (hint != null) Text(hint, color = TextDim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = Color.Black, checkedTrackColor = Accent,
            uncheckedThumbColor = TextDim, uncheckedTrackColor = Surface2, uncheckedBorderColor = TextDim.copy(alpha = .4f)))
    }
}

@Composable
fun ValueSlider(label: String, value: Int, range: IntRange, unit: String, step: Int = 1, valueText: String? = null, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(valueText ?: "$value $unit", color = AccentSoft, fontSize = 15.sp)
    }
    Slider(
        value = value.toFloat(), onValueChange = { onChange(((it - range.first) / step).roundToInt() * step + range.first) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = if (step > 1) (range.last - range.first) / step - 1 else 0,
        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Surface2)
    )
}

val darkColors = darkColorScheme(
    primary = Accent, onPrimary = Color.Black, background = Bg, surface = Surface,
    onSurface = Color.White, onBackground = Color.White, surfaceVariant = Surface2,
    onSurfaceVariant = TextDim, secondary = AccentSoft, surfaceContainer = Surface,
    surfaceContainerHigh = Surface2, outline = TextDim
)

/**
 * Merkt sich die Scroll-Position jeder Liste (Schlüssel z. B. "folder:/storage/…/Rock").
 * Beim Zurückkehren aus einem Ordner, Album oder Menü steht die Liste wieder dort,
 * wo man hineingetippt hat – statt wieder bei A.
 */
object ScrollMemory {
    private val pos = HashMap<String, Pair<Int, Int>>()
    fun get(key: String) = pos[key] ?: (0 to 0)
    fun put(key: String, index: Int, offset: Int) { pos[key] = index to offset }
}

@Composable
fun rememberListState(key: String): androidx.compose.foundation.lazy.LazyListState {
    val (i, o) = remember(key) { ScrollMemory.get(key) }
    val st = androidx.compose.runtime.saveable.rememberSaveable(key, saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
        androidx.compose.foundation.lazy.LazyListState(i, o)
    }
    DisposableEffect(key, st) { onDispose { ScrollMemory.put(key, st.firstVisibleItemIndex, st.firstVisibleItemScrollOffset) } }
    return st
}
