package de.cappy.tagplayer

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Lyrics-Stand eines Titels */
enum class LState(val label: String) {
    SYNCED("synchron"), PLAIN("nur Text"), NONE("nicht gefunden"), UNKNOWN("noch nicht gesucht")
}

/**
 * Übersicht, welche Titel Lyrics haben.
 * Quellen wie bei der Anzeige: eigene Lyrics, Lyrics in der MP3, Zwischenspeicher der Online-Suche.
 * Ob eine MP3 Lyrics enthält, wird einmal gelesen und mit Änderungsdatum gemerkt (lyrics/embedded.tsv),
 * damit nicht bei jedem Start alle Dateien geöffnet werden.
 */
object LyricsIndex {
    val states = mutableStateMapOf<String, LState>()
    var scanning by mutableStateOf(false)
    var scanDone by mutableIntStateOf(0)
    var scanTotal by mutableIntStateOf(0)

    private class Emb(val mtime: Long, val code: Char)   // 'S' synchron, 'P' Text, '-' keine
    private val emb = HashMap<String, Emb>()
    private var embLoaded = false
    private var embDirty = false
    private val lock = Mutex()

    private fun embFile(c: Context) = File(File(c.filesDir, "lyrics").apply { mkdirs() }, "embedded.tsv")

    private fun loadEmb(c: Context) {
        if (embLoaded) return
        embLoaded = true
        try {
            embFile(c).takeIf { it.exists() }?.forEachLine { l ->
                val p = l.split('\t'); if (p.size == 3 && p[2].isNotEmpty()) emb[p[0]] = Emb(p[1].toLongOrNull() ?: 0, p[2][0])
            }
        } catch (_: Exception) {}
    }

    private fun saveEmb(c: Context) {
        if (!embDirty) return
        embDirty = false
        try { embFile(c).writeText(emb.entries.joinToString("") { "${it.key}\t${it.value.mtime}\t${it.value.code}\n" }) } catch (_: Exception) {}
    }

    private fun code(text: String): Char {
        val l = Lyrics.parse(text, "") ?: return '-'
        return if (l.synced) 'S' else 'P'
    }

    /** Lyrics in der MP3: aus dem Merker oder frisch gelesen (nur IO-Thread) */
    private fun embedded(c: Context, path: String, reread: Boolean): Char {
        val mt = File(path).lastModified()
        val e = emb[path]
        if (!reread && e != null && e.mtime == mt) return e.code
        val k = code(Tags.readLyrics(path))
        emb[path] = Emb(mt, k); embDirty = true
        return k
    }

    private fun compute(c: Context, s: Song, reread: Boolean = false): LState {
        LyricsStore.manualFile(c, s.path).takeIf { it.exists() }?.let {
            return if (code(it.readText()) == 'S') LState.SYNCED else LState.PLAIN
        }
        val e = embedded(c, s.path, reread)
        if (e == 'S') return LState.SYNCED
        val cf = LyricsStore.cacheFile(c, s.path)
        val cache = if (cf.exists()) cf.readText() else null
        return when {
            cache == null -> if (e == 'P') LState.PLAIN else LState.UNKNOWN
            cache == "#PLAIN" -> LState.PLAIN
            cache == "#NONE" -> if (e == 'P') LState.PLAIN else LState.NONE
            code(cache) == 'S' -> LState.SYNCED
            else -> LState.PLAIN
        }
    }

    /** Stand für diese Titel ermitteln (fehlende zuerst). Läuft im Hintergrund. */
    suspend fun scan(c: Context, songs: List<Song>) = lock.withLock {
        val app = c.applicationContext
        withContext(Dispatchers.Main) { scanning = true; scanDone = 0; scanTotal = songs.size }
        try {
            withContext(Dispatchers.IO) {
                loadEmb(app)
                val batch = HashMap<String, LState>()
                songs.forEachIndexed { i, s ->
                    ensureActive()
                    batch[s.path] = try { compute(app, s) } catch (_: Exception) { LState.UNKNOWN }
                    if (batch.size >= 50 || i == songs.lastIndex) {
                        val b = HashMap(batch); batch.clear()
                        withContext(Dispatchers.Main) { states.putAll(b); scanDone = i + 1 }
                    }
                    if (i % 300 == 299) saveEmb(app)
                }
                saveEmb(app)
            }
        } finally { withContext(NonCancellable + Dispatchers.Main) { scanning = false } }
    }

