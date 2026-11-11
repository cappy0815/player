package de.cappy.tagplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import java.io.File
import java.io.FileOutputStream

/** Abspielbarer Eintrag für einen Titel (App, Benachrichtigung, Android Auto) */
fun songMediaItem(s: Song, mediaId: String = s.id.toString()): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(s.uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(s.title.ifBlank { s.fileName })
            .setArtist(s.artist)
            .setAlbumTitle(s.album)
            .setArtworkUri(CoverProvider.uri(s))
            .setIsBrowsable(false).setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    ).build()

/**
 * Bibliothek für Android Auto (und andere Media-Browser):
 * Start → Playlists · Alben · Interpreten · Ordner · Alle Titel · Zufallsmix
 *
 * Kennungen: "pl", "pl:<id>", "al", "al:<albumId>", "ar", "ar:<name>", "fo", "fo:<pfad>", "all", "mix",
 * Titel in einer Liste: "s:<liste>|<songId>" – beim Antippen wird die ganze Liste ab diesem Titel gespielt.
 */
class AutoLibrary(private val ctx: Context) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private suspend fun ensureLoaded() {
        Playlists.load(ctx)
        if (!Library.loadedOnce && hasAudio(ctx)) Library.load(ctx)
    }

    private fun folder(id: String, title: String, sub: String? = null, art: Uri? = null, style: Int? = null): MediaItem {
        val extras = Bundle()
        if (style != null) extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(sub).setArtworkUri(art)
                .setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).setExtras(extras).build()
        ).build()
    }

    private fun playable(id: String, title: String, sub: String?): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(sub)
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build()
        ).build()

    // ---------- Inhalte ----------
    private fun root(): MediaItem {
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        return MediaItem.Builder().setMediaId("root").setMediaMetadata(
            MediaMetadata.Builder().setTitle("Player").setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).setExtras(extras).build()
        ).build()
    }

    /** Titel einer Liste (Album, Ordner, Playlist …) */
    private fun songsOf(id: String): List<Song> {
        val all = Library.songs.toList()
        return when {
            id == "all" -> all.sortedBy { (it.title.ifBlank { it.fileName }).lowercase() }
            id == "mix" -> all.shuffled()
            id.startsWith("pl:") -> Playlists.byId(id.removePrefix("pl:").toLongOrNull() ?: -1)?.songs() ?: emptyList()
            id.startsWith("al:") -> all.filter { it.albumId.toString() == id.removePrefix("al:") }.sortedBy { it.track }
            id.startsWith("ar:") -> all.filter { it.artist == id.removePrefix("ar:") }.sortedWith(compareBy({ it.album.lowercase() }, { it.track }))
            id.startsWith("fo:") -> Folders.songsIn(all, id.removePrefix("fo:"))
            else -> emptyList()
        }
    }

    private fun children(parent: String): List<MediaItem> {
        val all = Library.songs.toList()
        return when {
            parent == "root" -> listOf(
                folder("pl", "Playlists", "${Playlists.all.size}"),
                folder("al", "Alben"),
                folder("ar", "Interpreten"),
                folder("fo", "Ordner"),
                folder("all", "Alle Titel", "${all.size}"),
                playable("mix", "Zufallsmix", "alle Titel gemischt")
            )
            parent == "pl" -> Playlists.all.map { pl -> folder("pl:${pl.id}", pl.name, "${pl.songs().size} Titel", pl.songs().firstOrNull()?.let { CoverProvider.uri(it) }) }
            parent == "al" -> all.groupBy { it.albumId }.values.sortedBy { it.first().album.lowercase() }.map { l ->
                val s = l.first(); folder("al:${s.albumId}", s.album.ifBlank { "Unbekanntes Album" }, s.artist, CoverProvider.uri(s))
            }
            parent == "ar" -> all.groupBy { it.artist }.entries.sortedBy { it.key.lowercase() }.map { (n, l) ->
                folder("ar:$n", n.ifBlank { "Unbekannter Interpret" }, "${l.size} Titel", CoverProvider.uri(l.first()))
            }
            parent == "fo" || parent.startsWith("fo:") -> {
                val dir = if (parent == "fo") Folders.root(all) else parent.removePrefix("fo:")
                Folders.subfolders(all, dir).map { (p, n) -> folder("fo:$p", File(p).name, "$n Titel") } +
                    Folders.songsIn(all, dir).map { songMediaItem(it, "s:fo:$dir|${it.id}") }
            }
            else -> songsOf(parent).map { songMediaItem(it, "s:$parent|${it.id}") }
        }
    }

    private fun page(list: List<MediaItem>, page: Int, size: Int): ImmutableList<MediaItem> {
        if (size <= 0 || size == Int.MAX_VALUE) return ImmutableList.copyOf(list)
        val from = (page * size).coerceAtMost(list.size)
        return ImmutableList.copyOf(list.subList(from, (from + size).coerceAtMost(list.size)))
    }

    private fun search(q: String): List<Song> {
        val t = q.trim().lowercase(); if (t.isEmpty()) return emptyList()
        return Library.songs.filter { s ->
            listOf(s.title, s.artist, s.album, s.fileName).any { it.lowercase().contains(t) }
        }.take(100)
    }

    /** Eintrag → abspielbare Titel (ganze Liste ab dem gewählten Titel) */
    private fun resolve(items: List<MediaItem>): Pair<List<MediaItem>, Int> {
        if (items.size == 1) {
            val it0 = items[0]
            val id = it0.mediaId
            // Sprachbefehl "Spiele …" ohne Kennung
            val q = it0.requestMetadata.searchQuery
            if (id.isBlank() && !q.isNullOrBlank()) return search(q).map { songMediaItem(it) } to 0
            if (id.startsWith("s:")) {
                val parent = id.removePrefix("s:").substringBeforeLast('|')
                val songId = id.substringAfterLast('|').toLongOrNull()
                val list = if (parent.startsWith("search:")) search(parent.removePrefix("search:")) else songsOf(parent)
                val start = list.indexOfFirst { it.id == songId }.coerceAtLeast(0)
                return list.map { songMediaItem(it) } to start
            }
            if (id == "mix" || id.startsWith("pl:") || id.startsWith("al:") || id.startsWith("ar:") || id == "all")
                return songsOf(id).map { songMediaItem(it) } to 0
        }
        // einzelne Titel (eigene App liefert fertige Einträge mit Adresse)
        val out = items.mapNotNull { m ->
            if (m.localConfiguration != null) m
            else (m.mediaId.substringAfterLast('|').toLongOrNull())?.let { Library.byId(it) }?.let { songMediaItem(it) }
        }
        return out to 0
    }

    // ---------- Media3-Callback ----------
    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                                  params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future { ensureLoaded(); LibraryResult.ofItem(root(), params) }

    override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                               page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future { ensureLoaded(); LibraryResult.ofItemList(page(children(parentId), page, pageSize), params) }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        ensureLoaded()
        val s = mediaId.substringAfterLast('|').toLongOrNull()?.let { Library.byId(it) }
        if (s != null) LibraryResult.ofItem(songMediaItem(s, mediaId), null)
        else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                          params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<Void>> =
        scope.future {
            ensureLoaded()
            session.notifySearchResultChanged(browser, query, search(query).size, params)
            LibraryResult.ofVoid()
        }

    override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                                   page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        ensureLoaded()
        LibraryResult.ofItemList(page(search(query).map { songMediaItem(it, "s:search:$query|${it.id}") }, page, pageSize), params)
    }

    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                 mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
        scope.future { ensureLoaded(); resolve(mediaItems).first.toMutableList() }

    override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                 mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        ensureLoaded()
        val (list, start) = resolve(mediaItems)
        // eigene App: Startindex wie übergeben; Auto-Auswahl: Index aus der Liste
        val expanded = mediaItems.size == 1 && list.size > 1
        MediaSession.MediaItemsWithStartPosition(list,
            if (expanded) start else startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0)),
            if (expanded) 0L else startPositionMs)
    }
}

