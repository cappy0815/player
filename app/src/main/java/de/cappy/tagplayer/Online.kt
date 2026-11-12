package de.cappy.tagplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs

private const val UA = "Player/0.9.1 ( https://github.com/cappy0815 )"

object Http {
    /** GET, liefert (Statuscode, Bytes). Folgt Weiterleitungen (Cover Art Archive -> archive.org). */
    fun get(url: String, timeoutMs: Int = 15000): Pair<Int, ByteArray?> {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json, image/*")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return code to null
                    u = URL(URL(u), loc).toString(); return@repeat
                }
                if (code != 200) return code to null
                return code to c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }
        return -1 to null
    }
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** Vergleichsform: klein, ohne Akzente, ohne Klammerzusätze und Satzzeichen */
fun norm(s: String): String {
    var x = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    x = x.replace(Regex("[(\\[].*?[)\\]]"), " ")
        .replace(Regex("\\b(feat|ft)\\.?\\b.*"), " ")
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9äöüß]+"), " ")
    return x.trim().replace(Regex("\\s+"), " ")
}

// ---------- MusicBrainz ----------
data class Candidate(
    val recordingId: String,
    val releaseId: String,
    val releaseGroupId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val year: String,
    val track: String,
    val trackTotal: String,
    val disc: String,
    val lengthMs: Long,
    val mbScore: Int,
    var confidence: Int = 0,
    /** Quelle: MusicBrainz, Deezer oder iTunes */
    val source: String = "MusicBrainz",
    /** direkter Cover-Link (Deezer/iTunes); leer = Cover Art Archive */
    val coverUrl: String = "",
    val genre: String = "",
    /** Kennung bei der Quelle (z. B. Deezer-Track), für Nachladen von Nummer/Jahr */
    val extId: String = ""
)

/** Gemeinsame Trefferquote 0–100: Titel, Interpret und Länge vergleichen */
fun scoreTrack(base: Int, c: Candidate, title: String, artist: String, durMs: Long): Int {
    var s = base
    val nt = norm(title); val nc = norm(c.title)
    // Titel wiegt am meisten: ein anderer Titel darf nie vor dem richtigen landen
    if (nt != nc) s -= if (nt.isNotEmpty() && (nc.contains(nt) || nt.contains(nc))) 8 else 45
    if (artist.isNotBlank()) {
        val na = norm(artist); val nca = norm(c.artist)
        if (na != nca) s -= if (nca.contains(na) || na.contains(nca)) 5 else 30
    } else s -= 10
    // Länge: Live-, Radio- und Album-Versionen weichen ab -> deutlich, aber nicht entscheidend
    if (durMs > 0 && c.lengthMs > 0) {
        val d = abs(durMs - c.lengthMs)
        s -= when { d <= 3000 -> 0; d <= 7000 -> 5; d <= 15000 -> 12; else -> 25 }
    } else s -= 5
    return s.coerceIn(0, 100)
}

/** Album laut Ordner: passt -> sicherer, passt nicht -> lieber zur Prüfung */
fun folderAdjust(c: Candidate, hint: AlbumHint?) {
    if (hint?.fromFolder != true) return
    val a = norm(hint.album); val b = norm(c.album)
    c.confidence = if (a == b) (c.confidence + 5).coerceAtMost(100)
        else if (b.contains(a) || a.contains(b)) c.confidence
        else (c.confidence - 15).coerceAtLeast(0)
}

/** Trefferquote für Alben (Cover-Prüfung): Albumtitel und Interpret vergleichen */
fun scoreAlbum(base: Int, hint: AlbumHint, title: String, artist: String): Int {
    var conf = base
    val na = norm(hint.album); val nt = norm(title)
    if (na != nt) conf -= if (nt.contains(na) || na.contains(nt)) 8 else 30
    if (hint.artist.isNotBlank()) {
        val x = norm(hint.artist); val y = norm(artist)
        if (x != y) conf -= if (y.contains(x) || x.contains(y)) 5 else 20
    }
    return conf.coerceIn(0, 100)
}

/** "(Remastered 2016)", " - 2011 Remaster" usw. entfernen (Deezer/iTunes hängen das an) */
fun cleanRemaster(t: String): String =
    t.replace(Regex("\\s*[(\\[][^)\\]]*\\bremaster(ed)?\\b[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+[-–]\\s+[^-–]*\\bremaster(ed)?\\b.*$", RegexOption.IGNORE_CASE), "").trim().ifBlank { t }

/** " - EP", " (Single)", Remaster-Zusätze vom Albumnamen entfernen (Deezer/iTunes hängen das an) */
fun cleanAlbum(a: String): String = cleanRemaster(a).let { a ->
    a.replace(Regex("\\s*[-–]\\s*(EP|Single)\\s*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*[(\\[](EP|Single)[)\\]]\\s*$", RegexOption.IGNORE_CASE), "").trim() }

object MusicBrainz {
    // MusicBrainz erlaubt im Schnitt 1 Anfrage pro Sekunde
    private val lock = Mutex()
    private var last = 0L