    /** Einen Titel neu bewerten (nach Suche, Speichern, Entfernen) */
    fun refresh(c: Context, s: Song) {
        val app = c.applicationContext
        AppScope.launch {
            val st = withContext(Dispatchers.IO) {
                lock.withLock { loadEmb(app); try { compute(app, s, reread = true).also { saveEmb(app) } } catch (_: Exception) { LState.UNKNOWN } }
            }
            states[s.path] = st
        }
    }

    fun of(s: Song?): LState? = s?.let { states[it.path] }
}

/**
 * Lyrics für viele Titel auf einmal suchen (Ordner, Album, Interpret, Playlist, Auswahl, alle).
 * Läuft im App-Bereich weiter, auch wenn man den Bildschirm verlässt.
 */
object LyricsBatch {
    var title by mutableStateOf("")
    val songs = mutableStateListOf<Song>()
    var retryNone by mutableStateOf(false)
    var upgradePlain by mutableStateOf(false)
    var running by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var current by mutableStateOf("")
    /** Ergebnis dieses Durchlaufs je Pfad */
    val result = mutableStateMapOf<String, LState>()
    private var job: Job? = null

    fun prepare(label: String, list: List<Song>) {
        if (running) return
        title = label
        songs.clear(); songs.addAll(list)
        result.clear(); done = 0; total = 0
    }

    fun wanted(s: Song): Boolean = when (LyricsIndex.of(s)) {
        LState.UNKNOWN -> true
        LState.NONE -> retryNone
        LState.PLAIN -> upgradePlain
        LState.SYNCED -> false
        null -> false
    }

    fun start(c: Context) {
        if (running) return
        val app = c.applicationContext
        val todo = songs.filter { wanted(it) }
        if (todo.isEmpty()) return
        running = true; done = 0; total = todo.size; result.clear()
        Diag.log(app, "Lyrics-Suche gestartet: $title (${todo.size} Titel, erneut bei nicht gefunden: $retryNone, nur Text verbessern: $upgradePlain)")
        job = AppScope.launch {
            try {
                for (s in todo) {
                    current = s.title.ifBlank { s.fileName }
                    val st = try { LyricsStore.searchFor(app, s) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        Diag.log(app, "Lyrics-Suche Fehler: ${s.path}: ${e.message}"); null
                    }
                    if (st != null) { result[s.path] = st; LyricsIndex.states[s.path] = st }
                    done++
                    delay(400)   // Quellen schonen
                }
            } finally {
                withContext(NonCancellable) {
                    running = false; current = ""
                    Diag.log(app, "Lyrics-Suche beendet: $done/$total · " + LState.entries.joinToString { "${it.label} ${result.values.count { v -> v == it }}" })
                }
            }
        }
    }

    fun stop() { job?.cancel() }
}

/** Kleines Lyrics-Zeichen für Listen: synchron (Akzent), nur Text (weiß), nicht gefunden (rot), sonst nichts */
@Composable
fun LyricsBadge(s: Song?, size: Dp = 16.dp) {
    if (!Prefs.get(LocalContext.current).lyricsBadges) return
    val st = LyricsIndex.of(s) ?: return
    val tint = when (st) {
        LState.SYNCED -> Accent
        LState.PLAIN -> Color.White.copy(alpha = .75f)
        LState.NONE -> Bad.copy(alpha = .8f)
        LState.UNKNOWN -> return
    }
    Icon(Icons.Default.Lyrics, "Lyrics: ${st.label}", tint = tint, modifier = Modifier.padding(start = 6.dp).size(size))
}

