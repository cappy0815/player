package de.cappy.tagplayer

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*

enum class MatchState { WAIT, RUNNING, APPLIED, REVIEW, NONE, SAME, ERROR }

/** Was geprüft wird: alle Tags oder nur das Albumcover */
enum class MatchMode(val label: String) { TAGS("Tags"), COVER("Cover") }

class MatchItem(val song: Song) {
    var state by mutableStateOf(MatchState.WAIT)
    var candidates by mutableStateOf<List<Candidate>>(emptyList())
    var info by mutableStateOf("")
}

/**
 * Online-Abgleich mehrerer Titel (nur auf Knopfdruck).
 * Tags: Treffer ab der eingestellten Quote werden automatisch gespeichert,
 *       alles darunter landet in "Prüfen" und wird nicht verändert.
 * Cover: Titel ohne Cover (oder alle, wenn "ersetzen" an) bekommen das Cover der
 *        gefundenen Veröffentlichung. Pro Album wird nur einmal gesucht.
 */
object Matcher {
    val items = mutableStateListOf<MatchItem>()
    var running by mutableStateOf(false)
    var autoApply by mutableStateOf(true)
    var replaceCovers by mutableStateOf(false)
    var mode by mutableStateOf(MatchMode.TAGS)
    var title by mutableStateOf("")
    private var job: Job? = null

    fun prepare(label: String, songs: List<Song>, m: MatchMode = MatchMode.TAGS) {
        if (running) return
        title = label
        mode = m
        items.clear(); items.addAll(songs.map { MatchItem(it) })
    }

    fun start(ctx: Context, scope: CoroutineScope) {
        if (running || items.isEmpty()) return
        running = true
        val app = ctx.applicationContext
        job = scope.launch {
            val changed = ArrayList<String>()
            try {
                if (mode == MatchMode.TAGS) runTags(app, changed) else runCovers(app, changed)
            } finally {
                withContext(NonCancellable) {
                    items.filter { it.state == MatchState.RUNNING }.forEach { it.state = MatchState.WAIT }
                    if (changed.isNotEmpty()) { Library.rescan(app, changed); Library.load(app) }
                    running = false
                }
            }
        }
    }

