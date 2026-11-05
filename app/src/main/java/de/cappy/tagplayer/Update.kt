package de.cappy.tagplayer

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Update-Prüfung wie bei Claude Limits und Soundboard:
 *  1. Versionsdatei auf der Projektseite  https://cappy0815.github.io/downloads/player.json
 *     {"version":"0.6","page":"…","download":"…apk","sha256":"…"}
 *  2. Rückfall: neuestes GitHub-Release von cappy0815/player
 * Es wird nichts automatisch installiert – die App zeigt nur an und öffnet den Download im Browser.
 * Gesendet wird nur eine normale Abfrage (Kennung "Player/<Version>"), keine Daten aus der App.
 */
object Update {
    const val VERSION_URL = "https://cappy0815.github.io/downloads/player.json"
    const val RELEASE_API = "https://api.github.com/repos/cappy0815/player/releases/latest"
    private const val DAY_MS = 24L * 3600 * 1000

    var busy by mutableStateOf(false); private set
    var latest by mutableStateOf(""); private set
    var lastCheck by mutableLongStateOf(0L); private set
    var failed by mutableStateOf(false); private set
    var auto by mutableStateOf(true); private set
    var showDialog by mutableStateOf(false)
    private var download = ""
    private var page = PROJECT_URL
    private var loaded = false

    private fun sp(c: Context) = c.getSharedPreferences("update", Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = sp(c)
        auto = p.getBoolean("auto", true); lastCheck = p.getLong("last", 0)
        latest = p.getString("latest", "") ?: ""; download = p.getString("download", "") ?: ""
        page = p.getString("page", PROJECT_URL) ?: PROJECT_URL
    }

    fun setAuto(c: Context, on: Boolean) { auto = on; sp(c).edit().putBoolean("auto", on).apply() }

    /** gibt es laut letzter Prüfung eine neuere Version? */
    fun available(c: Context): Boolean = latest.isNotEmpty() && newerThan(latest, appVersion(c))

    /** automatische Prüfung fällig (eingeschaltet, letzte Prüfung älter als 24 h) */
    fun due(): Boolean = auto && System.currentTimeMillis() - lastCheck > DAY_MS

    suspend fun check(c: Context): Boolean {
        load(c)
        if (busy) return false
        busy = true; failed = false
        val r: Triple<String, String, String>? = withContext(Dispatchers.IO) {
            try {
                val (code, b) = Http.get(VERSION_URL, 12000)
                if (code == 200 && b != null) {
                    val o = JSONObject(String(b))
                    val v = o.optString("version").trim().trimStart('v', 'V')
                    if (v.isNotEmpty()) return@withContext Triple(v, o.optString("download"), o.optString("page", PROJECT_URL))
                }
            } catch (_: Exception) {}
            try {
                val (code, b) = Http.get(RELEASE_API, 12000)
                if (code == 200 && b != null) {
                    val o = JSONObject(String(b))
                    val v = o.optString("tag_name").trim().trimStart('v', 'V')
                    val assets = o.optJSONArray("assets")
                    val dl = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                        .firstOrNull { it.optString("name").lowercase().endsWith(".apk") }?.optString("browser_download_url") ?: ""
                    if (v.isNotEmpty()) return@withContext Triple(v, dl, o.optString("html_url", GITHUB_URL))
                }
            } catch (_: Exception) {}
            null
        }
        lastCheck = System.currentTimeMillis()
        val e = sp(c).edit().putLong("last", lastCheck)
        if (r != null) {
            latest = r.first; download = r.second; page = r.third
            e.putString("latest", latest).putString("download", download).putString("page", page)
        } else failed = true
        e.apply()
        busy = false
        return r != null
    }

    fun openDownload(c: Context) = openUrl(c, download.ifBlank { page })
    fun openPage(c: Context) = openUrl(c, page)

    fun lastText(): String =
        if (lastCheck == 0L) "noch nie" else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(lastCheck))
}

/** Dialog „⬆ Update“ */
@Composable
fun UpdateDialog() {
    if (!Update.showDialog) return
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { Update.showDialog = false },
        containerColor = Surface2,
        title = { Text("⬆ Update", color = Color.White) },
        text = {
            Text("Version ${Update.latest} ist verfügbar (installiert: ${appVersion(ctx)}).\n\n" +
                "„Herunterladen“ öffnet die APK im Browser. Danach die Datei antippen und installieren – " +
                "Playlists, Lyrics und Einstellungen bleiben erhalten.", color = TextDim)
        },
        confirmButton = { TextButton(onClick = { Update.showDialog = false; Update.openDownload(ctx) }) { Text("Herunterladen", color = Accent) } },
        dismissButton = {
            Row {
                TextButton(onClick = { Update.showDialog = false; Update.openPage(ctx) }) { Text("Was ist neu?", color = TextDim) }
                TextButton(onClick = { Update.showDialog = false }) { Text("Später", color = TextDim) }
            }
        }
    )
}

/** Block für Einstellungen und Info: Status, automatische Prüfung, Jetzt prüfen */
@Composable
fun UpdateBlock() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Update.load(ctx) }
    val avail = Update.available(ctx)
    Text(
        when {
            Update.busy -> "Prüfe …"
            Update.failed -> "Update-Server nicht erreichbar – Internet prüfen"
            avail -> "Version ${Update.latest} ist verfügbar"
            Update.latest.isNotEmpty() -> "Aktuell (neueste Version ${Update.latest})"
            else -> "Noch nicht geprüft"
        } + "\nInstalliert: ${appVersion(ctx)} · zuletzt geprüft: ${Update.lastText()}",
        color = when { avail -> Good; Update.failed -> Accent; else -> TextDim }, fontSize = 13.sp, lineHeight = 18.sp
    )
    Spacer(Modifier.height(8.dp))
    SwitchRow("Täglich automatisch prüfen", "Beim Start höchstens einmal pro Tag; es wird nichts automatisch installiert",
        Update.auto) { Update.setAuto(ctx, it) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill("Jetzt prüfen", enabled = !Update.busy, filled = !avail, modifier = Modifier.weight(1f)) {
            scope.launch { Update.check(ctx) }
        }
        if (avail) Pill("Herunterladen", filled = true, modifier = Modifier.weight(1f)) { Update.openDownload(ctx) }
    }
}
