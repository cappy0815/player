package de.cappy.tagplayer

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

/**
 * Wiedergabe im Hintergrund. Media3 kümmert sich um Benachrichtigung,
 * Sperrbildschirm-Steuerung und Kopfhörertasten.
 * Zusätzlich: beim Ausschalten des Displays optional die Lyrics-Anzeige starten.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != Intent.ACTION_SCREEN_OFF) return
            val prefs = Prefs.get(c)
            val p = session?.player ?: return
            if (!prefs.stageOnLock || !p.isPlaying) return
            // Display wurde in der Lyrics-Anzeige selbst ausgeschaltet -> respektieren
            if (StageActivity.resumed || System.currentTimeMillis() - StageActivity.lastPaused < 2500) return
            if (prefs.stageOnlyCharging && !isCharging(c)) return
            if (!Settings.canDrawOverlays(c)) return   // ohne diese Berechtigung blockiert Android den Start
            c.startActivity(
                Intent(c, StageActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(StageActivity.EXTRA_FROM_LOCK, true)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true   // Audiofokus: bei Anrufen/Navi leiser bzw. Pause
            )
            .setHandleAudioBecomingNoisy(true)   // Kopfhörer raus -> Pause
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Bibliothek für Android Auto: Playlists, Alben, Interpreten, Ordner, Suche
        session = MediaLibrarySession.Builder(this, player, AutoLibrary(applicationContext))
            .setCustomLayout(com.google.common.collect.ImmutableList.of(AutoLibrary.stopButton()))
            .setSessionActivity(android.app.PendingIntent.getActivity(this, 0,
                android.content.Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()
        // Liste/Titel/Position merken (Weiterhören, Play-Taste nach Neustart, Android Auto)
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) Resume.save(this@PlaybackService, p)
            }
        })
        Diag.log(this, "Dienst gestartet")
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.player?.let { Resume.save(this, it) }
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        fun isCharging(c: Context): Boolean {
            val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }
    }
}

/** Verbindung der Oberfläche zum Wiedergabedienst, als Compose-State. */
object PlayerHub {
    var controller by mutableStateOf<MediaController?>(null)
        private set
    var isPlaying by mutableStateOf(false)
    var currentId by mutableLongStateOf(-1L)
    var durationMs by mutableLongStateOf(0L)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var queueIds by mutableStateOf<List<Long>>(emptyList())
    var currentIndex by mutableIntStateOf(-1)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    fun connect(ctx: Context) {
        if (controller != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(listener)
                sync(c)
            } catch (_: Exception) {}
        }, MoreExecutors.directExecutor())
    }

    private fun sync(p: Player) {
        isPlaying = p.isPlaying
        currentId = p.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        durationMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (Library.byId(currentId)?.durationMs ?: 0L)
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        queueIds = (0 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).mediaId.toLongOrNull() }
        currentIndex = if (p.mediaItemCount > 0) p.currentMediaItemIndex else -1
    }

    fun position(): Long = controller?.currentPosition ?: 0L

    private fun item(s: Song) = songMediaItem(s)

    /** wird nach dem Starten aus einer Liste gesetzt; Root öffnet dann ggf. die Lyrics-Ansicht */
    var startedFromList by mutableIntStateOf(0)

    fun play(list: List<Song>, index: Int) {
        val c = controller ?: return
        c.setMediaItems(list.map { item(it) }, index.coerceIn(0, list.lastIndex), 0L)
        c.prepare(); c.play()
        startedFromList++
    }

    // ---------- Wiedergabeliste ----------
    /** Direkt hinter dem laufenden Titel einreihen; ist nichts geladen, sofort abspielen */
    fun playNext(list: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(list, 0); return }
        c.addMediaItems(c.currentMediaItemIndex + 1, list.map { item(it) })
    }

    /** Ans Ende der Wiedergabeliste anhängen; ist nichts geladen, sofort abspielen */
    fun enqueue(list: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(list, 0); return }
        c.addMediaItems(list.map { item(it) })
        if (c.playbackState == Player.STATE_ENDED) { c.seekToDefaultPosition(c.mediaItemCount - list.size); c.play() }
    }

    fun jumpTo(i: Int) { controller?.let { if (i in 0 until it.mediaItemCount) { it.seekToDefaultPosition(i); it.play() } } }
    fun removeAt(i: Int) { controller?.let { if (i in 0 until it.mediaItemCount && i != it.currentMediaItemIndex) it.removeMediaItem(i) } }
    fun move(from: Int, to: Int) { controller?.let { if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount) it.moveMediaItem(from, to) } }
    /** Alles nach dem laufenden Titel entfernen */
    fun clearUpcoming() { controller?.let { val n = it.mediaItemCount; val c = it.currentMediaItemIndex; if (c + 1 < n) it.removeMediaItems(c + 1, n) } }

    /** Weiterhören: läuft schon eine Liste, weiterspielen; sonst die zuletzt gespielte wiederherstellen */
    fun resume(c: Context): Boolean {
        val ctl = controller ?: return false
        if (ctl.mediaItemCount > 0) { if (!ctl.isPlaying) { if (ctl.playbackState == Player.STATE_IDLE) ctl.prepare(); ctl.play() }; return true }
        val (songs, start, pos) = Resume.load(c) ?: return false
        ctl.setMediaItems(songs.map { item(it) }, start, pos)
        ctl.prepare(); ctl.play()
        return true
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun next() { controller?.seekToNextMediaItem() }
    fun prev() { controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun seek(ms: Long) { controller?.seekTo(ms) }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    /** Nach Tag-Änderung die Anzeige (Benachrichtigung, Sperrbildschirm) für diesen Titel aktualisieren */
    fun refreshMetadata(s: Song) {
        val c = controller ?: return
        for (i in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(i).mediaId == s.id.toString() && i != c.currentMediaItemIndex)
                c.replaceMediaItem(i, item(s))
        }
    }
}
