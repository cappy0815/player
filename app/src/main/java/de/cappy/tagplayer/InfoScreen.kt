package de.cappy.tagplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val GITHUB_URL = "https://github.com/cappy0815/player"
const val PROJECT_URL = "https://cappy0815.github.io/#player"

/** Versionsgeschichte, neueste zuerst */
val CHANGELOG = listOf(
    "0.8" to listOf(
        "Langdruck aufs App-Icon: Weiterhören, Zufallsmix, Suchen, Lyrics-Vollbild",
        "Listen merken sich die Position: zurück aus Ordner, Album oder Menü landet an derselben Stelle statt wieder bei A",
        "Zuletzt gespielte Liste und Position bleiben erhalten (Weiterhören, Play-Taste und Android Auto nach Neustart)"
    ),
    "0.7" to listOf(
        "Android Auto: Playlists, Alben, Interpreten, Ordner, Alle Titel, Zufallsmix und Suche im Auto",
        "Ordnerregel: in einem Album-Ordner bekommen alle Dateien dasselbe Album und dasselbe Cover",
        "Cover-Prüfung bearbeitet Ordner mit uneinheitlichen Covern immer; ohne sicheren Online-Treffer wird das Mehrheits-Cover übernommen",
        "Tag-Prüfung speichert keinen Treffer mit anderem Album als der Ordner (kommt in „Prüfen“)",
        "Diagnose-Datei in den Einstellungen: Ordner-, Tag- und Cover-Übersicht plus Protokoll, nur auf Wunsch teilen"
    ),
    "0.6" to listOf(
        "Tag- und Cover-Suche zusätzlich über Deezer und iTunes (mit Genre und großen Covern)",
        "Treffer aus mehreren Quellen bestätigen sich gegenseitig",
        "Fehlt zum besten Treffer ein Bild, wird automatisch das nächste sichere Cover genommen",
        "Quelle steht in der Trefferliste",
        "Update-Prüfung über die Projektseite, täglich automatisch, „⬆ Update“-Knopf"
    ),
    "0.5" to listOf(
        "Wiedergabeliste: ansehen, springen, sortieren, entfernen, als Playlist speichern",
        "Langdruck-Menü für Lieder, Alben, Interpreten, Ordner und Playlists",
        "Tag-Suche nutzt zuerst den Ordnernamen als Albumtitel (wie die Cover-Prüfung)",
        "„Als Nächstes spielen“ und „An Wiedergabeliste anhängen“",
        "Knopf „Anhängen“ in Album-, Interpreten- und Playlist-Ansicht",
        "Untertitel „tag it · play it · sing it“"
    ),
    "0.4.1" to listOf(
        "Cover-Prüfung nutzt zuerst den Namen des Speicherordners („Interpret - Album“ oder „Album“)",
        "Lyrics-Vollbild: „Display immer an“ standardmäßig aktiv, per ☀/☾-Knopf umschaltbar",
        "Lyrics ohne Zeitangaben: Knopf „Zeiten beim Hören setzen“ direkt in der Lyrics-Ansicht"
    ),
    "0.4" to listOf(
        "Laufendes Lied zur Playlist hinzufügen (Wiedergabe-Menü)",
        "Lyrics: zweite Quelle lyrics.ovh, bereinigte Suche für Live-, Remaster- und feat.-Titel",
        "Lyrics selbst suchen mit Trefferliste (Interpret und Titel frei eingeben)",
        "Eigenen Text einfügen und per Tippen synchronisieren",
        "Zeitversatz für synchronisierte Lyrics (früher/später)",
        "Info-Seite mit Versionsgeschichte, GitHub-Link und Update-Prüfung"
    ),
    "0.3.1" to listOf(
        "Knöpfe brechen nicht mehr um",
        "Mindestlänge wird als Zeit (m:ss) eingestellt, bis 5 Minuten"
    ),
    "0.3" to listOf(
        "Cover wird direkt aus der Datei angezeigt",
        "Geänderte Tags erscheinen sofort in der Bibliothek",
        "Jedes Speichern wird geprüft, Fehler erscheinen im Filter „Fehler“",
        "Seite „Tags/Cover prüfen“ überarbeitet"
    ),
    "0.2" to listOf(
        "App heißt jetzt „Player“",
        "Wischen zwischen den Reitern und zwischen Cover und Lyrics",
        "Ordneransicht, Tags und Cover für ganze Ordner prüfen",
        "Musikordner wählbar, kurze Dateien ausblenden",
        "Playlists",
        "Aktuelle Lyrics-Zeile wird hervorgehoben"
    ),
    "0.1" to listOf("Erste Version: Player, Tag-Editor, Online-Abgleich, Lyrics, Lyrics-Vollbild")
)

