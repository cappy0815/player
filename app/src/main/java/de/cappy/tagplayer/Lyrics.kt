package de.cappy.tagplayer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class LyricLine(val timeMs: Long, val text: String)

/** Geladener Text: synchronisiert (Zeilen mit Zeit) oder nur Text */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String) {
    /**
     * Index der aktuellen Zeile.
     * Synchronisiert: exakt nach Zeitstempel.
     * Ohne Zeitangaben: geschätzt aus Position/Länge (gleichmäßig über die Textzeilen verteilt).
     */
    fun indexAt(posMs: Long, durMs: Long = 0L): Int {
        if (lines.isEmpty()) return -1
        if (!synced) {
            if (durMs <= 0) return -1
            val textIdx = lines.indices.filter { lines[it].text.isNotBlank() }
            if (textIdx.isEmpty()) return -1
            val k = ((posMs.toDouble() / durMs) * textIdx.size).toInt().coerceIn(0, textIdx.lastIndex)
            return textIdx[k]
        }
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val m = (lo + hi) / 2
            if (lines[m].timeMs <= posMs) { ans = m; lo = m + 1 } else hi = m - 1
        }
        return ans
    }
    companion object {
        private val TS = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        private val META = Regex("^\\[(ar|ti|al|by|offset|length|re|ve|au|#):.*]$", RegexOption.IGNORE_CASE)

        /** Erkennt LRC-Format automatisch, sonst Klartext */
        fun parse(text: String, source: String): Lyrics? {
            if (text.isBlank()) return null
            val timed = ArrayList<LyricLine>()
            var offset = 0L
            text.lineSequence().forEach { raw ->
                val l = raw.trim()
                Regex("^\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(l)?.let { offset = it.groupValues[1].toLong() }
                if (META.matches(l)) return@forEach
                val stamps = TS.findAll(l).toList()
                if (stamps.isEmpty()) return@forEach
                val body = l.substring(stamps.last().range.last + 1).trim()
                    .replace(Regex("<\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?>"), "").trim()   // Wort-Zeitstempel entfernen
                for (m in stamps) {
                    val min = m.groupValues[1].toLong(); val sec = m.groupValues[2].toLong()
                    val fr = m.groupValues[3]
                    val ms = when (fr.length) { 0 -> 0L; 1 -> fr.toLong() * 100; 2 -> fr.toLong() * 10; else -> fr.take(3).toLong() }
                    timed += LyricLine(min * 60_000 + sec * 1000 + ms - offset, body)
                }
            }
            if (timed.size >= 3) return Lyrics(timed.sortedBy { it.timeMs }, true, source)
            val plain = text.lines().map { it.trim() }
            return Lyrics(plain.map { LyricLine(0, it) }, false, source)
        }
    }
}

/**
 * Lyrics zum laufenden Titel. Reihenfolge: eingebettet in der MP3 -> Zwischenspeicher -> LRCLIB.
 * Der Zwischenspeicher liegt im App-Ordner und hängt am Dateipfad.
 */
object LyricsStore {
    var current by mutableStateOf<Lyrics?>(null)
    var currentSongId by mutableStateOf(-1L)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)

    private fun key(path: String): String =
        MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cacheFile(ctx: Context, path: String) = File(File(ctx.filesDir, "lyrics").apply { mkdirs() }, key(path) + ".lrc")

    suspend fun load(ctx: Context, song: Song, allowOnline: Boolean, force: Boolean = false) {
        if (!force && currentSongId == song.id && current != null) return
        currentSongId = song.id
        current = null
        status = ""
        val prefs = Prefs.get(ctx)
        busy = true
        try {
            // Reihenfolge: synchronisiert vor Klartext. Klartext aus der Datei ist nur Rückfallebene,
            // damit online gefundene Zeitangaben (Highlighting) Vorrang haben.
            var fallback: Lyrics? = null
            if (!force) {
                val embedded = withContext(Dispatchers.IO) { Tags.readLyrics(song.path) }
                Lyrics.parse(embedded, "in der Datei")?.let {
                    if (it.synced) { if (currentSongId == song.id) current = it; return }
                    fallback = it
                }
                val cf = cacheFile(ctx, song.path)
                if (cf.exists()) {
                    val t = withContext(Dispatchers.IO) { cf.readText() }
                    if (t == "#NONE" || t == "#PLAIN") {
                        // schon online gesucht: nichts bzw. nichts Synchronisiertes
                        if (fallback != null) { current = fallback; return }
                        status = "Keine Lyrics gefunden"; return
                    }
                    Lyrics.parse(t, "gespeichert")?.let {
                        if (it.synced || fallback == null) { if (currentSongId == song.id) current = it; return }
                    }
                }
            }
            if (!allowOnline) {
                if (fallback != null) current = fallback else status = "Keine Lyrics vorhanden"
                return
            }
            if (fallback != null) current = fallback   // anzeigen, während online nach Zeitangaben gesucht wird
            else status = "Suche Lyrics …"
            val (title, artist) = MusicBrainz.searchTerms(song, null)
            val r = try { LrcLib.find(artist, title, song.album, song.durationMs) } catch (e: Exception) { null }
            if (currentSongId != song.id) return
            val synced = r?.synced?.ifBlank { null }
            when {
                synced != null -> {
                    current = Lyrics.parse(synced, "LRCLIB (synchron)")
                    status = ""
                    save(ctx, song, synced, prefs.lyricsToTag)
                }
                fallback != null -> { current = fallback; save(ctx, song, "#PLAIN", false) }
                r?.best != null -> {
                    current = Lyrics.parse(r.best!!, "LRCLIB")
                    status = ""
                    save(ctx, song, r.best!!, prefs.lyricsToTag)
                }
                r?.instrumental == true -> { status = "Instrumental"; save(ctx, song, "#NONE", false) }
                else -> { status = "Keine Lyrics gefunden"; save(ctx, song, "#NONE", false) }
            }
        } finally { busy = false }
    }

    private suspend fun save(ctx: Context, song: Song, text: String, toTag: Boolean) = withContext(Dispatchers.IO) {
        try { cacheFile(ctx, song.path).writeText(text) } catch (_: Exception) {}
        if (toTag && !text.startsWith("#") && Tags.canEdit(song.path)) try { Tags.writeLyrics(song.path, text) } catch (_: Exception) {}
    }

    /** Zwischenspeicher für einen Titel löschen (z. B. nach Tag-Änderung neu suchen) */
    fun forget(ctx: Context, song: Song) {
        cacheFile(ctx, song.path).delete()
        if (currentSongId == song.id) { current = null; currentSongId = -1 }
    }
}
