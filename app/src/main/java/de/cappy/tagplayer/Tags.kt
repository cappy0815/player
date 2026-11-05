package de.cappy.tagplayer

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.id3.valuepair.ImageFormats
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.ID3V2Version
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Inhalt der Tags, so wie der Editor ihn bearbeitet. */
data class TagData(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    val track: String = "",
    val trackTotal: String = "",
    val disc: String = "",
    val genre: String = "",
    val lyrics: String = "",
    val cover: ByteArray? = null,
    val coverMime: String = "image/jpeg"
) {
    /** Liste der Felder, die sich gegenüber [other] unterscheiden (für die Vorschau) */
    fun diff(other: TagData): List<Triple<String, String, String>> {
        val l = ArrayList<Triple<String, String, String>>()
        fun c(n: String, a: String, b: String) { if (a.trim() != b.trim()) l += Triple(n, a, b) }
        c("Titel", other.title, title); c("Interpret", other.artist, artist); c("Album", other.album, album)
        c("Album-Interpret", other.albumArtist, albumArtist); c("Jahr", other.year, year)
        c("Titelnr.", other.track, track); c("Titel gesamt", other.trackTotal, trackTotal)
        c("CD", other.disc, disc); c("Genre", other.genre, genre)
        if ((cover != null) && (other.cover == null || !cover.contentEquals(other.cover))) l += Triple("Cover", if (other.cover == null) "–" else "alt", "neu")
        return l
    }
}

object Tags {
    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        val o = TagOptionSingleton.getInstance()
        o.isAndroid = true                       // Artwork ohne java.awt
        o.setID3V2Version(ID3V2Version.ID3_V23)  // v2.3: liest jeder Player und der MediaStore
    }

    fun canEdit(path: String): Boolean {
        val f = File(path)
        return f.exists() && f.canWrite()
    }

    fun read(path: String): TagData {
        val af = AudioFileIO.read(File(path))
        val t: Tag = af.tag ?: return TagData()
        fun g(k: FieldKey) = try { t.getFirst(k) ?: "" } catch (_: Exception) { "" }
        val art = try { t.firstArtwork } catch (_: Exception) { null }
        return TagData(
            title = g(FieldKey.TITLE), artist = g(FieldKey.ARTIST), album = g(FieldKey.ALBUM),
            albumArtist = g(FieldKey.ALBUM_ARTIST), year = g(FieldKey.YEAR),
            track = g(FieldKey.TRACK), trackTotal = g(FieldKey.TRACK_TOTAL),
            disc = g(FieldKey.DISC_NO), genre = g(FieldKey.GENRE), lyrics = g(FieldKey.LYRICS),
            cover = art?.binaryData, coverMime = art?.mimeType ?: "image/jpeg"
        )
    }

    /** Nur die Lyrics lesen (beim Abspielen). */
    fun readLyrics(path: String): String = try {
        AudioFileIO.read(File(path)).tag?.getFirst(FieldKey.LYRICS) ?: ""
    } catch (_: Exception) { "" }

    /**
     * Schreibt die Tags in die Datei. Leere Felder werden gelöscht.
     * @param writeCover false = Cover in der Datei unverändert lassen
     */
    fun write(path: String, d: TagData, writeCover: Boolean) {
        val af = AudioFileIO.read(File(path))
        val t = af.tagOrCreateAndSetDefault
        fun s(k: FieldKey, v: String) {
            val x = v.trim()
            if (x.isEmpty()) try { t.deleteField(k) } catch (_: Exception) {}
            else t.setField(k, x)
        }
        s(FieldKey.TITLE, d.title); s(FieldKey.ARTIST, d.artist); s(FieldKey.ALBUM, d.album)
        s(FieldKey.ALBUM_ARTIST, d.albumArtist); s(FieldKey.YEAR, d.year)
        s(FieldKey.TRACK, d.track); s(FieldKey.TRACK_TOTAL, d.trackTotal)
        s(FieldKey.DISC_NO, d.disc); s(FieldKey.GENRE, d.genre); s(FieldKey.LYRICS, d.lyrics)
        if (writeCover) {
            try { t.deleteArtworkField() } catch (_: Exception) {}
            if (d.cover != null) t.setField(artwork(d.cover, d.coverMime))
        }
        af.commit()
        verify(path, d, writeCover)
    }

    /** Liest die Datei nach dem Schreiben erneut und prüft, ob die Änderung wirklich drin ist. */
    private fun verify(path: String, d: TagData, cover: Boolean) {
        val r = read(path)
        if (r.title.trim() != d.title.trim()) throw Exception("Schreiben nicht wirksam: Titel in der Datei ist „${r.title}“")
        if (r.artist.trim() != d.artist.trim()) throw Exception("Schreiben nicht wirksam: Interpret in der Datei ist „${r.artist}“")
        if (cover && d.cover != null && r.cover == null) throw Exception("Schreiben nicht wirksam: Cover fehlt in der Datei")
        if (cover && d.cover == null && r.cover != null) throw Exception("Schreiben nicht wirksam: Cover noch vorhanden")
    }

    /** Nur die Lyrics schreiben. */
    fun writeLyrics(path: String, lyrics: String) {
        val af = AudioFileIO.read(File(path))
        af.tagOrCreateAndSetDefault.setField(FieldKey.LYRICS, lyrics)
        af.commit()
    }

    private fun artwork(data: ByteArray, mime: String) = AndroidArtwork().apply {
        binaryData = data
        mimeType = if (mime.isNotBlank()) mime else ImageFormats.getMimeTypeForBinarySignature(data) ?: "image/jpeg"
        pictureType = PictureTypes.DEFAULT_ID   // 3 = Front Cover
        description = ""
    }
}