    private suspend fun throttled(url: String): Pair<Int, ByteArray?> = lock.withLock {
        val wait = 1100 - (System.currentTimeMillis() - last)
        if (wait > 0) delay(wait)
        val r = withContext(Dispatchers.IO) { Http.get(url) }
        last = System.currentTimeMillis()
        if (r.first == 503) {          // gedrosselt -> einmal kurz warten und wiederholen
            delay(2500)
            val r2 = withContext(Dispatchers.IO) { Http.get(url) }
            last = System.currentTimeMillis()
            return@withLock r2
        }
        r
    }

    private fun esc(s: String) = s.replace(Regex("([+\\-&|!(){}\\[\\]^\"~*?:\\\\/])"), "\\\\$1")

    /** Ermittelt Suchbegriffe; fehlen Titel/Interpret im Tag, wird der Dateiname "Interpret - Titel" genutzt. */
    fun searchTerms(song: Song, tag: TagData?): Pair<String, String> {
        var title = tag?.title?.ifBlank { null } ?: song.title
        var artist = tag?.artist?.ifBlank { null } ?: song.artist
        val base = song.fileName.substringBeforeLast('.').replace('_', ' ')
            .replace(Regex("^\\d{1,3}[ .\\-]+"), "")      // führende Titelnummer
        if (title.isBlank() || title == song.fileName.substringBeforeLast('.')) {
            val parts = base.split(" - ", limit = 2)
            if (parts.size == 2) { if (artist.isBlank()) artist = parts[0].trim(); title = parts[1].trim() }
            else title = base.trim()
        }
        return title to artist
    }

