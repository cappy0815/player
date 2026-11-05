# Player

Einfacher MP3-Player für Android mit eingebautem **Tag-Editor**, **Online-Abgleich** und **großen, mitlaufenden Lyrics**.

Gebaut für lokale MP3s auf dem Handy, ohne Konto, ohne Werbung, ohne Streaming.

## Funktionen

**Wiedergabe**
- Bibliothek mit Titeln, Alben, Interpreten, Ordnern und Playlists, Wechsel per Wischen
- Hintergrund-Wiedergabe mit Steuerung in Benachrichtigung und Sperrbildschirm
- Zufall, Wiederholen, Pause beim Abziehen des Kopfhörers
- Musikordner frei wählbar, kurze Dateien (Klingeltöne, Sprachnachrichten) ausblendbar

**Tags & Cover**
- Tags direkt in der MP3 bearbeiten: Titel, Interpret, Album, Album-Interpret, Jahr, Genre, Titelnummer, CD, Cover, Lyrics
- Vorher/Nachher-Vorschau vor dem Speichern
- Online-Abgleich über [MusicBrainz](https://musicbrainz.org), Cover vom [Cover Art Archive](https://coverartarchive.org)
- Prüfung für einzelne Titel, Alben, Interpreten, ganze Ordner oder Playlists
- Sichere Treffer (Schwelle einstellbar) werden automatisch gespeichert, unsichere landen in „Prüfen“
- Cover-Prüfung getrennt von den Tags: nur fehlende Cover ergänzen oder vorhandene ersetzen
- Abgleich nur auf Knopfdruck, nie automatisch im Hintergrund

**Lyrics**
- Automatische Suche über [LRCLIB](https://lrclib.net), synchronisierte Texte bevorzugt
- Aktuelle Zeile wird beim Abspielen hervorgehoben und mittig gehalten
- Zeile antippen springt an diese Stelle im Lied
- Optional in die MP3 schreiben
- **Vollbild-Lyrics:** schwarz, große Schrift, gedimmt, auch über dem Sperrbildschirm, mit Einbrennschutz für OLED

**Playlists**
- Anlegen, umbenennen, sortieren, löschen
- Ganze Ordner, Alben oder Interpreten auf einmal hinzufügen

## Installation

APK aus den [Releases](../../releases) laden und installieren (Installation aus unbekannten Quellen erlauben).

Beim ersten Start fragt die App nach Zugriff auf die Musik. Für das Speichern von Tags braucht sie zusätzlich **„Zugriff auf alle Dateien“**, für das Lyrics-Vollbild beim Sperren **„Über anderen Apps einblenden“**. Beides lässt sich in den Einstellungen der App erteilen.

Voraussetzung: Android 10 oder neuer.

## Hinweise

- Samsungs Always-On-Display lässt keine Inhalte fremder Apps zu. Das Lyrics-Vollbild hält deshalb das Display selbst gedimmt an und braucht mehr Akku. Option „nur am Ladekabel“ in den Einstellungen.
- MusicBrainz erlaubt etwa eine Anfrage pro Sekunde. Große Ordner brauchen entsprechend Zeit.
- Lyrics ohne Zeitangaben werden angezeigt, die aktuelle Stelle ist dann nur geschätzt.

## Datenschutz

Die App überträgt nur beim Abgleich bzw. bei der Lyrics-Suche Titel, Interpret, Album und Länge an MusicBrainz, Cover Art Archive und LRCLIB. Keine Konten, keine Werbung, keine Analyse. Einstellungen, Playlists und Lyrics-Zwischenspeicher bleiben auf dem Gerät.

## Selbst bauen

Kotlin, Jetpack Compose, Media3 (ExoPlayer), [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger).

```bash
./gradlew assembleRelease
```

Für eine signierte APK `keystore.properties.example` nach `keystore.properties` kopieren und den eigenen Schlüssel eintragen. Ohne die Datei entsteht eine unsignierte APK.

## Danke

- [MusicBrainz](https://musicbrainz.org) und [Cover Art Archive](https://coverartarchive.org) für die Metadaten und Cover
- [LRCLIB](https://lrclib.net) für die Lyrics
- [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) für das Lesen und Schreiben der Tags

---

Ein Projekt von [MCHLNWK](https://cappy0815.de)
