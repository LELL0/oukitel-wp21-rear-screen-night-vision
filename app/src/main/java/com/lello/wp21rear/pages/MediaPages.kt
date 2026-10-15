package com.lello.wp21rear.pages

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.RLog
import com.lello.wp21rear.ui.Draw
import com.lello.wp21rear.ui.Icons
import com.lello.wp21rear.ui.Page
import com.lello.wp21rear.ui.RearUi
import kotlin.concurrent.thread

/** Decodes [uri] scaled so its short side is about [size] px. */
fun decodeScaled(uri: Uri, size: Int = 360): Bitmap? = try {
    val src = ImageDecoder.createSource(Rear.app.contentResolver, uri)
    ImageDecoder.decodeBitmap(src) { d, info, _ ->
        val s = size.toFloat() / minOf(info.size.width, info.size.height)
        if (s < 1f) d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
} catch (e: Exception) {
    RLog.w("decode $uri: $e")
    null
}

/** Slideshow from the chosen folder (SAF tree) or, without one, the 40 newest photos. */
class PhotosPage(ui: RearUi) : Page(ui) {
    override val id = "photos"

    private var uris: List<Uri> = emptyList()
    private var idx = 0
    @Volatile private var cur: Bitmap? = null
    @Volatile private var prev: Bitmap? = null
    private var fadeStart = 0L
    private var loadedFolder: String? = "\u0000"
    private var visible = false
    private val advance = Runnable { step(1) }

    override fun onShow() {
        visible = true
        val folder = Rear.prefs.photoFolder
        if (folder != loadedFolder || uris.isEmpty()) {
            loadedFolder = folder
            thread(name = "photos-list") {
                uris = list(folder)
                idx = 0
                load()
            }
        } else {
            schedule()
        }
    }

    override fun onHide() {
        visible = false
        ui.cancel(advance)
    }

    private fun list(folder: String?): List<Uri> = try {
        if (folder != null) {
            val tree = Uri.parse(folder)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val out = ArrayList<Uri>()
            Rear.app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1)?.startsWith("image/") == true) out.add(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
                }
            }
            out.sortedBy { it.toString() }
        } else {
            val out = ArrayList<Uri>()
            Rear.app.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 40) out.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)))
            }
            out
        }
    } catch (e: Exception) {
        RLog.w("photos: $e")
        emptyList()
    }

    private fun load() {
        val u = uris.getOrNull(idx) ?: run { invalidate(); return }
        val b = decodeScaled(u)
        ui.post {
            prev = cur
            cur = b
            fadeStart = SystemClock.uptimeMillis()
            invalidate()
            schedule()
        }
    }

    private fun schedule() {
        ui.cancel(advance)
        if (visible && uris.size > 1) ui.postDelayed(Rear.prefs.photoIntervalSec * 1000L, advance)
    }

    private fun step(d: Int) {
        if (uris.isEmpty()) return
        idx = ((idx + d) % uris.size + uris.size) % uris.size
        thread(name = "photo-load") { load() }
    }

    override fun draw(c: Canvas, now: Long) {
        val b = cur
        if (b == null) {
            Icons.draw(c, "photo", Draw.C, 160f, 56f, Draw.FAINT)
            Draw.textC(c, if (uris.isEmpty()) "No photos" else "Loading…", Draw.C, 220f, 18f, Draw.DIM)
            if (uris.isEmpty()) Draw.textC(c, "pick a folder in the app", Draw.C, 246f, 14f, Draw.FAINT)
            return
        }
        val t = ((now - fadeStart) / 600f).coerceIn(0f, 1f)
        prev?.let { if (t < 1f) Draw.bitmapCover(c, it, 0f, 0f, Draw.S, Draw.S) }
        Draw.bitmapCover(c, b, 0f, 0f, Draw.S, Draw.S, (255 * t).toInt())
    }

    override fun animating(now: Long) = now - fadeStart < 650

    override fun onTap(x: Float, y: Float): Boolean {
        step(if (x < Draw.C) -1 else 1)
        return true
    }
}

/** Idle screen: an animated GIF/WebP or a muted looping video chosen in the app. */
class IdlePage(ui: RearUi) : Page(ui) {
    override val id = "idle"

    private var anim: AnimatedImageDrawable? = null
    private var staticBmp: Bitmap? = null
    private var player: MediaPlayer? = null
    private var reader: ImageReader? = null
    private var readerThread: HandlerThread? = null
    @Volatile private var videoFrame: Bitmap? = null
    private var loaded: String? = null
    private var error: String? = null

    private val cb = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) = invalidate()
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
    }

    override fun onShow() {
        val uri = Rear.prefs.idleMedia ?: run { error = "Choose a GIF or video in the app"; return }
        error = null
        if (uri != loaded) release()
        loaded = uri
        val u = Uri.parse(uri)
        val mime = Rear.app.contentResolver.getType(u) ?: ""
        if (mime.startsWith("video/")) startVideo(u) else startImage(u)
    }

    override fun onHide() {
        anim?.stop()
        player?.pause()
    }

    private fun startImage(u: Uri) {
        anim?.let { it.start(); return }
        if (staticBmp != null) return
        thread(name = "idle-decode") {
            try {
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(Rear.app.contentResolver, u)) { dec, _, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                ui.post {
                    if (d is AnimatedImageDrawable) {
                        d.callback = cb
                        d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        d.start()
                        anim = d
                    } else {
                        staticBmp = decodeScaled(u)
                    }
                    invalidate()
                }
            } catch (e: Exception) {
                error = "Cannot open media"
                RLog.w("idle image: $e")
            }
        }
    }

    private fun startVideo(u: Uri) {
        player?.let { it.start(); return }
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(Rear.app, u)
            var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 640
            var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 480
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            mmr.release()
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val ht = HandlerThread("idle-video").apply { start() }
            readerThread = ht
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2, HardwareBuffer.USAGE_CPU_READ_OFTEN)
            r.setOnImageAvailableListener({ ir ->
                val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = img.planes[0]
                    val rowPx = plane.rowStride / plane.pixelStride
                    val full = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(plane.buffer)
                    videoFrame = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
                    invalidate()
                } finally {
                    img.close()
                }
            }, Handler(ht.looper))
            reader = r
            val mp = MediaPlayer()
            mp.setDataSource(Rear.app, u)
            mp.setSurface(r.surface)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            error = "Cannot play video"
            RLog.w("idle video: $e")
        }
    }

    private fun release() {
        anim?.stop(); anim = null
        staticBmp = null
        player?.release(); player = null
        reader?.close(); reader = null
        readerThread?.quitSafely(); readerThread = null
        videoFrame = null
    }

    override fun draw(c: Canvas, now: Long) {
        error?.let {
            Icons.draw(c, "video", Draw.C, 160f, 50f, Draw.FAINT)
            Draw.textC(c, it, Draw.C, 220f, 16f, Draw.DIM, Draw.REG, 280f)
            return
        }
        val a = anim
        if (a != null) {
            val w = a.intrinsicWidth.toFloat()
            val h = a.intrinsicHeight.toFloat()
            val s = maxOf(Draw.S / w, Draw.S / h)
            c.save()
            c.translate((Draw.S - w * s) / 2, (Draw.S - h * s) / 2)
            c.scale(s, s)
            a.setBounds(0, 0, w.toInt(), h.toInt())
            a.draw(c)
            c.restore()
            return
        }
        (videoFrame ?: staticBmp)?.let { Draw.bitmapCover(c, it, 0f, 0f, Draw.S, Draw.S) }
    }

    override fun animating(now: Long) = anim?.isRunning == true || player?.isPlaying == true
}
