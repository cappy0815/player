package de.cappy.tagplayer

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Eine Playlist speichert Dateipfade, damit sie Neu-Einlesen und Tag-Änderungen übersteht. */
class Playlist(val id: Long, name: String, paths: List<String>) {
    var name by mutableStateOf(name)
    val paths = mutableStateListOf<String>().apply { addAll(paths) }
    /** Titel, die es (noch) in der Bibliothek gibt, in Playlist-Reihenfolge */
    fun songs(): List<Song> = paths.mapNotNull { Library.byPath(it) }
}

object Playlists {
    val all = mutableStateListOf<Playlist>()
    private var loaded = false
    private lateinit var file: File

    fun load(ctx: Context) {
        if (loaded) return
        file = File(ctx.filesDir, "playlists.json")
        loaded = true
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.getJSONArray("paths")
                all += Playlist(o.getLong("id"), o.getString("name"), (0 until p.length()).map { p.getString(it) })
            }
        } catch (_: Exception) {}
    }

    fun save() {
        if (!loaded) return
        val arr = JSONArray()
        all.forEach { pl -> arr.put(JSONObject().put("id", pl.id).put("name", pl.name).put("paths", JSONArray(pl.paths.toList()))) }
        try { file.writeText(arr.toString()) } catch (_: Exception) {}
    }

    fun byId(id: Long) = all.firstOrNull { it.id == id }
    fun create(name: String): Playlist = Playlist(System.currentTimeMillis(), name.trim().ifBlank { "Neue Playlist" }, emptyList()).also { all += it; save() }
    fun delete(p: Playlist) { all.remove(p); save() }
    fun rename(p: Playlist, n: String) { p.name = n.trim().ifBlank { p.name }; save() }
    /** Fügt Titel hinzu, Doppelte werden übersprungen. Rückgabe: Anzahl neu hinzugefügt */
    fun add(p: Playlist, songs: List<Song>): Int {
        var n = 0
        songs.forEach { if (it.path !in p.paths) { p.paths += it.path; n++ } }
        save(); return n
    }
    fun removeAt(p: Playlist, i: Int) { if (i in p.paths.indices) { p.paths.removeAt(i); save() } }
    fun move(p: Playlist, from: Int, to: Int) {
        if (from !in p.paths.indices || to !in p.paths.indices) return
        val x = p.paths.removeAt(from); p.paths.add(to, x); save()
    }
}

/** Offener Dialog "Zur Playlist hinzufügen" (global, damit er von überall aufrufbar ist) */
object PlaylistUi { var pending by mutableStateOf<List<Song>?>(null) }

@Composable
fun AddToPlaylistDialog() {
    val songs = PlaylistUi.pending ?: return
    val ctx = LocalContext.current
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { PlaylistUi.pending = null },
        containerColor = Surface2,
        title = { Text(if (songs.size == 1) "Zur Playlist hinzufügen" else "${songs.size} Titel zur Playlist", color = Color.White) },
        text = {
            Column {
                Playlists.all.forEach { pl ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val n = Playlists.add(pl, songs)
                        android.widget.Toast.makeText(ctx, "$n Titel zu „${pl.name}“ hinzugefügt", android.widget.Toast.LENGTH_SHORT).show()
                        PlaylistUi.pending = null
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = Accent)
                        Spacer(Modifier.width(12.dp))
                        Text(pl.name, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text("${pl.paths.size}", color = TextDim, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("Neue Playlist") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent, focusedLabelColor = Accent))
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = {
                val pl = Playlists.create(newName)
                Playlists.add(pl, songs)
                android.widget.Toast.makeText(ctx, "Playlist „${pl.name}“ angelegt", android.widget.Toast.LENGTH_SHORT).show()
                PlaylistUi.pending = null
            }) { Text("Anlegen", color = Accent) }
        },
        dismissButton = { TextButton(onClick = { PlaylistUi.pending = null }) { Text("Abbrechen", color = TextDim) } }
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) }, containerColor = Surface2,
        title = { Text(title, color = Color.White) },
        text = {
            OutlinedTextField(t, { t = it }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent))
        },
        confirmButton = { TextButton(enabled = t.isNotBlank(), onClick = { onDone(t) }) { Text("OK", color = Accent) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Abbrechen", color = TextDim) } }
    )
}