    /**
     * Tag-Suche über den Titel. Der Ordnername gilt als sicherer Hinweis auf das Album:
     * zuerst wird mit diesem Album gesucht (MusicBrainz-Feld release), erst ohne Treffer ohne Album.
     */
    suspend fun search(song: Song, tag: TagData?): List<Candidate> {
        val (title, artist0) = searchTerms(song, tag)
        if (title.isBlank()) return emptyList()
        val hint = AlbumHints.forSong(song, tag)
        val artist = artist0.ifBlank { hint?.artist ?: "" }
        var base = "recording:\"${esc(title)}\""
        if (artist.isNotBlank()) base += " AND artist:\"${esc(artist)}\""
        suspend fun query(q: String): JSONArray? {
            val (code, body) = throttled("https://musicbrainz.org/ws/2/recording?fmt=json&limit=10&query=" + Http.enc(q))
            if (code != 200 || body == null) throw Exception("MusicBrainz antwortet nicht (Code $code)")
            return JSONObject(String(body)).optJSONArray("recordings")?.takeIf { it.length() > 0 }
        }
        var recs: JSONArray? = null
        if (hint != null && hint.album.isNotBlank()) recs = query(base + " AND release:\"${esc(hint.album)}\"")
        if (recs == null) recs = query(base) ?: return emptyList()
        val albumHint = hint?.album ?: tag?.album?.ifBlank { null } ?: song.album
        val out = ArrayList<Candidate>()
        for (i in 0 until recs.length()) {
            val r = recs.getJSONObject(i)
            val rel = pickRelease(r.optJSONArray("releases"), albumHint) ?: continue
            val medium = rel.optJSONArray("media")?.optJSONObject(0)
            val tr = medium?.optJSONArray("track")?.optJSONObject(0)
            val disc = medium?.optInt("position", 1) ?: 1
            val year = (r.optString("first-release-date").ifBlank { rel.optString("date") }).take(4)
            val c = Candidate(
                recordingId = r.getString("id"),
                releaseId = rel.getString("id"),
                releaseGroupId = rel.optJSONObject("release-group")?.optString("id") ?: "",
                title = r.optString("title"),
                artist = credit(r.optJSONArray("artist-credit")),
                album = rel.optString("title"),
                albumArtist = credit(rel.optJSONArray("artist-credit")).ifBlank { credit(r.optJSONArray("artist-credit")) },
                year = year,
                track = tr?.optString("number") ?: "",
                trackTotal = medium?.optInt("track-count")?.takeIf { it > 0 }?.toString() ?: "",
                disc = if (disc > 1 || (rel.optJSONArray("media")?.length() ?: 1) > 1) disc.toString() else "",
                lengthMs = r.optLong("length", 0L),
                mbScore = r.optInt("score", 0)
            )
            c.confidence = confidence(c, title, artist, song.durationMs)
            folderAdjust(c, hint)
            out += c
        }
        return out.sortedByDescending { it.confidence }.distinctBy { it.recordingId }.take(8)
    }

    private fun credit(a: JSONArray?): String {
        if (a == null) return ""
        val sb = StringBuilder()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            sb.append(o.optString("name")).append(o.optString("joinphrase"))
        }
        return sb.toString().trim()
    }

    /**
     * Wählt die passendste Veröffentlichung:
     * 1. Albumname passt zum vorhandenen Tag
     * 2. offizielles Studioalbum ohne Zusatztyp (keine Compilation/Live)
     * 3. frühestes Datum
     */
    private fun pickRelease(rels: JSONArray?, albumHint: String): JSONObject? {
        if (rels == null || rels.length() == 0) return null
        val hint = norm(albumHint)
        var best: JSONObject? = null; var bestScore = Int.MIN_VALUE
        for (i in 0 until rels.length()) {
            val r = rels.getJSONObject(i)
            val rg = r.optJSONObject("release-group")
            var s = 0
            if (hint.isNotEmpty() && norm(r.optString("title")) == hint) s += 1000
            if (r.optString("status") == "Official") s += 300
            if (r.optString("status") == "Bootleg") s -= 500
            when (rg?.optString("primary-type")) { "Album" -> s += 200; "EP" -> s += 120; "Single" -> s += 80 }
            val sec = rg?.optJSONArray("secondary-types")
            if (sec == null || sec.length() == 0) s += 150
            val d = r.optString("date")
            if (d.length >= 4) s += (3000 - (d.take(4).toIntOrNull() ?: 3000)) / 10 else s -= 50
            if (s > bestScore) { bestScore = s; best = r }
        }
        return best
    }

    /** Eigene Trefferquote 0–100: MusicBrainz-Score, korrigiert um Titel-, Interpret- und Längenvergleich */
    private fun confidence(c: Candidate, title: String, artist: String, durMs: Long): Int =
        scoreTrack(c.mbScore, c, title, artist, durMs)

