package de.cappy.tagplayer

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Schreibt ins Diagnose-Protokoll, wohin der Ton geht (Musik- oder Gesprächskanal, welches Gerät)
 * und in welchem Modus Android steht. Damit lässt sich klären, warum der Ton im Auto als
 * „Sprache“ ankommt. Es wird nur gelesen, nichts umgestellt.
 */
object AudioDiag {
    private var cb: AudioDeviceCallback? = null

    private fun type(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth-MUSIK (A2DP)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth-SPRACHE (SCO/Telefon)"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Handy-Lautsprecher"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Hörmuschel"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Kabel-Kopfhörer"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_BUS -> "Auto-Audio (Bus)"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Weiterleitung (z. B. Android Auto/Cast)"
        else -> if (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET) "Bluetooth-LE" else "Typ $t"
    }

    private fun mode(m: Int) = when (m) {
        AudioManager.MODE_NORMAL -> "normal (Musik)"
        AudioManager.MODE_IN_CALL -> "TELEFONAT"
        AudioManager.MODE_IN_COMMUNICATION -> "GESPRÄCH (z. B. Messenger/Sprachassistent)"
        AudioManager.MODE_RINGTONE -> "Klingeln"
        else -> "Modus $m"
    }

    /** Momentaufnahme ins Protokoll */
    fun snapshot(c: Context, why: String) {
        try {
            val am = c.getSystemService(AudioManager::class.java)
            val sb = StringBuilder("Audio [$why]: Modus ${mode(am.mode)}")
            sb.append(" · Musik aktiv ${am.isMusicActive}")
            @Suppress("DEPRECATION") sb.append(" · SCO an ${am.isBluetoothScoOn}")
            sb.append(" · Lautstärke Musik ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
            sb.append(", Gespräch ${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}")
            if (Build.VERSION.SDK_INT >= 31) am.communicationDevice?.let { sb.append(" · Gesprächsgerät ${type(it.type)} „${it.productName}“") }
            if (Build.VERSION.SDK_INT >= 33) {
                val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
                sb.append(" · Musik geht an: " + am.getAudioDevicesForAttributes(attrs).joinToString { "${type(it.type)} „${it.productName}“" })
            }
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            sb.append(" · Ausgänge: " + outs.joinToString { "${type(it.type)} „${it.productName}“" }.ifBlank { "nur Handy" })
            // laufende Wiedergaben aller Apps (nur Art, ohne Inhalt)
            val pcs = am.activePlaybackConfigurations
            sb.append(" · Wiedergaben: " + pcs.joinToString { pc -> "usage ${pc.audioAttributes.usage}/content ${pc.audioAttributes.contentType}" }.ifBlank { "keine" })
            Diag.log(c, sb.toString())
        } catch (e: Exception) { Diag.log(c, "Audio [$why]: Fehler ${e.message}") }
    }

    /** Geräteänderungen mitschreiben (Auto verbunden/getrennt, Wechsel Musik ↔ Sprache) */
    fun start(c: Context) {
        if (cb != null) return
        val am = c.getSystemService(AudioManager::class.java)
        cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>) {
                d.filter { it.isSink }.forEach { Diag.log(c, "Audio: Gerät verbunden ${type(it.type)} „${it.productName}“") }
                snapshot(c, "Gerät verbunden")
            }
            override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>) {
                d.filter { it.isSink }.forEach { Diag.log(c, "Audio: Gerät getrennt ${type(it.type)} „${it.productName}“") }
            }
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        if (Build.VERSION.SDK_INT >= 31) am.addOnModeChangedListener(c.mainExecutor) { m -> Diag.log(c, "Audio: Modus gewechselt → ${mode(m)}") }
    }

    fun stop(c: Context) { cb?.let { c.getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(it) }; cb = null }
}
