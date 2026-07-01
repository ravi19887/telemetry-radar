package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.ObjectDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

sealed interface ModelLoadState {
    object Idle : ModelLoadState
    data class Downloading(val progress: Int) : ModelLoadState
    object LoadingDetector : ModelLoadState
    object Success : ModelLoadState
    data class Error(val message: String) : ModelLoadState
}

enum class GeometryType {
    PRISM,    // Couch, Chair (Neon Blue)
    CYLINDER, // Person (Pulsing Green)
    PANEL,    // TV, Laptop (Neon Orange)
    WIRE      // Other (Gray Wireframe)
}

data class DetectedObject(
    val id: Int,
    val label: String,
    val score: Float,
    val bounds: RectF, // Normalized bounds (0f to 1f)
    val geometryType: GeometryType
)

class TelemetryViewModel : ViewModel() {

    private val _modelLoadState = MutableStateFlow<ModelLoadState>(ModelLoadState.Idle)
    val modelLoadState: StateFlow<ModelLoadState> = _modelLoadState.asStateFlow()

    private val _detectedObjects = MutableStateFlow<List<DetectedObject>>(emptyList())
    val detectedObjects: StateFlow<List<DetectedObject>> = _detectedObjects.asStateFlow()

    private val _compassAngle = MutableStateFlow(0f)
    val compassAngle: StateFlow<Float> = _compassAngle.asStateFlow()

    private val _inferenceTime = MutableStateFlow(0L)
    val inferenceTime: StateFlow<Long> = _inferenceTime.asStateFlow()

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    val terminalLogs = mutableStateListOf<String>()

    private var detector: ObjectDetector? = null
    private var lastFpsCalculationTime = 0L
    private var frameCount = 0

    init {
        log("SYSTEM: INITIALIZING...")
        log("PATH ENGINE: INITIALIZING...")
        log("SPATIAL RADAR: STANDBY")
    }

    fun log(message: String) {
        terminalLogs.add(message)
        if (terminalLogs.size > 12) {
            terminalLogs.removeAt(0)
        }
    }

    fun initModelAndDetector(context: Context) {
        if (_modelLoadState.value is ModelLoadState.Success) return

        viewModelScope.launch {
            _modelLoadState.value = ModelLoadState.Downloading(0)
            log("SYSTEM: RESOLVING TELEMETRY MODEL...")
            
            val modelFile = File(context.filesDir, "efficientdet_lite0.tflite")
            val isCached = modelFile.exists() && modelFile.length() > 1000000
            
            if (isCached) {
                log("SYSTEM: RETRIEVING CACHED MODEL...")
                _modelLoadState.value = ModelLoadState.Downloading(100)
            } else {
                log("SYSTEM: COCO-80 NETWORK TARGET LOADED")
                try {
                    downloadModel(
                        context,
                        "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detector/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite"
                    ) { progress ->
                        _modelLoadState.value = ModelLoadState.Downloading(progress)
                        if (progress % 20 == 0 || progress == 100) {
                            log("SYSTEM: MODEL FETCHING... $progress%")
                        }
                    }
                } catch (e: Exception) {
                    log("SYSTEM_ERR: REMOTE HOST UNREACHABLE")
                    log("SYSTEM_ERR: FALLING BACK TO SYNTHETIC GENERATOR")
                    _modelLoadState.value = ModelLoadState.Error("Download failed: ${e.message}")
                    return@launch
                }
            }

            _modelLoadState.value = ModelLoadState.LoadingDetector
            log("SYSTEM: INSTANTIATING LiteRT COMPILER...")

            try {
                withContext(Dispatchers.Default) {
                    val modelFile = File(context.filesDir, "efficientdet_lite0.tflite")
                    val fileInputStream = FileInputStream(modelFile)
                    val fileChannel = fileInputStream.channel
                    val byteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
                    fileInputStream.close()

                    val baseOptions = BaseOptions.builder()
                        .setModelAssetBuffer(byteBuffer)
                        .build()
                    val options = ObjectDetectorOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setScoreThreshold(0.50f)
                        .setMaxResults(4)
                        .setRunningMode(RunningMode.IMAGE)
                        .build()
                    detector = ObjectDetector.createFromOptions(context, options)
                }
                log("SYSTEM: INITIALIZED")
                log("SPATIAL RADAR: ACTIVE")
                log("PATH ENGINE: CALIBRATING... CLEAR")
                _modelLoadState.value = ModelLoadState.Success
            } catch (e: Exception) {
                log("SYSTEM_ERR: LiteRT COMPILE EXCEPTION")
                _modelLoadState.value = ModelLoadState.Error("Init detector failed: ${e.message}")
            }
        }
    }

