package de.cappy.tagplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

sealed class Nav {
    data object Library : Nav()
    data object Player : Nav()
    data object Settings : Nav()
    data object Match : Nav()
    data class Album(val albumId: Long, val name: String) : Nav()
    data class Artist(val name: String) : Nav()
    data class Editor(val songId: Long, val candidates: List<Candidate> = emptyList(), val coverOnly: Boolean = false) : Nav()
    data class Folder(val path: String) : Nav()
    data class AlbumTags(val path: String, val preset: TagData? = null) : Nav()
    data class PlaylistNav(val id: Long) : Nav()
    data class LyricsEdit(val songId: Long) : Nav()
    data class LyricsSync(val songId: Long) : Nav()
    data object Info : Nav()
    data object Queue : Nav()
    data object Help : Nav()
}

/** Kleiner Navigationsstapel */
object Navi {
    val stack = mutableStateListOf<Nav>(Nav.Library)
    val top: Nav get() = stack.last()
    fun go(n: Nav) { stack.add(n) }
    fun back(): Boolean { if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }; return false }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun show(n: Nav) { if (top != n) { home(); if (n != Nav.Library) go(n) } }
}

object AppState {
    val resumeTick = mutableIntStateOf(0)
    /** offene Aktion vom App-Icon: RESUME, SHUFFLE, SEARCH, LYRICS */
    val shortcut = mutableStateOf<String?>(null)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PlayerHub.connect(this)
        handleShortcut(intent)
        setContent { MaterialTheme(colorScheme = darkColors) { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    /** Langdruck-Menü am App-Icon: Aktion merken, Root führt sie aus, sobald alles geladen ist */
    private fun handleShortcut(i: Intent?) {
        val a = i?.action ?: return
        if (a.startsWith("de.cappy.tagplayer.")) { AppState.shortcut.value = a.removePrefix("de.cappy.tagplayer."); i.action = null }
    }

    override fun onResume() {
        super.onResume()
        AppState.resumeTick.intValue++
    }
}

fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

fun hasAudio(ctx: Context) = ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED

fun hasAllFiles(): Boolean = Environment.isExternalStorageManager()

fun openAllFilesSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun openOverlaySettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    val tick = AppState.resumeTick.intValue
    var granted by remember { mutableStateOf(hasAudio(ctx)) }
    LaunchedEffect(tick) { granted = hasAudio(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasAudio(ctx)
    }
    LaunchedEffect(Unit) {
        val perms = mutableListOf(audioPermission())
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (!granted) launcher.launch(perms.toTypedArray())
    }
    LaunchedEffect(Unit) { Playlists.load(ctx) }
    // Aktion vom App-Icon ausführen, sobald Player-Verbindung und Bibliothek bereit sind
    val sc = AppState.shortcut.value
    LaunchedEffect(sc, PlayerHub.controller, Library.loadedOnce) {
        if (sc == null || PlayerHub.controller == null || !Library.loadedOnce) return@LaunchedEffect
        AppState.shortcut.value = null
        Diag.log(ctx, "App-Icon: $sc")
        when (sc) {
            "RESUME" -> { if (PlayerHub.resume(ctx)) Navi.show(Nav.Player)
                else android.widget.Toast.makeText(ctx, "Noch nichts gespielt – Zufallsmix starten oder Titel wählen", android.widget.Toast.LENGTH_LONG).show() }
            "SHUFFLE" -> { if (Library.songs.isNotEmpty()) { PlayerHub.play(Library.songs.shuffled(), 0); Navi.show(Nav.Player) } }
            "SEARCH" -> { Navi.home(); LibraryUi.openSearch = true }
            "LYRICS" -> { PlayerHub.resume(ctx); ctx.startActivity(Intent(ctx, StageActivity::class.java)) }
        }
    }
    // Einstellung „Beim Abspielen immer zu Lyrics wechseln“
    val started = PlayerHub.startedFromList
    LaunchedEffect(started) {
        if (started > 0 && Prefs.get(ctx).autoLyricsView && Navi.top != Nav.Player) { PlayerUi.showLyrics = true; Navi.go(Nav.Player) }
    }
    // Update-Prüfung höchstens einmal pro Tag beim Start
    LaunchedEffect(Unit) { Update.load(ctx); if (Update.due()) Update.check(ctx) }
    LaunchedEffect(granted) { if (granted && !Library.loadedOnce) Library.load(ctx) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (!granted) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Player braucht Zugriff auf deine Musik", color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(16.dp))
                Pill("Zugriff erlauben", filled = true) {
                    launcher.launch(arrayOf(audioPermission()))
                }
                Spacer(Modifier.height(10.dp))
                Pill("App-Einstellungen öffnen") {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                }
            }
            return@Box
        }

        BackHandler(enabled = Navi.stack.size > 1) { Navi.back() }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (val n = Navi.top) {
                    Nav.Library -> LibraryScreen()
                    Nav.Player -> PlayerScreen()
                    Nav.Settings -> SettingsScreen()
                    Nav.Match -> MatchScreen()
                    is Nav.Album -> SongListScreen(n.name, Library.songs.filter { it.albumId == n.albumId }.sortedBy { it.track },
                        matchLabel = "Album „${n.name}“", key = "album:${n.albumId}")
                    is Nav.Artist -> SongListScreen(n.name, Library.songs.filter { it.artist == n.name }, matchLabel = "Interpret „${n.name}“", key = "artist:${n.name}")
                    is Nav.Editor -> EditorScreen(n.songId, n.candidates, n.coverOnly)
                    is Nav.Folder -> FolderScreen(n.path)
                    is Nav.AlbumTags -> AlbumTagsScreen(n.path, n.preset)
                    is Nav.PlaylistNav -> PlaylistScreen(n.id)
                    is Nav.LyricsEdit -> LyricsEditScreen(n.songId)
                    is Nav.LyricsSync -> LyricsSyncScreen(n.songId)
                    Nav.Info -> InfoScreen()
                    Nav.Queue -> QueueScreen()
                    Nav.Help -> HelpScreen()
                }
            }
            if (Navi.top != Nav.Player && PlayerHub.currentId >= 0) MiniPlayer()
        }
        AddToPlaylistDialog()
        UpdateDialog()
    }
}

