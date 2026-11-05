package de.cappy.tagplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MatchScreen() {
    val ctx = LocalContext.current
    val prefs = Prefs.get(ctx)
    val tick = AppState.resumeTick.intValue
    val allFiles = remember(tick) { hasAllFiles() }
    val counts = Matcher.counts
    val done = Matcher.items.count { it.state != MatchState.WAIT && it.state != MatchState.RUNNING }
    var filter by remember { mutableStateOf<MatchState?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (Matcher.mode == MatchMode.COVER) "Cover prüfen" else "Tags prüfen", onBack = { Navi.back() })
        if (Matcher.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Noch nichts ausgewählt. Über das Menü eines Titels, Albums oder „Alle Titel online abgleichen“ starten.",
                    color = TextDim)
            }
            return@Column
        }
        val list = Matcher.items.filter { filter == null || it.state == filter }
        LazyColumn(Modifier.fillMaxSize()) {
            item { Column(Modifier.padding(horizontal = 16.dp)) {
                Text(Matcher.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("${Matcher.items.size} Titel · Quelle MusicBrainz (ca. 1 Titel pro Sekunde)", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                if (!allFiles) {
                    Card {
                        Text("Zum automatischen Speichern braucht die App „Zugriff auf alle Dateien“.", color = AccentSoft, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Pill("Berechtigung erteilen", filled = true) { openAllFilesSettings(ctx) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (Matcher.mode == MatchMode.COVER)
                    SwitchRow("Vorhandene Cover ersetzen", "Aus: nur Titel ohne Cover bekommen eins",
                        Matcher.replaceCovers) { if (!Matcher.running) Matcher.replaceCovers = it }
                SwitchRow("Sichere Treffer automatisch speichern",
                    "Ab ${prefs.threshold} % Trefferquote (einstellbar). Alles darunter kommt in „Prüfen“ und bleibt unverändert.",
                    Matcher.autoApply) { if (!Matcher.running) Matcher.autoApply = it }
                Spacer(Modifier.height(6.dp))
                if (Matcher.running) {
                    LinearProgressIndicator(progress = { done.toFloat() / Matcher.items.size }, Modifier.fillMaxWidth(),
                        color = Accent, trackColor = Surface2, drawStopIndicator = {})
                    Spacer(Modifier.height(8.dp))
                    Pill("Anhalten", modifier = Modifier.fillMaxWidth()) { Matcher.stop() }
                } else {
                    val waiting = Matcher.items.count { it.state == MatchState.WAIT }
                    Pill(if (waiting == Matcher.items.size) "Abgleich starten" else "Fortsetzen ($waiting offen)",
                        filled = true, enabled = waiting > 0 && (allFiles || !Matcher.autoApply), modifier = Modifier.fillMaxWidth()) {
                        Matcher.start(ctx, AppScope)
                    }
                    if (waiting < Matcher.items.size) {
                        Spacer(Modifier.height(8.dp))
                        Pill("Alles erneut prüfen", modifier = Modifier.fillMaxWidth()) { Matcher.reset() }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Filter: waagerecht scrollbar, damit nichts gequetscht wird
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChipX("Alle", filter == null) { filter = null }
                    FilterChipX("Gespeichert ${counts[MatchState.APPLIED] ?: 0}", filter == MatchState.APPLIED) { filter = MatchState.APPLIED }
                    FilterChipX("Prüfen ${counts[MatchState.REVIEW] ?: 0}", filter == MatchState.REVIEW) { filter = MatchState.REVIEW }
                    FilterChipX("Unverändert ${counts[MatchState.SAME] ?: 0}", filter == MatchState.SAME) { filter = MatchState.SAME }
                    FilterChipX("Kein Treffer ${counts[MatchState.NONE] ?: 0}", filter == MatchState.NONE) { filter = MatchState.NONE }
                    FilterChipX("Fehler ${counts[MatchState.ERROR] ?: 0}", filter == MatchState.ERROR) { filter = MatchState.ERROR }
                }
            }
                Spacer(Modifier.height(6.dp))
            }
            items(list, key = { it.song.id }) { m -> MatchRow(m) }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
fun FilterChipX(text: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(on, onClick, label = { Text(text, fontSize = 12.sp, maxLines = 1, softWrap = false) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.Black,
            labelColor = TextDim))
}

@Composable
fun MatchRow(m: MatchItem) {
    val (sym, col) = when (m.state) {
        MatchState.WAIT -> "·" to TextDim
        MatchState.RUNNING -> "…" to Accent
        MatchState.APPLIED -> "✓" to Good
        MatchState.SAME -> "=" to Good
        MatchState.REVIEW -> "?" to Accent
        MatchState.NONE -> "–" to TextDim
        MatchState.ERROR -> "!" to Bad
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !Matcher.running || m.state != MatchState.RUNNING) {
                Navi.go(Nav.Editor(m.song.id, if (m.state == MatchState.REVIEW) m.candidates else emptyList(), coverOnly = Matcher.mode == MatchMode.COVER))
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(sym, color = col, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(m.song.title.ifBlank { m.song.fileName }, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.info.ifBlank { m.song.artist }, color = if (m.state == MatchState.ERROR) Bad else TextDim, fontSize = 12.sp,
                maxLines = if (m.state == MatchState.ERROR) 3 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
