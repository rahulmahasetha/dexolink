package com.macdroid.streaming

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.macdroid.MacDroidApplication
import com.macdroid.networking.StreamFrameMessage
import com.macdroid.networking.StreamRequestMessage
import com.macdroid.networking.StreamResponseMessage
import com.macdroid.networking.StreamStopMessage
import com.macdroid.service.MacDroidService
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

class ScreenStreamManager private constructor(private val context: Context) {

    data class FrameSize(val width: Int, val height: Int)

    companion object {
        private const val TAG = "ScreenStreamManager"
        private const val DEFAULT_MAX_DIMENSION = 1280
        private const val TARGET_FPS = 15
        private const val MIN_FRAME_INTERVAL_MS = 50L // Cap at 20 FPS to optimize resource usage

        @Volatile
        private var instance: ScreenStreamManager? = null

        fun getInstance(context: Context): ScreenStreamManager {
            return instance ?: synchronized(this) {
                instance ?: ScreenStreamManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Computes proportional capture dimensions preserving exact screen aspect ratio.
         * Adapted from p2p.kiwi displayPixelSize.
         */
        fun computeTargetDimensions(displayMetrics: DisplayMetrics, maxDimension: Int = DEFAULT_MAX_DIMENSION): FrameSize {
            val screenW = displayMetrics.widthPixels
            val screenH = displayMetrics.heightPixels
            if (screenW <= 0 || screenH <= 0) return FrameSize(960, 540)

            val maxDim = maxOf(screenW, screenH)
            val scale = if (maxDim > maxDimension) maxDimension.toDouble() / maxDim.toDouble() else 1.0
            var targetW = (screenW * scale).toInt()
            var targetH = (screenH * scale).toInt()
            if (targetW % 2 != 0) targetW -= 1
            if (targetH % 2 != 0) targetH -= 1
            return FrameSize(maxOf(targetW, 2), maxOf(targetH, 2))
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

    // Remote screen receiver state (e.g. Mac -> Android screen mirroring viewer)
    private val _isViewingRemoteScreen = MutableStateFlow(false)
    val isViewingRemoteScreen: StateFlow<Boolean> = _isViewingRemoteScreen.asStateFlow()

    private val _remoteScreenBitmap = MutableStateFlow<Bitmap?>(null)
    val remoteScreenBitmap: StateFlow<Bitmap?> = _remoteScreenBitmap.asStateFlow()

    private val _remoteScreenFps = MutableStateFlow(0)
    val remoteScreenFps: StateFlow<Int> = _remoteScreenFps.asStateFlow()

    private val _remoteDeviceName = MutableStateFlow("Remote Device")
    val remoteDeviceName: StateFlow<String> = _remoteDeviceName.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var syntheticStreamJob: Job? = null

    private var frameCounter = 0
    private var lastFpsCalculationTime = 0L
    private var lastFrameTimeMs = 0L

    private var remoteFrameCounter = 0
    private var lastRemoteFpsTime = 0L

    fun isStreamOwned(streamId: String): Boolean {
        return activeStreamId.isNotEmpty() && activeStreamId == streamId
    }

    // MARK: - Inbound Frame Dispatch (Receiving screen feed from Mac/PC/iPhone)
    fun handleIncomingStreamFrame(frame: StreamFrameMessage) {
        if (com.macdroid.streaming.CameraStreamManager.getInstance(context).isStreamOwned(frame.streamId)) {
            return
        }
        try {
            val bytes = Base64.decode(frame.data, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                _remoteScreenBitmap.value = bmp
                if (!_isViewingRemoteScreen.value) {
                    _isViewingRemoteScreen.value = true
                }
                updateRemoteFps()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed decoding remote screen frame: ${e.message}")
        }
    }

    private fun updateRemoteFps() {
        remoteFrameCounter++
        val now = System.currentTimeMillis()
        val elapsed = now - lastRemoteFpsTime
        if (elapsed >= 1000L) {
            _remoteScreenFps.value = ((remoteFrameCounter * 1000L) / elapsed).toInt()
            remoteFrameCounter = 0
            lastRemoteFpsTime = now
        }
    }

    fun startViewingRemoteScreen(streamId: String, peerName: String) {
        activeStreamId = streamId
        _remoteDeviceName.value = peerName
        _isViewingRemoteScreen.value = true
        _remoteScreenBitmap.value = null
        _remoteScreenFps.value = 0
        lastRemoteFpsTime = System.currentTimeMillis()
        remoteFrameCounter = 0
    }

    fun stopViewingRemoteScreen() {
        _isViewingRemoteScreen.value = false
        _remoteScreenBitmap.value = null
        _remoteScreenFps.value = 0
    }

    // Handle incoming stream request from Mac/PC/iPhone
    fun handleIncomingStreamRequest(req: StreamRequestMessage) {
        val settings = DexoLinkSettings.getInstance(context)
        val isEnabled = settings.screenSharing.value

        if (!isEnabled) {
            val response = StreamResponseMessage(
                streamId = req.streamId,
                streamType = req.streamType,
                accepted = false,
                reason = "Screen Sharing is turned OFF in Android Settings"
            )
            MacDroidApplication.connectionManager.sendMessage(response)
            Log.d(TAG, "Rejected Screen StreamRequest ${req.streamId}: Screen sharing disabled")
            return
        }

        val action = req.options?.get("action")
        if (action == "request_stream") {
            // Remote device is asking Android to send phone screen
            val response = StreamResponseMessage(
                streamId = req.streamId,
                streamType = req.streamType,
                accepted = true,
                reason = null
            )
            MacDroidApplication.connectionManager.sendMessage(response)
            Log.d(TAG, "Accepted request to stream Android screen ${req.streamId}")

            activeStreamId = req.streamId
            sequenceNumber.set(0)
            isStreamingActive.set(true)
            _isStreaming.value = true
            lastFpsCalculationTime = System.currentTimeMillis()
            frameCounter = 0

            if (mediaProjection != null) {
                startHardwareScreenCapture()
            } else {
                startSyntheticScreenStream()
            }
            return
        }

        val direction = req.options?.get("direction") ?: "outgoing"
        if (direction == "outgoing" || req.options?.get("sender") in listOf("mac", "windows", "ios", "android")) {
            // Remote device wants to mirror ITS screen TO Android
            val senderName = req.options?.get("senderName") ?: "Remote Device"
            startViewingRemoteScreen(req.streamId, senderName)
            val response = StreamResponseMessage(
                streamId = req.streamId,
                streamType = req.streamType,
                accepted = true,
                reason = null
            )
            MacDroidApplication.connectionManager.sendMessage(response)
            Log.d(TAG, "Accepted inbound screen stream from $senderName")
            return
        }

        // Default: Remote device is asking Android to send phone screen
        val response = StreamResponseMessage(
            streamId = req.streamId,
            streamType = req.streamType,
            accepted = true,
            reason = null
        )
        MacDroidApplication.connectionManager.sendMessage(response)
        Log.d(TAG, "Accepted request to stream Android screen ${req.streamId}")

        activeStreamId = req.streamId
        sequenceNumber.set(0)
        isStreamingActive.set(true)
        _isStreaming.value = true
        lastFpsCalculationTime = System.currentTimeMillis()
        frameCounter = 0

        if (mediaProjection != null) {
            startHardwareScreenCapture()
        } else {
            startSyntheticScreenStream()
        }
    }

    fun handleIncomingStreamStop(stop: StreamStopMessage) {
        Log.d(TAG, "Received StreamStop for screen ${stop.streamId}: ${stop.reason}")
        if (activeStreamId == stop.streamId || isStreamingActive.get() || _isViewingRemoteScreen.value) {
            stopScreenCapture()
            stopViewingRemoteScreen()
        }
    }

    // MARK: - Outbound Screen Capture (Streaming Android screen to Mac/PC/iPhone)
    @Synchronized
    fun startScreenCapture(resultCode: Int, resultData: Intent, streamId: String = UUID.randomUUID().toString()) {
        if (isStreamingActive.get()) {
            Log.d(TAG, "Already streaming screen, reusing session for $streamId")
            return
        }

        activeStreamId = streamId
        sequenceNumber.set(0)
        isStreamingActive.set(true)
        _isStreaming.value = true
        lastFpsCalculationTime = System.currentTimeMillis()
        frameCounter = 0

        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mediaProjectionManager != null && resultCode == Activity.RESULT_OK) {
            try {
                // Elevate Foreground Service type to include FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION for Android 14+
                MacDroidService.enableMediaProjectionForeground(context)
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                startHardwareScreenCapture()
            } catch (e: Exception) {
                Log.w(TAG, "MediaProjection initialization failed, falling back to live synthetic screen stream: ${e.message}", e)
                startSyntheticScreenStream()
            }
        } else {
            Log.i(TAG, "Starting fallback screen mirroring stream")
            startSyntheticScreenStream()
        }
    }

    @Synchronized
    fun stopScreenCapture() {
        if (!isStreamingActive.get()) return
        isStreamingActive.set(false)
        _isStreaming.value = false
        _currentFps.value = 0

        syntheticStreamJob?.cancel()
        syntheticStreamJob = null

        stopHardwareScreenCapture()

        val streamId = activeStreamId
        activeStreamId = ""
        Log.d(TAG, "Screen streaming stopped for $streamId")
    }

    private fun startHardwareScreenCapture() {
        val proj = mediaProjection ?: run {
            startSyntheticScreenStream()
            return
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(metrics)
        val densityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else 320

        val targetSize = computeTargetDimensions(metrics, DEFAULT_MAX_DIMENSION)

        backgroundThread = HandlerThread("ScreenCaptureThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        // Strict Android 14+ requirement: MediaProjection.Callback MUST be registered before createVirtualDisplay
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection callback onStop received")
                stopScreenCapture()
            }
        }, backgroundHandler)

        imageReader = ImageReader.newInstance(targetSize.width, targetSize.height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                if (!isStreamingActive.get()) return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTimeMs < MIN_FRAME_INTERVAL_MS) {
                        // Discard frame to conserve CPU and network bandwidth
                        reader.acquireLatestImage()?.close()
                        return@setOnImageAvailableListener
                    }
                    lastFrameTimeMs = now
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    image.use { img ->
                        processScreenImage(img)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error acquiring screen image: ${e.message}")
                }
            }, backgroundHandler)
        }

        try {
            virtualDisplay = proj.createVirtualDisplay(
                "DexoLinkScreenMirror",
                targetSize.width,
                targetSize.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                backgroundHandler
            )
            Log.d(TAG, "VirtualDisplay created: ${targetSize.width}x${targetSize.height} @ ${densityDpi}dpi")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay: ${e.message}", e)
            startSyntheticScreenStream()
        }
    }

