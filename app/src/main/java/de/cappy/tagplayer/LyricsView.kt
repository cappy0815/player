package de.cappy.tagplayer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Aktuelle Abspielposition, 10x pro Sekunde */
@Composable
fun rememberPosition(): Long {
    var pos by remember { mutableLongStateOf(PlayerHub.position()) }
    LaunchedEffect(Unit) { while (true) { pos = PlayerHub.position(); delay(100) } }
    return pos
}

/**
 * Lyrics groß mit Highlighting der Stelle, an der das Lied gerade ist.
 * - Synchronisierte Lyrics (LRC): exakt nach Zeitstempel.
 * - Text ohne Zeitangaben: Stelle geschätzt aus Position/Länge, unten als "geschätzt" markiert.
 * Die aktuelle Zeile wird farbig hinterlegt, vergrößert und bei ca. 40 % der Höhe gehalten.
 * Scrollt man selbst, folgt die Anzeige 4 s lang nicht automatisch.
 */
@Composable
fun LyricsView(
    lyrics: Lyrics?,
    status: String,
    fontSp: Int,
    modifier: Modifier = Modifier,
    dim: Float = 1f,
    seekOnTap: Boolean = true
) {
    if (lyrics == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(status.ifBlank { "Keine Lyrics" }, color = TextDim.copy(alpha = dim), fontSize = 18.sp, textAlign = TextAlign.Center)
        }
        return
    }
    val pos = rememberPosition()
    // 250 ms Vorlauf, damit die Zeile nicht hinterherhängt
    val idx = lyrics.indexAt(pos + 250, PlayerHub.durationMs)
    val state = rememberLazyListState()
    val density = LocalDensity.current
    var viewH by remember { mutableIntStateOf(0) }

    // Eigenes Scrollen kurz respektieren
    val dragged by state.interactionSource.collectIsDraggedAsState()
    var userScrollAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) { if (dragged) userScrollAt = System.currentTimeMillis() }

    LaunchedEffect(idx, viewH, lyrics) {
        if (idx < 0 || viewH <= 0) return@LaunchedEffect
        if (System.currentTimeMillis() - userScrollAt < 4000) return@LaunchedEffect
        val item = idx + 1                          // Element 0 ist der Abstandshalter oben
        var info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == item }
        if (info == null) {                         // Zeile nicht sichtbar -> erst hinspringen
            state.scrollToItem(item)
            withFrameNanos { }                      // auf das neue Layout warten
            info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == item }
        }
        if (info != null) {
            val target = viewH * 0.40f
            val delta = info.offset + info.size / 2f - target
            if (kotlin.math.abs(delta) > 2f) state.animateScrollBy(delta, tween(450))
        }
    }

    Column(modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewH = it.height },
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { Spacer(Modifier.height(with(density) { (viewH * 0.40f).toDp() })) }
            itemsIndexed(lyrics.lines) { i, line ->
                val active = i == idx
                val alpha = when { idx < 0 -> .75f; active -> 1f; i < idx -> .28f; else -> .55f } * dim
                val col by animateColorAsState((if (active) Accent else Color.White).copy(alpha = alpha), tween(250), label = "lc")
                val bg by animateColorAsState(if (active) Accent.copy(alpha = .14f * dim) else Color.Transparent, tween(250), label = "lb")
                val scale by animateFloatAsState(if (active) 1.08f else 1f, tween(250), label = "ls")
                val text = line.text.ifBlank { if (lyrics.synced) "♪" else "" }
                Text(
                    text,
                    color = col,
                    fontSize = fontSp.sp,
                    lineHeight = (fontSp * 1.25f).sp,
                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(RoundedCornerShape(14.dp))
                        .background(bg)
                        .then(if (lyrics.synced && seekOnTap) Modifier.clickable { userScrollAt = 0; PlayerHub.seek(line.timeMs) } else Modifier)
                        .padding(horizontal = 10.dp, vertical = (fontSp / 6).dp)
                )
            }
            item { Spacer(Modifier.height(with(density) { (viewH * 0.55f).toDp() })) }
        }
        Text(
            "Quelle: ${lyrics.source}" + if (!lyrics.synced) " · ohne Zeitangaben, Stelle geschätzt" else "",
            color = TextDim.copy(alpha = .7f * dim), fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
    }
}
