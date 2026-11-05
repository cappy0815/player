package de.cappy.tagplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Einfache Drossel: Mindestabstand zwischen zwei Anfragen an denselben Dienst */
private class Throttle(private val gapMs: Long) {
    private val lock = Mutex()
    private var last = 0L
    suspend fun <T> run(block: () -> T): T = lock.withLock {
        val wait = gapMs - (System.currentTimeMillis() - last)
        if (wait > 0) delay(wait)
        try { withContext(Dispatchers.IO) { block() } } finally { last = System.currentTimeMillis() }
    }
}

// ---------- Deezer (frei, ohne Schlüssel; Limit 50 Anfragen / 5 s) ----------
object Deezer {
    private val t = Throttle(150)

    private suspend fun json(url: String): JSONObject? {
        repeat(2) { attempt ->
            val (c, b) = t.run { try { Http.get(url, 15000) } catch (_: Exception) { -1 to null } }
            if (c != 200 || b == null) return null
            val o = try { JSONObject(String(b)) } catch (_: Exception) { return null }
            // Kontingent überschritten -> kurz warten, einmal wiederholen
            if (o.optJSONObject("error")?.optInt("code") == 4 && attempt == 0) { delay(1500); return@repeat }
            return if (o.has("error")) null else o
        }
        return null
    }

    /** Titelsuche. Nummer/CD/Jahr fehlen in der Suche und werden mit [enrich] nachgeladen. */
    suspend fun search(song: Song, tag: TagData?, hint: AlbumHint?): List<Candidate> {
        val (title, artist0) = MusicBrainz.searchTerms(song, tag)
        if (title.isBlank()) return emptyList()
        val artist = artist0.ifBlank { hint?.artist ?: "" }
        val q = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        val out = ArrayList<Candidate>()
        if (hint?.fromFolder == true) try { out += fromAlbum(song, title, artist, hint) } catch (_: Exception) {}
        val o = json("https://api.deezer.com/search/track?limit=15&q=" + Http.enc(q)) ?: return out
        val arr = o.optJSONArray("data") ?: return out
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            val al = x.optJSONObject("album") ?: continue
            val c = Candidate(
                recordingId = "", releaseId = al.optLong("id").toString(), releaseGroupId = "",
                title = cleanRemaster(x.optString("title")), artist = x.optJSONObject("artist")?.optString("name") ?: "",
                album = cleanAlbum(al.optString("title")), albumArtist = x.optJSONObject("artist")?.optString("name") ?: "",
                year = "", track = "", trackTotal = "", disc = "",
                lengthMs = x.optLong("duration", 0) * 1000, mbScore = 100,
                source = "Deezer", coverUrl = al.optString("cover_big"), extId = x.optLong("id").toString()
            )
            c.confidence = scoreTrack(100, c, title, artist, song.durationMs)
            folderAdjust(c, hint)
            out += c
        }
        return out.sortedByDescending { it.confidence }.distinctBy { it.extId }.take(6)
    }

    /** Titellisten bereits gefundener Alben (Ordner = Album, gilt für alle Lieder im Ordner) */
    private val albumTracks = HashMap<String, List<Candidate>>()

    /**
     * Ordner = Album: Album bei Deezer suchen, Titelliste laden und das Lied darin finden.
     * Liefert Treffer mit Titelnummer und CD direkt aus der Titelliste.
     */
    private suspend fun fromAlbum(song: Song, title: String, artist: String, hint: AlbumHint): List<Candidate> {
        val key = norm(hint.artist) + "|" + norm(hint.album)
        val tracks = albumTracks.getOrPut(key) {
            val al = searchAlbum(hint).firstOrNull { it.confidence >= 80 } ?: return@getOrPut emptyList()
            val o = json("https://api.deezer.com/album/${al.releaseId}/tracks?limit=100") ?: return@getOrPut emptyList()
            val arr = o.optJSONArray("data") ?: return@getOrPut emptyList()
            val discs = (0 until arr.length()).maxOfOrNull { arr.getJSONObject(it).optInt("disk_number", 1) } ?: 1
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { x ->
                Candidate("", al.releaseId, "", cleanRemaster(x.optString("title")), x.optJSONObject("artist")?.optString("name") ?: al.artist,
                    al.album, al.albumArtist, "", x.optInt("track_position", 0).takeIf { it > 0 }?.toString() ?: "",
                    arr.length().toString(), if (discs > 1) x.optInt("disk_number", 0).toString() else "",
                    x.optLong("duration", 0) * 1000, 100, source = "Deezer", coverUrl = al.coverUrl, extId = x.optLong("id").toString())
            }
        }
        return tracks.map { t -> t.copy().also { c -> c.confidence = scoreTrack(100, c, title, artist, song.durationMs); folderAdjust(c, hint) } }
            .filter { it.confidence >= 50 }.sortedByDescending { it.confidence }.take(3)
    }

    /** Titelnummer, CD, Jahr, Titelanzahl und Genre nachladen (2 Anfragen) */
    suspend fun enrich(c: Candidate): Candidate {
        if (c.source != "Deezer" || c.extId.isBlank()) return c
        val tr = json("https://api.deezer.com/track/${c.extId}") ?: return c
        val al = json("https://api.deezer.com/album/${c.releaseId}")
        val genre = al?.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)?.optString("name") ?: ""
        val discs = al?.optJSONObject("tracks")?.optJSONArray("data")?.let { d ->
            (0 until d.length()).maxOfOrNull { d.getJSONObject(it).optInt("disk_number", 1) } } ?: 1
        val albumArtist = al?.optJSONObject("artist")?.optString("name")?.ifBlank { null } ?: c.albumArtist
        return c.copy(
            track = tr.optInt("track_position", 0).takeIf { it > 0 }?.toString() ?: "",
            disc = tr.optInt("disk_number", 0).takeIf { it > 0 && discs > 1 }?.toString() ?: "",
            year = (tr.optString("release_date").ifBlank { al?.optString("release_date") ?: "" }).take(4),
            trackTotal = al?.optInt("nb_tracks", 0)?.takeIf { it > 0 }?.toString() ?: "",
            genre = genre, albumArtist = albumArtist,
            coverUrl = al?.optString("cover_big")?.ifBlank { null } ?: c.coverUrl
        ).also { it.confidence = c.confidence }
    }

    /** Albumsuche für die Cover-Prüfung */
    suspend fun searchAlbum(hint: AlbumHint): List<Candidate> {
        val q = listOf(hint.artist, hint.album).filter { it.isNotBlank() }.joinToString(" ")
        val o = json("https://api.deezer.com/search/album?limit=10&q=" + Http.enc(q)) ?: return emptyList()
        val arr = o.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { a ->
            val name = cleanAlbum(a.optString("title")); val art = a.optJSONObject("artist")?.optString("name") ?: ""
            Candidate("", a.optLong("id").toString(), "", name, art, name, art, "", "",
                a.optInt("nb_tracks", 0).takeIf { it > 0 }?.toString() ?: "", "", 0, 100,
                confidence = scoreAlbum(100, hint, name, art), source = "Deezer", coverUrl = a.optString("cover_big"))
        }.sortedByDescending { it.confidence }.take(5)
    }
}