fun appVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
} catch (_: Exception) { "?" }

/** "0.10.1" > "0.9" */
fun newerThan(a: String, b: String): Boolean {
    val x = a.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
    val y = b.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
        if (p != q) return p > q
    }
    return false
}

fun openUrl(ctx: Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

@Composable
fun InfoScreen() {
    val ctx = LocalContext.current
    val version = remember { appVersion(ctx) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Info", onBack = { Navi.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)) {
            Card {
                Text("Player", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(TAGLINE, color = Accent, fontSize = 13.sp)
                Text("Version $version", color = TextDim, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text("MP3-Player mit Tag-Editor, Online-Abgleich und mitlaufenden Lyrics.", color = AccentSoft, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Pill("GitHub", modifier = Modifier.fillMaxWidth()) { openUrl(ctx, GITHUB_URL) }
            }

            SectionTitle("Updates")
            Card { UpdateBlock() }

            SectionTitle("Neu in dieser Version")
            Card {
                CHANGELOG.forEachIndexed { i, (v, items) ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    Text("Version $v", color = if (v == version) Accent else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    items.forEach { Text("•  $it", color = TextDim, fontSize = 13.sp, lineHeight = 18.sp) }
                }
            }

            SectionTitle("Datenquellen")
            Card {
                Line("MusicBrainz", "Tags (Titel, Interpret, Album, Jahr, Nummer)")
                Line("Deezer", "Tags und Cover")
                Line("Apple iTunes Search", "Tags, Genre und Cover")
                Line("Cover Art Archive", "Albumcover")
                Line("LRCLIB", "Lyrics mit und ohne Zeitangaben")
                Line("lyrics.ovh", "Lyrics ohne Zeitangaben (zweite Quelle)")
                Text("Danke an die Communitys hinter diesen freien Diensten.", color = TextDim, fontSize = 12.sp)
            }

            SectionTitle("Datenschutz")
            Card {
                Text("Daten gehen nur beim Abgleich, bei der Cover- und Lyrics-Suche an die genannten Dienste und bei der Update-Prüfung an cappy0815.github.io bzw. GitHub: " +
                    "Titel, Interpret, Album und Länge. Keine Konten, keine Werbung, keine Analyse. Einstellungen, Playlists und Lyrics bleiben auf dem Gerät.",
                    color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
            }

            SectionTitle("Open Source")
            Card {
                Line("AndroidX, Jetpack Compose, Media3", "Apache License 2.0")
                Line("Kotlin Coroutines", "Apache License 2.0")
                Line("Guava", "Apache License 2.0")
                Line("jaudiotagger", "LGPL 2.1")
                Spacer(Modifier.height(4.dp))
                Pill("Quellcode auf GitHub", modifier = Modifier.fillMaxWidth()) { openUrl(ctx, GITHUB_URL) }
            }

            SectionTitle("Hinweis")
            Card {
                Text("Private Entwicklung, kostenlos und ohne Gewähr. Tags werden direkt in deine Dateien geschrieben – " +
                    "bei wertvollen Sammlungen vorher eine Sicherung anlegen.",
                    color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(10.dp))
                Pill("Projektseite cappy0815", modifier = Modifier.fillMaxWidth()) { openUrl(ctx, PROJECT_URL) }
                Spacer(Modifier.height(8.dp))
                Text("Entwickelt von MCHLNWK gemeinsam mit dem KI-Assistenten Justus Jonas.", color = TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun Line(name: String, what: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(what, color = TextDim, fontSize = 13.sp, modifier = Modifier.weight(1.3f))
    }
}