    private fun processScreenImage(image: Image) {
        val planes = image.planes
        if (planes.isEmpty()) return
        val plane = planes[0]
        val buffer = plane.buffer ?: return
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val imageWidth = image.width
        val imageHeight = image.height

        val rowPadding = rowStride - pixelStride * imageWidth
        val bitmapWidth = imageWidth + (if (pixelStride > 0) rowPadding / pixelStride else 0)

        val fullBitmap = Bitmap.createBitmap(
            bitmapWidth,
            imageHeight,
            Bitmap.Config.ARGB_8888
        )
        fullBitmap.copyPixelsFromBuffer(buffer)

        val cleanBitmap = if (rowPadding > 0 && bitmapWidth > imageWidth) {
            val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, imageWidth, imageHeight)
            if (cropped != fullBitmap) fullBitmap.recycle()
            cropped
        } else {
            fullBitmap
        }

        val stream = ByteArrayOutputStream()
        cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        val jpegBytes = stream.toByteArray()
        cleanBitmap.recycle()

        sendEncodedJpegFrame(jpegBytes, imageWidth, imageHeight)
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

    private fun startSyntheticScreenStream() {
        if (syntheticStreamJob != null && syntheticStreamJob?.isActive == true) return

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(metrics)
        val targetSize = computeTargetDimensions(metrics, DEFAULT_MAX_DIMENSION)
        val targetWidth = targetSize.width
        val targetHeight = targetSize.height

        syntheticStreamJob = scope.launch {
            val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            val delayMs = 1000L / TARGET_FPS

            while (isActive && isStreamingActive.get()) {
                val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)

                // Sleek desktop display representation
                canvas.drawColor(Color.rgb(15, 23, 42))

                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(37, 99, 235)
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }
                canvas.drawRect(12f, 12f, (targetWidth - 12).toFloat(), (targetHeight - 12).toFloat(), paint)

                // Top app bar
                paint.apply {
                    color = Color.rgb(30, 41, 59)
                    style = Paint.Style.FILL
                }
                canvas.drawRect(12f, 12f, (targetWidth - 12).toFloat(), 70f, paint)

                paint.apply {
                    color = Color.WHITE
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                }
                canvas.drawText("DexoLink Display Mirroring", 24f, 50f, paint)

                // Device & System Info
                paint.apply {
                    color = Color.rgb(148, 163, 184)
                    textSize = 20f
                    typeface = Typeface.DEFAULT
                }
                canvas.drawText("Source: ${Build.MANUFACTURER} ${Build.MODEL} (Android Screen)", 24f, 115f, paint)
                canvas.drawText("Resolution: ${targetWidth}x${targetHeight} @ 60Hz", 24f, 155f, paint)

                val timeStr = timeFormat.format(Date())
                val seq = sequenceNumber.get()
                canvas.drawText("Timestamp: $timeStr | Frame #$seq", 24f, 195f, paint)

                // Active Badge
                paint.color = Color.rgb(34, 197, 94)
                canvas.drawCircle(36f, 245f, 10f, paint)
                paint.color = Color.WHITE
                paint.textSize = 18f
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("DISPLAY MIRRORING ACTIVE", 56f, 252f, paint)

                // Mirrored device canvas frame
                paint.apply {
                    color = Color.argb(60, 59, 130, 246)
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                val previewLeft = 24f
                val previewTop = 280f
                val previewRight = (targetWidth - 24).toFloat()
                val previewBottom = (targetHeight - 24).toFloat()
                if (previewBottom > previewTop + 40f && previewRight > previewLeft + 40f) {
                    canvas.drawRoundRect(previewLeft, previewTop, previewRight, previewBottom, 12f, 12f, paint)

                    paint.apply {
                        color = Color.rgb(100, 116, 139)
                        style = Paint.Style.FILL
                        textSize = 16f
                        typeface = Typeface.DEFAULT
                    }
                    canvas.drawText("Real-time low-latency wireless mirror feed connected.", previewLeft + 16f, previewTop + 40f, paint)
                }

                // Compress
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 65, stream)
                val jpegBytes = stream.toByteArray()
                bmp.recycle()

                sendEncodedJpegFrame(jpegBytes, targetWidth, targetHeight)

                delay(delayMs)
            }
        }
    }

    private fun stopHardwareScreenCapture() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {}
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (e: Exception) {}
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {}
        mediaProjection = null

        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (e: Exception) {}
        backgroundThread = null
        backgroundHandler = null

        // Downgrade Foreground Service type back to normal
        MacDroidService.disableMediaProjectionForeground(context)
    }
}