    private suspend fun runTags(ctx: Context, changed: MutableList<String>) {
        val prefs = Prefs.get(ctx)
        val coverCache = HashMap<String, ByteArray?>()   // Cover einmal pro Veröffentlichung
        for (it in items) {
            if (it.state != MatchState.WAIT) continue
            currentCoroutineContext().ensureActive()
            it.state = MatchState.RUNNING
            try {
                val old = withContext(Dispatchers.IO) { Tags.read(it.song.path) }
                val cands = TagSearch.search(it.song, old, prefs.threshold, thorough = false)
                it.candidates = cands
                var best = cands.firstOrNull()
                when {
                    best == null -> { it.state = MatchState.NONE; it.info = "Kein Treffer" }
                    best.confidence < prefs.threshold || !autoApply -> {
                        it.state = MatchState.REVIEW; it.info = "${best.confidence} %  ·  ${best.artist} – ${best.title}  ·  ${best.source}"
                    }
                    else -> {
                        best = TagSearch.enrich(best)
                        val wantCover = prefs.coverOnline && !(prefs.keepExistingCover && old.cover != null)
                        // Cover: zuerst vom Treffer selbst, sonst aus einem anderen sicheren Treffer zum selben Album
                        val cover = if (!wantCover) null else coverCache.getOrPut(best.source + best.releaseId) {
                            Covers.forCandidate(best) ?: Covers.firstAvailable(
                                cands.filter { c -> c !== best && norm(c.album) == norm(best.album) }, prefs.threshold)?.second
                        }
                        val neu = old.applyCandidate(best, cover)
                        val diff = neu.diff(old)
                        if (diff.isEmpty()) {
                            it.state = MatchState.SAME; it.info = "${best.confidence} %  ·  schon korrekt"
                        } else {
                            withContext(Dispatchers.IO) { Tags.write(it.song.path, neu, writeCover = cover != null) }
                            changed += it.song.path
                            it.state = MatchState.APPLIED
                            it.info = "${best.confidence} %  ·  ${best.source}  ·  ${diff.joinToString { d -> d.first }}"
                        }
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { it.state = MatchState.ERROR; it.info = e.message ?: e.javaClass.simpleName }
        }
    }

    private suspend fun runCovers(ctx: Context, changed: MutableList<String>) {
        val prefs = Prefs.get(ctx)
        // 1. Welche Titel brauchen ein Cover?
        val todo = ArrayList<MatchItem>()
        for (it in items) {
            if (it.state != MatchState.WAIT) continue
            val has = withContext(Dispatchers.IO) { try { Tags.read(it.song.path).cover != null } catch (_: Exception) { false } }
            if (has && !replaceCovers) { it.state = MatchState.SAME; it.info = "Cover vorhanden" } else todo += it
        }
        // 2. Gruppieren: aussagekräftiger Ordner = ein Album, sonst nach MediaStore-Album
        data class G(val hint: AlbumHint?, val items: MutableList<MatchItem>)
        val groups = LinkedHashMap<String, G>()
        for (it in todo) {
            val tag = withContext(Dispatchers.IO) { try { Tags.read(it.song.path) } catch (_: Exception) { null } }
            val h = AlbumHints.forSong(it.song, tag)
            val key = if (h?.fromFolder == true) "f:" + (AlbumHints.albumDir(it.song)?.path ?: "") else "a:${it.song.albumId}"
            groups.getOrPut(key) { G(h, ArrayList()) }.items += it
        }
        for ((key, g) in groups) {
            val group = g.items
            currentCoroutineContext().ensureActive()
            group.forEach { it.state = MatchState.RUNNING }
            try {
                val first = group.first()
                // a) Album-Suche über den Ordnernamen bzw. das Album-Tag
                val files = if (key.startsWith("f:")) java.io.File(key.removePrefix("f:")).walkTopDown().maxDepth(2)
                    .count { f -> f.isFile && f.extension.lowercase() == "mp3" } else 0
                var cands = if (g.hint != null) AlbumSearch.search(g.hint, files, prefs.threshold, thorough = false) else emptyList()
                // b) zusätzlich über den Titel, falls die Album-Suche nichts Sicheres findet
                if ((cands.firstOrNull()?.confidence ?: 0) < prefs.threshold) {
                    val old = withContext(Dispatchers.IO) { Tags.read(first.song.path) }
                    cands = (cands + TagSearch.search(first.song, old, prefs.threshold, thorough = true))
                        .sortedByDescending { it.confidence }
                        .distinctBy { it.source + ":" + it.releaseId }
                }
                group.forEach { it.candidates = cands }
                val top = cands.firstOrNull()
                val via = if (g.hint?.fromFolder == true) "Ordner „${g.hint.label}“" else "Album-Tag"
                if (top == null) { group.forEach { it.state = MatchState.NONE; it.info = "Kein Treffer ($via)" }; continue }
                if (top.confidence < prefs.threshold || !autoApply) {
                    group.forEach { it.state = MatchState.REVIEW; it.info = "${top.confidence} %  ·  ${top.album} (${top.artist})  ·  ${top.source}  ·  $via" }
                    continue
                }
                // erstes sicheres Ergebnis, zu dem es auch wirklich ein Bild gibt
                val found = Covers.firstAvailable(cands, prefs.threshold)
                if (found == null) { group.forEach { it.state = MatchState.NONE; it.info = "Kein Bild zu „${top.album}“ gefunden ($via)" }; continue }
                val (best, cover) = found
                for (it in group) {
                    try {
                        withContext(Dispatchers.IO) {
                            val t = Tags.read(it.song.path)
                            Tags.write(it.song.path, t.copy(cover = cover, coverMime = "image/jpeg"), writeCover = true)
                        }
                        changed += it.song.path
                        it.state = MatchState.APPLIED
                        it.info = "${best.confidence} %  ·  Cover von „${best.album}“ (${best.source})  ·  $via"
                    } catch (e: Exception) { it.state = MatchState.ERROR; it.info = e.message ?: e.javaClass.simpleName }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { group.forEach { it.state = MatchState.ERROR; it.info = e.message ?: e.javaClass.simpleName } }
        }
    }

    fun stop() { job?.cancel() }

    /** Alle Einträge zurücksetzen, um sie noch einmal zu prüfen */
    fun reset() {
        if (running) return
        items.forEach { it.state = MatchState.WAIT; it.info = ""; it.candidates = emptyList() }
    }

    val counts: Map<MatchState, Int> get() = items.groupingBy { it.state }.eachCount()
}