/**
 * Liefert eingebettete Cover als Bild (300 px) über eine content://-Adresse,
 * damit Android Auto und die Benachrichtigung sie anzeigen können. Nur lesend.
 */
class CoverProvider : ContentProvider() {
    companion object {
        const val AUTH = "de.cappy.tagplayer.cover"
        fun uri(s: Song): Uri = Uri.parse("content://$AUTH/${s.id}/${File(s.path).lastModified()}")
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        val id = uri.pathSegments.getOrNull(0)?.toLongOrNull() ?: return null
        val stamp = uri.pathSegments.getOrNull(1) ?: "0"
        val dir = File(c.cacheDir, "covers").apply { mkdirs() }
        val f = File(dir, "$id-$stamp.jpg")
        if (!f.exists()) {
            val path = Library.byId(id)?.path ?: queryPath(c, id) ?: return null
            val r = android.media.MediaMetadataRetriever()
            val data = try { r.setDataSource(path); r.embeddedPicture } catch (_: Exception) { null } finally { r.release() }
                ?: return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= 300 && o.outHeight / (sample * 2) >= 300) sample *= 2
            val b = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Pfad direkt aus dem MediaStore (Dienst läuft ohne geladene Bibliothek) */
    private fun queryPath(c: Context, id: Long): String? = try {
        c.contentResolver.query(
            android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
            arrayOf(android.provider.MediaStore.Audio.Media.DATA), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
