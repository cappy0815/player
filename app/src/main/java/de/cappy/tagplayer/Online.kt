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

private const val UA = "Player/0.3 ( https://github.com/cappy0815 )"

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
    var confidence: Int = 0
)

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

    suspend fun search(song: Song, tag: TagData?): List<Candidate> {
        val (title, artist) = searchTerms(song, tag)
        if (title.isBlank()) return emptyList()
        var q = "recording:\"${esc(title)}\""
        if (artist.isNotBlank()) q += " AND artist:\"${esc(artist)}\""
        val url = "https://musicbrainz.org/ws/2/recording?fmt=json&limit=10&query=" + Http.enc(q)
        val (code, body) = throttled(url)
        if (code != 200 || body == null) throw Exception("MusicBrainz antwortet nicht (Code $code)")
        val recs = JSONObject(String(body)).optJSONArray("recordings") ?: return emptyList()
        val albumHint = tag?.album?.ifBlank { null } ?: song.album
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
    private fun confidence(c: Candidate, title: String, artist: String, durMs: Long): Int {
        var s = c.mbScore
        val nt = norm(title); val nc = norm(c.title)
        if (nt != nc) s -= if (nt.isNotEmpty() && (nc.contains(nt) || nt.contains(nc))) 8 else 25
        if (artist.isNotBlank()) {
            val na = norm(artist); val nca = norm(c.artist)
            if (na != nca) s -= if (nca.contains(na) || na.contains(nca)) 5 else 25
        } else s -= 10
        if (durMs > 0 && c.lengthMs > 0) {
            val d = abs(durMs - c.lengthMs)
            s -= when { d <= 3000 -> 0; d <= 7000 -> 8; d <= 15000 -> 20; else -> 40 }
        } else s -= 5
        return s.coerceIn(0, 100)
    }

    /** Cover vom Cover Art Archive (500 px), zuerst Veröffentlichung, dann Release-Gruppe */
    suspend fun cover(c: Candidate): ByteArray? = withContext(Dispatchers.IO) {
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
    cover = cover ?: this.cover,
    coverMime = if (cover != null) "image/jpeg" else coverMime
)

// ---------- Lyrics (LRCLIB) ----------
data class LyricsResult(val synced: String?, val plain: String?, val instrumental: Boolean) {
    val best: String? get() = synced?.ifBlank { null } ?: plain?.ifBlank { null }
}

object LrcLib {
    suspend fun find(artist: String, title: String, album: String, durationMs: Long): LyricsResult? =
        withContext(Dispatchers.IO) {
            if (artist.isBlank() || title.isBlank()) return@withContext null
            val dur = (durationMs / 1000).toInt()
            // 1. exakte Abfrage (Dauer ±2 s laut LRCLIB)
            var u = "https://lrclib.net/api/get?artist_name=${Http.enc(artist)}&track_name=${Http.enc(title)}"
            if (album.isNotBlank()) u += "&album_name=${Http.enc(album)}"
            if (dur > 0) u += "&duration=$dur"
            val (code, b) = try { Http.get(u) } catch (_: Exception) { -1 to null }
            if (code == 200 && b != null) parse(JSONObject(String(b)))?.let { return@withContext it }
            // 2. Suche, bester Treffer nach Länge; synchronisierte Texte bevorzugt
            val s = "https://lrclib.net/api/search?artist_name=${Http.enc(artist)}&track_name=${Http.enc(title)}"
            val (c2, b2) = try { Http.get(s) } catch (_: Exception) { -1 to null }
            if (c2 != 200 || b2 == null) return@withContext null
            val arr = JSONArray(String(b2))
            var best: JSONObject? = null; var bestScore = Double.MAX_VALUE
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = if (dur > 0) abs(o.optDouble("duration", 0.0) - dur) else 0.0
                if (dur > 0 && d > 8) continue
                val score = d - (if (o.optString("syncedLyrics").isNotBlank() && !o.isNull("syncedLyrics")) 3 else 0)
                if (score < bestScore) { bestScore = score; best = o }
            }
            best?.let { parse(it) }
        }

    private fun parse(o: JSONObject): LyricsResult? {
        val synced = if (o.isNull("syncedLyrics")) null else o.optString("syncedLyrics")
        val plain = if (o.isNull("plainLyrics")) null else o.optString("plainLyrics")
        val instr = o.optBoolean("instrumental", false)
        if (synced.isNullOrBlank() && plain.isNullOrBlank() && !instr) return null
        return LyricsResult(synced, plain, instr)
    }
}
