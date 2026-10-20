package com.macdroid.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager

/**
 * WebRTC-compatible camera enumeration, format negotiation, and frame rotation helper.
 * Adapted from GetStream webrtc-android (Apache License 2.0).
 */
data class WebRtcCameraFormat(
    val width: Int,
    val height: Int,
    val maxFps: Int = 30
)

data class WebRtcCameraInfo(
    val id: String,
    val isFrontFacing: Boolean,
    val sensorOrientation: Int,
    val supportedFormats: List<WebRtcCameraFormat>
)

enum class ScreenRouteDirection(val degrees: Float, val label: String) {
    UP(0f, "Up (0 deg)"),
    RIGHT(90f, "Right (90 deg)"),
    DOWN(180f, "Down (180 deg)"),
    LEFT(270f, "Left (270 deg)");

    fun next(): ScreenRouteDirection {
        return when (this) {
            UP -> RIGHT
            RIGHT -> DOWN
            DOWN -> LEFT
            LEFT -> UP
        }
    }

    companion object {
        fun fromDegrees(degrees: Float): ScreenRouteDirection {
            val normalized = ((degrees % 360f) + 360f) % 360f
            return when {
                normalized in 45f..134f -> RIGHT
                normalized in 135f..224f -> DOWN
                normalized in 225f..314f -> LEFT
                else -> UP
            }
        }
    }
}

class WebRtcCameraHelper(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcCameraHelper"
        private const val DEFAULT_CAPTURE_WIDTH = 640
        private const val DEFAULT_CAPTURE_HEIGHT = 480
    }

    private val cameraManager: CameraManager? by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }

    private val windowManager: WindowManager? by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    fun enumerateCameras(): List<WebRtcCameraInfo> {
        val manager = cameraManager ?: return emptyList()
        val cameraList = mutableListOf<WebRtcCameraInfo>()

        try {
            val cameraIds = manager.cameraIdList
            for (id in cameraIds) {
                val characteristics = manager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val isFrontFacing = (lensFacing == CameraMetadata.LENS_FACING_FRONT)
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val outputSizes = streamMap?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    ?: streamMap?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                    ?: emptyArray()

                val formats = outputSizes.map { size: Size ->
                    WebRtcCameraFormat(size.width, size.height, 30)
                }

                cameraList.add(
                    WebRtcCameraInfo(
                        id = id,
                        isFrontFacing = isFrontFacing,
                        sensorOrientation = sensorOrientation,
                        supportedFormats = formats
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating Camera2 devices: ${e.message}")
        }

        return cameraList
    }

    fun findCameraId(preferFrontFacing: Boolean): String? {
        val cameras = enumerateCameras()
        val matched = cameras.firstOrNull { it.isFrontFacing == preferFrontFacing }
        return matched?.id ?: cameras.firstOrNull()?.id
    }

    fun getOptimalFormat(cameraId: String, targetWidth: Int = DEFAULT_CAPTURE_WIDTH, targetHeight: Int = DEFAULT_CAPTURE_HEIGHT): WebRtcCameraFormat {
        val cameras = enumerateCameras()
        val info = cameras.firstOrNull { it.id == cameraId } ?: return WebRtcCameraFormat(targetWidth, targetHeight, 30)

        val formats = info.supportedFormats
        if (formats.isEmpty()) return WebRtcCameraFormat(targetWidth, targetHeight, 30)

        // Try exact match first
        val exactMatch = formats.firstOrNull { it.width == targetWidth && it.height == targetHeight }
        if (exactMatch != null) return exactMatch

        // Find format closest in aspect ratio and resolution
        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val candidates = formats.filter { it.width >= 320 && it.width <= 1280 }

        val best = candidates.minByOrNull { fmt ->
            val aspect = fmt.width.toFloat() / fmt.height.toFloat()
            val aspectDiff = Math.abs(aspect - targetAspect)
            val resDiff = Math.abs((fmt.width * fmt.height) - (targetWidth * targetHeight))
            (aspectDiff * 100000) + (resDiff / 1000)
        }

        return best ?: formats.first()
    }

    /**
     * Calculates the net rotation degrees required for a camera frame based on sensor orientation,
     * device physical rotation, and front-facing mirroring.
     */
    fun calculateFrameRotation(isFrontFacing: Boolean, sensorOrientation: Int): Int {
        val displayRotation = try {
            windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        } catch (e: Exception) {
            Surface.ROTATION_0
        }

        val deviceOrientationDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        return if (isFrontFacing) {
            (sensorOrientation + deviceOrientationDegrees) % 360
        } else {
            (sensorOrientation - deviceOrientationDegrees + 360) % 360
        }
    }

    /**
     * Applies rotation and optional horizontal mirror (for front camera selfie mode) to a Bitmap.
     */
    fun transformBitmap(
        source: Bitmap,
        rotationDegrees: Float,
        mirrorHorizontal: Boolean
    ): Bitmap {
        if (rotationDegrees == 0f && !mirrorHorizontal) {
            return source
        }

        val matrix = Matrix()
        if (rotationDegrees != 0f) {
            matrix.postRotate(rotationDegrees)
        }
        if (mirrorHorizontal) {
            matrix.postScale(-1f, 1f, source.width / 2f, source.height / 2f)
        }

        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
