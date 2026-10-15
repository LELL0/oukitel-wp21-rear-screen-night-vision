package com.lello.wp21rear.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lello.wp21rear.core.RLog
import com.lello.wp21rear.service.NotifListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks the active media session through the notification listener permission
 * (MediaSessionManager.getActiveSessions needs an enabled listener component).
 */
object MediaRepo {
    interface Listener {
        fun onMediaChanged() {}
        /** Playback just started (used to pop up the music page). */
        fun onPlaybackStarted() {}
    }

    val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile var controller: MediaController? = null
        private set
    @Volatile var title: String = ""
        private set
    @Volatile var artist: String = ""
        private set
    @Volatile var art: Bitmap? = null
        private set
    @Volatile var duration: Long = 0
        private set
    @Volatile var appName: String = ""
        private set

    private var msm: MediaSessionManager? = null
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var wasPlaying = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
        override fun onSessionDestroyed() {
            pick(msm?.getActiveSessions(ComponentName(app, NotifListener::class.java)))
        }
    }

    /** Call once the notification listener is connected. */
    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (msm != null) return
        try {
            val m = app.getSystemService(MediaSessionManager::class.java)
            val comp = ComponentName(app, NotifListener::class.java)
            m.addOnActiveSessionsChangedListener(sessionsListener, comp, main)
            msm = m
            pick(m.getActiveSessions(comp))
        } catch (e: SecurityException) {
            RLog.w("media sessions: notification access missing ($e)")
        }
    }

    fun stop() {
        msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        msm = null
        controller?.unregisterCallback(cb)
        controller = null
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = best
            best?.registerCallback(cb, main)
        }
        changed()
    }

    private fun changed() {
        val c = controller
        val md = c?.metadata
        title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
        appName = c?.packageName?.let { NotifRepo.appName(app, it) } ?: ""
        val playing = isPlaying()
        for (l in listeners) l.onMediaChanged()
        if (playing && !wasPlaying) for (l in listeners) l.onPlaybackStarted()
        wasPlaying = playing
    }

    fun hasSession() = controller != null

    fun isPlaying() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Current position in ms, extrapolated from the last playback state. */
    fun position(): Long {
        val s = controller?.playbackState ?: return 0
        var p = s.position
        if (s.state == PlaybackState.STATE_PLAYING) {
            p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        return if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
    }

    fun playPause() {
        val c = controller ?: return
        if (isPlaying()) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun prev() = controller?.transportControls?.skipToPrevious()
    fun seek(ms: Long) = controller?.transportControls?.seekTo(ms)

    fun volume(ctx: Context): Pair<Int, Int> {
        val am = ctx.getSystemService(AudioManager::class.java)
        return Pair(am.getStreamVolume(AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    fun setVolume(ctx: Context, v: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
    }
}
