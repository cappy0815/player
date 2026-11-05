@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package de.cappy.tagplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, tagline: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Accent) }
        else Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (tagline != null) {
                Spacer(Modifier.width(8.dp))
                Text(tagline, color = Accent.copy(alpha = .85f), fontSize = 12.sp, maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        actions()
    }
}

@Composable
fun LibraryScreen() {
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = LibraryUi.page) { 5 }
    LaunchedEffect(pager.currentPage) { LibraryUi.page = pager.currentPage }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopBar("Player", tagline = TAGLINE) {
            if (Update.available(ctx)) TextButton(onClick = { Update.showDialog = true }) {
                Text("⬆ Update", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Suche", tint = Accent)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menü", tint = Accent) }
                DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                    DropdownMenuItem({ Text("Alle Titel: Tags prüfen") }, onClick = {
                        menu = false; Matcher.prepare("Alle Titel", Library.songs.toList()); Navi.go(Nav.Match)
                    })
                    DropdownMenuItem({ Text("Alle Titel: Cover prüfen") }, onClick = {
                        menu = false; Matcher.prepare("Alle Titel", Library.songs.toList(), MatchMode.COVER); Navi.go(Nav.Match)
                    })
                    DropdownMenuItem({ Text("Wiedergabeliste") }, onClick = { menu = false; Navi.go(Nav.Queue) })
                    DropdownMenuItem({ Text("Abgleich-Ergebnisse") }, onClick = { menu = false; Navi.go(Nav.Match) })
                    DropdownMenuItem({ Text("Bibliothek neu einlesen") }, onClick = { menu = false; scope.launch { Library.load(ctx) } })
                    DropdownMenuItem({ Text("Einstellungen") }, onClick = { menu = false; Navi.go(Nav.Settings) })
                    DropdownMenuItem({ Text("Info") }, onClick = { menu = false; Navi.go(Nav.Info) })
                }
            }
        }
        if (searching) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Titel, Interpret, Album, Datei") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent)
            )
            Spacer(Modifier.height(6.dp))
        }
        ScrollableTabRow(pager.currentPage, containerColor = Bg, contentColor = Accent, edgePadding = 8.dp) {
            listOf("Titel", "Alben", "Interpreten", "Ordner", "Playlists").forEachIndexed { i, t ->
                Tab(pager.currentPage == i, { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(t) },
                    selectedContentColor = Accent, unselectedContentColor = TextDim)
            }
        }
        if (Library.loading && Library.songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            return@Column
        }
        if (Library.songs.isEmpty() && pager.currentPage != 4) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Keine Musik gefunden.\nPrüfe in den Einstellungen die Musikordner und die Mindestlänge.", color = TextDim)
            }
            return@Column
        }
        val q = query.trim().lowercase()
        val songs = if (q.isEmpty()) Library.songs.toList() else Library.songs.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q) || it.fileName.lowercase().contains(q)
        }
        // Wischen zwischen den Reitern
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
        when (page) {
            0 -> SongList(songs)
            1 -> {
                val albums = songs.groupBy { it.albumId }.values.sortedBy { it.first().album.lowercase() }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(albums, key = { it.first().albumId }) { list ->
                        val s = list.first()
                        GroupRow(s, s.album.ifBlank { "Unbekanntes Album" },
                            "${list.map { it.artist }.distinct().take(2).joinToString(", ")} · ${list.size} Titel",
                            songs = { list.sortedBy { it.track } }) {
                            Navi.go(Nav.Album(s.albumId, s.album))
                        }
                    }
                }
            }
            3 -> {
                val root = Folders.root(songs)
                FolderList(songs, root, header = {
                    Column {
                        Text(Folders.shortName(root) + " · ${songs.size} Titel", color = TextDim, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        CheckButtons("Ordner „${java.io.File(root).name}“", songs)
                    }
                })
            }
            4 -> PlaylistTab()
            else -> {
                val artists = songs.groupBy { it.artist }.entries.sortedBy { it.key.lowercase() }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(artists, key = { "a:" + it.key }) { (name, list) ->
                        GroupRow(list.first(), name.ifBlank { "Unbekannter Interpret" },
                            "${list.map { it.album }.distinct().size} Alben · ${list.size} Titel", songs = { list }) { Navi.go(Nav.Artist(name)) }
                    }
                }
            }
        }
        }
    }
}

object LibraryUi { var page by mutableIntStateOf(0) }

@Composable
fun rememberSaveableInt(v: Int) = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(v) }

@Composable
fun GroupRow(cover: Song, title: String, sub: String, songs: (() -> List<Song>)? = null, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { if (songs != null) menu = true })
        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = if (songs != null) 0.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Cover(cover, 56.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (songs != null) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Optionen", tint = TextDim) }
            SongsMenu(menu, { menu = false }, title, songs = songs)
        }
    }
}

@Composable
fun SongList(songs: List<Song>, header: (@Composable () -> Unit)? = null) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (header != null) item { header() }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s) { PlayerHub.play(songs, i) } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
fun SongRow(s: Song, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val playing = PlayerHub.currentId == s.id
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .background(if (playing) Accent.copy(alpha = .08f) else Color.Transparent)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(s, 46.dp, 8.dp, px = 150)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title.ifBlank { s.fileName }, color = if (playing) Accent else Color.White, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "ohne Tags" },
                color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(fmtTime(s.durationMs), color = TextDim, fontSize = 12.sp)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Optionen", tint = TextDim) }
            SongsMenu(menu, { menu = false }, s.title.ifBlank { s.fileName }, single = s) { listOf(s) }
        }
    }
}

@Composable
fun SongListScreen(title: String, songs: List<Song>, matchLabel: String) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title.ifBlank { "Unbekannt" }, onBack = { Navi.back() })
        SongList(songs, header = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Abspielen", filled = true, modifier = Modifier.weight(1f)) { PlayerHub.play(songs, 0) }
                Pill("Anhängen", modifier = Modifier.weight(1f)) { PlayerHub.enqueue(songs) }
            }
            CheckButtons(matchLabel, songs)
        })
    }
}

@Composable
fun MiniPlayer() {
    val s = Library.byId(PlayerHub.currentId)
    val pos = rememberPosition()
    val dur = PlayerHub.durationMs
    Column(Modifier.fillMaxWidth().background(Surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        LinearProgressIndicator(
            progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = Accent, trackColor = Surface2, drawStopIndicator = {}
        )
        Row(Modifier.fillMaxWidth().clickable { Navi.go(Nav.Player) }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Cover(s, 44.dp, 8.dp, px = 150)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s?.title?.ifBlank { s.fileName } ?: "", color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s?.artist ?: "", color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { PlayerHub.toggle() }) {
                Icon(if (PlayerHub.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Accent, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = { PlayerHub.next() }) { Icon(Icons.Default.SkipNext, "Weiter", tint = Color.White) }
            IconButton(onClick = { Navi.go(Nav.Queue) }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Wiedergabeliste", tint = TextDim) }
        }
    }
}

/** Untertitel neben dem App-Namen */
const val TAGLINE = "tag it · play it · sing it"
