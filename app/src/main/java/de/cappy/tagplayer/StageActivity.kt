package de.cappy.tagplayer

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * "Always-On"-Lyrics: schwarzer Vollbildschirm mit großen Lyrics, gedimmt,
 * liegt auch über dem Sperrbildschirm. Samsungs echtes AOD lässt keine fremden
 * Inhalte zu, deshalb hält diese Anzeige das Display selbst (dunkel) an.
 * Seitentaste schaltet wie gewohnt aus.
 */
class StageActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FROM_LOCK = "fromLock"
        @Volatile var resumed = false
        @Volatile var lastPaused = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        applyIntent(intent)
        PlayerHub.connect(this)
        val prefs = Prefs.get(this)
        val ic = WindowCompat.getInsetsController(window, window.decorView)
        ic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ic.hide(WindowInsetsCompat.Type.systemBars())
        setContent {
            MaterialTheme(colorScheme = darkColors) {
                Stage(prefs, onClose = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(i: Intent?) {
        // Beim Sperren gestartet -> Display wieder einschalten (gedimmt)
        setTurnScreenOn(i?.getBooleanExtra(EXTRA_FROM_LOCK, false) == true)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        val prefs = Prefs.get(this)
        applyKeepOn()
        window.attributes = window.attributes.apply { screenBrightness = prefs.stageBrightness.coerceIn(1, 100) / 100f }
    }

    /** Display anlassen: Schalter "Immer an" und ggf. nur am Ladekabel */
    fun applyKeepOn() {
        val prefs = Prefs.get(this)
        val keep = prefs.stageKeepOn && (!prefs.stageOnlyCharging || PlaybackService.isCharging(this))
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        resumed = false
        lastPaused = System.currentTimeMillis()
        super.onPause()
    }
}

@Composable
fun Stage(prefs: Prefs, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val song = Library.byId(PlayerHub.currentId)
    var controls by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(IntOffset.Zero) }
    val sx by animateIntAsState(shift.x, tween(4000), label = "sx")
    val sy by animateIntAsState(shift.y, tween(4000), label = "sy")
    var now by remember { mutableStateOf(Date()) }

    // Bibliothek ggf. laden (Start direkt aus dem Sperrbildschirm)
    LaunchedEffect(Unit) { if (!Library.loadedOnce) Library.load(ctx) }
    // Lyrics zum aktuellen Titel
    LaunchedEffect(song?.id) { song?.let { LyricsStore.load(ctx, it, prefs.autoLyrics) } }
    // Einbrennschutz: alle 60 s ein paar Pixel verschieben
    LaunchedEffect(prefs.pixelShift) {
        while (true) {
            shift = if (prefs.pixelShift) IntOffset(Random.nextInt(-24, 25), Random.nextInt(-40, 41)) else IntOffset.Zero
            delay(60_000)
        }
    }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(10_000) } }
    // Beim Öffnen kurz anzeigen, ob das Display anbleibt
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(3500); hint = false }
    // Steuerung nach 6 s ausblenden
    LaunchedEffect(controls) { if (controls) { delay(6000); controls = false } }
    // Wiedergabe länger als 60 s gestoppt -> Anzeige schließen, Handy darf schlafen
    LaunchedEffect(PlayerHub.isPlaying) { if (!PlayerHub.isPlaying) { delay(60_000); onClose() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, null) { controls = !controls }
    ) {
        Column(Modifier.fillMaxSize().offset { IntOffset(sx, sy) }.windowInsetsPadding(WindowInsets.displayCutout)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song?.title?.ifBlank { song.fileName } ?: "", color = Color.White.copy(alpha = .55f), fontSize = 15.sp, maxLines = 1)
                    Text(song?.artist ?: "", color = TextDim.copy(alpha = .55f), fontSize = 13.sp, maxLines = 1)
                }
                Text(SimpleDateFormat("HH:mm", Locale.GERMANY).format(now), color = TextDim.copy(alpha = .6f), fontSize = 22.sp)
            }
            val lyr = if (LyricsStore.currentSongId == song?.id) LyricsStore.current else null
            if (lyr == null && song != null) {
                // ohne Lyrics: Titel groß
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(song.title.ifBlank { song.fileName }, color = Color.White, fontSize = (prefs.stageFontSp * 1.1f).sp,
                            lineHeight = (prefs.stageFontSp * 1.3f).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(song.artist, color = Accent, fontSize = (prefs.stageFontSp * .6f).sp)
                        Spacer(Modifier.height(16.dp))
                        Text(LyricsStore.status, color = TextDim, fontSize = 14.sp)
                    }
                }
            } else {
                LyricsView(lyr, LyricsStore.status, prefs.stageFontSp, Modifier.weight(1f).fillMaxWidth(), seekOnTap = false)
            }
        }

        AnimatedVisibility(
            hint || controls, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp)
        ) {
            val charging = PlaybackService.isCharging(ctx)
            Text(when {
                    !prefs.stageKeepOn -> "Display geht normal aus  ·  ☾"
                    prefs.stageOnlyCharging && !charging -> "Display bleibt nur am Ladekabel an"
                    else -> "Display bleibt an  ·  ☀"
                },
                color = TextDim, fontSize = 13.sp,
                modifier = Modifier.background(Surface.copy(alpha = .8f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp))
        }

        AnimatedVisibility(
            controls, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { PlayerHub.prev(); controls = true }) { Icon(Icons.Default.SkipPrevious, "Zurück", tint = Color.White, modifier = Modifier.size(36.dp)) }
                FilledIconButton(onClick = { PlayerHub.toggle(); controls = true }, modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Color.Black)) {
                    Icon(if (PlayerHub.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { PlayerHub.next(); controls = true }) { Icon(Icons.Default.SkipNext, "Weiter", tint = Color.White, modifier = Modifier.size(36.dp)) }
                IconButton(onClick = {
                    prefs.stageKeepOn = !prefs.stageKeepOn
                    (ctx as? StageActivity)?.applyKeepOn()
                    controls = true
                }) {
                    Icon(if (prefs.stageKeepOn) Icons.Default.LightMode else Icons.Default.Bedtime,
                        if (prefs.stageKeepOn) "Display immer an" else "Display geht normal aus",
                        tint = if (prefs.stageKeepOn) Accent else TextDim, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Schließen", tint = TextDim, modifier = Modifier.size(30.dp)) }
            }
        }
    }
}
