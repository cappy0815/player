package de.cappy.tagplayer

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

// ---------- Farben ----------
val Bg = Color(0xFF0D0F14)
val Surface = Color(0xFF171A21)
val Surface2 = Color(0xFF20242D)
val Accent = Color(0xFFFFB020)
val AccentSoft = Color(0xFFFFE2A8)
val TextDim = Color(0xFF9AA3B2)
val Good = Color(0xFF4CD787)
val Bad = Color(0xFFFF6B6B)

// ---------- Titel ----------
data class Song(
    val id: Long,
    val uri: Uri,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val year: Int,
    val modified: Long
) {
    val fileName: String get() = File(path).name
    val albumArtUri: Uri get() = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
}

/** Liest die Musik über den MediaStore (nur Dateien, die Android als Musik kennt). */
object Library {
    val songs = mutableStateListOf<Song>()
    var loading by mutableStateOf(false)
    var loadedOnce by mutableStateOf(false)
    /** zählt hoch, wenn Tags geändert wurden -> Cover/Listen neu laden */
    var version by mutableIntStateOf(0)

    /** Anzahl Titel, die wegen Mindestlänge bzw. Ordnerauswahl ausgeblendet sind */
    var hiddenShort by mutableIntStateOf(0)
    var hiddenFolder by mutableIntStateOf(0)
    private val loadLock = kotlinx.coroutines.sync.Mutex()

    suspend fun load(ctx: Context) = loadLock.withLock {
        loading = true
        val prefs = Prefs.get(ctx)
        val folders = prefs.musicFolders.map { it.trimEnd('/') + "/" }
        val minMs = prefs.minDurationSec * 1000L
        val raw = withContext(Dispatchers.IO) { fixStale(ctx, query(ctx)) }
        val inFolders = raw.filter { s -> folders.isEmpty() || folders.any { s.path.startsWith(it) } }
        // Dauer 0 = MediaStore kennt sie nicht -> nicht als "kurz" werten
        val list = inFolders.filter { s -> minMs <= 0 || s.durationMs <= 0 || s.durationMs >= minMs }
        hiddenFolder = raw.size - inFolders.size
        hiddenShort = inFolders.size - list.size
        // in einem Rutsch ersetzen, damit die Liste nicht kurz leer ist
        Snapshot.withMutableSnapshot { songs.clear(); songs.addAll(list) }
        loading = false; loadedOnce = true; version++
        Diag.log(ctx, "Bibliothek: ${list.size} Titel, ${raw.size} im MediaStore, $hiddenShort zu kurz, $hiddenFolder außerhalb der Ordner")
    }

    /**
     * Der MediaStore übernimmt geänderte Tags nicht immer sofort (Samsung). Ist die Datei neuer als
     * der MediaStore-Eintrag, werden Titel/Interpret/Album/Nr./Jahr direkt aus der Datei gelesen
     * und Android wird zum Neu-Einlesen aufgefordert.
     */
    private fun fixStale(ctx: Context, list: List<Song>): List<Song> {
        val stale = ArrayList<String>()
        val out = list.map { s ->
            val f = File(s.path)
            val fileSec = f.lastModified() / 1000
            if (fileSec - s.modified <= 2) return@map s
            stale += s.path
            try {
                val t = Tags.read(s.path)
                s.copy(
                    title = t.title.ifBlank { s.title }, artist = t.artist.ifBlank { s.artist },
                    album = t.album.ifBlank { s.album },
                    track = t.track.substringBefore('/').trim().toIntOrNull() ?: s.track,
                    year = t.year.take(4).toIntOrNull() ?: s.year, modified = fileSec
                )
            } catch (_: Exception) { s }
        }
        if (stale.isNotEmpty()) MediaScannerConnection.scanFile(ctx, stale.toTypedArray(), null, null)
        return out
    }

    fun byId(id: Long): Song? = songs.firstOrNull { it.id == id }
    fun byPath(path: String): Song? = songs.firstOrNull { it.path == path }

    private fun query(ctx: Context): List<Song> {
        val out = ArrayList<Song>()
        val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_MODIFIED
        )
        ctx.contentResolver.query(col, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE, ${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, ${MediaStore.Audio.Media.TRACK}"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val artist = c.getString(3)
                out += Song(
                    id = id,
                    uri = ContentUris.withAppendedId(col, id),
                    path = c.getString(1) ?: "",
                    title = c.getString(2) ?: "",
                    artist = if (artist == null || artist == "<unknown>") "" else artist,
                    album = c.getString(4) ?: "",
                    albumId = c.getLong(5),
                    durationMs = c.getLong(6),
                    // MediaStore liefert Disc*1000 + Titelnummer
                    track = c.getInt(7) % 1000,
                    year = c.getInt(8),
                    modified = c.getLong(9)
                )
            }
        }
        return out
    }

    /** Nach dem Schreiben der Tags: Android neu einlesen lassen. */
    suspend fun rescan(ctx: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        suspendCancellableCoroutine { cont ->
            var left = paths.size
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null) { _, _ ->
                left--
                if (left == 0 && cont.isActive) cont.resume(Unit)
            }
        }
    }
}

