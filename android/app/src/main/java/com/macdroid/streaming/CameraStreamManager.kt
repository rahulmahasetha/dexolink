package com.macdroid.streaming

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.macdroid.MacDroidApplication
import com.macdroid.networking.StreamFrameMessage
import com.macdroid.networking.StreamRequestMessage
import com.macdroid.networking.StreamResponseMessage
import com.macdroid.networking.StreamStopMessage
import com.macdroid.storage.DexoLinkSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CameraStreamManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CameraStreamManager"
        private const val FRAME_WIDTH = 640
        private const val FRAME_HEIGHT = 480
        private const val TARGET_FPS = 15

        @Volatile
        private var instance: CameraStreamManager? = null

        fun getInstance(context: Context): CameraStreamManager {
            return instance ?: synchronized(this) {
                instance ?: CameraStreamManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isStreamingActive = AtomicBoolean(false)
    private val sequenceNumber = AtomicLong(0)
    private var activeStreamId: String = ""

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentFps = MutableStateFlow(0)
    val currentFps: StateFlow<Int> = _currentFps.asStateFlow()

    // Remote camera receiver state (e.g. Mac/Windows -> Android camera viewer)
    private val _isViewingRemoteCamera = MutableStateFlow(false)
    val isViewingRemoteCamera: StateFlow<Boolean> = _isViewingRemoteCamera.asStateFlow()

    private val _remoteCameraBitmap = MutableStateFlow<Bitmap?>(null)
    val remoteCameraBitmap: StateFlow<Bitmap?> = _remoteCameraBitmap.asStateFlow()

    private val _remoteCameraFps = MutableStateFlow(0)
    val remoteCameraFps: StateFlow<Int> = _remoteCameraFps.asStateFlow()

    private val _remoteDeviceName = MutableStateFlow("Remote Device")
    val remoteDeviceName: StateFlow<String> = _remoteDeviceName.asStateFlow()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var syntheticStreamJob: Job? = null

    private var frameCounter = 0
    private var lastFpsCalculationTime = 0L

    private var remoteFrameCounter = 0
    private var lastRemoteFpsTime = 0L

    // MARK: - Inbound Frame Dispatch (Receiving camera feed from Mac/PC)
    fun handleIncomingStreamFrame(frame: StreamFrameMessage) {
        try {
            val bytes = Base64.decode(frame.data, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                _remoteCameraBitmap.value = bmp
                if (!_isViewingRemoteCamera.value) {
                    _isViewingRemoteCamera.value = true
                }
                updateRemoteFps()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed decoding remote camera frame: ${e.message}")
        }
    }

    private fun updateRemoteFps() {
        remoteFrameCounter++
        val now = System.currentTimeMillis()
        val elapsed = now - lastRemoteFpsTime
        if (elapsed >= 1000L) {
            _remoteCameraFps.value = ((remoteFrameCounter * 1000L) / elapsed).toInt()
            remoteFrameCounter = 0
            lastRemoteFpsTime = now
        }
    }

    fun startViewingRemoteCamera(streamId: String, peerName: String) {
        activeStreamId = streamId
        _remoteDeviceName.value = peerName
        _isViewingRemoteCamera.value = true
        _remoteCameraBitmap.value = null
        _remoteCameraFps.value = 0
        lastRemoteFpsTime = System.currentTimeMillis()
        remoteFrameCounter = 0
    }

    fun stopViewingRemoteCamera() {
        _isViewingRemoteCamera.value = false
        _remoteCameraBitmap.value = null
        _remoteCameraFps.value = 0
    }

    // Active camera lens facing (back vs front)
    private var currentLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    // Handle incoming stream request from Mac/Windows/iOS/Android
    fun handleIncomingStreamRequest(req: StreamRequestMessage) {
        val action = req.options?.get("action")
        if (action == "switch_camera") {
            Log.d(TAG, "Received remote switch_camera request for stream ${req.streamId}")
            switchCamera()
            return
        }

        val settings = DexoLinkSettings.getInstance(context)
        val isEnabled = settings.cameraSharing.value

        if (!isEnabled) {
            val response = StreamResponseMessage(
                streamId = req.streamId,
                streamType = req.streamType,
                accepted = false,
                reason = "Camera Sharing is turned OFF in Android Settings"
            )
            MacDroidApplication.connectionManager.sendMessage(response)
            Log.d(TAG, "Rejected StreamRequest ${req.streamId}: Camera sharing disabled")
            return
        }

        if (action == "request_stream") {
            // Remote device wants Android phone to stream its camera
            val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                val response = StreamResponseMessage(
                    streamId = req.streamId,
                    streamType = req.streamType,
                    accepted = false,
                    reason = "Camera permission not granted on Android phone"
                )
                MacDroidApplication.connectionManager.sendMessage(response)
                Log.w(TAG, "Rejected StreamRequest ${req.streamId}: Camera permission missing")
                return
            }

            val response = StreamResponseMessage(
                streamId = req.streamId,
                streamType = req.streamType,
                accepted = true,
                reason = null
            )
            MacDroidApplication.connectionManager.sendMessage(response)
            Log.d(TAG, "Accepted request_stream ${req.streamId}, starting camera feed...")

            startCameraStreaming(req.streamId)
            return
        }

        val direction = req.options?.get("direction") ?: "outgoing"
        if (direction == "outgoing" || req.options?.get("sender") in listOf("mac", "windows", "ios", "android")) {
            // Remote device wants to stream ITS camera TO Android
            val senderName = req.options?.get("senderName") ?: "Remote Device"
            startViewingRemoteCamera(req.streamId, senderName)
            val response = StreamResponseMessage(
                streamId = req.streamId,
                streamType = req.streamType,
                accepted = true,
                reason = null
            )
            MacDroidApplication.connectionManager.sendMessage(response)
            Log.d(TAG, "Accepted inbound camera stream from $senderName")
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            val response = StreamResponseMessage(
                streamId = req.streamId,
                streamType = req.streamType,
                accepted = false,
                reason = "Camera permission not granted on Android phone"
            )
            MacDroidApplication.connectionManager.sendMessage(response)
            Log.w(TAG, "Rejected StreamRequest ${req.streamId}: Camera permission missing")
            return
        }

        // Accept and start camera stream to remote
        val response = StreamResponseMessage(
            streamId = req.streamId,
            streamType = req.streamType,
            accepted = true,
            reason = null
        )
        MacDroidApplication.connectionManager.sendMessage(response)
        Log.d(TAG, "Accepted StreamRequest ${req.streamId}, starting camera feed...")

        startCameraStreaming(req.streamId)
    }

    fun handleIncomingStreamStop(stop: StreamStopMessage) {
        Log.d(TAG, "Received StreamStop for ${stop.streamId}: ${stop.reason}")
        if (activeStreamId == stop.streamId || isStreamingActive.get() || _isViewingRemoteCamera.value) {
            stopCameraStreaming()
            stopViewingRemoteCamera()
        }
    }

    fun switchCamera() {
        val newFacing = if (currentLensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        setCameraFacing(newFacing)
    }

    fun setCameraFacing(facing: Int) {
        if (currentLensFacing == facing) return
        currentLensFacing = facing
        _isFrontCamera.value = (facing == CameraCharacteristics.LENS_FACING_FRONT)
        Log.d(TAG, "Switched camera lens to: ${if (_isFrontCamera.value) "FRONT" else "BACK"}")

        if (isStreamingActive.get()) {
            stopHardwareCameraCapture()
            syntheticStreamJob?.cancel()
            syntheticStreamJob = null

            val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                try {
                    startHardwareCameraCapture()
                } catch (e: Exception) {
                    Log.w(TAG, "Error restarting hardware camera capture after lens switch: ${e.message}")
                    startSyntheticStreamLoop()
                }
            } else {
                startSyntheticStreamLoop()
            }
        }
    }

    @Synchronized
    fun startCameraStreaming(streamId: String = UUID.randomUUID().toString()) {
        if (isStreamingActive.get()) {
            Log.d(TAG, "Already streaming camera, reusing session for $streamId")
            return
        }

        activeStreamId = streamId
        sequenceNumber.set(0)
        isStreamingActive.set(true)
        _isStreaming.value = true
        lastFpsCalculationTime = System.currentTimeMillis()
        frameCounter = 0

        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            try {
                startHardwareCameraCapture()
            } catch (e: Exception) {
                Log.w(TAG, "Hardware camera capture initialization failed, using synthetic fallback: ${e.message}")
                startSyntheticStreamLoop()
            }
        } else {
            Log.i(TAG, "Camera permission not granted, streaming status preview")
            startSyntheticStreamLoop()
        }
    }

    @Synchronized
    fun stopCameraStreaming() {
        if (!isStreamingActive.get()) return
        isStreamingActive.set(false)
        _isStreaming.value = false
        _currentFps.value = 0

        syntheticStreamJob?.cancel()
        syntheticStreamJob = null

        stopHardwareCameraCapture()

        val streamId = activeStreamId
        activeStreamId = ""
        Log.d(TAG, "Camera streaming stopped for $streamId")
    }

    private fun startHardwareCameraCapture() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            startSyntheticStreamLoop()
            return
        }

        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == currentLensFacing
            } ?: cameraManager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            null
        }

        if (cameraId == null) {
            Log.w(TAG, "No suitable camera found, using live fallback")
            startSyntheticStreamLoop()
            return
        }

        backgroundThread = HandlerThread("CameraBackgroundThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        imageReader = ImageReader.newInstance(FRAME_WIDTH, FRAME_HEIGHT, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                if (!isStreamingActive.get()) return@setOnImageAvailableListener
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    processCameraImage(image)
                    image.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error acquiring camera image: ${e.message}")
                }
            }, backgroundHandler)
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    if (isStreamingActive.get()) {
                        startSyntheticStreamLoop()
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Camera open error: $error, switching to live fallback")
                    if (isStreamingActive.get()) {
                        startSyntheticStreamLoop()
                    }
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException opening camera: ${e.message}")
            startSyntheticStreamLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Exception opening camera: ${e.message}")
            startSyntheticStreamLoop()
        }
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return
        val handler = backgroundHandler ?: return

        try {
            val previewSurface = reader.surface
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            camera.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!isStreamingActive.get()) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
                        Log.d(TAG, "Camera capture session repeating request started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start camera repeating request: ${e.message}")
                        startSyntheticStreamLoop()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera configuration failed, falling back to synthetic stream")
                    startSyntheticStreamLoop()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating camera capture session: ${e.message}")
            startSyntheticStreamLoop()
        }
    }

    private fun processCameraImage(image: Image) {
        val planes = image.planes
        if (planes.isEmpty()) return
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        sendEncodedJpegFrame(bytes, FRAME_WIDTH, FRAME_HEIGHT)
    }

    private fun sendEncodedJpegFrame(jpegBytes: ByteArray, width: Int, height: Int) {
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val seq = sequenceNumber.incrementAndGet()

        val msg = StreamFrameMessage(
            streamId = activeStreamId,
            sequence = seq,
            format = "jpeg",
            width = width,
            height = height,
            data = base64
        )
        MacDroidApplication.connectionManager.sendMessage(msg)

        updateFpsCounter()
    }

    private fun updateFpsCounter() {
        frameCounter++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsCalculationTime
        if (elapsed >= 1000L) {
            val calculatedFps = ((frameCounter * 1000L) / elapsed).toInt()
            _currentFps.value = calculatedFps
            frameCounter = 0
            lastFpsCalculationTime = now
        }
    }

    private fun startSyntheticStreamLoop() {
        if (syntheticStreamJob != null && syntheticStreamJob?.isActive == true) return

        syntheticStreamJob = scope.launch {
            val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            val delayMs = 1000L / TARGET_FPS

            while (isActive && isStreamingActive.get()) {
                val bmp = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)

                // Dark elegant background
                canvas.drawColor(Color.rgb(18, 20, 24))

                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(0, 200, 255)
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
                canvas.drawRect(8f, 8f, (FRAME_WIDTH - 8).toFloat(), (FRAME_HEIGHT - 8).toFloat(), paint)

                // Header
                paint.apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                    textSize = 28f
                    typeface = Typeface.DEFAULT_BOLD
                }
                canvas.drawText("DexoLink Android Camera", 30f, 60f, paint)

                // Device Info
                paint.apply {
                    color = Color.rgb(160, 175, 200)
                    textSize = 20f
                    typeface = Typeface.DEFAULT
                }
                canvas.drawText("Device: ${Build.MANUFACTURER} ${Build.MODEL}", 30f, 100f, paint)

                // Timestamp, Seq & Lens
                val timeStr = timeFormat.format(Date())
                val seq = sequenceNumber.get()
                val lensName = if (currentLensFacing == CameraCharacteristics.LENS_FACING_FRONT) "Front Lens" else "Rear Lens"
                canvas.drawText("Time: $timeStr | Frame #$seq | $lensName", 30f, 135f, paint)

                // Live status badge
                paint.color = Color.rgb(34, 197, 94)
                canvas.drawCircle(45f, 180f, 12f, paint)
                paint.color = Color.WHITE
                paint.textSize = 18f
                canvas.drawText("LIVE STREAM ACTIVE", 70f, 186f, paint)

                // Crosshair viewfinder
                paint.apply {
                    color = Color.argb(80, 0, 200, 255)
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                val cx = (FRAME_WIDTH / 2).toFloat()
                val cy = (FRAME_HEIGHT / 2 + 30).toFloat()
                canvas.drawCircle(cx, cy, 70f, paint)
                canvas.drawLine(cx - 90f, cy, cx + 90f, cy, paint)
                canvas.drawLine(cx, cy - 90f, cx, cy + 90f, paint)

                // Compress to JPEG
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                val jpegBytes = stream.toByteArray()
                bmp.recycle()

                sendEncodedJpegFrame(jpegBytes, FRAME_WIDTH, FRAME_HEIGHT)

                delay(delayMs)
            }
        }
    }

    private fun stopHardwareCameraCapture() {
        try {
            captureSession?.close()
        } catch (e: Exception) {}
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (e: Exception) {}
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (e: Exception) {}
        imageReader = null

        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (e: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }
}
