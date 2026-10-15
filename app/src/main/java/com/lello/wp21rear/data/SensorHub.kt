package com.lello.wp21rear.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Sensors with reference-counted registration so nothing listens while no page or feature needs it.
 * Callbacks arrive on a private sensor thread.
 */
class SensorHub(ctx: Context) {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("sensors").apply { start() }
    private val handler = Handler(thread.looper)

    val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val rotation: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val light: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    val proximity: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    val stepCounter: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    // latest values
    @Volatile var ax = 0f; @Volatile var ay = 0f; @Volatile var az = 9.8f
    @Volatile var heading = 0f            // degrees, 0 = north
    @Volatile var headingAccuracy = 0
    @Volatile var lux = -1f
    @Volatile var near = false
    @Volatile var steps = -1f              // counter since boot

    val accelListeners = CopyOnWriteArrayList<() -> Unit>()
    val headingListeners = CopyOnWriteArrayList<() -> Unit>()
    val lightListeners = CopyOnWriteArrayList<() -> Unit>()
    val proxListeners = CopyOnWriteArrayList<() -> Unit>()
    val stepListeners = CopyOnWriteArrayList<() -> Unit>()

    private val refs = HashMap<Int, MutableSet<String>>()
    private val rotMat = FloatArray(9)
    private val orient = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    ax = e.values[0]; ay = e.values[1]; az = e.values[2]
                    for (l in accelListeners) l()
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotMat, e.values)
                    SensorManager.getOrientation(rotMat, orient)
                    var deg = Math.toDegrees(orient[0].toDouble()).toFloat()
                    if (deg < 0) deg += 360f
                    heading = deg
                    for (l in headingListeners) l()
                }
                Sensor.TYPE_LIGHT -> {
                    lux = e.values[0]
                    for (l in lightListeners) l()
                }
                Sensor.TYPE_PROXIMITY -> {
                    near = e.values[0] < (e.sensor.maximumRange.coerceAtMost(5f))
                    for (l in proxListeners) l()
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    steps = e.values[0]
                    for (l in stepListeners) l()
                }
            }
        }

        override fun onAccuracyChanged(s: Sensor, accuracy: Int) {
            if (s.type == Sensor.TYPE_ROTATION_VECTOR) headingAccuracy = accuracy
        }
    }

    /** Start listening to [sensor] on behalf of [who]; [periodUs] and [batchUs] tune battery use. */
    fun acquire(sensor: Sensor?, who: String, periodUs: Int = SensorManager.SENSOR_DELAY_UI, batchUs: Int = 0) {
        sensor ?: return
        synchronized(refs) {
            val set = refs.getOrPut(sensor.type) { HashSet() }
            val first = set.isEmpty()
            set.add(who)
            if (first) sm.registerListener(listener, sensor, periodUs, batchUs, handler)
        }
    }

    fun release(sensor: Sensor?, who: String) {
        sensor ?: return
        synchronized(refs) {
            val set = refs[sensor.type] ?: return
            if (set.remove(who) && set.isEmpty()) sm.unregisterListener(listener, sensor)
        }
    }

    /** Device lying face down (rear screen facing up). */
    fun faceDown(): Boolean = az < -7f && sqrt(ax * ax + ay * ay) < 4f

    /** Pitch/roll in degrees for the spirit level, as seen from the rear screen. */
    fun tilt(): Pair<Float, Float> {
        val roll = Math.toDegrees(atan2(ax.toDouble(), sqrt((ay * ay + az * az).toDouble()))).toFloat()
        val pitch = Math.toDegrees(atan2(ay.toDouble(), sqrt((ax * ax + az * az).toDouble()))).toFloat()
        return Pair(roll, pitch)
    }

    fun quit() {
        sm.unregisterListener(listener)
        thread.quitSafely()
    }
}
