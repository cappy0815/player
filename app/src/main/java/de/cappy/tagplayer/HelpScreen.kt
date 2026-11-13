package de.cappy.tagplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Hilfe: alle Funktionen kurz erklärt, Abschnitte zum Auf- und Zuklappen */
private val HELP: List<Pair<String, List<String>>> = listOf(
    "Erste Schritte" to listOf(
        "Beim ersten Start fragt Player nach Zugriff auf deine Musik. Ohne diese Erlaubnis bleibt die Bibliothek leer.",
        "Zum Speichern von Tags, Covern und Lyrics in die MP3 braucht die App zusätzlich „Zugriff auf alle Dateien“ (Einstellungen → Berechtigungen).",
        "Unter Einstellungen → Bibliothek wählst du die Musikordner. Ohne Auswahl zeigt die App alle Musik auf dem Gerät. Kurze Dateien (Klingeltöne, Sprachnachrichten) blendest du dort mit „Ausblenden, wenn kürzer als“ aus."
    ),
    "Bibliothek" to listOf(
        "Oben zwischen Titel, Alben, Interpreten, Ordner und Playlists wischen oder den Reiter antippen.",
        "Die Titelliste ist alphabetisch sortiert. Oben rechts in der Liste lässt sich die Sortierung ändern (Interpret, Album, Dateiname, zuletzt geändert).",
        "Langer Druck (oder ⋮) auf ein Lied, Album, einen Interpreten, Ordner oder eine Playlist öffnet das Menü: Abspielen, Als Nächstes spielen, An Wiedergabeliste anhängen, Zur Playlist, Tags oder Cover prüfen.",
        "Gehst du aus einem Ordner oder Album zurück, steht die Liste wieder an derselben Stelle.",
        "Lupe oben: Suche nach Titel, Interpret, Album oder Dateiname."
    ),
    "Wiedergabe" to listOf(
        "Unten erscheint der Miniplayer: antippen öffnet die Wiedergabe, die Knöpfe daneben öffnen Lyrics und Wiedergabeliste.",
        "In der Wiedergabe zwischen Cover und Lyrics wischen.",
        "Wiedergabeliste: zeigt, was gerade läuft und was danach kommt. Antippen springt zum Titel, langer Druck sortiert oder entfernt. Oben: Lyrics, als Playlist speichern, kommende Titel entfernen.",
        "Im Hintergrund steuerst du Player über die Benachrichtigung (Pull-down): Zurück, Pause/Weiter, Weiter und „Beenden“. Beenden stoppt die Wiedergabe und schließt die Benachrichtigung.",
        "Langer Druck auf das App-Icon: Weiterhören, Zufallsmix, Suchen, Lyrics-Vollbild. Die Einträge lassen sich auch auf den Startbildschirm ziehen.",
        "Die zuletzt gespielte Liste bleibt auch nach einem Neustart erhalten („Weiterhören“, Kopfhörer-Taste, Android Auto)."
    ),
    "Lyrics" to listOf(
        "Lyrics kommen aus der MP3, sonst automatisch aus LRCLIB oder lyrics.ovh. Texte mit Zeitangaben haben Vorrang: dann wird die aktuelle Zeile hervorgehoben und mittig gehalten.",
        "Eine Zeile antippen springt an diese Stelle im Lied.",
        "Nichts gefunden oder falsch? In der Lyrics-Ansicht „Selbst suchen oder einfügen“ bzw. ⋮ → „Lyrics suchen / bearbeiten“. Interpret und Titel lassen sich frei eingeben – bei Live-Aufnahmen hilft der Titel ohne „(Live)“.",
        "Text ohne Zeitangaben: „Zeiten beim Hören setzen“ – das Lied startet von vorn, du tippst bei jeder Zeile auf den großen Knopf.",
        "Kommt der Text zu früh oder zu spät, im Lyrics-Editor den Zeitversatz in 0,1- oder 0,5-Sekunden-Schritten anpassen.",
        "Einstellung „Beim Abspielen immer zu Lyrics wechseln“ (Einstellungen → Wiedergabe) öffnet beim Start eines Titels direkt die Lyrics."
    ),
    "Lyrics-Vollbild" to listOf(
        "Vollbild-Symbol in der Wiedergabe: schwarzer Bildschirm, große Schrift, gedimmt.",
        "Das Display bleibt an. Antippen zeigt die Steuerung, ☀/☾ schaltet „Display immer an“ um.",
        "„Beim Sperren anzeigen“ (Einstellungen → Lyrics-Display): läuft Musik und das Display geht aus, erscheinen die Lyrics über dem Sperrbildschirm. Braucht „Über anderen Apps einblenden“.",
        "Samsungs Always-On-Display lässt keine Inhalte fremder Apps zu – deshalb hält die App das Display selbst gedimmt an. Das braucht mehr Akku; Option „nur am Ladekabel“."
    ),
    "Tags & Cover" to listOf(
        "Tag-Editor: langer Druck auf ein Lied → Tags bearbeiten. Vor dem Speichern siehst du alle Änderungen.",
        "Online prüfen: über das Menü eines Lieds, Albums, Ordners oder einer Playlist. Gesucht wird in MusicBrainz, Deezer und iTunes. Sichere Treffer (Schwelle in den Einstellungen) werden automatisch gespeichert, unsichere landen in „Prüfen“ – antippen und selbst wählen.",
        "Der Ordnername gilt als sicherer Hinweis auf das Album („Interpret - Album“ oder „Album“). Ein Treffer mit anderem Album wird nicht automatisch gespeichert.",
        "Ordnerregel: in einem Album-Ordner bekommen alle Dateien dasselbe Album und dasselbe Cover.",
        "Album-Tags für Ordner setzen (Ordner-Menü oder Ordneransicht): Album, Album-Interpret, Jahr, Genre, Titelanzahl, CD-Nummer und Cover für alle Dateien in einem Zug. Eine Bilddatei im Ordner (cover.jpg, folder.jpg …) wird als Cover vorgeschlagen. Für einen Oberordner geht das für alle Alben darunter auf einmal."
    ),
    "Playlists" to listOf(
        "Reiter Playlists → „Neue Playlist“. Titel über das Menü → „Zur Playlist“, ganze Ordner/Alben mit „Alle zur Playlist“.",
        "In der Playlist: Stift oben zum Sortieren und Entfernen, ⋮ zum Umbenennen oder Löschen. Die Musikdateien bleiben dabei unberührt."
    ),
    "Android Auto" to listOf(
        "Player erscheint im Auto als Medien-App mit Playlists, Alben, Interpreten, Ordnern, Alle Titel, Zufallsmix und Suche.",
        "Weil die App nicht aus dem Play Store kommt, einmalig in Android Auto „Unbekannte Quellen“ einschalten: Handy-Einstellungen → Apps → Android Auto → „Weitere Einstellungen in der App“ → ganz unten 10-mal auf „Version“ tippen → oben rechts ⋮ → Entwicklereinstellungen → „Unbekannte Quellen“. Dann neu verbinden. Menünamen können je nach Version abweichen."
    ),
    "Updates & Diagnose" to listOf(
        "Player prüft einmal täglich, ob es eine neue Version gibt, und zeigt dann oben „⬆ Update“. Einstellungen → Updates: Status, „Jetzt prüfen“, automatische Prüfung abschalten.",
        "Diagnose (Einstellungen → Diagnose): erstellt auf Wunsch eine ZIP-Datei mit Ordnern, Tags, Cover-Prüfsummen, Abgleich-Ergebnissen und Protokoll – keine Musik, keine Bilder. Sie verlässt das Handy nur, wenn du sie teilst."
    ),
    "Probleme?" to listOf(
        "Tags werden nicht gespeichert: „Zugriff auf alle Dateien“ erteilen. Unter „Prüfen“ → Filter „Fehler“ steht der Grund.",
        "Ein Titel fehlt: Mindestlänge und Musikordner in den Einstellungen prüfen. Dort steht auch, wie viele Titel ausgeblendet sind.",
        "Keine Steuerung in der Benachrichtigung: Benachrichtigungen für Player erlauben (Einstellungen → Berechtigungen).",
        "Android Auto zeigt nur eine Sanduhr: direkt danach eine Diagnose-Datei erstellen und teilen."
    )
)

