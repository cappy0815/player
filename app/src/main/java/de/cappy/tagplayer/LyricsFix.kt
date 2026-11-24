package de.cappy.tagplayer

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Korrekturmodus für Lyrics bei laufendem Lied.
 * Tippen auf eine Zeile heißt: „Diese Zeile wird gerade gesungen.“
 * - Zeile bekommt die aktuelle Position (minus Reaktionszeit).
 * - Alle folgenden Zeilen mit Zeit rutschen um denselben Betrag mit (Abstände bleiben).
 * - Frühere Zeilen, die danach lägen, verlieren ihre Zeit und werden dazwischen verteilt.
 * - Zeilen ohne Zeit (Text ohne Zeitangaben) werden zwischen bekannten Zeilen gleichmäßig verteilt,
 *   nach der letzten bekannten Zeile mit dem bisherigen Durchschnittsabstand.
 * Speichern legt das Ergebnis als eigene Lyrics ab (Vorrang vor allem anderen).
 */
object LyricsFix {
    /** Reaktionszeit beim Tippen */
    const val REACT_MS = 200L

    var active by mutableStateOf(false)
    var songId by mutableLongStateOf(-1L)
    var texts: List<String> = emptyList()
    private var times = LongArray(0)
    private var known = BooleanArray(0)
    private val undo = ArrayList<Pair<LongArray, BooleanArray>>()
    var changes by mutableIntStateOf(0)
    var canUndo by mutableStateOf(false)
    /** Vorschau mit allen Zeiten (für die Anzeige) */
    var preview by mutableStateOf<Lyrics?>(null)

    fun start(song: Song, l: Lyrics) {
        songId = song.id
        if (l.synced) {
            texts = l.lines.map { it.text }
            times = LongArray(texts.size) { l.lines[it].timeMs }
            known = BooleanArray(texts.size) { true }
        } else {
            texts = l.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
            times = LongArray(texts.size)
            known = BooleanArray(texts.size)
        }
        undo.clear(); canUndo = false; changes = 0
        rebuild()
        active = true
    }

    fun cancel() { active = false; preview = null; songId = -1 }

    private fun push() { undo += times.copyOf() to known.copyOf(); if (undo.size > 50) undo.removeAt(0); canUndo = true }

    fun undo() {
        val (t, k) = undo.removeLastOrNull() ?: return
        times = t; known = k; canUndo = undo.isNotEmpty(); changes--; rebuild()
    }

    /** Zeile [i] wird jetzt gesungen */
    fun tap(i: Int) {
        if (i !in texts.indices) return
        push()
        val eff = effective()
        val now = (PlayerHub.position() - REACT_MS).coerceAtLeast(0)
        val delta = now - eff[i]
        times[i] = now; known[i] = true
        for (j in i + 1 until times.size) if (known[j]) times[j] = (times[j] + delta).coerceAtLeast(0)
        for (j in 0 until i) if (known[j] && times[j] >= now) known[j] = false
        changes++
        rebuild()
    }

    /** Alles um [ms] verschieben */
    fun shiftAll(ms: Long) {
        push()
        for (j in times.indices) if (known[j]) times[j] = (times[j] + ms).coerceAtLeast(0)
        changes++
        rebuild()
    }

    /** Zeiten aller Zeilen, unbekannte verteilt */
    private fun effective(): LongArray {
        val n = times.size
        val e = LongArray(n)
        val k = (0 until n).filter { known[it] }
        val dur = PlayerHub.durationMs.takeIf { it > 0 } ?: 240_000L
        if (k.isEmpty()) { for (j in 0 until n) e[j] = dur * j / n.coerceAtLeast(1); return e }
        for (j in k) e[j] = times[j]
        // vor der ersten bekannten Zeile: von 0 bis dorthin
        val f = k.first()
        for (j in 0 until f) e[j] = times[f] * j / (f + 1)
        // zwischen bekannten Zeilen
        for (x in 0 until k.size - 1) {
            val a = k[x]; val b = k[x + 1]
            for (j in a + 1 until b) e[j] = times[a] + (times[b] - times[a]) * (j - a) / (b - a)
        }
        // nach der letzten: Durchschnittsabstand, sonst Rest des Lieds gleichmäßig
        val l = k.last()
        if (l < n - 1) {
            val step = if (k.size >= 2 && times[l] > times[f]) (times[l] - times[f]) / (l - f)
                       else ((dur - times[l]) / (n - l)).coerceAtLeast(1000)
            for (j in l + 1 until n) e[j] = times[l] + step * (j - l)
        }
        return e
    }

    private fun rebuild() {
        val e = effective()
        preview = Lyrics(texts.indices.map { LyricLine(e[it], texts[it]) }, true, "Korrektur")
    }

    /** Speichern als eigene Lyrics */
    fun save(c: Context) {
        val song = Library.byId(songId) ?: run { cancel(); return }
        val text = preview?.toLrc() ?: run { cancel(); return }
        val app = c.applicationContext
        val n = changes
        cancel()
        AppScope.launch {
            LyricsStore.setManual(app, song, text, "eigene (korrigiert)")
            Diag.log(app, "Lyrics korrigiert: ${song.path} ($n Korrekturen)")
            Toast.makeText(app, "Lyrics gespeichert", Toast.LENGTH_SHORT).show()
        }
    }

    /** Titel gewechselt: Änderungen sichern statt verwerfen */
    fun onSongChanged(c: Context, newId: Long) {
        if (!active || newId == songId) return
        if (changes > 0) save(c) else cancel()
    }
}

/** Leiste im Korrekturmodus (Wiedergabe und Vollbild) */
@Composable
fun LyricsFixBar(modifier: Modifier = Modifier, dim: Float = 1f) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
        .background(Surface2.copy(alpha = .92f), RoundedCornerShape(16.dp)).padding(10.dp)) {
        Text("Korrektur: Tippe die Zeile an, die gerade gesungen wird. Die folgenden Zeilen rutschen mit.",
            color = AccentSoft.copy(alpha = dim), fontSize = 12.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("−0,5 s", modifier = Modifier.weight(1f)) { LyricsFix.shiftAll(-500) }
            Pill("+0,5 s", modifier = Modifier.weight(1f)) { LyricsFix.shiftAll(500) }
            Pill("Zurück", enabled = LyricsFix.canUndo, modifier = Modifier.weight(1f)) { LyricsFix.undo() }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Abbrechen", modifier = Modifier.weight(1f)) { LyricsFix.cancel() }
            Pill("Speichern", filled = true, enabled = LyricsFix.changes > 0, modifier = Modifier.weight(1f)) { LyricsFix.save(ctx) }
        }
    }
}

/** Knopf „Korrigieren“ – startet den Modus für den laufenden Titel */
@Composable
fun LyricsFixButton(song: Song?, lyrics: Lyrics?, color: Color = Accent) {
    if (song == null || lyrics == null || LyricsFix.active) return
    Text("✎ Korrigieren", color = color, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Surface.copy(alpha = .8f))
            .clickable { LyricsFix.start(song, lyrics) }
            .padding(horizontal = 14.dp, vertical = 6.dp))
}
