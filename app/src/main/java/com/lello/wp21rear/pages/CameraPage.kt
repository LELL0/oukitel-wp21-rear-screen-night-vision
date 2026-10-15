package com.lello.wp21rear.pages

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.RLog
import com.lello.wp21rear.ui.Draw
import com.lello.wp21rear.ui.Icons
import com.lello.wp21rear.ui.Page
import com.lello.wp21rear.ui.RearUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Live viewfinder on the rear panel, so the main cameras can take selfies. Tap = photo,
 * long-press = start/stop video, top buttons = switch camera / flash / self-timer.
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(ExperimentalCamera2Interop::class)
class CameraPage(ui: RearUi) : Page(ui) {
    override val id = "camera"

    private val main = Handler(Looper.getMainLooper())
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val owner = CamOwner()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var capture: ImageCapture? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordStart = 0L

    private var cameraIds: List<String> = emptyList()
    private var camIndex = 0
    private var flash = false
    private var timerSec = -1
    private var countdownEnd = 0L
    private var flashUntil = 0L
    @Volatile private var preview: Bitmap? = null
    @Volatile private var status: String? = "Starting camera…"
    @Volatile private var visible = false
    private val previewMatrix = Matrix()

    override val keepAwake get() = visible

    // ------------------------------------------------------------------ lifecycle

    override fun onShow() {
        visible = true
        if (timerSec < 0) timerSec = Rear.prefs.cameraTimerSec
        status = "Starting camera…"
        if (ContextCompat.checkSelfPermission(Rear.app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status = "Camera permission missing\n(open the app on the phone)"
            invalidate()
            return
        }
        // Camera access from the background needs the camera foreground-service type, which the
        // service can only claim when started by root (see RearService.ensureCameraForeground).
        Rear.service?.ensureCameraForeground { ok ->
            main.post {
                if (!visible) return@post
                if (!ok) RLog.w("camera: foreground type not granted; trying anyway")
                startCamera()
            }
        } ?: main.post { startCamera() }
    }

    override fun onHide() {
        visible = false
        main.post {
            stopRecording()
            owner.stop()
            provider?.unbindAll()
            camera = null
            preview = null
        }
    }

    private fun startCamera() {
        // Explicit config: before the first unlock CameraX cannot read its config from the manifest.
        try {
            ProcessCameraProvider.configureInstance(androidx.camera.camera2.Camera2Config.defaultConfig())
        } catch (_: IllegalStateException) {
            // already configured
        }
        val f = try {
            ProcessCameraProvider.getInstance(Rear.app)
        } catch (e: Exception) {
            status = "Camera unavailable: ${e.message}"
            RLog.e("camera provider", e)
            invalidate()
            return
        }
        f.addListener({
            try {
                val p = f.get()
                provider = p
                cameraIds = p.availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId }.sorted()
                if (camIndex >= cameraIds.size) camIndex = 0
                bind(false)
            } catch (e: Exception) {
                status = "Camera error: ${e.message}"
                RLog.e("camera provider", e)
                invalidate()
            }
        }, ContextCompat.getMainExecutor(Rear.app))
    }

    private fun selector(): CameraSelector {
        val id = cameraIds.getOrNull(camIndex) ?: "0"
        return CameraSelector.Builder().addCameraFilter { infos: List<CameraInfo> ->
            infos.filter { Camera2CameraInfo.from(it).cameraId == id }
        }.build()
    }

