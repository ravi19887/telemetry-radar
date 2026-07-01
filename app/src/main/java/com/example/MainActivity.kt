package com.example

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TelemetryDashboard(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun TelemetryDashboard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: TelemetryViewModel = viewModel()

    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val modelLoadState by viewModel.modelLoadState.collectAsStateWithLifecycle()
    val detectedObjects by viewModel.detectedObjects.collectAsStateWithLifecycle()
    val compassAngle by viewModel.compassAngle.collectAsStateWithLifecycle()
    val inferenceTime by viewModel.inferenceTime.collectAsStateWithLifecycle()
    val fps by viewModel.fps.collectAsStateWithLifecycle()

    // Setup CameraX and Model Loading when permission is granted
    LaunchedEffect(cameraPermissionState.status.isGranted) {
        if (cameraPermissionState.status.isGranted) {
            viewModel.initModelAndDetector(context)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                val cameraExecutor = Executors.newSingleThreadExecutor()
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        val bitmap = imageProxy.toBitmap()
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val rotatedBitmap = rotateBitmap(bitmap, rotation)
                        viewModel.analyzeImage(rotatedBitmap)
                    } catch (e: Exception) {
                        // Suppress or log internal conversion glitches safely
                    } finally {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        imageAnalysis
                    )
                    viewModel.log("SYSTEM: OPTICAL FEED BOUND")
                } catch (e: Exception) {
                    viewModel.log("SYSTEM_ERR: BIND FAILURE")
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    // Full screen canvas container
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        val widthDp = maxWidth
        val heightDp = maxHeight

        if (!cameraPermissionState.status.isGranted) {
            // Cybersecurity permission lock screen
            PermissionLockScreen(onRequestPermission = {
                cameraPermissionState.launchPermissionRequest()
            })
        } else {
            // Infinite transition to power our tactical breathe animations
            val infiniteTransition = rememberInfiniteTransition(label = "breathe")
            val gridBreath by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(4000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "breath_grid"
            )

            // Animated angle to make the HUD pathfinder compass incredibly smooth
            val animatedCompassAngle by animateFloatAsState(
                targetValue = compassAngle,
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "compass_smooth"
            )

            // Dynamic background canvas: perspective grid and custom neon shapes
            Canvas(modifier = Modifier.fillMaxSize()) {
                val sizeW = size.width
                val sizeH = size.height
                val horizonY = sizeH * 0.48f

                // 1. Draw 3D perspective grid lines
                val numGridLines = 14
                for (i in 0..numGridLines) {
                    val progress = i.toFloat() / numGridLines
                    val y = horizonY + (sizeH - horizonY) * Math.pow(progress.toDouble(), 2.2).toFloat()
                    val opacity = (progress * 0.35f * (0.6f + 0.4f * gridBreath)).coerceIn(0f, 1f)
                    
                    drawLine(
                        color = Color(0xFF00E5FF).copy(alpha = opacity),
                        start = Offset(0f, y),
                        end = Offset(sizeW, y),
                        strokeWidth = 2f
                    )
                }

                // Draw perspective vertical radial lines
                val numRadialLines = 16
                val vpX = sizeW / 2f
                val vpY = horizonY - 15f
                for (i in 0..numRadialLines) {
                    val progress = i.toFloat() / numRadialLines
                    val bottomX = sizeW * -0.5f + (sizeW * 2f) * progress
                    val opacity = (0.22f * (0.6f + 0.4f * gridBreath)).coerceIn(0f, 1f)

                    drawLine(
                        color = Color(0xFF00E5FF).copy(alpha = opacity),
                        start = Offset(vpX, vpY),
                        end = Offset(bottomX, sizeH),
                        strokeWidth = 2f
                    )
                }

                // 2. Draw Vignette Gradient to fade elements out around boundaries
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color(0xBB020202), Color(0xFF0D0D0D)),
                        center = center,
                        radius = size.maxDimension * 0.72f
                    ),
                    topLeft = Offset.Zero,
                    size = size
                )

                // 3. Render Detected Objects as abstract cyberpunk geometries
                detectedObjects.forEach { obj ->
                    val left = obj.bounds.left * sizeW
                    val top = obj.bounds.top * sizeH
                    val right = obj.bounds.right * sizeW
                    val bottom = obj.bounds.bottom * sizeH
                    val width = right - left
                    val height = bottom - top

                    when (obj.geometryType) {
                        GeometryType.PRISM -> {
                            // Glowing blue 3D prism for chairs/couches
                            val dx = width * 0.14f
                            val dy = -height * 0.14f
                            val leftB = left + dx
                            val topB = top + dy
                            val rightB = right + dx
                            val bottomB = bottom + dy

                            // Back face
                            drawRect(
                                color = Color(0x5500E5FF),
                                topLeft = Offset(leftB, topB),
                                size = Size(width, height),
                                style = Stroke(width = 2f)
                            )
                            // Front face
                            drawRect(
                                color = Color(0xFF00E5FF),
                                topLeft = Offset(left, top),
                                size = Size(width, height),
                                style = Stroke(width = 4f)
                            )
                            // Connections
                            drawLine(Color(0x9900E5FF), Offset(left, top), Offset(leftB, topB), strokeWidth = 2.5f)
                            drawLine(Color(0x9900E5FF), Offset(right, top), Offset(rightB, topB), strokeWidth = 2.5f)
                            drawLine(Color(0x9900E5FF), Offset(left, bottom), Offset(leftB, bottomB), strokeWidth = 2.5f)
                            drawLine(Color(0x9900E5FF), Offset(right, bottom), Offset(rightB, bottomB), strokeWidth = 2.5f)
                        }
                        GeometryType.CYLINDER -> {
                            // Pulsing green cylinder scanner for people
                            val rx = width / 2f
                            val ry = rx * 0.22f
                            val cx = left + rx

                            // Top and Bottom ovals
                            drawOval(
                                color = Color(0xFF00E676),
                                topLeft = Offset(left, top - ry),
                                size = Size(width, ry * 2),
                                style = Stroke(width = 4f)
                            )
                            drawOval(
                                color = Color(0xFF00E676),
                                topLeft = Offset(left, bottom - ry),
                                size = Size(width, ry * 2),
                                style = Stroke(width = 4f)
                            )
                            // Sides
                            drawLine(Color(0xFF00E676), Offset(left, top), Offset(left, bottom), strokeWidth = 4f)
                            drawLine(Color(0xFF00E676), Offset(right, top), Offset(right, bottom), strokeWidth = 4f)

                            // Cyber matrix scan rings
                            drawOval(
                                color = Color(0x6600E676),
                                topLeft = Offset(left, top + height * 0.33f - ry),
                                size = Size(width, ry * 2),
                                style = Stroke(width = 2f)
                            )
                            drawOval(
                                color = Color(0x6600E676),
                                topLeft = Offset(left, top + height * 0.66f - ry),
                                size = Size(width, ry * 2),
                                style = Stroke(width = 2f)
                            )
                        }
                        GeometryType.PANEL -> {
                            // Floating orange double-line panel for screens/laptops
                            drawRect(
                                color = Color(0xFFFF9100),
                                topLeft = Offset(left, top),
                                size = Size(width, height),
                                style = Stroke(width = 4.5f)
                            )
                            val pad = 12f
                            if (width > pad * 2 && height > pad * 2) {
                                drawRect(
                                    color = Color(0x77FF9100),
                                    topLeft = Offset(left + pad, top + pad),
                                    size = Size(width - pad * 2, height - pad * 2),
                                    style = Stroke(width = 2f)
                                )
                            }
                            // Retro corners ticks
                            val tLen = 18f
                            drawLine(Color(0xFFFF9100), Offset(left - 6f, top), Offset(left - 6f - tLen, top), strokeWidth = 3f)
                            drawLine(Color(0xFFFF9100), Offset(left, top - 6f), Offset(left, top - 6f - tLen), strokeWidth = 3f)
                            drawLine(Color(0xFFFF9100), Offset(right + 6f, top), Offset(right + 6f + tLen, top), strokeWidth = 3f)
                            drawLine(Color(0xFFFF9100), Offset(right, top - 6f), Offset(right, top - 6f - tLen), strokeWidth = 3f)
                            drawLine(Color(0xFFFF9100), Offset(left - 6f, bottom), Offset(left - 6f - tLen, bottom), strokeWidth = 3f)
                            drawLine(Color(0xFFFF9100), Offset(left, bottom + 6f), Offset(left, bottom + 6f + tLen), strokeWidth = 3f)
                            drawLine(Color(0xFFFF9100), Offset(right + 6f, bottom), Offset(right + 6f + tLen, bottom), strokeWidth = 3f)
                            drawLine(Color(0xFFFF9100), Offset(right, bottom + 6f), Offset(right, bottom + 6f + tLen), strokeWidth = 3f)
                        }
                        GeometryType.WIRE -> {
                            // Minimalist gray bracket box for any other items
                            val bSz = (width * 0.25f).coerceAtMost(30f)
                            // Top-left
                            drawLine(Color(0xFF4D4D4D), Offset(left, top), Offset(left + bSz, top), strokeWidth = 3f)
                            drawLine(Color(0xFF4D4D4D), Offset(left, top), Offset(left, top + bSz), strokeWidth = 3f)
                            // Top-right
                            drawLine(Color(0xFF4D4D4D), Offset(right, top), Offset(right - bSz, top), strokeWidth = 3f)
                            drawLine(Color(0xFF4D4D4D), Offset(right, top), Offset(right, top + bSz), strokeWidth = 3f)
                            // Bottom-left
                            drawLine(Color(0xFF4D4D4D), Offset(left, bottom), Offset(left + bSz, bottom), strokeWidth = 3f)
                            drawLine(Color(0xFF4D4D4D), Offset(left, bottom), Offset(left, bottom - bSz), strokeWidth = 3f)
                            // Bottom-right
                            drawLine(Color(0xFF4D4D4D), Offset(right, bottom), Offset(right - bSz, bottom), strokeWidth = 3f)
                            drawLine(Color(0xFF4D4D4D), Offset(right, bottom), Offset(right, bottom - bSz), strokeWidth = 3f)
                        }
                    }
                }
            }

            // 4. Overlaid labels beside geometries using exact layout offsets
            detectedObjects.forEach { obj ->
                val leftOffset = widthDp * obj.bounds.left
                val topOffset = (heightDp * obj.bounds.top - 28.dp).coerceAtLeast(8.dp)

                val themeColor = when (obj.geometryType) {
                    GeometryType.PRISM -> Color(0xFF00E5FF)
                    GeometryType.CYLINDER -> Color(0xFF00E676)
                    GeometryType.PANEL -> Color(0xFFFF9100)
                    GeometryType.WIRE -> Color(0xFF888888)
                }

                val idStr = String.format("%02d", obj.id)
                val typeName = when (obj.geometryType) {
                    GeometryType.PRISM -> "PRISM"
                    GeometryType.CYLINDER -> "CYLINDER"
                    GeometryType.PANEL -> "PANEL"
                    GeometryType.WIRE -> "WIRE"
                }
                val labelText = "[ID: ${idStr}_$typeName | CONF: ${(obj.score * 100).toInt()}%]"

                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftOffset.roundToPx(), topOffset.roundToPx()) }
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xDD000000))
                        .border(1.dp, themeColor.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = labelText,
                        color = themeColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 5. Left Top: Terminal text log console
            TerminalConsole(
                logs = viewModel.terminalLogs,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(16.dp)
                    .align(Alignment.TopStart)
            )

            // 6. Center Bottom: Dynamic Pathfinder Compass HUD
            CompassHUD(
                targetAngle = animatedCompassAngle,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .align(Alignment.BottomCenter)
            )

            // 7. Right Bottom: Latency & Frame status indicators
            TelemetryHUDReadout(
                inferenceTime = inferenceTime,
                fps = fps,
                loadState = modelLoadState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
fun TerminalConsole(logs: List<String>, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_blink"
    )

    Box(
        modifier = modifier
            .width(280.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x22000000))
            .border(1.dp, Color(0x3300E676), RoundedCornerShape(6.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "⚡ TELEMETRY CENTRAL CONSOLE",
                color = Color(0xFF00E676),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            logs.forEachIndexed { index, logLine ->
                val isLast = index == logs.lastIndex
                Row {
                    Text(
                        text = logLine,
                        color = if (logLine.contains("SYSTEM_ERR")) Color(0xFFFF5252) else Color(0xCC00E676),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isLast) {
                        Text(
                            text = " █",
                            color = Color(0xFF00E676).copy(alpha = cursorAlpha),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompassHUD(targetAngle: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(200.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Glowing Vector Ring
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(45.dp))
                .background(Color(0x11000000))
                .border(1.5.dp, Color(0x3300E676), RoundedCornerShape(45.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerPt = center
                val radius = size.width / 2

                // Draw tick marks
                for (deg in 0 until 360 step 45) {
                    val rad = Math.toRadians(deg.toDouble())
                    val startX = (centerPt.x + (radius - 10) * Math.cos(rad)).toFloat()
                    val startY = (centerPt.y + (radius - 10) * Math.sin(rad)).toFloat()
                    val endX = (centerPt.x + radius * Math.cos(rad)).toFloat()
                    val endY = (centerPt.y + radius * Math.sin(rad)).toFloat()
                    drawLine(
                        color = Color(0x4400E676),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 1.5f
                    )
                }

                // Draw animated pathfinder needle
                rotate(degrees = targetAngle, pivot = centerPt) {
                    val arrowPath = Path().apply {
                        moveTo(centerPt.x, centerPt.y - 32f) // pointer tip
                        lineTo(centerPt.x - 12f, centerPt.y + 16f)
                        lineTo(centerPt.x, centerPt.y + 8f)
                        lineTo(centerPt.x + 12f, centerPt.y + 16f)
                        close()
                    }
                    drawPath(
                        path = arrowPath,
                        color = Color(0xFF00E676),
                        style = Fill
                    )
                    drawPath(
                        path = arrowPath,
                        color = Color(0xBB00E676),
                        style = Stroke(width = 2f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Pathfinder state readout
        val textStatus = when {
            targetAngle == 0f -> "PATH INTEGRITY: NOMINAL"
            targetAngle < 0f -> "OBSTACLE DEVIATION: PORT"
            else -> "OBSTACLE DEVIATION: STARBOARD"
        }
        val textAngle = if (targetAngle == 0f) "STEER 000°" else "STEER ${Math.abs(targetAngle.toInt())}° ${if (targetAngle < 0) "L" else "R"}"
        val statusColor = if (targetAngle == 0f) Color(0xFF00E676) else Color(0xFFFF9100)

        Text(
            text = textStatus,
            color = statusColor,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = textAngle,
            color = statusColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun TelemetryHUDReadout(
    inferenceTime: Long,
    fps: Int,
    loadState: ModelLoadState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x22000000))
            .border(1.dp, Color(0x3300E5FF), RoundedCornerShape(6.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val progressText = when (loadState) {
                is ModelLoadState.Downloading -> "MODEL DOWNLOAD: ${loadState.progress}%"
                is ModelLoadState.LoadingDetector -> "COMPILING LiteRT..."
                is ModelLoadState.Success -> "LiteRT AGENT: ONLINE"
                is ModelLoadState.Error -> "LiteRT AGENT: ERROR"
                else -> "LiteRT AGENT: OFFLINE"
            }

            Text(
                text = progressText,
                color = when (loadState) {
                    is ModelLoadState.Success -> Color(0xFF00E5FF)
                    is ModelLoadState.Error -> Color(0xFFFF5252)
                    else -> Color(0xFFFF9100)
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider(
                color = Color(0x2200E5FF),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(
                text = "INFERENCE: ${inferenceTime}ms",
                color = Color(0xCC00E5FF),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "RADAR FPS: $fps",
                color = Color(0xCC00E5FF),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun PermissionLockScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .widthIn(max = 400.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x11000000))
                .border(1.5.dp, Color(0xFFFF5252), RoundedCornerShape(12.dp))
                .padding(24.dp)
        ) {
            Text(
                text = "⚠️ ACCESS RESTRICTED",
                color = Color(0xFFFF5252),
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "THE HUD SYSTEM REQUIRES HIGH-SPEED CAMERA FEED ENCODING TO COMPUTE SPATIAL TELEMETRY DEVIATIONS IN REAL TIME.",
                color = Color(0xCCFF5252),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x1A00E676))
                    .border(1.5.dp, Color(0xFF00E676), RoundedCornerShape(4.dp))
                    .clickable { onRequestPermission() }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "[ GRANT AUTHORIZATION ]",
                    color = Color(0xFF00E676),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// Utility to rotate analyzed frame bitmaps appropriately
private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = android.graphics.Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