// ---------- Apple iTunes Search (frei, ohne Schlüssel; ca. 20 Anfragen pro Minute) ----------
object ITunes {
    private val t = Throttle(3000)

    private suspend fun results(url: String): JSONArray? {
        val (c, b) = t.run { try { Http.get(url, 15000) } catch (_: Exception) { -1 to null } }
        if (c != 200 || b == null) return null
        return try { JSONObject(String(b).trim()).optJSONArray("results") } catch (_: Exception) { null }
    }

    /** Cover in 600 px statt 100 px */
    private fun art(o: JSONObject) = o.optString("artworkUrl100").replace("100x100bb", "600x600bb")

    suspend fun search(song: Song, tag: TagData?, hint: AlbumHint?): List<Candidate> {
        val (title, artist0) = MusicBrainz.searchTerms(song, tag)
        if (title.isBlank()) return emptyList()
        val artist = artist0.ifBlank { hint?.artist ?: "" }
        val term = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        val arr = results("https://itunes.apple.com/search?entity=song&country=de&limit=15&term=" + Http.enc(term)) ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            val discs = x.optInt("discCount", 1)
            val c = Candidate(
                recordingId = "", releaseId = x.optLong("collectionId").toString(), releaseGroupId = "",
                title = cleanRemaster(x.optString("trackName")), artist = x.optString("artistName"),
                album = cleanAlbum(x.optString("collectionName")),
                albumArtist = x.optString("collectionArtistName").ifBlank { x.optString("artistName") },
                year = x.optString("releaseDate").take(4),
                track = x.optInt("trackNumber", 0).takeIf { it > 0 }?.toString() ?: "",
                trackTotal = x.optInt("trackCount", 0).takeIf { it > 0 }?.toString() ?: "",
                disc = if (discs > 1) x.optInt("discNumber", 0).takeIf { it > 0 }?.toString() ?: "" else "",
                lengthMs = x.optLong("trackTimeMillis", 0), mbScore = 100,
                source = "iTunes", coverUrl = art(x), genre = x.optString("primaryGenreName")
            )
            c.confidence = scoreTrack(100, c, title, artist, song.durationMs)
            folderAdjust(c, hint)
            out += c
        }
        return out.sortedByDescending { it.confidence }.take(6)
    }

    suspend fun searchAlbum(hint: AlbumHint): List<Candidate> {
        val term = listOf(hint.artist, hint.album).filter { it.isNotBlank() }.joinToString(" ")
        val arr = results("https://itunes.apple.com/search?entity=album&country=de&limit=10&term=" + Http.enc(term)) ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { a ->
            val name = cleanAlbum(a.optString("collectionName")); val art = a.optString("artistName")
            Candidate("", a.optLong("collectionId").toString(), "", name, art, name, art, a.optString("releaseDate").take(4), "",
                a.optInt("trackCount", 0).takeIf { it > 0 }?.toString() ?: "", "", 0, 100,
                confidence = scoreAlbum(100, hint, name, art), source = "iTunes", coverUrl = art(a), genre = a.optString("primaryGenreName"))
        }.sortedByDescending { it.confidence }.take(5)
    }
}

