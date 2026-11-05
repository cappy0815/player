package de.cappy.tagplayer

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------- Hilfen für LRC ----------
fun lrcStamp(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return "[%02d:%02d.%02d]".format(t / 60_000, (t / 1000) % 60, (t % 1000) / 10)
}

fun Lyrics.toLrc(): String =
    if (synced) lines.joinToString("\n") { lrcStamp(it.timeMs) + it.text } else lines.joinToString("\n") { it.text }

/** Alle Zeitstempel um [deltaMs] verschieben */
fun shiftLrc(text: String, deltaMs: Long): String {
    val l = Lyrics.parse(text, "") ?: return text
    if (!l.synced) return text
    return l.copy(lines = l.lines.map { it.copy(timeMs = (it.timeMs + deltaMs).coerceAtLeast(0)) }).toLrc()
}

/** Zeilen ohne Zeitstempel (Vorlage für das Synchronisieren) */
fun plainLines(text: String): List<String> {
    val l = Lyrics.parse(text, "") ?: return emptyList()
    return l.lines.map { it.text.trim() }.filter { it.isNotEmpty() && it != "♪" }
}

/** Übergabe des Textes an den Synchronisier-Bildschirm */
object SyncUi { var text by mutableStateOf("") }

// ---------- Lyrics suchen & bearbeiten ----------
@Composable
fun LyricsEditScreen(songId: Long) {
    val ctx = LocalContext.current
    val song = remember(songId) { Library.byId(songId) }
    val scope = rememberCoroutineScope()
    if (song == null) { TopBar("Lyrics", onBack = { Navi.back() }); return }

    val terms = remember(songId) { MusicBrainz.searchTerms(song, null) }
    var artist by remember { mutableStateOf(terms.second) }
    var title by remember { mutableStateOf(cleanTitle(terms.first)) }
    var hits by remember { mutableStateOf<List<LyricsHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("eigene") }
    var loaded by remember { mutableStateOf(false) }
    val hasManual = remember(loaded) { LyricsStore.hasManual(ctx, song) }

    LaunchedEffect(songId) { text = LyricsStore.rawFor(ctx, song); loaded = true }
    val parsed = remember(text) { Lyrics.parse(text, "") }

    Column(Modifier.fillMaxSize()) {
        TopBar("Lyrics", onBack = { Navi.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding()) {
            Text("${song.artist} – ${song.title.ifBlank { song.fileName }}", color = TextDim, fontSize = 13.sp)

            SectionTitle("Online suchen")
            Text("Bei Live-Aufnahmen hilft oft der reine Titel ohne „(Live)“. Gesucht wird in LRCLIB und lyrics.ovh.",
                color = TextDim, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(6.dp))
            Field("Interpret", artist) { artist = it }
            Field("Titel", title) { title = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(if (searching) "Suche …" else "Suchen", filled = true, enabled = !searching && title.isNotBlank()) {
                    searching = true
                    scope.launch { hits = LyricsSearch.manual(artist.trim(), title.trim()); searching = false }
                }
                if (searching) { Spacer(Modifier.width(10.dp)); CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp) }
            }
            hits?.let { list ->
                Spacer(Modifier.height(8.dp))
                if (list.isEmpty()) Text("Nichts gefunden. Anderen Titel versuchen oder den Text unten selbst einfügen.", color = TextDim, fontSize = 13.sp)
                list.forEach { h ->
                    HitRow(h, song.durationMs) {
                        text = h.text; source = h.source + if (h.hasSync) " (synchron)" else ""
                        hits = null
                        Toast.makeText(ctx, if (h.hasSync) "Übernommen – mit Zeitangaben. Speichern nicht vergessen." else "Übernommen – ohne Zeitangaben. Jetzt speichern oder synchronisieren.", Toast.LENGTH_LONG).show()
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            SectionTitle("Text")
            Text(when {
                parsed == null -> "Leer. Text hier einfügen (eine Zeile pro Textzeile), dann speichern oder synchronisieren."
                parsed.synced -> "Mit Zeitangaben (${parsed.lines.size} Zeilen) – die Zeile läuft beim Abspielen mit."
                else -> "Ohne Zeitangaben (${parsed.lines.count { it.text.isNotBlank() }} Zeilen). Mit „Synchronisieren“ setzt du die Zeiten selbst."
            }, color = if (parsed?.synced == true) Good else TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(text, { text = it; source = "eigene" }, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = if (parsed?.synced == true) FontFamily.Monospace else FontFamily.Default),
                placeholder = { Text("Liedtext einfügen …") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent))

            if (parsed?.synced == true) {
                Spacer(Modifier.height(10.dp))
                Text("Zeitversatz: Text kommt zu früh oder zu spät?", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("−0,5 s", modifier = Modifier.weight(1f)) { text = shiftLrc(text, -500) }
                    Pill("−0,1 s", modifier = Modifier.weight(1f)) { text = shiftLrc(text, -100) }
                    Pill("+0,1 s", modifier = Modifier.weight(1f)) { text = shiftLrc(text, 100) }
                    Pill("+0,5 s", modifier = Modifier.weight(1f)) { text = shiftLrc(text, 500) }
                }
                Text("Minus = Text erscheint früher, Plus = später.", color = TextDim, fontSize = 12.sp)
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Speichern", filled = true, enabled = parsed != null, modifier = Modifier.weight(1f)) {
                    scope.launch {
                        LyricsStore.setManual(ctx, song, text.trim(), source)
                        Toast.makeText(ctx, "Lyrics gespeichert", Toast.LENGTH_SHORT).show()
                        Navi.back()
                    }
                }
                Pill("Synchronisieren", enabled = plainLines(text).isNotEmpty(), modifier = Modifier.weight(1f)) {
                    SyncUi.text = text
                    Navi.go(Nav.LyricsSync(song.id))
                }
            }
            if (hasManual) {
                Spacer(Modifier.height(10.dp))
                Pill("Eigene Lyrics entfernen", modifier = Modifier.fillMaxWidth()) {
                    LyricsStore.clearManual(ctx, song)
                    scope.launch { LyricsStore.load(ctx, song, Prefs.get(ctx).autoLyrics, force = true) }
                    Toast.makeText(ctx, "Entfernt – es gilt wieder die automatische Suche", Toast.LENGTH_SHORT).show()
                    Navi.back()
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun HitRow(h: LyricsHit, songMs: Long, onPick: () -> Unit) {
    val diff = if (h.durationSec > 0 && songMs > 0) (h.durationSec - songMs / 1000.0) else null
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface)
            .border(1.dp, (if (h.hasSync) Good else TextDim).copy(alpha = .35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onPick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("${h.artist} – ${h.title}", color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(h.source, h.album.ifBlank { null },
                if (h.durationSec > 0) fmtTime((h.durationSec * 1000).toLong()) else null,
                diff?.let { if (kotlin.math.abs(it) <= 3) "Länge passt" else "%+.0f s".format(it) }).joinToString(" · "),
                color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(h.text.lineSequence().map { it.replace(Regex("\\[[0-9:.]+]"), "").trim() }.firstOrNull { it.isNotBlank() } ?: "",
                color = TextDim.copy(alpha = .8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (h.hasSync) "synchron" else "nur Text", color = if (h.hasSync) Good else TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------- Synchronisieren: Zeiten per Tippen setzen ----------
@Composable
fun LyricsSyncScreen(songId: Long) {
    val ctx = LocalContext.current
    val song = remember(songId) { Library.byId(songId) }
    val scope = rememberCoroutineScope()
    if (song == null) { TopBar("Synchronisieren", onBack = { Navi.back() }); return }
    val lines = remember { plainLines(SyncUi.text) }
    val times = remember { mutableStateListOf<Long>() }
    var started by remember { mutableStateOf(false) }
    val pos = rememberPosition()
    val listState = rememberLazyListState()
    val done = times.size >= lines.size

    // Titel abspielen, falls ein anderer läuft
    fun ensurePlaying(fromMs: Long) {
        if (PlayerHub.currentId != song.id) PlayerHub.play(listOf(song), 0)
        PlayerHub.seek(fromMs)
        if (!PlayerHub.isPlaying) PlayerHub.toggle()
    }

    LaunchedEffect(times.size) { if (times.size > 0) listState.animateScrollToItem((times.size - 2).coerceAtLeast(0)) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Synchronisieren", onBack = { Navi.back() })
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(if (!started) "Start spielt den Titel von vorn. Tippe dann jedes Mal auf den großen Knopf, wenn die markierte Zeile gesungen wird."
                else if (!done) "Zeile ${times.size + 1} von ${lines.size}  ·  ${fmtTime(pos)}"
                else "Fertig. Speichern – oder mit „Zurück“ einzelne Zeilen korrigieren.",
                color = if (done) Good else TextDim, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            itemsIndexed(lines) { i, l ->
                val next = i == times.size
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (next && started) Accent.copy(alpha = .16f) else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (i < times.size) fmtTime(times[i]) + "." + ((times[i] % 1000) / 100) else "–",
                        color = if (i < times.size) Good else TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(56.dp))
                    Text(l, color = when { next -> Accent; i < times.size -> Color.White.copy(alpha = .6f); else -> Color.White },
                        fontSize = if (next) 18.sp else 15.sp, fontWeight = if (next) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(Surface).padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
            if (!started) {
                Pill("Start", filled = true, modifier = Modifier.fillMaxWidth()) { times.clear(); started = true; ensurePlaying(0) }
            } else {
                Text(
                    if (done) "Alle Zeilen gesetzt" else "Jetzt: ${lines[times.size]}",
                    color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (done) Good else Accent)
                        .clickable(enabled = !done) { times.add(PlayerHub.position()) }
                        .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 12.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Zurück", enabled = times.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        // letzte Zeit verwerfen und 3 s vor die vorherige Zeile springen
                        times.removeAt(times.lastIndex)
                        ensurePlaying(((times.lastOrNull() ?: 0L) - 3000).coerceAtLeast(0))
                    }
                    Pill(if (PlayerHub.isPlaying) "Pause" else "Weiter", modifier = Modifier.weight(1f)) { PlayerHub.toggle() }
                    Pill("Neu", modifier = Modifier.weight(1f)) { times.clear(); ensurePlaying(0) }
                }
                Spacer(Modifier.height(10.dp))
                Pill("Speichern", filled = true, enabled = done, modifier = Modifier.fillMaxWidth()) {
                    // 150 ms Reaktionszeit ausgleichen
                    val lrc = lines.indices.joinToString("\n") { lrcStamp((times[it] - 150).coerceAtLeast(0)) + lines[it] }
                    scope.launch {
                        LyricsStore.setManual(ctx, song, lrc, "eigene (synchron)")
                        Toast.makeText(ctx, "Synchronisierte Lyrics gespeichert", Toast.LENGTH_SHORT).show()
                        Navi.back(); if (Navi.top is Nav.LyricsEdit) Navi.back()
                    }
                }
            }
        }
    }
}