    private fun bind(forVideo: Boolean) {
        val p = provider ?: return
        if (!visible) return
        p.unbindAll()
        val res = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        ).build()
        val a = ImageAnalysis.Builder()
            .setResolutionSelector(res)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        a.setAnalyzer(analysisExec) { img ->
            try {
                val src = img.toBitmap()
                preview = square(src, img.imageInfo.rotationDegrees)
                status = null
                invalidate()
            } catch (e: Exception) {
                RLog.w("camera frame: $e")
            } finally {
                img.close()
            }
        }
        analysis = a
        try {
            if (forVideo) {
                val rec = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
                val v = VideoCapture.withOutput(rec)
                video = v
                camera = p.bindToLifecycle(owner, selector(), a, v)
            } else {
                val cap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                    .build()
                capture = cap
                camera = p.bindToLifecycle(owner, selector(), a, cap)
            }
            owner.start()
        } catch (e: Exception) {
            status = "Camera ${cameraIds.getOrNull(camIndex)}: ${e.message}"
            RLog.e("camera bind", e)
        }
        invalidate()
    }

    /** Rotates upright and centre-crops to 360x360, mirrored for a selfie-mirror feel if enabled. */
    private fun square(src: Bitmap, rotation: Int): Bitmap {
        val s = minOf(src.width, src.height)
        previewMatrix.reset()
        val scale = 360f / s
        previewMatrix.postTranslate(-src.width / 2f, -src.height / 2f)
        previewMatrix.postRotate(rotation.toFloat())
        val front = facing() == CameraCharacteristics.LENS_FACING_FRONT
        // Back camera aimed at the user: mirror like a mirror; the front camera faces away.
        val mirror = Rear.prefs.cameraMirrorPreview && !front
        previewMatrix.postScale(if (mirror) -scale else scale, scale)
        previewMatrix.postTranslate(180f, 180f)
        val out = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, previewMatrix, Draw.bmpPaint)
        return out
    }

    private fun facing(): Int? = camera?.cameraInfo?.let {
        Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
    }

    private fun hasFlash() = camera?.cameraInfo?.hasFlashUnit() == true

    private fun camLabel(): String {
        val id = cameraIds.getOrNull(camIndex) ?: return ""
        return when {
            facing() == CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            id == "0" -> "Main"
            id == "2" -> "Night"
            id == "3" -> "Macro"
            else -> "Cam $id"
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun draw(c: Canvas, now: Long) {
        val p = preview
        if (p != null) c.drawBitmap(p, 0f, 0f, Draw.bmpPaint)
        status?.let { s ->
            Icons.draw(c, "camera", Draw.C, 140f, 56f, Draw.FAINT)
            s.split('\n').forEachIndexed { i, line -> Draw.textC(c, line, Draw.C, 205f + i * 24f, 17f, Draw.DIM, Draw.REG, 280f) }
        }
        // top controls on a dark band
        Draw.fill.color = 0x66000000
        c.drawRect(0f, 0f, Draw.S, 92f, Draw.fill)
        Icons.draw(c, "flip", 112f, 62f, 30f, Draw.WHITE)
        Draw.textC(c, camLabel(), 112f, 86f, 11f, Draw.DIM)
        if (hasFlash()) Icons.draw(c, if (flash) "flash" else "flash_off", Draw.C, 44f, 28f, if (flash) Draw.AMBER else Draw.WHITE)
        Icons.draw(c, "timer", 248f, 62f, 28f, if (timerSec > 0) Draw.AMBER else Draw.WHITE)
        if (timerSec > 0) Draw.textC(c, "${timerSec}s", 248f, 86f, 11f, Draw.AMBER)

        val rec = recording != null
        if (rec) {
            val secs = (SystemClock.uptimeMillis() - recordStart) / 1000
            Draw.circle(c, 150f, 316f, 7f, if ((now / 500) % 2 == 0L) Draw.RED else 0xFF7F1D1D.toInt())
            Draw.textL(c, "%d:%02d".format(secs / 60, secs % 60), 164f, 316f, 18f, Draw.WHITE, Draw.MED)
        } else {
            Draw.ring(c, Draw.C, 312f, 22f, 4f, Draw.WHITE)
            Draw.circle(c, Draw.C, 312f, 16f, Draw.withAlpha(Draw.WHITE, 200))
        }
        if (countdownEnd > now) {
            val left = ((countdownEnd - now + 999) / 1000).toInt()
            Draw.textC(c, "$left", Draw.C, Draw.C, 120f, Draw.WHITE, Draw.THIN)
        }
        if (flashUntil > now) {
            Draw.fill.color = Draw.withAlpha(Draw.WHITE, (255 * (flashUntil - now) / 200f).toInt())
            c.drawRect(0f, 0f, Draw.S, Draw.S, Draw.fill)
        }
    }

    override fun animating(now: Long) = visible && (preview != null || countdownEnd > now || flashUntil > now)

    // ------------------------------------------------------------------ input

    override fun onTap(x: Float, y: Float): Boolean {
        if (y < 100f) {
            when {
                x < 150f -> switchCamera()
                x < 215f -> { if (hasFlash()) { flash = !flash; capture?.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF } }
                else -> timerSec = when (timerSec) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }
            }
            invalidate()
            return true
        }
        if (recording != null) { stopRecording(); return true }
        if (countdownEnd > SystemClock.uptimeMillis()) { countdownEnd = 0; return true } // cancel
        if (timerSec > 0) {
            countdownEnd = SystemClock.uptimeMillis() + timerSec * 1000L
            ui.postDelayed(timerSec * 1000L, Runnable { if (countdownEnd != 0L) { countdownEnd = 0; takePhoto() } })
            invalidate()
        } else {
            takePhoto()
        }
        return true
    }

    override fun onLongPress(x: Float, y: Float): Boolean {
        if (recording != null) stopRecording() else main.post { startRecording() }
        return true
    }

    private fun switchCamera() {
        if (cameraIds.size < 2 || recording != null) return
        camIndex = (camIndex + 1) % cameraIds.size
        preview = null
        status = "Switching…"
        main.post { bind(false) }
    }

    private fun name(prefix: String) = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun takePhoto() {
        val cap = capture ?: return
        flashUntil = SystemClock.uptimeMillis() + 200
        invalidate()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name("REAR_"))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/RearScreen")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            Rear.app.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        cap.takePicture(opts, analysisExec, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                RLog.i("photo saved ${r.savedUri}")
                ui.toast("Photo saved")
            }

            override fun onError(e: ImageCaptureException) {
                RLog.e("photo failed", e)
                ui.toast("Photo failed")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        bind(true)
        val v = video ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name("REAR_VID_"))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/RearScreen")
        }
        val out = MediaStoreOutputOptions.Builder(Rear.app.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = v.output.prepareRecording(Rear.app, out)
        if (ContextCompat.checkSelfPermission(Rear.app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recordStart = SystemClock.uptimeMillis()
        recording = pending.start(ContextCompat.getMainExecutor(Rear.app)) { e ->
            if (e is VideoRecordEvent.Finalize) {
                if (e.hasError()) RLog.w("video error ${e.error}: ${e.cause}") else RLog.i("video saved ${e.outputResults.outputUri}")
                ui.toast(if (e.hasError()) "Video failed" else "Video saved")
            }
        }
        ui.toast("Recording")
        invalidate()
    }

    private fun stopRecording() {
        val r = recording ?: return
        recording = null
        r.stop()
        main.post { bind(false) }
        invalidate()
    }

    /** CameraX needs a LifecycleOwner; this one is started only while the page is shown. */
    private class CamOwner : LifecycleOwner {
        private val reg = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = reg
        init { reg.currentState = Lifecycle.State.CREATED }
        fun start() { reg.currentState = Lifecycle.State.RESUMED }
        fun stop() { reg.currentState = Lifecycle.State.CREATED }
    }
}