    /**
     * Album-Suche (Veröffentlichungen) für die Cover-Prüfung, z. B. mit dem Ordnernamen.
     * Ein Treffer pro Release-Gruppe; darin bevorzugt: offiziell, passende Titelanzahl, frühestes Datum.
     * title = Albumname, damit die Trefferliste "Interpret – Album" zeigt.
     */
    suspend fun searchRelease(hint: AlbumHint, filesInFolder: Int): List<Candidate> {
        if (hint.album.isBlank()) return emptyList()
        var q = "release:\"${esc(hint.album)}\""
        if (hint.artist.isNotBlank()) q += " AND artist:\"${esc(hint.artist)}\""
        val (code, body) = throttled("https://musicbrainz.org/ws/2/release?fmt=json&limit=25&query=" + Http.enc(q))
        if (code != 200 || body == null) throw Exception("MusicBrainz antwortet nicht (Code $code)")
        val rels = JSONObject(String(body)).optJSONArray("releases") ?: return emptyList()
        val byGroup = LinkedHashMap<String, Pair<Int, Candidate>>()
        for (i in 0 until rels.length()) {
            val r = rels.getJSONObject(i)
            val rg = r.optJSONObject("release-group")?.optString("id") ?: ""
            val artistName = credit(r.optJSONArray("artist-credit"))
            val conf = scoreAlbum(r.optInt("score", 0), hint, r.optString("title"), artistName)
            // Auswahl innerhalb der Gruppe
            var pref = 0
            if (r.optString("status") == "Official") pref += 50
            if (r.optString("status") == "Bootleg") pref -= 100
            if (filesInFolder > 0 && r.optInt("track-count", -1) == filesInFolder) pref += 30
            val d = r.optString("date")
            pref += if (d.length >= 4) (3000 - (d.take(4).toIntOrNull() ?: 3000)) / 20 else -20
            val c = Candidate(
                recordingId = "", releaseId = r.getString("id"), releaseGroupId = rg,
                title = r.optString("title"), artist = artistName, album = r.optString("title"),
                albumArtist = artistName, year = d.take(4), track = "",
                trackTotal = r.optInt("track-count", 0).takeIf { it > 0 }?.toString() ?: "", disc = "",
                lengthMs = 0, mbScore = r.optInt("score", 0), confidence = conf
            )
            val key = rg.ifBlank { c.releaseId }
            val old = byGroup[key]
            if (old == null || c.confidence > old.second.confidence || (c.confidence == old.second.confidence && pref > old.first))
                byGroup[key] = pref to c
        }
        return byGroup.values.map { it.second }.sortedByDescending { it.confidence }.take(8)
    }

    /** Cover vom Cover Art Archive (500 px), zuerst Veröffentlichung, dann Release-Gruppe */
    suspend fun cover(c: Candidate): ByteArray? = withContext(Dispatchers.IO) {
        if (c.releaseId.isBlank() || c.source != "MusicBrainz") return@withContext null
        val urls = listOf("https://coverartarchive.org/release/${c.releaseId}/front-500") +
            if (c.releaseGroupId.isNotBlank()) listOf("https://coverartarchive.org/release-group/${c.releaseGroupId}/front-500") else emptyList()
        for (u in urls) {
            val (code, b) = try { Http.get(u, 20000) } catch (_: Exception) { -1 to null }
            if (code == 200 && b != null && b.size > 1000) return@withContext b
        }
        null
    }
}

/** Übernimmt einen Treffer in vorhandene Tags; leere Felder des Treffers lassen den alten Wert stehen. */
fun TagData.applyCandidate(c: Candidate, cover: ByteArray?): TagData = copy(
    title = c.title.ifBlank { title },
    artist = c.artist.ifBlank { artist },
    album = c.album.ifBlank { album },
    albumArtist = c.albumArtist.ifBlank { albumArtist },
    year = c.year.ifBlank { year },
    track = c.track.ifBlank { track },
    trackTotal = c.trackTotal.ifBlank { trackTotal },
    disc = c.disc.ifBlank { disc },
    genre = c.genre.ifBlank { genre },
    cover = cover ?: this.cover,
    coverMime = if (cover != null) "image/jpeg" else coverMime
)

