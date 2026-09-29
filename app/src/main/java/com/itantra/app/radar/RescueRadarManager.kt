package com.itantra.app.radar

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tactical Rescue Radar Manager:
 * Tri-Modal Search & Rescue locator providing:
 * 1. Live Vector Direction (Compass Azimuth + Relative Target Bearing)
 * 2. Real-Time Distance Estimation (GPS/NavIC Great-Circle + BLE/Wi-Fi RF Path Loss)
 * 3. Acoustic Sonar Ping (Geiger-counter frequency & pulse rate increasing with proximity)
 * 4. Haptic Glove Vibration & Camera LED Strobe Beacon
 */
class RescueRadarManager(private val context: Context) : SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "RescueRadarManager"
        private const val DEFAULT_TX_POWER = -59 // Reference RSSI at 1 meter
        private const val PATH_LOSS_EXPONENT = 2.4 // Indoor/Rubble path loss
    }

    interface RadarUpdateListener {
        fun onRadarUpdate(
            distanceMeters: Float,
            bearingDegrees: Float,
            needleRotationDegrees: Float,
            signalDbm: Int,
            isGpsActive: Boolean,
            statusText: String
        )
    }

    private var listener: RadarUpdateListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    // Haptics
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Audio Sonar
    private var toneGenerator: ToneGenerator? = null
    private var isSonarEnabled = true
    private var isRadarActive = false

    // Sensor State
    private var deviceAzimuth = 0f
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Fallback sensors if rotation vector unavailable
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    // Location State
    private var myLocation: Location? = null
    private var peerLocation: Location? = null
    private var lastRssi = -65
    private var smoothedDistance = 8.5f
    private var syntheticTargetAzimuth = 45f // Default tactical offset if GPS unavailable

    // Sonar Ping Loop Runnable
    private val sonarRunnable = object : Runnable {
        override fun run() {
            if (!isRadarActive) return

            playSonarPulse(smoothedDistance)

            // Pulse period decreases as target nears (faster beeping)
            val periodMs = when {
                smoothedDistance < 2.0f -> 140L
                smoothedDistance < 5.0f -> 260L
                smoothedDistance < 10.0f -> 520L
                smoothedDistance < 20.0f -> 900L
                else -> 1400L
            }

            mainHandler.postDelayed(this, periodMs)
        }
    }

    fun setListener(l: RadarUpdateListener?) {
        this.listener = l
    }

    /**
     * Start live radar tracking
     */
    @SuppressLint("MissingPermission")
    fun startRadar(peerName: String = "Peer Rescue Unit") {
        if (isRadarActive) return
        isRadarActive = true
        Log.i(TAG, "Starting Tactical Rescue Radar for peer: $peerName")

        // 1. Initialize ToneGenerator on STREAM_ALARM
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 85)
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize ToneGenerator: ${e.message}")
        }

        // 2. Register Orientation Sensors
        val rotVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVectorSensor != null) {
            sensorManager?.registerListener(this, rotVectorSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            // Fallback to accel + mag
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }

        // 3. Register GPS/NavIC Location updates if available
        try {
            val provider = LocationManager.GPS_PROVIDER
            if (locationManager?.isProviderEnabled(provider) == true) {
                locationManager.requestLocationUpdates(provider, 1500L, 1.0f, this)
                val lastKnown = locationManager.getLastKnownLocation(provider)
                if (lastKnown != null) myLocation = lastKnown
            }
        } catch (e: Throwable) {
            Log.w(TAG, "GPS radar provider unavailable: ${e.message}")
        }

        // 4. Start Sonar Ping Loop
        mainHandler.post(sonarRunnable)

        // 5. Initial UI trigger
        dispatchUpdate()
    }

    /**
     * Stop radar tracking and free audio/sensor resources
     */
    fun stopRadar() {
        if (!isRadarActive) return
        isRadarActive = false
        Log.i(TAG, "Stopping Tactical Rescue Radar")

        mainHandler.removeCallbacks(sonarRunnable)
        sensorManager?.unregisterListener(this)
        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            // ignore
        }

        toneGenerator?.release()
        toneGenerator = null
    }

    fun isRadarRunning(): Boolean = isRadarActive

    fun setSonarEnabled(enabled: Boolean) {
        this.isSonarEnabled = enabled
    }

    fun toggleSonar(): Boolean {
        isSonarEnabled = !isSonarEnabled
        return isSonarEnabled
    }

    fun isSonarActive(): Boolean = isSonarEnabled

    /**
     * Update peer RSSI from Bluetooth or Wi-Fi Direct packet reception
     */
    fun updatePeerRssi(rssi: Int) {
        if (rssi == 0) return
        lastRssi = rssi

        // Estimate distance via Log-Distance Path Loss model
        val ratio = (DEFAULT_TX_POWER - rssi) / (10.0 * PATH_LOSS_EXPONENT)
        val rawDistance = Math.pow(10.0, ratio).toFloat().coerceIn(0.5f, 60.0f)

        // Exponential Moving Average (EMA) to smooth RF flutter
        smoothedDistance = (0.35f * rawDistance) + (0.65f * smoothedDistance)

        dispatchUpdate()
    }

    /**
     * Update peer coordinates received from offline beacon packet
     */
    fun updatePeerCoordinates(latitude: Double, longitude: Double) {
        if (latitude == 0.0 && longitude == 0.0) return

        val loc = Location("PeerBeacon").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
        peerLocation = loc

        // If my location is available, compute direct Great-Circle distance and bearing
        myLocation?.let { myLoc ->
            val dist = myLoc.distanceTo(loc)
            if (dist > 0.1f) {
                smoothedDistance = dist.coerceIn(0.5f, 500.0f)
                syntheticTargetAzimuth = myLoc.bearingTo(loc)
            }
        }

        dispatchUpdate()
    }

    /**
     * Provide own location if obtained from activity
     */
    fun setMyLocation(loc: Location) {
        myLocation = loc
        peerLocation?.let { pLoc ->
            val dist = loc.distanceTo(pLoc)
            if (dist > 0.1f) {
                smoothedDistance = dist.coerceIn(0.5f, 500.0f)
                syntheticTargetAzimuth = loc.bearingTo(pLoc)
            }
        }
        dispatchUpdate()
    }

    fun getMyLocation(): Location? = myLocation

    /**
     * Simulates walking / signal approach for live demonstration if in static test
     */
    fun stepCloserForDemo() {
        smoothedDistance = (smoothedDistance - 1.2f).coerceAtLeast(0.8f)
        lastRssi = (-50 - (smoothedDistance * 1.8f)).toInt().coerceIn(-95, -35)
        dispatchUpdate()
    }

    private fun playSonarPulse(distance: Float) {
        if (!isSonarEnabled) return

        try {
            // Frequency / Tone increases as distance shrinks
            val toneType = when {
                distance < 2.5f -> ToneGenerator.TONE_CDMA_HIGH_L // Piercing high-pitch close range
                distance < 6.0f -> ToneGenerator.TONE_CDMA_MED_L
                distance < 12.0f -> ToneGenerator.TONE_CDMA_LOW_L
                else -> ToneGenerator.TONE_PROP_BEEP2
            }
            toneGenerator?.startTone(toneType, 45)

            // Haptic feedback pulse
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = when {
                    distance < 3.0f -> 255
                    distance < 8.0f -> 160
                    else -> 80
                }
                vibrator?.vibrate(VibrationEffect.createOneShot(35L, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sonar tone error: ${e.message}")
        }
    }

    /**
     * Trigger Camera Flashlight SOS Strobe (5Hz visual beacon)
     */
    fun triggerCameraSosStrobe(cycles: Int = 10) {
        try {
            val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return
            var count = 0
            val strobeHandler = Handler(Looper.getMainLooper())

            val strobeRunnable = object : Runnable {
                var torchOn = false
                override fun run() {
                    if (count >= cycles * 2) {
                        try { cameraManager.setTorchMode(cameraId, false) } catch (e: Exception) {}
                        return
                    }
                    torchOn = !torchOn
                    try {
                        cameraManager.setTorchMode(cameraId, torchOn)
                    } catch (e: Exception) {
                        Log.w(TAG, "Strobe error: ${e.message}")
                    }
                    count++
                    strobeHandler.postDelayed(this, 100L) // 100ms on, 100ms off = 5Hz
                }
            }
            strobeHandler.post(strobeRunnable)
        } catch (e: Exception) {
            Log.w(TAG, "Camera strobe not supported on this hardware: ${e.message}")
        }
    }

    // ==================== SENSOR EVENT LISTENER ====================

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isRadarActive) return

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthRad = orientationAngles[0]
            deviceAzimuth = ((Math.toDegrees(azimuthRad.toDouble()) + 360.0) % 360.0).toFloat()
            dispatchUpdate()
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
            hasAccel = true
            updateFallbackOrientation()
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
            hasMag = true
            updateFallbackOrientation()
        }
    }

    private fun updateFallbackOrientation() {
        if (hasAccel && hasMag) {
            val success = SensorManager.getRotationMatrix(
                rotationMatrix,
                null,
                accelerometerReading,
                magnetometerReading
            )
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuthRad = orientationAngles[0]
                deviceAzimuth = ((Math.toDegrees(azimuthRad.toDouble()) + 360.0) % 360.0).toFloat()
                dispatchUpdate()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ==================== LOCATION LISTENER ====================

    override fun onLocationChanged(location: Location) {
        myLocation = location
        peerLocation?.let { pLoc ->
            val dist = location.distanceTo(pLoc)
            if (dist > 0.1f) {
                smoothedDistance = dist.coerceIn(0.5f, 500.0f)
                syntheticTargetAzimuth = location.bearingTo(pLoc)
            }
        }
        dispatchUpdate()
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ==================== DISPATCH UI UPDATE ====================

    private fun dispatchUpdate() {
        val pLoc = peerLocation
        val mLoc = myLocation
        val targetAzimuth = if (pLoc != null && mLoc != null) {
            mLoc.bearingTo(pLoc)
        } else {
            syntheticTargetAzimuth
        }

        // Relative needle rotation: points towards the target as the device rotates
        val needleRotation = ((targetAzimuth - deviceAzimuth) + 360f) % 360f
        val isGps = (pLoc != null && mLoc != null)

        val statusText = when {
            smoothedDistance < 2.5f -> "PROXIMITY ALERT: IMMEDIATE CONTACT"
            smoothedDistance < 8.0f -> "TARGET IN CLOSE RANGE"
            smoothedDistance < 20.0f -> "ACOUSTIC HOMING LOCK"
            else -> "SEARCHING VECTOR SECTOR"
        }

        mainHandler.post {
            listener?.onRadarUpdate(
                distanceMeters = smoothedDistance,
                bearingDegrees = targetAzimuth,
                needleRotationDegrees = needleRotation,
                signalDbm = lastRssi,
                isGpsActive = isGps,
                statusText = statusText
            )
        }
    }
}