// ---------- Alle Quellen zusammen ----------
private val SOURCE_ORDER = listOf("MusicBrainz", "Deezer", "iTunes")

/**
 * Sortieren: zuerst Trefferquote, bei Gleichstand gewinnt, was mehrere Quellen übereinstimmend liefern,
 * dann MusicBrainz vor Deezer vor iTunes. Übereinstimmung hebt außerdem unsichere Treffer an (+5).
 */
private fun rank(all: List<Candidate>, key: (Candidate) -> String): List<Candidate> {
    val agree = all.groupBy { key(it) }.mapValues { e -> e.value.map { it.source }.toSet().size }
    all.forEach { c -> if ((agree[key(c)] ?: 0) >= 2) c.confidence = (c.confidence + 5).coerceAtMost(100) }
    return all.sortedWith(compareByDescending<Candidate> { it.confidence }
        .thenByDescending { agree[key(it)] ?: 0 }
        .thenBy { SOURCE_ORDER.indexOf(it.source) })
}

object TagSearch {
    /**
     * Titel in MusicBrainz und Deezer suchen; iTunes zusätzlich, wenn [thorough] oder noch nichts Sicheres dabei ist.
     * Der Ordnername fließt in allen Quellen als Album-Hinweis ein.
     */
    suspend fun search(song: Song, tag: TagData?, threshold: Int, thorough: Boolean): List<Candidate> {
        val hint = AlbumHints.forSong(song, tag)
        val all = ArrayList<Candidate>()
        var error: Exception? = null
        try { all += MusicBrainz.search(song, tag) } catch (e: Exception) { error = e }
        try { all += Deezer.search(song, tag, hint) } catch (_: Exception) {}
        if (thorough || (all.maxOfOrNull { it.confidence } ?: 0) < threshold)
            try { all += ITunes.search(song, tag, hint) } catch (_: Exception) {}
        if (all.isEmpty() && error != null) throw error
        return rank(all) { norm(it.title) + "|" + norm(it.album) }.take(12)
    }

    /** Vor dem Übernehmen fehlende Angaben nachladen (Deezer) */
    suspend fun enrich(c: Candidate): Candidate = if (c.source == "Deezer") Deezer.enrich(c) else c
}

object AlbumSearch {
    /** Album für die Cover-Prüfung in MusicBrainz, Deezer und ggf. iTunes suchen */
    suspend fun search(hint: AlbumHint, filesInFolder: Int, threshold: Int, thorough: Boolean): List<Candidate> {
        val all = ArrayList<Candidate>()
        try { all += MusicBrainz.searchRelease(hint, filesInFolder) } catch (_: Exception) {}
        try { all += Deezer.searchAlbum(hint) } catch (_: Exception) {}
        if (thorough || (all.maxOfOrNull { it.confidence } ?: 0) < threshold)
            try { all += ITunes.searchAlbum(hint) } catch (_: Exception) {}
        return rank(all) { norm(it.album) + "|" + norm(it.albumArtist) }.take(12)
    }
}

object Covers {
    private suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val (c, b) = try { Http.get(url, 20000) } catch (_: Exception) { -1 to null }
        if (c == 200 && b != null && b.size > 1000) b else null
    }

    /** Cover zu einem Treffer: direkter Link (Deezer/iTunes) oder Cover Art Archive */
    suspend fun forCandidate(c: Candidate): ByteArray? =
        download(c.coverUrl) ?: MusicBrainz.cover(c)

    /**
     * Erstes verfügbares Cover aus der Liste (ab [minConfidence]). Hat das Archiv zum besten
     * MusicBrainz-Treffer kein Bild, wird der nächste Treffer bzw. die nächste Quelle versucht.
     */
    suspend fun firstAvailable(cands: List<Candidate>, minConfidence: Int): Pair<Candidate, ByteArray>? {
        for (c in cands.filter { it.confidence >= minConfidence }.take(5)) {
            forCandidate(c)?.let { return c to it }
        }
        return null
    }
}