// ---------- Lyrics (LRCLIB, lyrics.ovh) ----------
data class LyricsResult(val synced: String?, val plain: String?, val instrumental: Boolean, val source: String = "LRCLIB") {
    val best: String? get() = synced?.ifBlank { null } ?: plain?.ifBlank { null }
}

/** Ein Treffer der manuellen Suche */
data class LyricsHit(
    val source: String, val artist: String, val title: String, val album: String,
    val durationSec: Double, val synced: String?, val plain: String?
) {
    val hasSync get() = !synced.isNullOrBlank()
    val text get() = synced?.ifBlank { null } ?: plain ?: ""
}

/**
 * Bereinigt Titel für die Suche: "(Live)", "- Live at …", "[Remastered 2011]", "feat. X",
 * "(Radio Edit)", "(Acoustic Version)" usw. entfernen. Gerade Live-Aufnahmen findet man so
 * oft über die Studioversion.
 */
fun cleanTitle(t: String): String {
    var x = t
    x = x.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), " ")
    x = x.replace(Regex("\\s+[-–—]\\s+.*$"), " ")
    x = x.replace(Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE), " ")
    x = x.replace(Regex("\\b(live|remaster(ed)?|radio edit|single version|album version|acoustic|unplugged)\\b.*$", RegexOption.IGNORE_CASE), " ")
    return x.trim().replace(Regex("\\s+"), " ").ifBlank { t.trim() }
}

fun cleanArtist(a: String): String =
    a.replace(Regex("\\s+(feat\\.?|ft\\.?|featuring|&|x|,|und|and)\\s+.*$", RegexOption.IGNORE_CASE), "").trim().ifBlank { a.trim() }

object LrcLib {
    private fun parse(o: JSONObject): LyricsResult? {
        val synced = if (o.isNull("syncedLyrics")) null else o.optString("syncedLyrics")
        val plain = if (o.isNull("plainLyrics")) null else o.optString("plainLyrics")
        val instr = o.optBoolean("instrumental", false)
        if (synced.isNullOrBlank() && plain.isNullOrBlank() && !instr) return null
        return LyricsResult(synced, plain, instr, "LRCLIB")
    }

    private fun searchRaw(url: String): JSONArray? {
        val (c, b) = try { Http.get(url) } catch (_: Exception) { -1 to null }
        return if (c == 200 && b != null) try { JSONArray(String(b)) } catch (_: Exception) { null } else null
    }