/** Tab "Playlists" in der Bibliothek */
@Composable
fun PlaylistTab() {
    var create by remember { mutableStateOf(false) }
    if (create) NameDialog("Neue Playlist", "") { n -> create = false; if (n != null) Navi.go(Nav.PlaylistNav(Playlists.create(n).id)) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp)) { Pill("Neue Playlist", filled = true, modifier = Modifier.fillMaxWidth()) { create = true } }
        }
        if (Playlists.all.isEmpty()) item {
            Text("Noch keine Playlists. Titel über ⋮ → „Zur Playlist“ hinzufügen oder hier eine neue anlegen.",
                color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        items(Playlists.all, key = { it.id }) { pl ->
            val songs = pl.songs()
            Row(Modifier.fillMaxWidth().clickable { Navi.go(Nav.PlaylistNav(pl.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Cover(songs.firstOrNull(), 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(pl.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${songs.size} Titel · ${fmtTime(songs.sumOf { it.durationMs })}", color = TextDim, fontSize = 13.sp)
                }
                IconButton(onClick = { if (songs.isNotEmpty()) PlayerHub.play(songs, 0) }) {
                    Icon(Icons.Default.PlayArrow, "Abspielen", tint = Accent)
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(id: Long) {
    val pl = Playlists.byId(id)
    if (pl == null) { TopBar("Playlist", onBack = { Navi.back() }); return }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    if (rename) NameDialog("Umbenennen", pl.name) { n -> rename = false; if (n != null) Playlists.rename(pl, n) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = Surface2,
        title = { Text("„${pl.name}“ löschen?", color = Color.White) },
        text = { Text("Nur die Playlist wird gelöscht, die Musikdateien bleiben.", color = TextDim) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; Playlists.delete(pl); Navi.back() }) { Text("Löschen", color = Bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen", color = TextDim) } }
    )
    // Einträge mit Index, fehlende Dateien werden übersprungen
    val entries = pl.paths.mapIndexedNotNull { i, p -> Library.byPath(p)?.let { i to it } }
    val songs = entries.map { it.second }

    Column(Modifier.fillMaxSize()) {
        TopBar(pl.name, onBack = { Navi.back() }) {
            IconButton(onClick = { edit = !edit }) { Icon(if (edit) Icons.Default.Check else Icons.Default.Edit, "Bearbeiten", tint = Accent) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menü", tint = Accent) }
                DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                    DropdownMenuItem({ Text("Umbenennen") }, onClick = { menu = false; rename = true })
                    DropdownMenuItem({ Text("Löschen") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text("${songs.size} Titel · ${fmtTime(songs.sumOf { it.durationMs })}" +
                    if (songs.size < pl.paths.size) " · ${pl.paths.size - songs.size} nicht gefunden" else "",
                    color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Abspielen", filled = true, enabled = songs.isNotEmpty(), modifier = Modifier.weight(1f)) { PlayerHub.play(songs, 0) }
                    Pill("Zufällig", enabled = songs.isNotEmpty(), modifier = Modifier.weight(1f)) { PlayerHub.play(songs.shuffled(), 0) }
                }
                CheckButtons("Playlist „${pl.name}“", songs, playlistButton = false)
            }
            itemsIndexed(entries, key = { _, e -> "p${e.first}:${e.second.id}" }) { pos, (idx, s) ->
                if (!edit) SongRow(s) { PlayerHub.play(songs, pos) }
                else Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title.ifBlank { s.fileName }, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, color = TextDim, fontSize = 13.sp, maxLines = 1)
                    }
                    IconButton(enabled = idx > 0, onClick = { Playlists.move(pl, idx, idx - 1) }) { Icon(Icons.Default.KeyboardArrowUp, "Hoch", tint = if (idx > 0) Accent else TextDim) }
                    IconButton(enabled = idx < pl.paths.lastIndex, onClick = { Playlists.move(pl, idx, idx + 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Runter", tint = if (idx < pl.paths.lastIndex) Accent else TextDim) }
                    IconButton(onClick = { Playlists.removeAt(pl, idx) }) { Icon(Icons.Default.RemoveCircleOutline, "Entfernen", tint = Bad) }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}