@Composable
fun HelpScreen() {
    val ctx = LocalContext.current
    val open = remember { mutableStateListOf(HELP.first().first) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Hilfe", onBack = { Navi.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)) {
            ProjectLink(ctx)
            HELP.forEach { (title, lines) ->
                val isOpen = title in open
                Spacer(Modifier.height(10.dp))
                Card {
                    Row(Modifier.fillMaxWidth().clickable { if (isOpen) open.remove(title) else open.add(title) }) {
                        Text(title, color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(if (isOpen) "▲" else "▼", color = TextDim, fontSize = 14.sp)
                    }
                    if (isOpen) {
                        Spacer(Modifier.height(8.dp))
                        lines.forEach { l ->
                            Row(Modifier.padding(bottom = 6.dp)) {
                                Text("•", color = Accent, fontSize = 14.sp, modifier = Modifier.width(14.dp))
                                Text(l, color = Color(0xFFD9DDE4), fontSize = 14.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            ProjectLink(ctx)
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** Klickbarer Link zur Projektseite (Vorgabe: in jeder Hilfe) */
@Composable
private fun ProjectLink(ctx: android.content.Context) {
    Text("Projektseite: cappy0815.github.io › Player", color = Accent, fontSize = 14.sp,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable { openUrl(ctx, PROJECT_URL) }.padding(vertical = 8.dp))
}
