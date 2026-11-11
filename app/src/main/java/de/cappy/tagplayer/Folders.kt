@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package de.cappy.tagplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Ordneransicht auf Basis der Dateipfade aus dem MediaStore */
object Folders {
    /** Gemeinsamer Oberordner aller Titel (z. B. /storage/emulated/0/Music) */
    fun root(songs: List<Song>): String {
        if (songs.isEmpty()) return "/"
        var p = File(songs.first().path).parent ?: "/"
        for (s in songs) {
            while (!(s.path.startsWith("$p/")) && p.length > 1) p = File(p).parent ?: "/"
        }
        return p
    }

    /** Alle Titel in [dir] inklusive Unterordner */
    fun songsBelow(songs: List<Song>, dir: String): List<Song> =
        songs.filter { it.path.startsWith("$dir/") }.sortedBy { it.path.lowercase() }

    /** Direkte Unterordner von [dir] mit Anzahl Titel darunter */
    fun subfolders(songs: List<Song>, dir: String): List<Pair<String, Int>> =
        songsBelow(songs, dir)
            .mapNotNull { s -> s.path.removePrefix("$dir/").substringBefore('/', "").ifBlank { null } }
            .groupingBy { it }.eachCount()
            .entries.sortedBy { it.key.lowercase() }
            .map { "$dir/${it.key}" to it.value }

    /** Titel direkt in [dir] */
    fun songsIn(songs: List<Song>, dir: String): List<Song> =
        songs.filter { File(it.path).parent == dir }.sortedWith(compareBy({ it.track }, { it.fileName.lowercase() }))

    fun shortName(path: String): String = path.removePrefix("/storage/emulated/0").ifBlank { "/" }
}

/** Knöpfe "Tags prüfen" / "Cover prüfen" für eine Auswahl von Titeln */
@Composable
fun CheckButtons(label: String, songs: List<Song>, playlistButton: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill("Tags prüfen", modifier = Modifier.weight(1f), enabled = songs.isNotEmpty()) {
            Matcher.prepare(label, songs, MatchMode.TAGS); Navi.go(Nav.Match)
        }
        Pill("Cover prüfen", modifier = Modifier.weight(1f), enabled = songs.isNotEmpty()) {
            Matcher.prepare(label, songs, MatchMode.COVER); Navi.go(Nav.Match)
        }
    }
    if (playlistButton) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Pill("Alle zur Playlist", modifier = Modifier.fillMaxWidth(), enabled = songs.isNotEmpty()) { PlaylistUi.pending = songs }
    }
}

@Composable
fun FolderList(all: List<Song>, dir: String, header: (@Composable () -> Unit)? = null) {
    val subs = Folders.subfolders(all, dir)
    val direct = Folders.songsIn(all, dir)
    LazyColumn(Modifier.fillMaxSize(), state = rememberListState("folder:$dir")) {
        if (header != null) item { header() }
        items(subs, key = { "d:" + it.first }) { (path, count) ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { Navi.go(Nav.Folder(path)) }, onLongClick = { menu = true })
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(File(path).name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$count Titel", color = TextDim, fontSize = 13.sp)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Optionen", tint = TextDim) }
                    SongsMenu(menu, { menu = false }, "Ordner „${File(path).name}“") { Folders.songsBelow(Library.songs.toList(), path) }
                }
            }
        }
        itemsIndexed(direct, key = { _, s -> s.id }) { i, s -> SongRow(s) { PlayerHub.play(direct, i) } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
fun FolderScreen(path: String) {
    val all = Library.songs.toList()
    val below = Folders.songsBelow(all, path)
    Column(Modifier.fillMaxSize()) {
        TopBar(File(path).name, onBack = { Navi.back() })
        FolderList(all, path, header = {
            Column {
                Text(Folders.shortName(path), color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                Text("${below.size} Titel inkl. Unterordner", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Pill("Alles abspielen", filled = true, modifier = Modifier.fillMaxWidth(), enabled = below.isNotEmpty()) { PlayerHub.play(below, 0) }
                }
                CheckButtons("Ordner „${File(path).name}“", below)
                Spacer(Modifier.height(6.dp))
            }
        })
    }
}
