package com.lello.wp21nightvision

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Night vision for the Oukitel WP21: live viewfinder from the infrared-sensitive night camera,
 * control of the IR illuminator (root), gain and colour modes (night green, mono, heat map, amber),
 * photos saved as seen, raw video.
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity(), SensorEventListener {
    private val ir = IrController()
    private val filter = Filter()
    private val exec = Executors.newSingleThreadExecutor()
    private val saveExec = Executors.newSingleThreadExecutor()
    private lateinit var view: NvView
    private lateinit var irButton: Button
    private lateinit var autoButton: Button
    private lateinit var camButton: Button
    private lateinit var recButton: Button
    private val modeButtons = ArrayList<Pair<Mode, Button>>()
    private lateinit var evBar: SeekBar

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraIds: List<String> = emptyList()
    private var camIndex = 0
    private var wantIr = false
    private var autoIr = false
    private var lux = -1f
    private var lastSavedUri: Uri? = null

    private val prefs by lazy { getSharedPreferences("nv", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        filter.mode = Mode.entries[prefs.getInt("mode", Mode.GREEN.ordinal).coerceIn(0, Mode.entries.size - 1)]
        filter.gain = prefs.getFloat("gain", 2f)
        autoIr = prefs.getBoolean("auto_ir", false)
        buildUi()
        thread {
            val ok = ir.start()
            runOnUiThread {
                if (!ok) toast("IR control unavailable: ${ir.lastError}")
                updateButtons()
            }
        }
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startCamera() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else toast("Camera permission is required")
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (wantIr) setIr(true)
    }

    override fun onPause() {
        // never leave the illuminator on in the background
        getSystemService(SensorManager::class.java).unregisterListener(this)
        stopRecording()
        thread { ir.set(false) }
        super.onPause()
    }

    override fun onDestroy() {
        thread { ir.stop() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun chip(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = GradientDrawable().apply { setColor(0x55000000); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0x6600FF66) }
        setOnClickListener { onClick() }
    }

    private fun styleOn(b: Button, on: Boolean, onColor: Int = 0xFF00C853.toInt()) {
        (b.background as GradientDrawable).setColor(if (on) onColor else 0x55000000)
        b.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        view = NvView(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(36), dp(8), dp(8))
        }
        camButton = chip("Camera") { switchCamera() }
        irButton = chip("IR off") { wantIr = !wantIr; autoIr = false; prefs.edit().putBoolean("auto_ir", false).apply(); setIr(wantIr) }
        autoButton = chip("Auto IR") {
            autoIr = !autoIr
            prefs.edit().putBoolean("auto_ir", autoIr).apply()
            updateButtons()
        }
        for (b in listOf(camButton, irButton, autoButton)) top.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(28))
            setBackgroundColor(0x66000000)
        }
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (m in Mode.entries) {
            val b = chip(m.label) {
                filter.mode = m
                prefs.edit().putInt("mode", m.ordinal).apply()
                updateButtons()
            }
            modeButtons.add(m to b)
            modes.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        bottom.addView(android.widget.HorizontalScrollView(this).apply { addView(modes); isHorizontalScrollBarEnabled = false })
        bottom.addView(sliderRow("Gain", 7, ((filter.gain - 1f)).toInt()) { v ->
            filter.gain = 1f + v
            prefs.edit().putFloat("gain", filter.gain).apply()
        })
        evBar = SeekBar(this)
        bottom.addView(sliderRow("Exposure", 0, 0, evBar) { v ->
            val cam = camera ?: return@sliderRow
            val range = cam.cameraInfo.exposureState.exposureCompensationRange
            cam.cameraControl.setExposureCompensationIndex(range.lower + v)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val gallery = chip("Last") { lastSavedUri?.let { startActivity(Intent(Intent.ACTION_VIEW, it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } ?: toast("No photo yet") }
        val shutter = Button(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(5), 0xFF00C853.toInt()) }
            setOnClickListener { takePhoto() }
        }
        recButton = chip("Rec") { if (recording != null) stopRecording() else startRecording() }
        actions.addView(gallery, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(shutter, LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(24), dp(8), dp(24), 0) })
        actions.addView(recButton, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(actions)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        updateButtons()
    }

    private fun sliderRow(name: String, max: Int, value: Int, bar: SeekBar = SeekBar(this), onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val label = TextView(this).apply { text = name; setTextColor(0xFFB9F6CA.toInt()); textSize = 12f; typeface = Typeface.MONOSPACE }
        bar.max = max
        bar.progress = value
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        row.addView(label, LinearLayout.LayoutParams(dp(80), -2))
        row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun updateButtons() {
        irButton.text = if (!ir.available) "IR n/a" else if (ir.isOn) "IR ON" else "IR off"
        styleOn(irButton, ir.isOn, 0xFFFF1744.toInt())
        styleOn(autoButton, autoIr)
        for ((m, b) in modeButtons) styleOn(b, m == filter.mode)
        camButton.text = camLabel()
        recButton.text = if (recording != null) "Stop" else "Rec"
        styleOn(recButton, recording != null, 0xFFFF1744.toInt())
    }

    private fun setIr(on: Boolean) {
        thread {
            ir.set(on)
            runOnUiThread { updateButtons() }
        }
    }

    // ------------------------------------------------------------------ auto IR

    override fun onSensorChanged(e: SensorEvent) {
        lux = e.values[0]
        if (!autoIr || !ir.available) return
        // hysteresis: on below 3 lux, off above 15
        if (lux < 3f && !ir.isOn) setIr(true)
        if (lux > 15f && ir.isOn) setIr(false)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            provider = p
            cameraIds = p.availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId }.sorted()
            // the 20 MP night camera is id 2 on the WP21
            camIndex = cameraIds.indexOf(prefs.getString("camera", "2")).takeIf { it >= 0 } ?: 0
            bind(false)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun selector(): CameraSelector {
        val id = cameraIds.getOrNull(camIndex) ?: "0"
        return CameraSelector.Builder().addCameraFilter { infos: List<CameraInfo> ->
            infos.filter { Camera2CameraInfo.from(it).cameraId == id }
        }.build()
    }

    private fun camLabel(): String {
        val id = cameraIds.getOrNull(camIndex) ?: return "Camera"
        return when (id) {
            "0" -> "Main cam"
            "1" -> "Front cam"
            "2" -> "Night cam"
            "3" -> "Macro cam"
            else -> "Cam $id"
        }
    }

    private fun switchCamera() {
        if (cameraIds.size < 2 || recording != null) return
        camIndex = (camIndex + 1) % cameraIds.size
        prefs.edit().putString("camera", cameraIds[camIndex]).apply()
        bind(false)
        updateButtons()
    }

    private fun bind(forVideo: Boolean) {
        val p = provider ?: return
        p.unbindAll()
        val res = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(960, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        ).build()
        val a = ImageAnalysis.Builder()
            .setResolutionSelector(res)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        a.setAnalyzer(exec) { img -> process(img) }
        try {
            if (forVideo) {
                val v = VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build())
                video = v
                camera = p.bindToLifecycle(this, selector(), a, v)
            } else {
                val c = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                capture = c
                camera = p.bindToLifecycle(this, selector(), a, c)
            }
            camera?.cameraInfo?.exposureState?.let { st ->
                val r = st.exposureCompensationRange
                evBar.max = r.upper - r.lower
                evBar.progress = st.exposureCompensationIndex - r.lower
                evBar.isEnabled = st.isExposureCompensationSupported
            }
        } catch (e: Exception) {
            toast("Camera error: ${e.message}")
        }
    }

    private var frameBmp: Bitmap? = null
    private var pixels = IntArray(0)
    private var fpsCount = 0
    private var fpsStart = SystemClock.uptimeMillis()
    @Volatile private var fps = 0f

    private fun process(img: ImageProxy) {
        try {
            val src = img.toBitmap()
            val n = src.width * src.height
            if (pixels.size != n) pixels = IntArray(n)
            src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
            filter.apply(pixels, n)
            var out = frameBmp
            if (out == null || out.width != src.width || out.height != src.height) {
                out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            }
            out!!.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
            frameBmp = out
            fpsCount++
            val now = SystemClock.uptimeMillis()
            if (now - fpsStart >= 1000) { fps = fpsCount * 1000f / (now - fpsStart); fpsCount = 0; fpsStart = now }
            view.show(out.copy(Bitmap.Config.ARGB_8888, false), img.imageInfo.rotationDegrees,
                hud())
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    private fun hud(): String = buildString {
        append(camLabel().uppercase())
        append("  ").append(if (ir.isOn) "IR ON" else "IR OFF")
        append("  ").append(filter.mode.label.uppercase())
        append("  GAIN ").append(filter.gain.toInt()).append("x")
        if (lux >= 0) append("  ").append(lux.toInt()).append(" LUX")
        append("  %.0f FPS".format(fps))
        if (recording != null) append("  ● REC")
    }

    private fun name(prefix: String) = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Full-resolution photo with the current mode applied, saved to Pictures/NightVision. */
    private fun takePhoto() {
        val c = capture ?: return
        view.flash()
        c.takePicture(saveExec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rot = image.imageInfo.rotationDegrees
                    var b = image.toBitmap()
                    image.close()
                    if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    val outBmp = filter.apply(b)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name("NV_"))
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/NightVision")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                    contentResolver.openOutputStream(uri)!!.use { outBmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    lastSavedUri = uri
                    runOnUiThread { toast("Saved to Pictures/NightVision") }
                } catch (e: Exception) {
                    runOnUiThread { toast("Save failed: ${e.message}") }
                }
            }

            override fun onError(e: ImageCaptureException) {
                runOnUiThread { toast("Photo failed: ${e.message}") }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        bind(true)
        val v = video ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name("NV_VID_"))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/NightVision")
        }
        val out = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        var pending = v.output.prepareRecording(this, out)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { e ->
            if (e is VideoRecordEvent.Finalize) {
                toast(if (e.hasError()) "Video failed" else "Video saved to Movies/NightVision (unfiltered)")
                if (!e.hasError()) lastSavedUri = e.outputResults.outputUri
            }
        }
        updateButtons()
    }

    private fun stopRecording() {
        val r = recording ?: return
        recording = null
        r.stop()
        bind(false)
        updateButtons()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Full-screen processed viewfinder with a HUD line; pinch to zoom. */
    inner class NvView(ctx: Context) : View(ctx) {
        @Volatile private var bmp: Bitmap? = null
        @Volatile private var rot = 0
        @Volatile private var hudText = ""
        private var flashUntil = 0L
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB9F6CA.toInt(); typeface = Typeface.MONOSPACE; textSize = 30f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        private val m = Matrix()
        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val z = cam.cameraInfo.zoomState.value ?: return true
                cam.cameraControl.setZoomRatio((z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                return true
            }
        })

        fun show(b: Bitmap, rotation: Int, hud: String) {
            bmp = b
            rot = rotation
            hudText = hud
            postInvalidate()
        }

        fun flash() {
            flashUntil = SystemClock.uptimeMillis() + 150
            invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            scaler.onTouchEvent(e)
            return true
        }

        override fun onDraw(c: Canvas) {
            val b = bmp ?: return
            val bw = if (rot % 180 == 0) b.width else b.height
            val bh = if (rot % 180 == 0) b.height else b.width
            val s = maxOf(width.toFloat() / bw, height.toFloat() / bh)
            m.reset()
            m.postTranslate(-b.width / 2f, -b.height / 2f)
            m.postRotate(rot.toFloat())
            m.postScale(s, s)
            m.postTranslate(width / 2f, height / 2f)
            c.drawBitmap(b, m, paint)
            c.drawText(hudText, 24f, height * 0.18f, hudPaint)
            if (SystemClock.uptimeMillis() < flashUntil) {
                c.drawColor(0x88FFFFFF.toInt())
                postInvalidateDelayed(40)
            }
        }
    }
}
