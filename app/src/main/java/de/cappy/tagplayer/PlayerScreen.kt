package de.cappy.tagplayer

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.launch

object PlayerUi { var showLyrics by mutableStateOf(false) }

@Composable
fun PlayerScreen() {
    val ctx = LocalContext.current
    val prefs = Prefs.get(ctx)
    val s = Library.byId(PlayerHub.currentId)
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(s?.id, prefs.autoLyrics) { s?.let { LyricsStore.load(ctx, it, prefs.autoLyrics) } }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        TopBar("Wiedergabe", onBack = { Navi.back() }) {
            IconButton(onClick = { ctx.startActivity(Intent(ctx, StageActivity::class.java)) }) {
                Icon(Icons.Default.Fullscreen, "Lyrics-Display", tint = Accent)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menü", tint = Accent) }
                DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                    DropdownMenuItem({ Text("Zur Playlist") }, onClick = { menu = false; s?.let { PlaylistUi.pending = listOf(it) } })
                    DropdownMenuItem({ Text("Tags bearbeiten") }, onClick = { menu = false; s?.let { Navi.go(Nav.Editor(it.id)) } })
                    DropdownMenuItem({ Text("Lyrics suchen / bearbeiten") }, onClick = { menu = false; s?.let { Navi.go(Nav.LyricsEdit(it.id)) } })
                    DropdownMenuItem({ Text("Lyrics automatisch neu suchen") }, onClick = {
                        menu = false
                        s?.let { scope.launch { LyricsStore.forget(ctx, it); LyricsStore.load(ctx, it, true, force = true) } }
                    })
                }
            }
        }
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nichts in der Wiedergabe", color = TextDim) }
            return@Column
        }
        val coverSize = (LocalConfiguration.current.screenWidthDp - 48).coerceAtMost(420).dp
        // Cover <-> Lyrics per Wischen
        val pager = rememberPagerState(initialPage = if (PlayerUi.showLyrics) 1 else 0) { 2 }
        LaunchedEffect(pager.currentPage) { PlayerUi.showLyrics = pager.currentPage == 1 }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 1) {
                val cur = if (LyricsStore.currentSongId == s.id) LyricsStore.current else null
                LyricsView(cur, LyricsStore.status, (prefs.stageFontSp * 0.7f).toInt().coerceAtLeast(22), Modifier.fillMaxSize(),
                    onEdit = { Navi.go(Nav.LyricsEdit(s.id)) })
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Cover(s, coverSize, 20.dp, px = 800) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            repeat(2) { i ->
                Box(Modifier.padding(horizontal = 4.dp).size(if (pager.currentPage == i) 8.dp else 6.dp)
                    .clip(CircleShape).background(if (pager.currentPage == i) Accent else TextDim.copy(alpha = .5f)))
            }
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(s.title.ifBlank { s.fileName }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" · "), color = TextDim, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            SeekBar()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { PlayerHub.toggleShuffle() }) {
                    Icon(Icons.Default.Shuffle, "Zufall", tint = if (PlayerHub.shuffle) Accent else TextDim)
                }
                IconButton(onClick = { PlayerHub.prev() }, Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Zurück", tint = Color.White, modifier = Modifier.size(38.dp))
                }
                FilledIconButton(onClick = { PlayerHub.toggle() }, modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Color.Black)) {
                    Icon(if (PlayerHub.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", modifier = Modifier.size(42.dp))
                }
                IconButton(onClick = { PlayerHub.next() }, Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, "Weiter", tint = Color.White, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = { PlayerHub.cycleRepeat() }) {
                    Icon(if (PlayerHub.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Wiederholen",
                        tint = if (PlayerHub.repeat == Player.REPEAT_MODE_OFF) TextDim else Accent)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(if (pager.currentPage == 1) "Cover" else "Lyrics", filled = pager.currentPage == 0, modifier = Modifier.weight(1f)) {
                    scope.launch { pager.animateScrollToPage(1 - pager.currentPage) }
                }
                Pill("Vollbild", modifier = Modifier.weight(1f)) { ctx.startActivity(Intent(ctx, StageActivity::class.java)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun SeekBar() {
    val pos = rememberPosition()
    val dur = PlayerHub.durationMs
    var drag by remember { mutableStateOf<Float?>(null) }
    val v = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Slider(
        value = v, onValueChange = { drag = it },
        onValueChangeFinished = { drag?.let { PlayerHub.seek((it * dur).toLong()) }; drag = null },
        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Surface2)
    )
    Row(Modifier.fillMaxWidth()) {
        Text(fmtTime(if (drag != null) (v * dur).toLong() else pos), color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(fmtTime(dur), color = TextDim, fontSize = 12.sp, textAlign = TextAlign.End)
    }
}
