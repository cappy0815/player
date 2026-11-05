package de.cappy.tagplayer

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen(songId: Long, preset: List<Candidate>, coverOnly: Boolean = false) {
    val ctx = LocalContext.current
    val prefs = Prefs.get(ctx)
    val song = remember(songId) { Library.byId(songId) }
    val scope = rememberCoroutineScope()
    val tick = AppState.resumeTick.intValue
    val allFiles = remember(tick) { hasAllFiles() }

    var original by remember { mutableStateOf<TagData?>(null) }
    var d by remember { mutableStateOf(TagData()) }
    var coverChanged by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cands by remember { mutableStateOf(preset) }
    var searching by remember { mutableStateOf(false) }
    var searchInfo by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var lyrBusy by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }

    if (song == null) { TopBar("Tags", onBack = { Navi.back() }); return }

    LaunchedEffect(songId) {
        try {
            val t = withContext(Dispatchers.IO) { Tags.read(song.path) }
            original = t; d = t
        } catch (e: Exception) { error = "Datei kann nicht gelesen werden: ${e.message}" }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val b = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            if (b != null) { d = d.copy(cover = b, coverMime = ctx.contentResolver.getType(uri) ?: "image/jpeg"); coverChanged = true }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Tags bearbeiten", onBack = { Navi.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding()) {
            Text(song.path, color = TextDim, fontSize = 12.sp)
            if (!allFiles) {
                Spacer(Modifier.height(10.dp))
                Card {
                    Text("Zum Speichern braucht die App „Zugriff auf alle Dateien“. Android erlaubt sonst kein direktes Schreiben in die MP3.",
                        color = AccentSoft, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Pill("Berechtigung erteilen", filled = true) { openAllFilesSettings(ctx) }
                }
            }
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Bad) }

            // ---------- Online-Abgleich ----------
            SectionTitle(if (coverOnly) "Cover online suchen (Cover Art Archive)" else "Online abgleichen (MusicBrainz)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (searching) "Suche …" else "Treffer suchen", filled = true, enabled = !searching && original != null) {
                    searching = true; searchInfo = ""
                    scope.launch {
                        try {
                            cands = if (coverOnly) {
                                val h = AlbumHints.forSong(song, d)
                                val byAlbum = if (h != null) MusicBrainz.searchRelease(h, 0) else emptyList()
                                if (h != null) searchInfo = "Gesucht mit " + (if (h.fromFolder) "Ordner" else "Album-Tag") + " „${h.label}“"
                                (byAlbum + MusicBrainz.search(song, d)).sortedByDescending { it.confidence }
                                    .distinctBy { it.releaseGroupId.ifBlank { it.releaseId } }
                            } else MusicBrainz.search(song, d)
                            if (cands.isEmpty()) searchInfo += (if (searchInfo.isNotBlank()) "\n" else "") + "Kein Treffer. Titel/Interpret von Hand eintragen und erneut suchen."
                        } catch (e: Exception) { searchInfo = e.message ?: "Fehler" }
                        searching = false
                    }
                }
                if (searching) CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp)
            }
            if (applying) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("Lade Cover …", color = AccentSoft, fontSize = 13.sp)
                }
            }
            if (searchInfo.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(searchInfo, color = TextDim, fontSize = 13.sp) }
            Spacer(Modifier.height(8.dp))
            cands.forEach { c ->
                CandidateRow(c, prefs.threshold) {
                    if (applying) return@CandidateRow
                    applying = true
                    scope.launch {
                        // Cover laden dauert je nach Archiv einige Sekunden -> Anzeige "Lade Cover …"
                        val cover = try { if (prefs.coverOnline || coverOnly) MusicBrainz.cover(c) else null } finally { applying = false }
                        if (coverOnly) {
                            // nur das Cover übernehmen, Tags bleiben
                            if (cover != null) { d = d.copy(cover = cover, coverMime = "image/jpeg"); coverChanged = true; cands = emptyList() }
                            Toast.makeText(ctx, if (cover != null) "Cover übernommen – speichern" else "Für diese Veröffentlichung gibt es kein Cover im Archiv", Toast.LENGTH_SHORT).show()
                        } else {
                            d = d.applyCandidate(c, cover)
                            if (cover != null) coverChanged = true
                            cands = emptyList()
                            Toast.makeText(ctx, "Übernommen – prüfen und speichern", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // ---------- Felder ----------
            SectionTitle("Tags")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(null, 96.dp, 12.dp, bytes = d.cover)
                Spacer(Modifier.width(14.dp))
                Column {
                    Pill("Bild wählen") { pickImage.launch("image/*") }
                    Spacer(Modifier.height(8.dp))
                    Pill("Cover entfernen", enabled = d.cover != null) { d = d.copy(cover = null); coverChanged = true }
                }
            }
            Spacer(Modifier.height(10.dp))
            Field("Titel", d.title) { d = d.copy(title = it) }
            Field("Interpret", d.artist) { d = d.copy(artist = it) }
            Field("Album", d.album) { d = d.copy(album = it) }
            Field("Album-Interpret", d.albumArtist) { d = d.copy(albumArtist = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Jahr", d.year, Modifier.weight(1f), number = true) { d = d.copy(year = it) }
                Field("Genre", d.genre, Modifier.weight(1.6f)) { d = d.copy(genre = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Titelnr.", d.track, Modifier.weight(1f), number = true) { d = d.copy(track = it) }
                Field("von", d.trackTotal, Modifier.weight(1f), number = true) { d = d.copy(trackTotal = it) }
                Field("CD", d.disc, Modifier.weight(1f), number = true) { d = d.copy(disc = it) }
            }

            SectionTitle("Lyrics")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (lyrBusy) "Suche …" else "Online suchen (LRCLIB)", enabled = !lyrBusy) {
                    lyrBusy = true
                    scope.launch {
                        val r = try { LrcLib.find(d.artist, d.title, d.album, song.durationMs) } catch (_: Exception) { null }
                        lyrBusy = false
                        val t = r?.best
                        if (t != null) d = d.copy(lyrics = t)
                        Toast.makeText(ctx, when {
                            t != null && r.synced != null -> "Synchronisierte Lyrics gefunden"
                            t != null -> "Lyrics gefunden (ohne Zeitangaben)"
                            r?.instrumental == true -> "Laut LRCLIB instrumental"
                            else -> "Nichts gefunden"
                        }, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(d.lyrics, { d = d.copy(lyrics = it) }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent))

            // ---------- Änderungen & Speichern ----------
            val o = original
            if (o != null) {
                val changes = d.diff(o).filter { it.first != "Cover" } +
                    (if (coverChanged) listOf(Triple("Cover", "", if (d.cover == null) "entfernt" else "neu")) else emptyList()) +
                    (if (d.lyrics.trim() != o.lyrics.trim()) listOf(Triple("Lyrics", "", "geändert")) else emptyList())
                SectionTitle("Änderungen")
                if (changes.isEmpty()) Text("Keine", color = TextDim, fontSize = 14.sp)
                changes.forEach { (n, a, b) ->
                    Text(buildString { append(n); append(": "); if (a.isNotBlank()) append("$a → "); append(b) },
                        color = AccentSoft, fontSize = 13.sp)
                }
                Spacer(Modifier.height(14.dp))
                Pill(if (saving) "Speichere …" else "Speichern", filled = true, enabled = !saving && allFiles && changes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()) {
                    saving = true
                    AppScope.launch {
                        try {
                            withContext(Dispatchers.IO) { Tags.write(song.path, d, writeCover = coverChanged) }
                            LyricsStore.forget(ctx, song)
                            Library.rescan(ctx, listOf(song.path))
                            Library.load(ctx)
                            Library.byId(song.id)?.let { PlayerHub.refreshMetadata(it) }
                            Toast.makeText(ctx, "Gespeichert", Toast.LENGTH_SHORT).show()
                            if (Navi.top is Nav.Editor) Navi.back()
                        } catch (e: Exception) {
                            error = "Speichern fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
                        }
                        saving = false
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, modifier.padding(bottom = 6.dp), label = { Text(label) }, singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent, focusedLabelColor = Accent)
    )
}

@Composable
fun CandidateRow(c: Candidate, threshold: Int, onPick: () -> Unit) {
    val col = when { c.confidence >= threshold -> Good; c.confidence >= 60 -> Accent; else -> Bad }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface)
            .border(1.dp, col.copy(alpha = .35f), RoundedCornerShape(12.dp)).clickable(onClick = onPick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${c.confidence}%", color = col, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
        Column(Modifier.weight(1f)) {
            Text("${c.artist} – ${c.title}", color = Color.White, fontSize = 14.sp)
            Text(listOfNotNull(c.album.ifBlank { null }, c.year.ifBlank { null },
                c.track.ifBlank { null }?.let { "Nr. $it" + if (c.trackTotal.isNotBlank()) "/${c.trackTotal}" else "" },
                if (c.lengthMs > 0) fmtTime(c.lengthMs) else null).joinToString(" · "),
                color = TextDim, fontSize = 12.sp)
        }
        Text("Übernehmen", color = Accent, fontSize = 13.sp)
    }
}
