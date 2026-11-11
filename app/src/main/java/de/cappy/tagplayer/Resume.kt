package de.cappy.tagplayer

import android.content.Context
import androidx.media3.common.Player

/**
 * Merkt sich die zuletzt gespielte Liste (Titel-IDs), den Titel und die Position.
 * Damit funktionieren „Weiterhören“ am App-Icon, die Play-Taste von Kopfhörer/Auto nach einem
 * Neustart und Android Auto („Wiedergabe fortsetzen“).
 */
object Resume {
    private fun sp(c: Context) = c.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun save(c: Context, p: Player) {
        if (p.mediaItemCount == 0) return
        val ids = (0 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).mediaId.toLongOrNull() }
        if (ids.isEmpty()) return
        sp(c).edit().putString("ids", ids.joinToString(","))
            .putInt("index", p.currentMediaItemIndex.coerceIn(0, ids.lastIndex))
            .putLong("pos", p.currentPosition.coerceAtLeast(0)).apply()
    }

    /** gespeicherte Titel (nur noch vorhandene), Startindex, Position */
    fun load(c: Context): Triple<List<Song>, Int, Long>? {
        val ids = sp(c).getString("ids", "")?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return null
        if (ids.isEmpty()) return null
        val idx = sp(c).getInt("index", 0)
        val curId = ids.getOrNull(idx)
        val songs = ids.mapNotNull { Library.byId(it) }
        if (songs.isEmpty()) return null
        val start = songs.indexOfFirst { it.id == curId }
        return Triple(songs, start.coerceAtLeast(0), if (start >= 0) sp(c).getLong("pos", 0) else 0L)
    }
}
