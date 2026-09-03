package com.trungkien.cleanvehicle

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.AspectRatioStrategy
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionSelector
import androidx.camera.core.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var status: TextView

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var detector: YoloXTinyDetector? = null

    @Volatile
    private var cameraRunning = false

    @Volatile
    private var lastInferenceCompleteMs = 0L

    @Volatile
    private var lastInferenceMs = 0f

    @Volatile
    private var lastVehicleCount = 0

    @Volatile
    private var lastPersonCount = 0

    @Volatile
    private var inferenceCounter = 0L

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadModel()
        else status.text = "CẦN QUYỀN CAMERA"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        buildUi()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            loadModel()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        mainHandler.post(heartbeat)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        overlay = DetectionOverlay(this)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(175, 0, 0, 0))
            textSize = 14f
            setPadding(18, 12, 18, 12)
            text = "TRUNGKIEN CLEAN V1\nĐANG NẠP MODEL..."
        }

        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )

        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )

        root.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            )
        )

        setContentView(root)
    }

    private fun loadModel() {
        status.text = "TRUNGKIEN CLEAN V1\nĐANG NẠP YOLOX-TINY..."

        modelExecutor.execute {
            runCatching {
                val modelFile = copyAssetModel()
                YoloXTinyDetector(modelFile)
            }.onSuccess { created ->
                detector = created

                runOnUiThread {
                    status.text =
                        "TRUNGKIEN CLEAN V1\nYOLOX-TINY/${created.runtimeName} • SẴN SÀNG"
                    startCamera()
                }
            }.onFailure { error ->
                runOnUiThread {
                    status.text =
                        "LỖI MODEL\n${error.javaClass.simpleName}: ${error.message}"
                }
            }
        }
    }

    private fun copyAssetModel(): File {
        val target = File(filesDir, "yolox_tiny_clean_v1.onnx")

        assets.open("yolox_tiny.onnx").use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, 256 * 1024)
            }
        }

        require(target.length() > 5_000_000L) {
            "Model quá nhỏ: ${target.length()} bytes"
        }

        return target
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                    .also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(
                            AspectRatio.RATIO_4_3,
                            AspectRatioStrategy.FALLBACK_RULE_AUTO,
                        )
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analyzerExecutor, ::analyze)

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )

                cameraRunning = true
            }.onFailure { error ->
                status.text = "LỖI CAMERA\n${error.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        val d = detector

        if (d == null) {
            image.close()
            return
        }

        try {
            val result = d.detect(image)

            lastInferenceCompleteMs = SystemClock.elapsedRealtime()
            lastInferenceMs = result.inferenceMs
            inferenceCounter++

            lastVehicleCount = result.detections.count {
                YoloXTinyDetector.isVehicle(it.classId)
            }

            lastPersonCount = result.detections.count {
                it.classId == 0
            }

            runOnUiThread {
                overlay.update(result)
            }
        } catch (error: Throwable) {
            runOnUiThread {
                status.text =
                    "AI ERROR\n${error.javaClass.simpleName}: ${error.message}"
                overlay.clear()
            }
        } finally {
            image.close()
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val d = detector
            val now = SystemClock.elapsedRealtime()

            if (d != null) {
                val age = if (lastInferenceCompleteMs == 0L) {
                    Long.MAX_VALUE
                } else {
                    now - lastInferenceCompleteMs
                }

                val state = when {
                    !cameraRunning -> "CAMERA..."
                    lastInferenceCompleteMs == 0L -> "AI ĐANG CHẠY LẦN ĐẦU..."
                    age > 3_000L -> "⚠ AI STALL ${age / 1000f}s"
                    else -> "AI LIVE"
                }

                status.text = buildString {
                    append("TRUNGKIEN CLEAN V1\n")
                    append("YOLOX-TINY/").append(d.runtimeName).append(" • ").append(state)
                    append("\nXE ").append(lastVehicleCount)
                    append(" • NGƯỜI ").append(lastPersonCount)
                    append(" • ").append(lastInferenceMs.roundToInt()).append(" ms")
                    append(" • #").append(inferenceCounter)
                }
            }

            mainHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(heartbeat)
        detector?.close()
        detector = null

        analyzerExecutor.shutdownNow()
        modelExecutor.shutdownNow()

        super.onDestroy()
    }
}