    /** Bester Treffer: Länge innerhalb [tol] s, synchronisiert bevorzugt */
    private fun pick(arr: JSONArray?, dur: Int, tol: Int): LyricsResult? {
        if (arr == null) return null
        var best: JSONObject? = null; var bestScore = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = if (dur > 0) abs(o.optDouble("duration", 0.0) - dur) else 0.0
            if (dur > 0 && d > tol) continue
            val hasSync = !o.isNull("syncedLyrics") && o.optString("syncedLyrics").isNotBlank()
            val hasAny = hasSync || (!o.isNull("plainLyrics") && o.optString("plainLyrics").isNotBlank())
            if (!hasAny) continue
            val score = d - (if (hasSync) 20 else 0)
            if (score < bestScore) { bestScore = score; best = o }
        }
        return best?.let { parse(it) }
    }

    /**
     * Automatische Suche in Stufen:
     * 1. exakt (Interpret, Titel, Album, Länge ±2 s)
     * 2. Suche Interpret + Titel, Länge ±8 s
     * 3. bereinigter Titel/Interpret (ohne Live, Remaster, feat. …), Länge ±30 s
     * 4. Freitextsuche "Interpret Titel", Länge ±30 s
     */
    suspend fun find(artist: String, title: String, album: String, durationMs: Long): LyricsResult? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val dur = (durationMs / 1000).toInt()
            // Text ohne Zeitangaben nur merken und weitersuchen – synchronisierte Treffer haben Vorrang
            var plain: LyricsResult? = null
            fun take(r: LyricsResult?): LyricsResult? {
                if (r == null) return null
                if (r.synced != null || r.instrumental) return r
                if (plain == null) plain = r
                return null
            }
            if (artist.isNotBlank()) {
                var u = "https://lrclib.net/api/get?artist_name=${Http.enc(artist)}&track_name=${Http.enc(title)}"
                if (album.isNotBlank()) u += "&album_name=${Http.enc(album)}"
                if (dur > 0) u += "&duration=$dur"
                val (code, b) = try { Http.get(u) } catch (_: Exception) { -1 to null }
                if (code == 200 && b != null) take(parse(JSONObject(String(b))))?.let { return@withContext it }
                take(pick(searchRaw("https://lrclib.net/api/search?artist_name=${Http.enc(artist)}&track_name=${Http.enc(title)}"), dur, 8))
                    ?.let { return@withContext it }
            }
            val ct = cleanTitle(title); val ca = cleanArtist(artist)
            if (ca.isNotBlank() && (ct != title || ca != artist))
                take(pick(searchRaw("https://lrclib.net/api/search?artist_name=${Http.enc(ca)}&track_name=${Http.enc(ct)}"), dur, 30))
                    ?.let { return@withContext it }
            take(pick(searchRaw("https://lrclib.net/api/search?q=${Http.enc("$ca $ct".trim())}"), dur, 30))
                ?.let { return@withContext it }
            plain
        }

    /** Freie Suche für die Trefferliste */
    suspend fun search(query: String): List<LyricsHit> = withContext(Dispatchers.IO) {
        val arr = searchRaw("https://lrclib.net/api/search?q=${Http.enc(query)}") ?: return@withContext emptyList()
        (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            val sy = if (o.isNull("syncedLyrics")) null else o.optString("syncedLyrics").ifBlank { null }
            val pl = if (o.isNull("plainLyrics")) null else o.optString("plainLyrics").ifBlank { null }
            if (sy == null && pl == null) null
            else LyricsHit("LRCLIB", o.optString("artistName"), o.optString("trackName"), o.optString("albumName"),
                o.optDouble("duration", 0.0), sy, pl)
        }.sortedByDescending { it.hasSync }
    }
}

/** Zweite Quelle, nur Text ohne Zeitangaben. Kostenlos, ohne Schlüssel. */
object LyricsOvh {
    suspend fun find(artist: String, title: String, strict: Boolean = true): LyricsResult? = withContext(Dispatchers.IO) {
        if (artist.isBlank() || title.isBlank()) return@withContext null
        val u = "https://api.lyrics.ovh/v1/${Http.enc(artist).replace("+", "%20")}/${Http.enc(title).replace("+", "%20")}"
        val (c, b) = try { Http.get(u, 12000) } catch (_: Exception) { -1 to null }
        if (c != 200 || b == null) return@withContext null
        val t = try { JSONObject(String(b)).optString("lyrics") } catch (_: Exception) { "" }
            .replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        // manche Einträge haben keine Zeilenumbrüche (alles in einer Zeile) -> für die Anzeige unbrauchbar
        if (t.isBlank() || (strict && t.length > 250 && t.count { it == '\n' } < 3)) null
        else LyricsResult(null, t, false, "lyrics.ovh")
    }
}

/** Alle Quellen nacheinander: LRCLIB (mehrstufig), dann lyrics.ovh mit Original- und bereinigtem Titel */
object LyricsSearch {
    suspend fun auto(artist: String, title: String, album: String, durationMs: Long): LyricsResult? {
        val l = try { LrcLib.find(artist, title, album, durationMs) } catch (_: Exception) { null }
        if (l?.synced != null || l?.instrumental == true) return l
        val o = LyricsOvh.find(artist, title) ?: run {
            val ct = cleanTitle(title); val ca = cleanArtist(artist)
            if (ct != title || ca != artist) LyricsOvh.find(ca, ct) else null
        }
        return l ?: o
    }

