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
 * Ordnerregel: In einem Album-Ordner haben alle Dateien dasselbe Album und dasselbe Cover.
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
        Diag.log(app, "Abgleich ${mode.label} gestartet: $title (${items.size} Titel, Schwelle ${Prefs.get(app).threshold} %, automatisch $autoApply, ersetzen $replaceCovers)")
        job = scope.launch {
            val changed = ArrayList<String>()
            try {
                if (mode == MatchMode.TAGS) runTags(app, changed) else runCovers(app, changed)
            } finally {
                withContext(NonCancellable) {
                    items.filter { it.state == MatchState.RUNNING }.forEach { it.state = MatchState.WAIT }
                    if (changed.isNotEmpty()) { Library.rescan(app, changed); Library.load(app) }
                    running = false
                    Diag.log(app, "Abgleich beendet: " + counts.entries.joinToString { "${it.key} ${it.value}" })
                }
            }
        }
    }

    private fun albumMatches(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
    }

    /** Alle Titel eines Album-Ordners in der Bibliothek (auch die nicht ausgewählten) */
    private fun folderSongs(dir: String): List<Song> =
        Library.songs.filter { AlbumHints.albumDir(it)?.path == dir }

    /** Cover-Prüfsummen eines Ordners: einheitlich? häufigstes Cover? */
    private class FolderCovers(val md5s: Map<String, String>) {
        val withCover get() = md5s.values.filter { it.isNotEmpty() }
        val consistent get() = md5s.isNotEmpty() && md5s.values.all { it.isNotEmpty() } && md5s.values.toSet().size == 1
        /** häufigstes vorhandenes Cover (Pfad einer Datei damit), wenn es eindeutig die Mehrheit hat */
        val majorityPath: String? get() {
            val counts = withCover.groupingBy { it }.eachCount()
            val top = counts.maxByOrNull { it.value } ?: return null
            if (counts.count { it.value == top.value } > 1) return null    // Gleichstand: unklar
            return md5s.entries.first { it.value == top.key }.key
        }
    }

    private suspend fun readCovers(paths: List<String>): FolderCovers = withContext(Dispatchers.IO) {
        FolderCovers(paths.associateWith { p -> try { Diag.md5(Tags.read(p).cover) } catch (_: Exception) { "" } })
    }

    private suspend fun coverOf(path: String): ByteArray? = withContext(Dispatchers.IO) { try { Tags.read(path).cover } catch (_: Exception) { null } }

    /** Gruppen bilden: Album-Ordner = ein Album, sonst je Titel bzw. MediaStore-Album */
    private data class Group(val key: String, val hint: AlbumHint?, val dir: String?, val items: MutableList<MatchItem>)

    private suspend fun groups(byAlbumId: Boolean): List<Group> {
        val map = LinkedHashMap<String, Group>()
        for (it in items) {
            if (it.state != MatchState.WAIT) continue
            val tag = withContext(Dispatchers.IO) { try { Tags.read(it.song.path) } catch (_: Exception) { null } }
            val h = AlbumHints.forSong(it.song, tag)
            val dir = AlbumHints.albumDir(it.song)?.path
            val key = when {
                h?.fromFolder == true && dir != null -> "f:$dir"
                byAlbumId -> "a:${it.song.albumId}"
                else -> "s:${it.song.id}"
            }
            map.getOrPut(key) { Group(key, h, if (key.startsWith("f:")) dir else null, ArrayList()) }.items += it
        }
        return map.values.toList()
    }

    private fun log(ctx: Context, m: MatchItem) {
        val top = m.candidates.take(3).joinToString(" | ") { "${it.confidence}% ${it.source} ${it.album}" }
        Diag.log(ctx, "${mode.label} ${m.state}: ${m.song.path} · ${m.info} · Treffer: $top")
    }

    /**
     * Tags: pro Titel suchen. Bei Album-Ordnern gilt der Ordner als Album:
     *  - bevorzugt wird ein Treffer, dessen Album zum Ordner passt (auch knapp unter der Schwelle)
     *  - ein sicherer Treffer mit anderem Album wird NICHT gespeichert, sondern kommt in „Prüfen“
     *  - Album, Album-Interpret, Jahr und Cover sind für alle Titel des Ordners gleich (vom ersten Treffer)
     */
    private suspend fun runTags(ctx: Context, changed: MutableList<String>) {
        val prefs = Prefs.get(ctx)
        for (g in groups(byAlbumId = false)) {
            val folderRule = g.dir != null && g.hint != null
            var ref: Candidate? = null                // erster gespeicherter Treffer im Ordner
            var folderCover: ByteArray? = null
            var coverTried = false
            val existing = if (folderRule) readCovers(folderSongs(g.dir!!).map { it.path }) else null
            for (it in g.items) {
                currentCoroutineContext().ensureActive()
                it.state = MatchState.RUNNING
                try {
                    val old = withContext(Dispatchers.IO) { Tags.read(it.song.path) }
                    val cands = TagSearch.search(it.song, old, prefs.threshold, thorough = false)
                    it.candidates = cands
                    val best = cands.firstOrNull()
                    val folderAlbum = ref?.album ?: g.hint?.album ?: ""
                    var pick: Candidate? = if (!folderRule) best?.takeIf { c -> c.confidence >= prefs.threshold }
                        else cands.firstOrNull { c -> albumMatches(c.album, folderAlbum) && c.confidence >= prefs.threshold - 10 }
                    if (!autoApply) pick = null
                    when {
                        best == null -> { it.state = MatchState.NONE; it.info = "Kein Treffer" }
                        pick == null -> {
                            it.state = MatchState.REVIEW
                            it.info = "${best.confidence} %  ·  ${best.artist} – ${best.title} | ${best.album}  ·  ${best.source}" +
                                if (folderRule && best.confidence >= prefs.threshold && !albumMatches(best.album, folderAlbum))
                                    "  ·  Album passt nicht zum Ordner „$folderAlbum“" else ""
                        }
                        else -> {
                            var c = TagSearch.enrich(pick)
                            if (folderRule) {
                                val r = ref
                                if (r == null) ref = c
                                else c = c.copy(album = r.album, albumArtist = r.albumArtist.ifBlank { c.albumArtist },
                                    year = r.year.ifBlank { c.year }, trackTotal = c.trackTotal.ifBlank { r.trackTotal },
                                    genre = c.genre.ifBlank { r.genre }).also { x -> x.confidence = pick.confidence }
                            }
                            // Cover
                            var cover: ByteArray? = null
                            if (prefs.coverOnline) {
                                if (folderRule) {
                                    if (!coverTried) {
                                        coverTried = true
                                        folderCover = Covers.forCandidate(ref!!) ?: Covers.firstAvailable(
                                            cands.filter { k -> albumMatches(k.album, ref!!.album) }, prefs.threshold - 10)?.second
                                            ?: existing?.majorityPath?.let { p -> coverOf(p) }
                                    }
                                    val keep = prefs.keepExistingCover && existing?.consistent == true
                                    if (!keep && folderCover != null && Diag.md5(old.cover) != Diag.md5(folderCover)) cover = folderCover
                                } else if (!(prefs.keepExistingCover && old.cover != null)) {
                                    cover = Covers.forCandidate(c) ?: Covers.firstAvailable(
                                        cands.filter { k -> k !== pick && norm(k.album) == norm(c.album) }, prefs.threshold)?.second
                                }
                            }
                            val neu = old.applyCandidate(c, cover)
                            val diff = neu.diff(old)
                            if (diff.isEmpty()) {
                                it.state = MatchState.SAME; it.info = "${pick.confidence} %  ·  schon korrekt"
                            } else {
                                withContext(Dispatchers.IO) { Tags.write(it.song.path, neu, writeCover = cover != null) }
                                changed += it.song.path
                                it.state = MatchState.APPLIED
                                it.info = "${pick.confidence} %  ·  ${pick.source}  ·  ${diff.joinToString { d -> d.first }}" +
                                    if (folderRule) "  ·  Ordner „${ref?.album}“" else ""
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { it.state = MatchState.ERROR; it.info = e.message ?: e.javaClass.simpleName }
                log(ctx, it)
            }
            // Ordnerregel: ausgewählte Titel ohne Treffer bekommen trotzdem das Ordner-Cover
            val fc = folderCover
            if (folderRule && autoApply && fc != null && !(prefs.keepExistingCover && existing?.consistent == true)) {
                for (it in g.items.filter { x -> x.state == MatchState.REVIEW || x.state == MatchState.NONE }) {
                    try {
                        val t = withContext(Dispatchers.IO) { Tags.read(it.song.path) }
                        if (Diag.md5(t.cover) == Diag.md5(fc)) continue
                        withContext(Dispatchers.IO) { Tags.write(it.song.path, t.copy(cover = fc, coverMime = "image/jpeg"), writeCover = true) }
                        changed += it.song.path
                        it.info += "  ·  Ordner-Cover übernommen"
                        Diag.log(ctx, "Ordner-Cover übernommen: ${it.song.path}")
                    } catch (e: Exception) { Diag.log(ctx, "Ordner-Cover Fehler: ${it.song.path}: ${e.message}") }
                }
            }
        }
    }

    /**
     * Cover: pro Album einmal suchen.
     * Album-Ordner: alle Dateien im Ordner bekommen dasselbe Cover. Ein Ordner mit uneinheitlichen
     * Covern wird immer bearbeitet (auch wenn „Vorhandene Cover ersetzen“ aus ist). Findet die
     * Online-Suche nichts Sicheres, wird das Cover vereinheitlicht, das im Ordner die Mehrheit hat.
     */
    private suspend fun runCovers(ctx: Context, changed: MutableList<String>) {
        val prefs = Prefs.get(ctx)
        for (g in groups(byAlbumId = true)) {
            currentCoroutineContext().ensureActive()
            val folderRule = g.dir != null
            val covers = readCovers(g.items.map { it.song.path })
            val via = if (g.hint?.fromFolder == true) "Ordner „${g.hint.label}“" else "Album-Tag"
            // Was ist zu tun?
            val targets: List<MatchItem> = when {
                replaceCovers -> g.items
                folderRule && covers.consistent -> emptyList()
                folderRule -> g.items                                   // uneinheitlich oder teils ohne Cover
                else -> g.items.filter { covers.md5s[it.song.path].isNullOrEmpty() }
            }
            g.items.filter { it !in targets }.forEach {
                it.state = MatchState.SAME; it.info = if (folderRule) "Cover im Ordner einheitlich" else "Cover vorhanden"; log(ctx, it)
            }
            if (targets.isEmpty()) continue
            targets.forEach { it.state = MatchState.RUNNING }
            val reason = if (folderRule && !covers.consistent && covers.withCover.isNotEmpty()) "  ·  Ordner war uneinheitlich" else ""
            try {
                val first = targets.first()
                val files = if (folderRule) java.io.File(g.dir!!).walkTopDown().maxDepth(2)
                    .count { f -> f.isFile && f.extension.lowercase() == "mp3" } else 0
                var cands = if (g.hint != null) AlbumSearch.search(g.hint, files, prefs.threshold, thorough = false) else emptyList()
                if ((cands.firstOrNull()?.confidence ?: 0) < prefs.threshold) {
                    val old = withContext(Dispatchers.IO) { Tags.read(first.song.path) }
                    cands = (cands + TagSearch.search(first.song, old, prefs.threshold, thorough = true))
                        .sortedByDescending { it.confidence }.distinctBy { it.source + ":" + it.releaseId }
                }
                targets.forEach { it.candidates = cands }
                val top = cands.firstOrNull()
                var cover: ByteArray? = null
                var label = ""
                if (top != null && top.confidence >= prefs.threshold && autoApply) {
                    Covers.firstAvailable(cands, prefs.threshold)?.let { (b, img) -> cover = img; label = "${b.confidence} %  ·  Cover von „${b.album}“ (${b.source})" }
                }
                // Rückfall: Mehrheits-Cover im Ordner
                if (cover == null && folderRule && autoApply) covers.majorityPath?.let { p ->
                    cover = coverOf(p); label = "Cover aus dem Ordner vereinheitlicht (online " + (if (top == null) "kein Treffer" else "${top.confidence} %") + ")"
                }
                val img = cover
                if (img == null) {
                    targets.forEach {
                        it.state = if (top != null && top.confidence < prefs.threshold) MatchState.REVIEW else MatchState.NONE
                        it.info = when {
                            top == null -> "Kein Treffer ($via)"
                            top.confidence < prefs.threshold || !autoApply -> "${top.confidence} %  ·  ${top.album} (${top.artist})  ·  ${top.source}  ·  $via"
                            else -> "Kein Bild zu „${top.album}“ gefunden ($via)"
                        }
                        log(ctx, it)
                    }
                    continue
                }
                val md5 = Diag.md5(img)
                for (it in targets) {
                    try {
                        if (covers.md5s[it.song.path] == md5) { it.state = MatchState.SAME; it.info = "Cover passt"; log(ctx, it); continue }
                        withContext(Dispatchers.IO) {
                            val t = Tags.read(it.song.path)
                            Tags.write(it.song.path, t.copy(cover = img, coverMime = "image/jpeg"), writeCover = true)
                        }
                        changed += it.song.path
                        it.state = MatchState.APPLIED
                        it.info = "$label  ·  $via$reason"
                    } catch (e: Exception) { it.state = MatchState.ERROR; it.info = e.message ?: e.javaClass.simpleName }
                    log(ctx, it)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { targets.forEach { it.state = MatchState.ERROR; it.info = e.message ?: e.javaClass.simpleName; log(ctx, it) } }
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
