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

    private lateinit var settingsButton:
        TextView

    private val analyzerExecutor =
        Executors.newSingleThreadExecutor()

    private val modelExecutor =
        Executors.newSingleThreadExecutor()

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private lateinit var licenseManager:
        AdasLicenseManager

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

    private var analysisCounter =
        0L

    private var technicalInfo =
        false

    private var previousHmwWarning =
        false

    private var previousLdwWarning =
        false

    private var calibrationWasLocked =
        false

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
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

        licenseManager =
            AdasLicenseManager(
                this
            )

        if (
            !licenseManager.hasAccess()
        ) {
            buildLicenseGate()
            return
        }

        speedProvider =
            AdasSpeedProvider(
                this
            )

        calibrator =
            AdasAutoCalibrator(
                this
            )

        calibrationWasLocked =
            calibrator.geometry.locked

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

        licenseManager.startTrialClock()

        requestPermissionsAndStart()

        mainHandler.post(
            heartbeat
        )
    }

    private fun buildLicenseGate() {
        @Suppress(
            "DEPRECATION"
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        setContentView(
            AdasLicenseGateView(
                context =
                    this,
                licenseManager =
                    licenseManager,
                onActivated = {
                    recreate()
                },
            )
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
                        120,
                        0,
                        0,
                        0,
                    )
                )

                textSize =
                    12f

                setPadding(
                    12,
                    8,
                    12,
                    8,
                )

                text =
                    "TrungKien ADAS • ĐANG NẠP..."
            }

        settingsButton =
            TextView(
                this
            ).apply {
                setTextColor(
                    Color.WHITE
                )

                setBackgroundColor(
                    Color.argb(
                        195,
                        0,
                        105,
                        92,
                    )
                )

                textSize =
                    14f

                setPadding(
                    18,
                    12,
                    18,
                    12,
                )

                text =
                    "⚙ CÀI ĐẶT"

                setOnClickListener {
                    showSettings()
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
                Gravity.BOTTOM or
                    Gravity.START,
            ).apply {
                bottomMargin =
                    10

                marginStart =
                    10
            }
        )

        root.addView(
            settingsButton,
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

    private fun showSettings() {
        licenseManager.stopTrialClock()

        AdasSettingsDialog(
            context =
                this,
            licenseManager =
                licenseManager,
            voice =
                voice,
            technicalEnabled =
                technicalInfo,
            onTechnicalChanged = {
                enabled ->
                technicalInfo =
                    enabled

                overlay.setTechnicalInfo(
                    enabled
                )
            },
            onLicenseActivated = {
                recreate()
            },
        ).apply {
            setOnDismissListener {
                if (
                    !licenseManager.isLicensed() &&
                    licenseManager.hasAccess()
                ) {
                    licenseManager.startTrialClock()
                }
            }

            show()
        }
    }

    private fun loadModels() {
        status.text =
            "TrungKien ADAS • ĐANG NẠP AI..."

        modelExecutor.execute {
            runCatching {
                val roadFile =
                    copyAsset(
                        "yolox_tiny.onnx",
                        "yolox_tiny_trungkien_adas.onnx",
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
            }.onSuccess { models ->
                roadDetector =
                    models.first

                laneDetector =
                    models.second

                runOnUiThread {
                    startCamera()
                }
            }.onFailure { error ->
                runOnUiThread {
                    status.text =
                        "LỖI MODEL • ${error.javaClass.simpleName}: ${error.message}"
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

        if (
            target.exists() &&
            target.length() >
                minimumSize
        ) {
            return target
        }

        assets.open(
            assetName
        ).use { input ->
            target.outputStream().use { output ->
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
                "ufld_culane_trungkien_adas.onnx",
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
        ).use { input ->
            target.outputStream().use { output ->
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
            }.onFailure { error ->
                status.text =
                    "LỖI CAMERA • ${error.message}"
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

                val lockedNow =
                    calibrator.geometry.locked

                if (
                    !calibrationWasLocked &&
                    lockedNow
                ) {
                    calibrationWasLocked =
                        true

                    voice.calibrationSuccess()

                    runOnUiThread {
                        overlay.showCalibrationSuccess()
                    }
                }

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
                    "AI ERROR • ${error.javaClass.simpleName}: ${error.message}"
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
            voice.headwayTooClose()
        }

        previousHmwWarning =
            snapshot.warnings.hmwWarning

        previousLdwWarning =
            snapshot.warnings.ldwWarning
    }

    private val heartbeat =
        object : Runnable {
            override fun run() {
                licenseManager.consumeTrialNow()

                if (
                    !licenseManager.hasAccess()
                ) {
                    beeper.updateFcwLevel(
                        0
                    )

                    recreate()
                    return
                }

                val snapshot =
                    latestSnapshot

                status.text =
                    if (
                        technicalInfo
                    ) {
                        buildString {
                            append(
                                "TrungKien ADAS • "
                            )

                            append(
                                licenseStatusText()
                            )

                            append(
                                " • GPS "
                            )

                            append(
                                snapshot.speedKph
                                    ?.roundToInt()
                                    ?: -1
                            )

                            append(
                                " km/h • AI "
                            )

                            append(
                                roadInferenceMs
                                    .roundToInt()
                            )

                            append(
                                "ms • LANE "
                            )

                            append(
                                laneInferenceMs
                                    .roundToInt()
                            )

                            append(
                                "ms • CAL "
                            )

                            append(
                                if (
                                    snapshot.lane.locked
                                ) {
                                    "OK"
                                } else {
                                    "${snapshot.lane.samples}/12"
                                }
                            )

                            append(
                                " • "
                            )

                            append(
                                snapshot.debugText
                            )
                        }
                    } else {
                        buildString {
                            append(
                                "TrungKien ADAS • "
                            )

                            append(
                                licenseStatusText()
                            )

                            append(
                                " • "
                            )

                            append(
                                if (
                                    snapshot.lane.locked
                                ) {
                                    "CAL OK"
                                } else {
                                    "ĐANG HIỆU CHỈNH"
                                }
                            )
                        }
                    }

                mainHandler.postDelayed(
                    this,
                    1_000L,
                )
            }
        }

    private fun licenseStatusText(): String {
        if (
            licenseManager.isLicensed()
        ) {
            return licenseManager.licenseSummary()
        }

        val totalSeconds =
            (
                licenseManager.remainingTrialMs() +
                    999L
                ) /
                1000L

        return "DÙNG THỬ %02d:%02d".format(
            Locale.US,
            totalSeconds /
                60L,
            totalSeconds %
                60L,
        )
    }

    override fun onResume() {
        super.onResume()

        if (
            ::licenseManager.isInitialized &&
            licenseManager.hasAccess()
        ) {
            licenseManager.startTrialClock()
        }
    }

    override fun onPause() {
        if (
            ::licenseManager.isInitialized
        ) {
            licenseManager.stopTrialClock()
        }

        super.onPause()
    }

    override fun onDestroy() {
        if (
            ::licenseManager.isInitialized
        ) {
            licenseManager.stopTrialClock()
        }

        mainHandler.removeCallbacks(
            heartbeat
        )

        beeper.updateFcwLevel(
            0
        )

        beeper.close()

        if (
            ::voice.isInitialized
        ) {
            voice.close()
        }

        if (
            ::speedProvider.isInitialized
        ) {
            speedProvider.stop()
        }

        roadDetector?.close()

        laneDetector?.close()

        analyzerExecutor.shutdownNow()

        modelExecutor.shutdownNow()

        super.onDestroy()
    }

    companion object {
        private const val UFLD_FILE_SIZE =
            178_076_232L
    }
}
