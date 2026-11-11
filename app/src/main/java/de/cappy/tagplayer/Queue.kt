@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package de.cappy.tagplayer

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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

/**
 * Gemeinsames Langdruck-/⋮-Menü für einen oder mehrere Titel
 * (Lied, Album, Interpret, Ordner, Playlist).
 * [songs] wird erst beim Antippen eines Eintrags ausgewertet.
 */
@Composable
fun SongsMenu(expanded: Boolean, onDismiss: () -> Unit, label: String, single: Song? = null, folder: String? = null, songs: () -> List<Song>) {
    val ctx = LocalContext.current
    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()
    DropdownMenu(expanded, onDismiss, containerColor = Surface2) {
        DropdownMenuItem({ Text("Abspielen") }, leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
            onClick = { onDismiss(); songs().takeIf { it.isNotEmpty() }?.let { PlayerHub.play(it, 0) } })
        DropdownMenuItem({ Text("Als Nächstes spielen") }, leadingIcon = { Icon(Icons.Default.SkipNext, null) },
            onClick = { onDismiss(); val l = songs(); if (l.isNotEmpty()) { PlayerHub.playNext(l); toast(if (l.size == 1) "Kommt als Nächstes" else "${l.size} Titel kommen als Nächstes") } })
        DropdownMenuItem({ Text("An Wiedergabeliste anhängen") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
            onClick = { onDismiss(); val l = songs(); if (l.isNotEmpty()) { PlayerHub.enqueue(l); toast(if (l.size == 1) "An Wiedergabeliste angehängt" else "${l.size} Titel angehängt") } })
        DropdownMenuItem({ Text("Zur Playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
            onClick = { onDismiss(); songs().takeIf { it.isNotEmpty() }?.let { PlaylistUi.pending = it } })
        HorizontalDivider(color = TextDim.copy(alpha = .2f))
        if (single != null) DropdownMenuItem({ Text("Tags bearbeiten") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
            onClick = { onDismiss(); Navi.go(Nav.Editor(single.id)) })
        if (folder != null) DropdownMenuItem({ Text("Album-Tags für Ordner setzen") }, leadingIcon = { Icon(Icons.Default.LibraryMusic, null) },
            onClick = { onDismiss(); Navi.go(Nav.AlbumTags(folder)) })
        DropdownMenuItem({ Text("Tags online prüfen") }, leadingIcon = { Icon(Icons.Default.Sync, null) },
            onClick = { onDismiss(); Matcher.prepare(label, songs()); Navi.go(Nav.Match) })
        DropdownMenuItem({ Text("Cover online prüfen") }, leadingIcon = { Icon(Icons.Default.Image, null) },
            onClick = { onDismiss(); Matcher.prepare(label, songs(), MatchMode.COVER); Navi.go(Nav.Match) })
    }
}

/** Wiedergabeliste (Warteschlange): was gerade läuft und was danach kommt */
@Composable
fun QueueScreen() {
    val ids = PlayerHub.queueIds
    val cur = PlayerHub.currentIndex
    val songs = ids.map { Library.byId(it) }
    var save by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { if (cur > 0) state.scrollToItem((cur - 1).coerceAtLeast(0)) }

    if (save) NameDialog("Als Playlist speichern", "Wiedergabeliste") { n ->
        save = false
        if (n != null) Playlists.add(Playlists.create(n), songs.filterNotNull())
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, containerColor = Surface2,
        title = { Text("Kommende Titel entfernen?", color = Color.White) },
        text = { Text("Der laufende Titel spielt weiter, alles danach wird aus der Wiedergabeliste genommen.", color = TextDim) },
        confirmButton = { TextButton(onClick = { confirmClear = false; PlayerHub.clearUpcoming() }) { Text("Entfernen", color = Bad) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen", color = TextDim) } }
    )

    Column(Modifier.fillMaxSize()) {
        TopBar("Wiedergabeliste", onBack = { Navi.back() }) {
            IconButton(enabled = songs.isNotEmpty(), onClick = { save = true }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Als Playlist speichern", tint = Accent) }
            IconButton(enabled = cur < ids.lastIndex, onClick = { confirmClear = true }) { Icon(Icons.Default.ClearAll, "Kommende entfernen", tint = Accent) }
        }
        if (ids.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Leer. Titel antippen zum Abspielen, oder lange drücken → „An Wiedergabeliste anhängen“.", color = TextDim)
            }
            return@Column
        }
        Text("${ids.size} Titel · noch ${fmtTime(songs.drop(cur + 1).sumOf { it?.durationMs ?: 0 })} nach dem aktuellen" +
            if (PlayerHub.shuffle) " · Zufall ist an, die Reihenfolge beim Abspielen weicht ab" else "",
            color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { i, s -> "q$i:${s?.id}" }) { i, s ->
                var menu by remember { mutableStateOf(false) }
                val playing = i == cur
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { PlayerHub.jumpTo(i) }, onLongClick = { menu = true })
                        .background(if (playing) Accent.copy(alpha = .10f) else Color.Transparent)
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        if (playing) Icon(Icons.Default.GraphicEq, "läuft", tint = Accent, modifier = Modifier.size(20.dp))
                        else Text("${i + 1}", color = if (i < cur) TextDim.copy(alpha = .5f) else TextDim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Cover(s, 40.dp, 6.dp, px = 120)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s?.title?.ifBlank { s.fileName } ?: "(nicht mehr vorhanden)",
                            color = when { playing -> Accent; i < cur -> Color.White.copy(alpha = .5f); else -> Color.White },
                            fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s?.artist ?: "", color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Optionen", tint = TextDim) }
                        DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                            DropdownMenuItem({ Text("Jetzt spielen") }, leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                onClick = { menu = false; PlayerHub.jumpTo(i) })
                            if (i != cur && i != cur + 1) DropdownMenuItem({ Text("Als Nächstes spielen") }, leadingIcon = { Icon(Icons.Default.SkipNext, null) },
                                onClick = { menu = false; PlayerHub.move(i, if (i < cur) cur else cur + 1) })
                            DropdownMenuItem({ Text("Nach oben") }, enabled = i > 0, leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                                onClick = { menu = false; PlayerHub.move(i, i - 1) })
                            DropdownMenuItem({ Text("Nach unten") }, enabled = i < ids.lastIndex, leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                                onClick = { menu = false; PlayerHub.move(i, i + 1) })
                            if (s != null) DropdownMenuItem({ Text("Zur Playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                onClick = { menu = false; PlaylistUi.pending = listOf(s) })
                            DropdownMenuItem({ Text("Aus Wiedergabeliste entfernen") }, enabled = !playing,
                                leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) },
                                onClick = { menu = false; PlayerHub.removeAt(i) })
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
