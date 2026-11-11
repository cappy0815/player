package de.cappy.tagplayer

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Protokoll und Diagnose-Datei.
 * Das Protokoll liegt nur im App-Speicher. Die Diagnose-Datei (ZIP) wird nur auf Wunsch in den
 * Einstellungen erstellt und über das normale Android-Teilen weitergegeben – es wird nie etwas
 * automatisch verschickt. Inhalt: Dateinamen, Ordner, Tags, Cover-Prüfsummen, Abgleich-Ergebnisse,
 * Protokoll. Keine Musik, keine Bilder.
 */
object Diag {
    private const val MAX = 512 * 1024
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)

    private fun dir(c: Context) = File(c.filesDir, "diag").apply { mkdirs() }

    @Synchronized
    fun log(c: Context, msg: String) {
        try {
            val f = File(dir(c), "log.txt")
            if (f.length() > MAX) { File(dir(c), "log.1.txt").delete(); f.renameTo(File(dir(c), "log.1.txt")) }
            File(dir(c), "log.txt").appendText(ts.format(Date()) + "  " + msg.replace('\n', ' ') + "\n")
        } catch (_: Exception) {}
    }

    fun clear(c: Context) { dir(c).listFiles()?.forEach { it.delete() } }

    fun logSize(c: Context): Long = dir(c).listFiles()?.sumOf { it.length() } ?: 0L

    fun md5(b: ByteArray?): String = if (b == null) "" else
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }.take(10)

    private fun tsv(vararg v: Any?) = v.joinToString("\t") { (it?.toString() ?: "").replace('\t', ' ').replace('\n', ' ') }

    /**
     * Erstellt die Diagnose-Datei. [progress] bekommt (erledigt, gesamt).
     * Rückgabe: ZIP-Datei im Cache (wird beim nächsten Erstellen überschrieben).
     */
    suspend fun build(c: Context, progress: (Int, Int) -> Unit): File = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(c)
        val songs = Library.songs.toList()
        val out = File(File(c.cacheDir, "diag").apply { mkdirs(); listFiles()?.forEach { it.delete() } },
            "Player-Diagnose-" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.GERMANY).format(Date()) + ".zip")

        // Tags aller Titel lesen
        data class Row(val s: Song, val t: TagData?, val err: String?, val hint: AlbumHint?, val dir: String)
        val rows = ArrayList<Row>(songs.size)
        songs.forEachIndexed { i, s ->
            var t: TagData? = null; var err: String? = null
            try { t = Tags.read(s.path) } catch (e: Exception) { err = e.javaClass.simpleName + ": " + (e.message ?: "") }
            rows += Row(s, t, err, AlbumHints.forSong(s, t), AlbumHints.albumDir(s)?.path ?: File(s.path).parent ?: "")
            if (i % 20 == 0) withContext(Dispatchers.Main) { progress(i, songs.size) }
        }

        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }

            // 1. Übersicht
            put("summary.txt", buildString {
                appendLine("Player-Diagnose ${ts.format(Date())}")
                appendLine("App ${appVersion(c)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Zugriff auf alle Dateien: ${hasAllFiles()}")
                appendLine("Titel in der Bibliothek: ${songs.size} · zu kurz ausgeblendet: ${Library.hiddenShort} · außerhalb der Ordner: ${Library.hiddenFolder}")
                appendLine("Musikordner: ${prefs.musicFolders.ifEmpty { setOf("(alle)") }.joinToString()}")
                appendLine("Mindestlänge: ${prefs.minDurationSec} s · Schwelle: ${prefs.threshold} % · Cover online: ${prefs.coverOnline} · vorhandenes Cover behalten: ${prefs.keepExistingCover}")
                appendLine("Playlists: ${Playlists.all.size} · Lyrics in MP3 schreiben: ${prefs.lyricsToTag}")
                appendLine("Lesefehler: ${rows.count { it.err != null }}")
            })

            // 2. Alle Titel
            put("library.tsv", buildString {
                appendLine(tsv("ordner", "datei", "dauer_s", "ms_titel", "ms_interpret", "ms_album", "ms_album_id",
                    "tag_titel", "tag_interpret", "tag_album", "tag_albuminterpret", "tag_nr", "tag_von", "tag_cd", "tag_jahr", "tag_genre",
                    "cover_bytes", "cover_md5", "lyrics", "hinweis_interpret", "hinweis_album", "hinweis_aus", "fehler"))
                rows.forEach { r ->
                    val t = r.t
                    appendLine(tsv(r.dir, File(r.s.path).relativeToOrSelf(File(r.dir)).path, r.s.durationMs / 1000,
                        r.s.title, r.s.artist, r.s.album, r.s.albumId,
                        t?.title, t?.artist, t?.album, t?.albumArtist, t?.track, t?.trackTotal, t?.disc, t?.year, t?.genre,
                        t?.cover?.size ?: 0, md5(t?.cover), if (t?.lyrics.isNullOrBlank()) "" else "ja",
                        r.hint?.artist, r.hint?.album, when (r.hint?.fromFolder) { true -> "Ordner"; false -> "Tag"; null -> "" }, r.err))
                }
            })

            // 3. Je Ordner: einheitlich?
            put("folders.tsv", buildString {
                appendLine(tsv("ordner", "titel", "alben_im_tag", "media_store_alben", "verschiedene_cover", "ohne_cover", "hinweis", "befund"))
                rows.groupBy { it.dir }.toSortedMap().forEach { (d, l) ->
                    val albums = l.map { it.t?.album?.trim() ?: "" }.distinct()
                    val ids = l.map { it.s.albumId }.distinct()
                    val covers = l.mapNotNull { it.t?.cover }.map { md5(it) }.distinct()
                    val noCover = l.count { it.t?.cover == null }
                    val befund = buildList {
                        if (albums.size > 1) add("Album uneinheitlich")
                        if (covers.size > 1) add("Cover uneinheitlich")
                        if (noCover in 1 until l.size) add("teils ohne Cover")
                        if (noCover == l.size) add("ohne Cover")
                        if (ids.size > 1) add("Android sieht ${ids.size} Alben")
                    }.ifEmpty { listOf("OK") }.joinToString(", ")
                    val h = l.first().hint
                    appendLine(tsv(d, l.size, albums.joinToString(" | "), ids.size, covers.size, noCover,
                        h?.let { (if (it.fromFolder) "Ordner: " else "Tag: ") + it.label }, befund))
                }
            })

            // 4. Letzter Abgleich in dieser Sitzung
            put("matches.txt", buildString {
                appendLine("Abgleich: ${Matcher.title} (${Matcher.mode.label}) · ${Matcher.items.size} Titel")
                Matcher.items.forEach { m ->
                    appendLine("\n${m.state}  ${m.song.path}")
                    appendLine("   ${m.info}")
                    m.candidates.take(6).forEach { k ->
                        appendLine("   ${k.confidence}% ${k.source}: ${k.artist} – ${k.title} | ${k.album} ${k.year} Nr ${k.track}/${k.trackTotal}")
                    }
                }
            })

            // 5. Protokoll
            listOf("log.1.txt", "log.txt").map { File(dir(c), it) }.filter { it.exists() }.forEach { put(it.name, it.readText()) }
        }
        withContext(Dispatchers.Main) { progress(songs.size, songs.size) }
        log(c, "Diagnose-Datei erstellt: ${out.name} (${out.length() / 1024} KB, ${songs.size} Titel)")
        out
    }

    /** Über das Android-Teilen weitergeben (Mail, Drive, Messenger …) */
    fun share(c: Context, f: File) {
        val uri = FileProvider.getUriForFile(c, "de.cappy.tagplayer.files", f)
        val i = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Player-Diagnose ${appVersion(c)}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, "Diagnose-Datei teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
