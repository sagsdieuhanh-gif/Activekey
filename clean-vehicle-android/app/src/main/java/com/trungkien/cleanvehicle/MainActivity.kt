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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var previewView:
        PreviewView

    private lateinit var overlay:
        DetectionOverlay

    private lateinit var status:
        TextView

    private lateinit var modeButton:
        TextView

    private val analyzerExecutor =
        Executors.newSingleThreadExecutor()

    private val modelExecutor =
        Executors.newSingleThreadExecutor()

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private lateinit var speedProvider:
        AdasSpeedProvider

    private lateinit var calibrator:
        AdasAutoCalibrator

    private lateinit var voice:
        GoogleAdasVoice

    private val decisionEngine =
        AdasDecisionEngine()

    private val beeper =
        AdasBeeper()

    @Volatile
    private var roadDetector:
        YoloXTinyDetector? =
        null

    @Volatile
    private var laneDetector:
        UfldLaneDetector? =
        null

    @Volatile
    private var latestSnapshot =
        AdasSnapshot()

    @Volatile
    private var roadInferenceMs =
        0f

    @Volatile
    private var laneInferenceMs =
        0f

    @Volatile
    private var roadCounter =
        0L

    @Volatile
    private var laneCounter =
        0L

    @Volatile
    private var cameraRunning =
        false

    private var analysisCounter =
        0L

    private var debugMode =
        false

    private var previousHmwWarning =
        false

    private var previousLdwWarning =
        false

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            result ->
            val cameraGranted =
                result[
                    Manifest.permission.CAMERA
                ] ==
                    true ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA,
                    ) ==
                        PackageManager.PERMISSION_GRANTED

            if (
                !cameraGranted
            ) {
                status.text =
                    "CẦN QUYỀN CAMERA"

                return@registerForActivityResult
            }

            speedProvider.start()

            loadModels()
        }

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(
            savedInstanceState
        )

        speedProvider =
            AdasSpeedProvider(
                this
            )

        calibrator =
            AdasAutoCalibrator(
                this
            )

        voice =
            GoogleAdasVoice(
                this
            )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        @Suppress(
            "DEPRECATION"
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        buildUi()

        requestPermissionsAndStart()

        mainHandler.post(
            heartbeat
        )
    }

    private fun requestPermissionsAndStart() {
        val camera =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA,
            ) ==
                PackageManager.PERMISSION_GRANTED

        val location =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) ==
                PackageManager.PERMISSION_GRANTED

        if (
            camera
        ) {
            if (
                location
            ) {
                speedProvider.start()
            }

            loadModels()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    private fun buildUi() {
        val root =
            FrameLayout(
                this
            ).apply {
                setBackgroundColor(
                    Color.BLACK
                )
            }

        previewView =
            PreviewView(
                this
            ).apply {
                implementationMode =
                    PreviewView.ImplementationMode.PERFORMANCE

                scaleType =
                    PreviewView.ScaleType.FILL_CENTER
            }

        overlay =
            DetectionOverlay(
                this
            )

        status =
            TextView(
                this
            ).apply {
                setTextColor(
                    Color.WHITE
                )

                setBackgroundColor(
                    Color.argb(
                        175,
                        0,
                        0,
                        0,
                    )
                )

                textSize =
                    13f

                setPadding(
                    18,
                    12,
                    18,
                    12,
                )

                text =
                    "TRUNGKIEN ADAS V2.0.1.1 FULL\nĐANG NẠP..."
            }

        modeButton =
            TextView(
                this
            ).apply {
                setTextColor(
                    Color.WHITE
                )

                setBackgroundColor(
                    Color.argb(
                        185,
                        0,
                        120,
                        85,
                    )
                )

                textSize =
                    14f

                setPadding(
                    20,
                    13,
                    20,
                    13,
                )

                text =
                    "DRIVE"

                setOnClickListener {
                    debugMode =
                        !debugMode

                    text =
                        if (
                            debugMode
                        ) {
                            "DEBUG"
                        } else {
                            "DRIVE"
                        }

                    this@MainActivity.overlay.setDebugMode(
                        debugMode
                    )
                }
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
                Gravity.TOP or
                    Gravity.START,
            )
        )

        root.addView(
            modeButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or
                    Gravity.END,
            ).apply {
                topMargin =
                    12

                marginEnd =
                    12
            }
        )

        setContentView(
            root
        )
    }

    private fun loadModels() {
        status.text =
            "TRUNGKIEN ADAS V2.0.1.1 FULL\nĐANG NẠP YOLOX + UFLD..."

        modelExecutor.execute {
            runCatching {
                val roadFile =
                    copyAsset(
                        "yolox_tiny.onnx",
                        "yolox_tiny_adas_v20.onnx",
                        5_000_000L,
                    )

                val laneFile =
                    copyLaneAsset()

                YoloXTinyDetector(
                    roadFile
                ) to
                    UfldLaneDetector(
                        laneFile
                    )
            }.onSuccess {
                models ->
                roadDetector =
                    models.first

                laneDetector =
                    models.second

                runOnUiThread {
                    startCamera()
                }
            }.onFailure {
                error ->
                runOnUiThread {
                    status.text =
                        "LỖI MODEL\n" +
                            "${error.javaClass.simpleName}: ${error.message}"
                }
            }
        }
    }

    private fun copyAsset(
        assetName: String,
        targetName: String,
        minimumSize: Long,
    ): File {
        val target =
            File(
                filesDir,
                targetName,
            )

        assets.open(
            assetName
        ).use {
            input ->
            target.outputStream().use {
                output ->
                input.copyTo(
                    output,
                    256 *
                        1024,
                )
            }
        }

        require(
            target.length() >
                minimumSize
        )

        return target
    }

    private fun copyLaneAsset(): File {
        val target =
            File(
                filesDir,
                "ufld_culane_adas_v20.onnx",
            )

        if (
            target.exists() &&
            target.length() ==
                UFLD_FILE_SIZE
        ) {
            return target
        }

        assets.open(
            "ufld_culane.onnx"
        ).use {
            input ->
            target.outputStream().use {
                output ->
                input.copyTo(
                    output,
                    512 *
                        1024,
                )
            }
        }

        require(
            target.length() ==
                UFLD_FILE_SIZE
        )

        return target
    }

    private fun startCamera() {
        val future =
            ProcessCameraProvider.getInstance(
                this
            )

        future.addListener({
            runCatching {
                val provider =
                    future.get()

                val preview =
                    Preview.Builder()
                        .setTargetAspectRatio(
                            AspectRatio.RATIO_4_3
                        )
                        .build()
                        .also {
                            it.surfaceProvider =
                                previewView.surfaceProvider
                        }

                val selector =
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy(
                                AspectRatio.RATIO_4_3,
                                AspectRatioStrategy.FALLBACK_RULE_AUTO,
                            )
                        )
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(
                                    640,
                                    480,
                                ),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            )
                        )
                        .build()

                val analysis =
                    ImageAnalysis.Builder()
                        .setResolutionSelector(
                            selector
                        )
                        .setOutputImageFormat(
                            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )
                        .setBackpressureStrategy(
                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build()

                analysis.setAnalyzer(
                    analyzerExecutor,
                    ::analyze,
                )

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )

                cameraRunning =
                    true
            }.onFailure {
                error ->
                status.text =
                    "LỖI CAMERA\n${error.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(
        image: ImageProxy,
    ) {
        val road =
            roadDetector

        val lane =
            laneDetector

        if (
            road ==
                null ||
            lane ==
                null
        ) {
            image.close()
            return
        }

        try {
            val roadResult =
                road.detect(
                    image
                )

            roadInferenceMs =
                roadResult.inferenceMs

            roadCounter++

            val filtered =
                calibrator.filterSelfVehicle(
                    roadResult.detections
                )

            val snapshot =
                decisionEngine.update(
                    detections =
                        filtered,
                    lane =
                        calibrator.geometry,
                    hoodTopNorm =
                        calibrator.hoodTopNorm(),
                    speedKph =
                        speedProvider.speedKph,
                    nowMs =
                        SystemClock.elapsedRealtime(),
                )

            latestSnapshot =
                snapshot

            handleWarnings(
                snapshot
            )

            runOnUiThread {
                overlay.updateRoad(
                    roadResult,
                    snapshot,
                )
            }

            analysisCounter++

            if (
                analysisCounter %
                    2L ==
                0L
            ) {
                val laneResult =
                    lane.detect(
                        image
                    )

                laneInferenceMs =
                    laneResult.inferenceMs

                laneCounter++

                calibrator.observe(
                    laneResult
                )

                runOnUiThread {
                    overlay.updateLane(
                        laneResult
                    )
                }
            }
        } catch (
            error: Throwable
        ) {
            beeper.updateFcwLevel(
                0
            )

            runOnUiThread {
                status.text =
                    "AI ERROR\n" +
                        "${error.javaClass.simpleName}: ${error.message}"
            }
        } finally {
            image.close()
        }
    }

    private fun handleWarnings(
        snapshot: AdasSnapshot,
    ) {
        beeper.updateFcwLevel(
            snapshot.warnings.fcwLevel
        )

        if (
            snapshot.warnings.leadMovedEvent
        ) {
            beeper.leadMovedCue()
            voice.leadMoved()
        }

        if (
            snapshot.warnings.voiceFcwEvent
        ) {
            voice.collisionRisk()
        }

        if (
            snapshot.warnings.ldwWarning &&
            !previousLdwWarning
        ) {
            beeper.laneCue()
        }

        if (
            snapshot.warnings.voiceLdwEvent
        ) {
            voice.laneDeparture()
        }

        if (
            snapshot.warnings.hmwWarning &&
            !previousHmwWarning &&
            snapshot.warnings.fcwLevel ==
                0
        ) {
            beeper.headwayCue()
        }

        previousHmwWarning =
            snapshot.warnings.hmwWarning

        previousLdwWarning =
            snapshot.warnings.ldwWarning
    }

    private val heartbeat =
        object : Runnable {
            override fun run() {
                val snapshot =
                    latestSnapshot

                val lane =
                    snapshot.lane

                status.text =
                    if (
                        debugMode
                    ) {
                        buildString {
                            append(
                                "TRUNGKIEN ADAS V2.0.1.1 FULL • DEBUG\n"
                            )

                            append(
                                "GPS "
                            )

                            append(
                                snapshot.speedKph
                                    ?.roundToInt()
                                    ?: -1
                            )

                            append(
                                " km/h • ROAD "
                            )

                            append(
                                roadInferenceMs
                                    .roundToInt()
                            )

                            append(
                                " ms #"
                            )

                            append(
                                roadCounter
                            )

                            append(
                                "\nAUTO "
                            )

                            append(
                                if (
                                    lane.locked
                                ) {
                                    "LOCK"
                                } else {
                                    "LEARN"
                                }
                            )

                            append(
                                " H="
                            )

                            append(
                                String.format(
                                    Locale.US,
                                    "%.2f",
                                    lane.horizonNorm,
                                )
                            )

                            append(
                                " ROLL="
                            )

                            append(
                                String.format(
                                    Locale.US,
                                    "%.1f°",
                                    lane.rollDeg,
                                )
                            )

                            append(
                                " CONF="
                            )

                            append(
                                (
                                    lane.confidence *
                                        100f
                                    )
                                    .roundToInt()
                            )

                            append(
                                "%"
                            )

                            append(
                                "\nLANE "
                            )

                            append(
                                laneInferenceMs
                                    .roundToInt()
                            )

                            append(
                                " ms #"
                            )

                            append(
                                laneCounter
                            )

                            append(
                                " • HOOD "
                            )

                            append(
                                String.format(
                                    Locale.US,
                                    "%.2f",
                                    snapshot.hoodTopNorm,
                                )
                            )

                            append(
                                "\n"
                            )

                            append(
                                snapshot.debugText
                            )

                            appendLeadNumbers(
                                snapshot
                            )
                        }
                    } else {
                        buildString {
                            append(
                                "ADAS V2.0.1"
                            )

                            append(
                                " • "
                            )

                            append(
                                snapshot.speedKph
                                    ?.roundToInt()
                                    ?: 0
                            )

                            append(
                                " km/h"
                            )

                            append(
                                if (
                                    lane.locked
                                ) {
                                    " • CAL"
                                } else {
                                    " • CAL..."
                                }
                            )

                            appendLeadNumbers(
                                snapshot
                            )
                        }
                    }

                mainHandler.postDelayed(
                    this,
                    1_000L,
                )
            }
        }

    private fun StringBuilder.appendLeadNumbers(
        snapshot: AdasSnapshot,
    ) {
        val lead =
            snapshot.lead

        if (
            lead !=
            null
        ) {
            append(
                "\nFRONT ≈ "
            )

            append(
                String.format(
                    Locale.US,
                    "%.1f m",
                    lead.distanceMeters,
                )
            )

            if (
                snapshot.headwaySeconds !=
                null
            ) {
                append(
                    " • HMW "
                )

                append(
                    String.format(
                        Locale.US,
                        "%.1f s",
                        snapshot.headwaySeconds,
                    )
                )
            }

            if (
                snapshot.ttcSeconds !=
                null
            ) {
                append(
                    " • TTC "
                )

                append(
                    String.format(
                        Locale.US,
                        "%.1f s",
                        snapshot.ttcSeconds,
                    )
                )

                append(
                    " • FCW "
                )

                append(
                    snapshot.warnings.fcwLevel
                )
            }
        }

        if (
            snapshot.timeToLaneCrossSeconds !=
            null
        ) {
            append(
                "\nTLC "
            )

            append(
                String.format(
                    Locale.US,
                    "%.1f s",
                    snapshot.timeToLaneCrossSeconds,
                )
            )
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(
            heartbeat
        )

        beeper.updateFcwLevel(
            0
        )

        beeper.close()

        voice.close()

        speedProvider.stop()

        roadDetector?.close()

        laneDetector?.close()

        roadDetector =
            null

        laneDetector =
            null

        analyzerExecutor.shutdownNow()

        modelExecutor.shutdownNow()

        super.onDestroy()
    }

    companion object {
        private const val UFLD_FILE_SIZE =
            178_076_232L
    }
}