    private suspend fun downloadModel(context: Context, url: String, onProgress: (Int) -> Unit) {
        val modelFile = File(context.filesDir, "efficientdet_lite0.tflite")
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to download model: $response")
                val body = response.body ?: throw IOException("Empty response body")
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(modelFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (contentLength > 0) {
                                val progress = ((totalBytesRead * 100) / contentLength).toInt()
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        }
    }

    fun analyzeImage(bitmap: Bitmap) {
        val currentDetector = detector ?: return

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) {
                val mpImage = BitmapImageBuilder(bitmap).build()
                currentDetector.detect(mpImage)
            }
            val endTime = System.currentTimeMillis()
            _inferenceTime.value = endTime - startTime

            // Compute FPS
            frameCount++
            val now = System.currentTimeMillis()
            if (lastFpsCalculationTime == 0L) {
                lastFpsCalculationTime = now
            } else if (now - lastFpsCalculationTime >= 1000) {
                _fps.value = frameCount
                frameCount = 0
                lastFpsCalculationTime = now
            }

            // Map detections to our schema
            val detections = result.detections() ?: emptyList()
            val detectedList = detections.mapIndexed { index, detection ->
                val boundingBox = detection.boundingBox()
                val normLeft = (boundingBox.left / bitmap.width).coerceIn(0f, 1f)
                val normTop = (boundingBox.top / bitmap.height).coerceIn(0f, 1f)
                val normRight = (boundingBox.right / bitmap.width).coerceIn(0f, 1f)
                val normBottom = (boundingBox.bottom / bitmap.height).coerceIn(0f, 1f)
                val rect = RectF(normLeft, normTop, normRight, normBottom)

                val category = detection.categories().firstOrNull()
                val label = category?.categoryName() ?: "unknown"
                val score = category?.score() ?: 0f

                DetectedObject(
                    id = index + 1,
                    label = label,
                    score = score,
                    bounds = rect,
                    geometryType = mapCategoryToGeometry(label)
                )
            }

            _detectedObjects.value = detectedList
            _compassAngle.value = computeUnobstructedPath(detectedList)
        }
    }

    private fun mapCategoryToGeometry(label: String): GeometryType {
        return when (label.lowercase()) {
            "chair", "couch", "sofa", "bed" -> GeometryType.PRISM
            "person" -> GeometryType.CYLINDER
            "tv", "laptop", "monitor", "cell phone" -> GeometryType.PANEL
            else -> GeometryType.WIRE
        }
    }

    private fun computeUnobstructedPath(objects: List<DetectedObject>): Float {
        val numSectors = 7
        val sectors = BooleanArray(numSectors) { true } // true = clear

        for (obj in objects) {
            val left = obj.bounds.left
            val right = obj.bounds.right

            val startSec = (left * numSectors).toInt().coerceIn(0, numSectors - 1)
            val endSec = (right * numSectors).toInt().coerceIn(0, numSectors - 1)

            for (i in startSec..endSec) {
                sectors[i] = false
            }
        }

        // Sectors layout angles:
        // Index 0: Far Left (-60°)
        // Index 1: Mid Left (-40°)
        // Index 2: Left-Center (-20°)
        // Index 3: Center (0°) -> Clear Path Ahead
        // Index 4: Right-Center (20°)
        // Index 5: Mid Right (40°)
        // Index 6: Far Right (60°)
        val sectorAngles = floatArrayOf(-60f, -40f, -20f, 0f, 20f, 40f, 60f)

        // If straight ahead is open, steer 0
        if (sectors[3]) return 0f

        // Otherwise find closest open sector to the center (index 3)
        var bestSector = -1
        var minDiff = Int.MAX_VALUE
        for (i in 0 until numSectors) {
            if (sectors[i]) {
                val diff = Math.abs(i - 3)
                if (diff < minDiff) {
                    minDiff = diff
                    bestSector = i
                }
            }
        }

        return if (bestSector != -1) sectorAngles[bestSector] else 0f
    }

    override fun onCleared() {
        super.onCleared()
        detector?.close()
    }
}