@Composable
fun LyricsBatchScreen() {
    val ctx = LocalContext.current
    val songs = LyricsBatch.songs
    var filter by remember { mutableStateOf<LState?>(null) }
    // Stand der gewählten Titel sicher ermitteln (falls die Hintergrundprüfung noch nicht durch ist)
    LaunchedEffect(songs.size) { if (songs.any { LyricsIndex.of(it) == null }) LyricsIndex.scan(ctx, songs.toList()) }
    val counts = LState.entries.associateWith { st -> songs.count { LyricsIndex.of(it) == st } }
    val unknownYet = songs.count { LyricsIndex.of(it) == null }
    val todo = songs.count { LyricsBatch.wanted(it) }
    val list = if (filter == null) songs.toList() else songs.filter { LyricsIndex.of(it) == filter }

    Column(Modifier.fillMaxSize()) {
        TopBar("Lyrics", onBack = { Navi.back() })
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(LyricsBatch.title, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(plural(songs.size, "Titel", "Titel"), color = TextDim, fontSize = 12.sp)
                    if (unknownYet > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("Prüfe vorhandene Lyrics … noch $unknownYet", color = AccentSoft, fontSize = 12.sp)
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Surface2)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChipX("Alle", filter == null) { filter = null }
                        LState.entries.forEach { st -> FilterChipX("${st.label.replaceFirstChar { it.uppercase() }} ${counts[st] ?: 0}", filter == st) { filter = st } }
                    }
                    Spacer(Modifier.height(6.dp))
                    SwitchRow("„Nicht gefunden“ erneut versuchen", "Sonst werden nur Titel gesucht, die noch nie gesucht wurden",
                        LyricsBatch.retryNone) { LyricsBatch.retryNone = it }
                    SwitchRow("„Nur Text“ nach synchronen Lyrics suchen", "Damit die Zeilen mitlaufen (Hervorhebung)",
                        LyricsBatch.upgradePlain) { LyricsBatch.upgradePlain = it }
                    Spacer(Modifier.height(6.dp))
                    if (LyricsBatch.running) {
                        Text("${LyricsBatch.done} / ${LyricsBatch.total} · ${LyricsBatch.current}", color = TextDim, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = { LyricsBatch.done.toFloat() / LyricsBatch.total.coerceAtLeast(1) }, Modifier.fillMaxWidth(),
                            color = Accent, trackColor = Surface2, drawStopIndicator = {})
                        Spacer(Modifier.height(8.dp))
                        Pill("Anhalten", modifier = Modifier.fillMaxWidth()) { LyricsBatch.stop() }
                    } else {
                        Pill(if (todo == 0) "Nichts zu suchen" else "Lyrics suchen (${plural(todo, "Titel", "Titel")})", filled = true,
                            enabled = todo > 0 && unknownYet == 0, modifier = Modifier.fillMaxWidth()) { LyricsBatch.start(ctx) }
                        if (LyricsBatch.total > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text("Letzte Suche: " + LState.entries.filter { it != LState.UNKNOWN }
                                .joinToString(" · ") { st -> "${LyricsBatch.result.values.count { it == st }} ${st.label}" },
                                color = TextDim, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(list, key = { it.id }) { s -> LyricsBatchRow(s) }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun LyricsBatchRow(s: Song) {
    val st = LyricsIndex.of(s)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(s, 38.dp, 6.dp, px = 120)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title.ifBlank { s.fileName }, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(st?.label ?: "…", fontSize = 12.sp, color = when (st) {
            LState.SYNCED -> Accent; LState.PLAIN -> Color.White; LState.NONE -> Bad; else -> TextDim
        })
        TextButton(onClick = { Navi.go(Nav.LyricsEdit(s.id)) }) { Text("Bearbeiten", color = Accent, fontSize = 12.sp) }
    }
}
