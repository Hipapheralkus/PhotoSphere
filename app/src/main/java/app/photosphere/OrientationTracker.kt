package app.photosphere

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.*

/** Orientation only. This does not infer camera translation from accelerometer integration. */
class OrientationTracker(context: Context, private val onPose: (Pose) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // Magnetic rotation is less prone to accumulated yaw drift. Interference can still disturb it;
    // either sensor is a capture hint, not a substitute for later visual image alignment.
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val matrix = FloatArray(9)
    private var running = false
    private var yawAnchor: Double? = null
    private var latestAbsoluteYaw: Double? = null
    private var gyroTimestampNs = 0L
    private var gyroSpeed = Double.POSITIVE_INFINITY

    /** False means the sensors required for trustworthy automatic capture are unavailable. */
    fun start(): Boolean {
        if (running) return true
        if (rotation == null || gyro == null) return false
        yawAnchor = null
        latestAbsoluteYaw = null
        gyroTimestampNs = 0L
        gyroSpeed = Double.POSITIVE_INFINITY
        val rotationOk = sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME)
        val gyroOk = sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        running = rotationOk && gyroOk
        if (!running) sensors.unregisterListener(this)
        return running
    }

    fun stop() { sensors.unregisterListener(this); running = false }

    /** A new capture session needs a new anchor. An unfinished session cannot resume across restarts. */
    fun resetYawAnchor() { yawAnchor = latestAbsoluteYaw }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val x = event.values[0].toDouble()
            val y = event.values[1].toDouble()
            val z = event.values[2].toDouble()
            gyroSpeed = Math.toDegrees(sqrt(x * x + y * y + z * z))
            gyroTimestampNs = event.timestamp
            return
        }
        if (event.sensor.type != rotation?.type) return
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        // Android device axes: +X right, +Y top of portrait screen, +Z toward viewer.
        // The rear camera looks along -Z. The rotation matrix maps these axes to world.
        val angles = OrientationMath.fromRotationMatrix(matrix)
        val absoluteYaw = angles.yaw
        latestAbsoluteYaw = absoluteYaw
        if (yawAnchor == null) yawAnchor = absoluteYaw
        val gyroAgeNs = event.timestamp - gyroTimestampNs
        val speed = if (gyroTimestampNs > 0 && gyroAgeNs in -50_000_000L..250_000_000L) gyroSpeed else Double.POSITIVE_INFINITY
        onPose(Pose(wrapDegrees(absoluteYaw - yawAnchor!!), angles.pitch, angles.roll, speed, event.timestamp, event.values.copyOf()))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