// ---------- Einstellungen ----------
class Prefs private constructor(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tagplayer", Context.MODE_PRIVATE)
    private fun i(k: String, d: Int) = mutableIntStateOf(sp.getInt(k, d))
    private fun b(k: String, d: Boolean) = mutableStateOf(sp.getBoolean(k, d))

    private val _threshold = i("threshold", 90)
    /** Ab dieser Trefferquote (0–100) wird beim Abgleich automatisch übernommen */
    var threshold: Int get() = _threshold.intValue; set(v) { _threshold.intValue = v; sp.edit().putInt("threshold", v).apply() }

    private val _cover = b("coverOnline", true)
    var coverOnline: Boolean get() = _cover.value; set(v) { _cover.value = v; sp.edit().putBoolean("coverOnline", v).apply() }

    private val _keepCover = b("keepCover", true)
    /** Vorhandenes Cover beim automatischen Abgleich nicht ersetzen */
    var keepExistingCover: Boolean get() = _keepCover.value; set(v) { _keepCover.value = v; sp.edit().putBoolean("keepCover", v).apply() }

    private val _autoLyrics = b("autoLyrics", true)
    /** Beim Abspielen automatisch online nach Lyrics suchen */
    var autoLyrics: Boolean get() = _autoLyrics.value; set(v) { _autoLyrics.value = v; sp.edit().putBoolean("autoLyrics", v).apply() }

    private val _lyricsToTag = b("lyricsToTag", false)
    /** Gefundene Lyrics zusätzlich in die MP3 schreiben */
    var lyricsToTag: Boolean get() = _lyricsToTag.value; set(v) { _lyricsToTag.value = v; sp.edit().putBoolean("lyricsToTag", v).apply() }

    private val _font = i("stageFont", 40)
    var stageFontSp: Int get() = _font.intValue; set(v) { _font.intValue = v; sp.edit().putInt("stageFont", v).apply() }

    private val _bright = i("stageBright", 15)
    var stageBrightness: Int get() = _bright.intValue; set(v) { _bright.intValue = v; sp.edit().putInt("stageBright", v).apply() }

    private val _onLock = b("stageOnLock", false)
    /** Beim Sperren (Display aus) während der Wiedergabe die Lyrics-Anzeige zeigen */
    var stageOnLock: Boolean get() = _onLock.value; set(v) { _onLock.value = v; sp.edit().putBoolean("stageOnLock", v).apply() }

    private val _keepOn = b("stageKeepOn", true)
    /** Im Lyrics-Vollbild Display anlassen (Standard an, im Vollbild per Knopf umschaltbar) */
    var stageKeepOn: Boolean get() = _keepOn.value; set(v) { _keepOn.value = v; sp.edit().putBoolean("stageKeepOn", v).apply() }

    private val _onlyCharging = b("stageOnlyCharging", false)
    var stageOnlyCharging: Boolean get() = _onlyCharging.value; set(v) { _onlyCharging.value = v; sp.edit().putBoolean("stageOnlyCharging", v).apply() }

    private val _minDur = i("minDurationSec", 30)
    /** Kürzere Dateien (Klingeltöne, Sprachnachrichten, Jingles) ausblenden; 0 = aus */
    var minDurationSec: Int get() = _minDur.intValue; set(v) { _minDur.intValue = v; sp.edit().putInt("minDurationSec", v).apply() }

    private val _folders = mutableStateOf(sp.getStringSet("musicFolders", emptySet())!!.toSet())
    /** Ausgewählte Musikordner (absolute Pfade); leer = alle Musik auf dem Gerät */
    var musicFolders: Set<String> get() = _folders.value; set(v) { _folders.value = v; sp.edit().putStringSet("musicFolders", v).apply() }

    private val _shift = b("pixelShift", true)
    var pixelShift: Boolean get() = _shift.value; set(v) { _shift.value = v; sp.edit().putBoolean("pixelShift", v).apply() }

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs =
            inst ?: synchronized(this) { inst ?: Prefs(ctx.applicationContext).also { inst = it } }
    }
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
