# Player

*tag it · play it · sing it*

Einfacher MP3-Player für Android mit eingebautem **Tag-Editor**, **Online-Abgleich** und **großen, mitlaufenden Lyrics**.

Gebaut für lokale MP3s auf dem Handy, ohne Konto, ohne Werbung, ohne Streaming.

## Funktionen

**Wiedergabe**
- Bibliothek mit Titeln, Alben, Interpreten, Ordnern und Playlists, Wechsel per Wischen
- Hintergrund-Wiedergabe mit Steuerung in Benachrichtigung und Sperrbildschirm
- Zufall, Wiederholen, Pause beim Abziehen des Kopfhörers
- Wiedergabeliste: ansehen, springen, sortieren, entfernen, als Playlist speichern
- Langdruck-Menü für Lieder, Alben, Interpreten, Ordner und Playlists: Abspielen, Als Nächstes, Anhängen, Zur Playlist, Tags/Cover prüfen
- Musikordner frei wählbar, kurze Dateien (Klingeltöne, Sprachnachrichten) ausblendbar

**Tags & Cover**
- Tags direkt in der MP3 bearbeiten: Titel, Interpret, Album, Album-Interpret, Jahr, Genre, Titelnummer, CD, Cover, Lyrics
- Vorher/Nachher-Vorschau vor dem Speichern
- Online-Abgleich über [MusicBrainz](https://musicbrainz.org), [Deezer](https://developers.deezer.com/api) und die [iTunes Search API](https://performance-partners.apple.com/search-api), Cover zusätzlich vom [Cover Art Archive](https://coverartarchive.org)
- Treffer mehrerer Quellen bestätigen sich gegenseitig; fehlt zum besten Treffer ein Bild, wird das nächste sichere Cover genommen
- Der Name des Speicherordners („Interpret - Album“ oder „Album“) gilt als sicherer Hinweis auf das Album, für Tags und Cover
- Prüfung für einzelne Titel, Alben, Interpreten, ganze Ordner oder Playlists
- Sichere Treffer (Schwelle einstellbar) werden automatisch gespeichert, unsichere landen in „Prüfen“
- Cover-Prüfung getrennt von den Tags: nur fehlende Cover ergänzen oder vorhandene ersetzen
- Abgleich nur auf Knopfdruck, nie automatisch im Hintergrund

**Lyrics**
- Automatische Suche über [LRCLIB](https://lrclib.net) und [lyrics.ovh](https://lyrics.ovh), synchronisierte Texte bevorzugt
- Live-, Remaster- und feat.-Titel werden für die Suche bereinigt
- Manuelle Suche mit Trefferliste, Interpret und Titel frei eingebbar
- Text ohne Zeitangaben wird trotzdem angezeigt; Zeiten lassen sich beim Hören per Tippen setzen
- Eigenen Text einfügen, Zeitversatz für synchrone Texte
- Aktuelle Zeile wird beim Abspielen hervorgehoben und mittig gehalten
- Zeile antippen springt an diese Stelle im Lied
- Optional in die MP3 schreiben
- **Vollbild-Lyrics:** schwarz, große Schrift, gedimmt, Display bleibt an (umschaltbar), auch über dem Sperrbildschirm, mit Einbrennschutz für OLED

**Playlists**
- Anlegen, umbenennen, sortieren, löschen
- Ganze Ordner, Alben oder Interpreten auf einmal hinzufügen, laufendes Lied direkt aus der Wiedergabe

## Versionen

Siehe [CHANGELOG.md](CHANGELOG.md). In der App unter ⋮ → Info, dort auch „Nach Update suchen“.

## Installation

Aktuelle Version auf der Projektseite laden: **[cappy0815.github.io › Player](https://cappy0815.github.io/#player)** (APK mit SHA-256-Prüfsumme). Installation aus unbekannten Quellen erlauben.

Beim ersten Start fragt die App nach Zugriff auf die Musik. Für das Speichern von Tags braucht sie zusätzlich **„Zugriff auf alle Dateien“**, für das Lyrics-Vollbild beim Sperren **„Über anderen Apps einblenden“**. Beides lässt sich in den Einstellungen der App erteilen.

Voraussetzung: Android 10 oder neuer.

## Updates

Die App prüft einmal täglich, ob es eine neue Version gibt (Einstellungen → Updates, abschaltbar), und zeigt dann oben „⬆ Update“. Heruntergeladen wird über den Browser, die neue APK installiert sich über die alte – Playlists, Lyrics und Einstellungen bleiben erhalten. Es wird nichts automatisch installiert.

Grundlage ist die Versionsdatei [`downloads/player.json`](https://cappy0815.github.io/downloads/player.json) auf der Projektseite, Rückfall ist das neueste GitHub-Release.

## Hinweise

- Samsungs Always-On-Display lässt keine Inhalte fremder Apps zu. Das Lyrics-Vollbild hält deshalb das Display selbst gedimmt an und braucht mehr Akku. Option „nur am Ladekabel“ in den Einstellungen.
- MusicBrainz erlaubt etwa eine Anfrage pro Sekunde. Große Ordner brauchen entsprechend Zeit.
- Lyrics ohne Zeitangaben werden angezeigt, die aktuelle Stelle ist dann nur geschätzt.

## Datenschutz

Die App überträgt nur beim Abgleich bzw. bei der Lyrics-Suche Titel, Interpret, Album und Länge an MusicBrainz, Deezer, iTunes, Cover Art Archive, LRCLIB und lyrics.ovh, bei der Update-Prüfung wird cappy0815.github.io bzw. GitHub abgefragt. Keine Konten, keine Werbung, keine Analyse. Einstellungen, Playlists und Lyrics-Zwischenspeicher bleiben auf dem Gerät.

## Selbst bauen

Kotlin, Jetpack Compose, Media3 (ExoPlayer), [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) (LGPL 2.1). AndroidX, Compose, Media3, Coroutines und Guava stehen unter der Apache License 2.0.

```bash
./gradlew assembleRelease
```

Für eine signierte APK `keystore.properties.example` nach `keystore.properties` kopieren und den eigenen Schlüssel eintragen. Ohne die Datei entsteht eine unsignierte APK.

## Danke

- [MusicBrainz](https://musicbrainz.org), [Deezer](https://www.deezer.com), Apple iTunes und [Cover Art Archive](https://coverartarchive.org) für die Metadaten und Cover
- [LRCLIB](https://lrclib.net) und [lyrics.ovh](https://lyrics.ovh) für die Lyrics
- [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) für das Lesen und Schreiben der Tags

---

Ein Projekt von [MCHLNWK](https://cappy0815.de)
