package de.cappy.tagplayer

import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val p = Prefs.get(ctx)
    val tick = AppState.resumeTick.intValue
    val allFiles = remember(tick) { hasAllFiles() }
    val overlay = remember(tick) { Settings.canDrawOverlays(ctx) }
    // Bibliothek neu einlesen, wenn Ordner oder Mindestlänge geändert wurden
    LaunchedEffect(p.musicFolders, p.minDurationSec) {
        kotlinx.coroutines.delay(600)
        if (Library.loadedOnce) Library.load(ctx)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let { treeToPath(it) }
        if (path != null) p.musicFolders = p.musicFolders + path
        else if (uri != null) android.widget.Toast.makeText(ctx, "Dieser Ordner liegt nicht im Gerätespeicher bzw. auf der SD-Karte", android.widget.Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Einstellungen", onBack = { Navi.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)) {

            SectionTitle("Berechtigungen")
            Card {
                Status("Zugriff auf alle Dateien (Tags speichern)", allFiles) { openAllFilesSettings(ctx) }
                Status("Über anderen Apps einblenden (Lyrics beim Sperren)", overlay) { openOverlaySettings(ctx) }
            }

            SectionTitle("Bibliothek")
            Card {
                Text("Musikordner", color = Color.White, fontSize = 15.sp)
                Text(if (p.musicFolders.isEmpty()) "Keine Auswahl: alle Musik auf dem Gerät" else "Nur Musik in diesen Ordnern (inkl. Unterordner)",
                    color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                p.musicFolders.sorted().forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(Folders.shortName(f), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { p.musicFolders = p.musicFolders - f }) { Icon(Icons.Default.Close, "Entfernen", tint = TextDim) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Pill("Ordner hinzufügen", filled = true, modifier = Modifier.fillMaxWidth()) { pickFolder.launch(null) }
                Spacer(Modifier.height(14.dp))
                ValueSlider("Ausblenden, wenn kürzer als", p.minDurationSec, 0..300, "s", step = 5,
                    valueText = if (p.minDurationSec == 0) "aus" else fmtTime(p.minDurationSec * 1000L) + " min") { p.minDurationSec = it }
                Text(if (p.minDurationSec == 0) "Aus: alle Längen werden angezeigt" else "Alle Titel unter ${fmtTime(p.minDurationSec * 1000L)} min werden ausgeblendet.",
                    color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(buildString {
                    append("${Library.songs.size} Titel in der Bibliothek")
                    if (Library.hiddenShort > 0) append(" · ${Library.hiddenShort} zu kurz ausgeblendet")
                    if (Library.hiddenFolder > 0) append(" · ${Library.hiddenFolder} außerhalb der Ordner")
                    if (Library.loading) append(" · lade …")
                }, color = AccentSoft, fontSize = 12.sp)
            }

            SectionTitle("Online-Abgleich")
            Card {
                ValueSlider("Automatisch übernehmen ab", p.threshold, 60..100, "%") { p.threshold = it }
                Text("Die Trefferquote setzt sich aus dem MusicBrainz-Score und dem Vergleich von Titel, Interpret und Länge zusammen. " +
                    "Darunter wird nichts geschrieben, der Titel landet in „Prüfen“.", color = TextDim, fontSize = 12.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(10.dp))
                SwitchRow("Cover online laden", "Cover Art Archive, 500 px", p.coverOnline) { p.coverOnline = it }
                SwitchRow("Vorhandenes Cover behalten", "Beim automatischen Abgleich nur fehlende Cover ergänzen",
                    p.keepExistingCover) { p.keepExistingCover = it }
            }

            SectionTitle("Lyrics")
            Card {
                SwitchRow("Beim Abspielen automatisch suchen", "LRCLIB, nur wenn die Datei keine Lyrics enthält", p.autoLyrics) { p.autoLyrics = it }
                SwitchRow("Gefundene Lyrics in die MP3 schreiben", "Sonst nur im App-Speicher (braucht „alle Dateien“)",
                    p.lyricsToTag) { p.lyricsToTag = it }
            }

            SectionTitle("Lyrics-Display")
            Card {
                ValueSlider("Schriftgröße", p.stageFontSp, 24..72, "sp") { p.stageFontSp = it }
                ValueSlider("Helligkeit", p.stageBrightness, 1..100, "%") { p.stageBrightness = it }
                SwitchRow("Beim Sperren anzeigen",
                    "Läuft Musik und das Display geht aus (Timeout oder Seitentaste), schaltet es gedimmt mit den Lyrics wieder ein. " +
                        "Seitentaste in der Anzeige schaltet dann wirklich aus.", p.stageOnLock) {
                    p.stageOnLock = it
                    if (it && !Settings.canDrawOverlays(ctx)) openOverlaySettings(ctx)
                }
                SwitchRow("Display im Vollbild immer an", "Standard. Im Vollbild auch per ☀/☾-Knopf umschaltbar (antippen blendet ihn ein)",
                    p.stageKeepOn) { p.stageKeepOn = it }
                SwitchRow("Display nur am Ladekabel anlassen", "Ohne Kabel geht das Display nach dem normalen Timeout aus",
                    p.stageOnlyCharging) { p.stageOnlyCharging = it }
                SwitchRow("Einbrennschutz", "Inhalt wandert jede Minute ein Stück (OLED)", p.pixelShift) { p.pixelShift = it }
                Spacer(Modifier.height(6.dp))
                Pill("Lyrics-Display jetzt öffnen", filled = true, modifier = Modifier.fillMaxWidth()) {
                    ctx.startActivity(Intent(ctx, StageActivity::class.java))
                }
            }

            SectionTitle("Updates")
            Card { UpdateBlock() }

            SectionTitle("Info")
            Card {
                Pill("Info, Versionen & Update", modifier = Modifier.fillMaxWidth()) { Navi.go(Nav.Info) }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
fun Status(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = !ok, onClick = onFix).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "!", color = if (ok) Good else Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp))
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (!ok) Text("Erteilen", color = Accent, fontSize = 13.sp)
    }
}

/**
 * Wandelt die Ordnerauswahl des Systems (SAF-Baum) in einen Dateipfad um.
 * "primary:Music/Rock" -> /storage/emulated/0/Music/Rock, "1234-ABCD:Musik" -> /storage/1234-ABCD/Musik.
 * Gelesen wird weiterhin über den MediaStore, eine dauerhafte SAF-Freigabe ist nicht nötig.
 */
fun treeToPath(uri: Uri): String? = try {
    val id = DocumentsContract.getTreeDocumentId(uri)
    val vol = id.substringBefore(':'); val rel = id.substringAfter(':', "")
    val base = if (vol.equals("primary", true)) Environment.getExternalStorageDirectory().absolutePath else "/storage/$vol"
    (if (rel.isBlank()) base else "$base/$rel").trimEnd('/')
} catch (_: Exception) { null }