    /** Manuelle Suche: Trefferliste aus LRCLIB plus lyrics.ovh */
    suspend fun manual(artist: String, title: String): List<LyricsHit> {
        val q = "$artist $title".trim()
        val hits = ArrayList<LyricsHit>()
        try { hits += LrcLib.search(q) } catch (_: Exception) {}
        if (cleanTitle(title) != title) try { hits += LrcLib.search("$artist ${cleanTitle(title)}".trim()) } catch (_: Exception) {}
        LyricsOvh.find(artist, title, strict = false)?.let { hits += LyricsHit("lyrics.ovh", artist, title, "", 0.0, null, it.plain) }
        return hits.distinctBy { it.source + it.artist.lowercase() + it.title.lowercase() + it.durationSec.toInt() + it.hasSync }.take(25)
    }
}

// ---------- Hinweis auf das Album aus dem Speicherordner ----------
data class AlbumHint(val artist: String, val album: String, val fromFolder: Boolean) {
    val label get() = if (artist.isBlank()) album else "$artist – $album"
}

object AlbumHints {
    /** Ordnernamen, die nichts über das Album sagen */
    private val GENERIC = setOf("music", "musik", "download", "downloads", "audio", "mp3", "mp3s", "songs", "lieder",
        "whatsapp audio", "sounds", "media", "sdcard", "emulated", "storage", "alben", "albums", "albumy", "neu", "new",
        "sonstiges", "various", "diverse", "misc", "ringtones", "notifications", "recordings", "aufnahmen", "telegram")

    private fun cleanFolder(x: String): String = x
        .replace('_', ' ')
        .replace(Regex("[(\\[]\\s*(19|20)\\d{2}\\s*[)\\]]"), " ")                 // "(1986)" / "[2015]"
        .replace(Regex("^\\s*(19|20)\\d{2}\\s*[-–.]\\s*"), "")                     // "1986 - Album"
        .replace(Regex("[(\\[][^)\\]]*(flac|mp3|320|kbps|cd ?rip|web)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), " ")
        .trim().replace(Regex("\\s+"), " ")

    /** Ordner, in dem das Album liegt ("CD1", "Disc 2" usw. werden übersprungen) */
    fun albumDir(s: Song): java.io.File? {
        var dir = java.io.File(s.path).parentFile ?: return null
        if (Regex("^(cd|disc|disk|teil)\\s*\\d+.*$", RegexOption.IGNORE_CASE).matches(dir.name.trim())) dir = dir.parentFile ?: dir
        return dir
    }

    /**
     * Album-Hinweis: zuerst der Ordnername ("Interpret - Album" oder "Album" mit Interpret aus Tag bzw.
     * Oberordner), sonst das Album-Tag.
     */
    fun forSong(s: Song, tag: TagData?): AlbumHint? {
        val tagArtist = tag?.albumArtist?.ifBlank { null } ?: tag?.artist?.ifBlank { null } ?: s.artist
        val tagAlbum = tag?.album?.ifBlank { null } ?: s.album
        val dir = albumDir(s)
        val n = cleanFolder(dir?.name ?: "")
        val parent = cleanFolder(dir?.parentFile?.name ?: "")
        val folderUseful = n.isNotBlank() && norm(n) !in GENERIC && norm(n) != norm(tagArtist)
        if (folderUseful) {
            val parts = n.split(Regex("\\s+[-–]\\s+"), limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) return AlbumHint(parts[0].trim(), parts[1].trim(), true)
            val artist = tagArtist.ifBlank { if (parent.isNotBlank() && norm(parent) !in GENERIC) parent else "" }
            return AlbumHint(artist, n, true)
        }
        return if (tagAlbum.isNotBlank()) AlbumHint(tagArtist, tagAlbum, false) else null
    }
}
