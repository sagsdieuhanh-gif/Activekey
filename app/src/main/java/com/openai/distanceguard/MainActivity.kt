package com.openai.distanceguard

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
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
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlayView
    private lateinit var modelStatus: TextView
    private lateinit var pedestrianStatus: TextView
    private lateinit var laneModelStatus: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var summaryStatus: TextView
    private lateinit var speedText: TextView
    private lateinit var distanceText: TextView
    private lateinit var motionText: TextView
    private lateinit var laneText: TextView
    private lateinit var riskText: TextView
    private lateinit var muteButton: TextView
    private lateinit var correctionButton: TextView
    private lateinit var signButton: TextView
    private lateinit var hoodDoneButton: TextView
    private lateinit var licenseGate: LicenseGate
    private lateinit var hoodStore: HoodExclusionStore
    private lateinit var thermalGuard: ThermalGuard
    private lateinit var trafficSignStore: TrafficSignStateStore
    private lateinit var debugLogger: AdasDebugLogger
    private val laneHybridFusion = LaneHybridFusion()
    private val followingDistanceAdvisor = FollowingDistanceAdvisor()
    private val speedLimitMonitor = SpeedLimitMonitor()
    private val trafficSignState = AtomicReference(TrafficSignState(enabled = false))
    @Volatile private var signSenseEngine: SignSenseEngine? = null
    private var lastSignSubmitNs = 0L
    private var accessFeaturesStarted = false
    private var licenseDialog: AlertDialog? = null
    private var lastProtectedFrameNs = 0L
    private var urgentVisionUntilElapsedMs = 0L
    private var lastUiRenderElapsedMs = 0L
    private var hoodEditStartBoundary = HoodExclusionStore.DEFAULT_BOUNDARY
    private var displayEcoMode = false
    private var debugMode = false

    private val inferenceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DistanceGuard-Frame").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val laneExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DistanceGuard-LaneCore").apply { priority = Thread.NORM_PRIORITY }
    }
    private val roadUserExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DistanceGuard-Pedestrian").apply { priority = Thread.NORM_PRIORITY }
    }
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "DistanceGuard-IO") }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var laneCoreEngine: LaneSenseEngine? = null
    @Volatile private var visionEngine: RoadSenseEngine? = null
    @Volatile private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var corrector = AdaptiveDistanceCorrector()
    private val laneModelLoading = AtomicBoolean(false)
    private val laneInferenceBusy = AtomicBoolean(false)
    private val roadUserInferenceBusy = AtomicBoolean(false)
    private val cameraStarting = AtomicBoolean(false)
    private val preprocessor = RgbaPreprocessor(320)
    private val roadSensePreprocessor = RoadSensePreprocessor()
    private val roadUserTemporalFilter = RoadUserTemporalFilter()
    private val laneCoreEnginePreprocessor = LaneSensePreprocessor()
    private val laneCoreEngineInterpreter = LaneSenseInterpreter()
    private lateinit var calibrationStore: CalibrationStore
    private lateinit var correctionStore: DistanceCorrectionStore
    private lateinit var estimator: GroundPlaneDistanceEstimator
    private lateinit var leadProjector: MetricLeadProjector
    private lateinit var targetSelector: TargetSelector
    private lateinit var pedestrianSelector: PedestrianHazardSelector
    private lateinit var vehicleRangeFusion: VehicleRangeFusion
    private lateinit var pedestrianRangeStabilizer: GroundRangeStabilizer
    private lateinit var sideCollisionMonitor: SideCollisionMonitor
    private lateinit var autoCalibrator: AutoCameraCalibrator
    private val autoDistanceCalibrator = AutoDistanceCalibrator()
    private val tracker = DistanceTracker()
    private val riskStabilizer = RiskStabilizer()
    private val pedestrianTracker = DistanceTracker()
    private val laneDetector = LaneDetector(320)
    private lateinit var speaker: WarningSpeaker
    private lateinit var gpsProvider: GpsSpeedProvider

    private val latestUi = AtomicReference<FrameUi?>(null)
    private val latestTargetForCalibration = AtomicReference<TargetMeasurement?>(null)
    private val latestLaneForCalibration = AtomicReference<LaneState?>(null)
    private data class StampedLane(val state: LaneState, val timestampNs: Long)
    private data class StampedLead(val lead: MetricLead?, val timestampNs: Long)
    private data class StampedPedestrians(
        val detections: List<Detection>,
        val timestampNs: Long,
        val nightMode: Boolean,
        val meanLuma: Float,
    )
    private val latestLaneSenseLane = AtomicReference<StampedLane?>(null)
    private val latestMetricLead = AtomicReference<StampedLead?>(null)
    private val latestRoadUsers = AtomicReference<StampedPedestrians?>(null)
    private val uiPosted = AtomicBoolean(false)
    private var lastFrameNs = 0L
    private var smoothedFps = 0f
    private var aiThreadPrioritySet = false
    private var lastLaneSubmitNs = 0L
    private var lastRoadUserSubmitNs = 0L
    private var roadUserPassCounter = 0
    private var lastVehicleMeasurementNs = 0L
    private var lastRangeTrackId = -1
    private var lastPedestrianMeasurementNs = 0L
    @Volatile private var visionErrorDetail: String? = null
    private var consecutiveVisionFailures = 0
    @Volatile private var lastGpsSnapshot = GpsSpeedSnapshot(status = GpsStatus.SEARCHING)
    @Volatile private var lastAutoCalibrationState = AutoCalibrationState.CALIBRATING
    private var lastCalibrationPersistNs = 0L

    private val trialTicker = object : Runnable {
        override fun run() {
            if (::licenseGate.isInitialized) {
                val status = licenseGate.tickForegroundUsage()
                refreshCompactStatus()
                if (!status.allowed) {
                    if (accessFeaturesStarted) stopProtectedFeatures()
                    showLicenseDialog(blocking = true)
                }
            }
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    private data class FrameUi(
        val detections: List<Detection>,
        val target: TargetMeasurement?,
        val track: TrackState?,
        val rangeQuality: RangeQuality?,
        val rangeSource: RangeSource?,
        val rangeUncertaintyM: Float?,
        val followingAdvice: FollowingDistanceAdvice,
        val gps: GpsSpeedSnapshot,
        val metrics: DrivingMetrics,
        val risk: RiskLevel,
        val pedestrianHazard: PedestrianHazard?,
        val pedestrianTrack: TrackState?,
        val pedestrianRangeQuality: RangeQuality?,
        val pedestrianRisk: PedestrianRiskLevel,
        val sideHazards: List<SideCollisionHazard>,
        val lane: LaneState,
        val sourceAspect: Float,
        val inferenceMs: Float,
        val fps: Float,
        val calibration: Calibration,
        val autoCalibrationState: AutoCalibrationState,
    )

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
        if (cameraGranted && ::licenseGate.isInitialized && licenseGate.status().allowed) {
            previewView.post { startCamera() }
        } else if (!cameraGranted) {
            modelStatus.text = "Cần quyền CAMERA để hoạt động"
            Toast.makeText(this, "Ứng dụng cần quyền camera để nhận diện xe phía trước.", Toast.LENGTH_LONG).show()
        }

        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && ::licenseGate.isInitialized && licenseGate.status().allowed) {
            gpsProvider.start()
        } else if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            updateGpsChip(
                GpsSpeedSnapshot(
                    status = if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        GpsStatus.COARSE_ONLY
                    } else GpsStatus.NO_PERMISSION
                )
            )
        }
    }

    private val gpsStatusTicker = object : Runnable {
        override fun run() {
            if (::gpsProvider.isInitialized) updateGpsChip(gpsProvider.snapshot())
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        licenseGate = LicenseGate(this)
        hoodStore = HoodExclusionStore(this)
        thermalGuard = ThermalGuard(this) { runOnUiThread { applyDisplayPowerPolicy(); refreshCompactStatus() } }
        trafficSignStore = TrafficSignStateStore(this)
        debugLogger = AdasDebugLogger(this)
        trafficSignState.set(trafficSignStore.loadState())
        displayEcoMode = getSharedPreferences("display_power_v12", android.content.Context.MODE_PRIVATE).getBoolean("eco_mode", false)
        calibrationStore = CalibrationStore(this)
        correctionStore = DistanceCorrectionStore(this)
        corrector = AdaptiveDistanceCorrector(correctionStore.load())
        estimator = GroundPlaneDistanceEstimator(calibrationStore.load())
        laneDetector.neutralOffsetFraction = estimator.calibration.laneNeutralOffsetFraction
        leadProjector = MetricLeadProjector(estimator, corrector)
        targetSelector = TargetSelector(estimator, corrector)
        pedestrianSelector = PedestrianHazardSelector(estimator, corrector)
        vehicleRangeFusion = VehicleRangeFusion(estimator, corrector)
        pedestrianRangeStabilizer = GroundRangeStabilizer(estimator, corrector)
        sideCollisionMonitor = SideCollisionMonitor(estimator) { corrector }
        autoCalibrator = AutoCameraCalibrator(this, estimator.calibration)
        speaker = WarningSpeaker(this) { status, ok ->
            runOnUiThread {
                // Technical voice details stay in Settings; only warn when Vietnamese is unavailable.
                if (!ok && !status.startsWith("Đang")) {
                    Toast.makeText(this, "$status. Mở cài đặt TTS của Android để cài giọng Việt.", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { muted = calibrationStore.isMuted() }
        gpsProvider = GpsSpeedProvider(this) { snapshot ->
            runOnUiThread { updateGpsChip(snapshot) }
        }

        buildUi()
        overlay.setCalibration(estimator.calibration)
        overlay.setHoodExclusion(hoodStore.boundaryY)
        updateMuteButton()
        updateCorrectionButton()
        updateSignButton()
        applyDisplayPowerPolicy()
        val access = licenseGate.status()
        if (access.allowed) startProtectedFeatures() else showLicenseDialog(blocking = true)
    }

    override fun onResume() {
        super.onResume()
        if (::licenseGate.isInitialized) licenseGate.startUsageSession()
        if (::thermalGuard.isInitialized) thermalGuard.start()
        if (::autoCalibrator.isInitialized && licenseGate.status().allowed) autoCalibrator.start()
        if (::gpsProvider.isInitialized && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && licenseGate.status().allowed) gpsProvider.start()
        mainHandler.removeCallbacks(gpsStatusTicker)
        mainHandler.post(gpsStatusTicker)
        mainHandler.removeCallbacks(trialTicker)
        mainHandler.post(trialTicker)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(gpsStatusTicker)
        mainHandler.removeCallbacks(trialTicker)
        if (::licenseGate.isInitialized) licenseGate.pauseUsageSession()
        if (::thermalGuard.isInitialized) thermalGuard.stop()
        if (::autoCalibrator.isInitialized) autoCalibrator.stop()
        if (::gpsProvider.isInitialized) gpsProvider.stop()
        super.onPause()
    }

    private fun startProtectedFeatures() {
        if (!licenseGate.status().allowed) {
            showLicenseDialog(blocking = true)
            return
        }
        accessFeaturesStarted = true
        speaker.suppressFor(2_800L)
        if (visionEngine == null) loadVisionCore()
        if (laneCoreEngine == null) loadLaneCore()
        requestInitialPermissionsAndStart()
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) gpsProvider.start()
        if (trafficSignStore.enabled) ensureSignSenseEngine()
        refreshCompactStatus()
    }

    private fun stopProtectedFeatures() {
        accessFeaturesStarted = false
        runCatching { cameraProvider?.unbindAll() }
        gpsProvider.stop()
        targetSelector.reset()
        sideCollisionMonitor.reset()
        laneHybridFusion.reset()
        tracker.reset()
        riskStabilizer.reset()
        pedestrianTracker.reset()
        followingDistanceAdvisor.reset()
        speedLimitMonitor.reset()
        stopSignSenseEngine()
        latestUi.set(null)
        latestTargetForCalibration.set(null)
        speaker.onNoTarget()
        runOnUiThread {
            distanceText.text = "— m"
            motionText.text = "Hết thời gian dùng thử"
            riskText.text = "NHẬP KEY ADMIN ĐỂ TIẾP TỤC"
            riskText.setTextColor(Color.rgb(255, 193, 7))
            overlay.update(
                emptyList(), null, null, RiskLevel.CLEAR, null,
                null, null, null, PedestrianRiskLevel.CLEAR,
                emptyList(), LaneState(null, null, 0f, 0f, LaneDepartureLevel.UNAVAILABLE, null),
                preprocessor.displayAspect,
            )
            refreshCompactStatus()
        }
    }

    private fun showLicenseDialog(blocking: Boolean) {
        if (!::licenseGate.isInitialized) return
        if (licenseDialog?.isShowing == true) return
        val status = licenseGate.status()
        if (status.state == LicenseGate.AccessState.LICENSED && blocking) return

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val statusText = TextView(this).apply {
            text = when (status.state) {
                LicenseGate.AccessState.LICENSED -> "Thiết bị đã được kích hoạt."
                LicenseGate.AccessState.TRIAL -> "Bản dùng thử còn ${formatTrial(status.remainingTrialMs)}. Có thể nhập key admin ngay để kích hoạt."
                LicenseGate.AccessState.EXPIRED -> "Đã hết 5 phút dùng thử. Camera và các chức năng phân tích đã tạm khóa."
            }
            setTextColor(Color.DKGRAY)
            textSize = 14f
        }
        val device = TextView(this).apply {
            text = "MÃ THIẾT BỊ\n${status.deviceCode}"
            setTextColor(Color.rgb(20, 70, 150))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(12))
            setTextIsSelectable(true)
        }
        val keyInput = EditText(this).apply {
            hint = "Dán key do admin cấp"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            minLines = 2
            textSize = 14f
        }
        box.addView(statusText)
        box.addView(device)
        box.addView(keyInput, LinearLayout.LayoutParams(-1, -2))

        val builder = AlertDialog.Builder(this)
            .setTitle("BẢN QUYỀN / KEY • V13")
            .setView(box)
            .setPositiveButton("KÍCH HOẠT", null)
        if (!blocking) builder.setNegativeButton("ĐÓNG", null)
        val dialog = builder.create()
        dialog.setCancelable(!blocking)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = keyInput.text?.toString().orEmpty()
                if (raw.isBlank()) {
                    keyInput.error = "Chưa nhập key"
                    return@setOnClickListener
                }
                licenseGate.installKey(raw).onSuccess {
                    Toast.makeText(this, "Kích hoạt thành công.", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    licenseDialog = null
                    startProtectedFeatures()
                }.onFailure {
                    keyInput.error = it.message ?: "Key không hợp lệ"
                }
            }
        }
        dialog.setOnDismissListener { licenseDialog = null }
        licenseDialog = dialog
        dialog.show()
    }

    private fun formatTrial(ms: Long): String {
        val totalSeconds = ((ms + 999L) / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun requestInitialPermissionsAndStart() {
        val missing = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) missing += Manifest.permission.CAMERA
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Android 12+ expects FINE and COARSE in the same runtime request.
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (missing.isEmpty()) {
            previewView.post { startCamera() }
            gpsProvider.start()
        } else {
            permissionsLauncher.launch(missing.distinct().toTypedArray())
        }
    }

    private fun requestPreciseLocation() {
        permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        )
    }

    private fun buildUi() {
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        overlay = DetectionOverlayView(this)
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // Internal status views are kept for detailed diagnostics but no longer clutter the camera screen.
        modelStatus = chip("XE PHÍA TRƯỚC: đang nạp ROAD CORE…")
        pedestrianStatus = chip("ROAD USERS: đang nạp ROAD CORE…")
        laneModelStatus = chip("LÀN: đang nạp LANE CORE…")
        gpsStatus = chip("GPS đang chuẩn bị…")

        summaryStatus = chip("HỆ THỐNG đang chuẩn bị…").apply {
            textSize = if (portrait) 12f else 13f
            setOnClickListener { showSystemStatusDialog() }
            setOnLongClickListener {
                debugMode = !debugMode
                this@MainActivity.overlay.setDebugStatus(debugMode)
                Toast.makeText(
                    this@MainActivity,
                    if (debugMode) "DEBUG ADAS: BẬT" else "DEBUG ADAS: TẮT",
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
        }
        root.addView(summaryStatus, FrameLayout.LayoutParams(-2, dp(38)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(10)
            topMargin = dp(10)
            if (portrait) rightMargin = dp(100)
        })

        // Compact sound control: large enough to tap while driving, but kept away from the road center.
        muteButton = actionButton("", compact = true).apply {
            contentDescription = "Bật hoặc tắt cảnh báo giọng nói"
            setOnClickListener {
                speaker.muted = !speaker.muted
                calibrationStore.setMuted(speaker.muted)
                updateMuteButton()
            }
        }
        root.addView(muteButton, FrameLayout.LayoutParams(dp(52), dp(44)).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = dp(10)
            topMargin = dp(10)
        })

        hoodDoneButton = actionButton("✓  LƯU VÙNG", compact = true).apply {
            visibility = View.GONE
            contentDescription = "Lưu vùng bỏ qua đầu xe"
            setOnClickListener { finishHoodEdit() }
        }
        root.addView(hoodDoneButton, FrameLayout.LayoutParams(dp(150), dp(44)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(58)
        })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(if (portrait) 12 else 20), dp(7), dp(if (portrait) 12 else 20), dp(7))
            background = roundedBackground(Color.argb(155, 0, 0, 0), 16f)
        }
        val mainMetrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        speedText = TextView(this).apply {
            text = "GPS — km/h"
            setTextColor(Color.WHITE)
            textSize = if (portrait) 17f else 21f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        distanceText = TextView(this).apply {
            text = "— m"
            setTextColor(Color.WHITE)
            textSize = if (portrait) 44f else 42f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        mainMetrics.addView(speedText, LinearLayout.LayoutParams(0, -2, 0.42f))
        mainMetrics.addView(distanceText, LinearLayout.LayoutParams(0, -2, 0.58f))

        motionText = TextView(this).apply {
            text = "Chưa thấy xe ô tô phía trước"
            setTextColor(Color.LTGRAY)
            textSize = if (portrait) 12f else 14f
            gravity = Gravity.CENTER
            maxLines = 2
        }
        laneText = TextView(this).apply {
            text = ""
            visibility = View.GONE
            setTextColor(Color.LTGRAY)
            textSize = if (portrait) 11f else 13f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        riskText = TextView(this).apply {
            text = "HỖ TRỢ LÁI • KHÔNG THAY THẾ NGƯỜI LÁI"
            setTextColor(Color.LTGRAY)
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val noticeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(5))
        }
        noticeRow.addView(riskText, LinearLayout.LayoutParams(-1, -2))

        // Optional sign reader is a separate one-tap feature. When OFF its OCR/sign pipeline is
        // fully closed so it does not consume CPU/GPU or add heat.
        signButton = actionButton("◇  BIỂN BÁO AI: TẮT").apply {
            contentDescription = "Bật hoặc tắt đọc biển báo"
            setOnClickListener { toggleTrafficSignReader() }
        }

        // Frequently used controls stay one tap away. Equal-size buttons make them easier to hit
        // without forcing the driver to search inside a text menu.
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val autoButton = actionButton("◎  AUTO GÓC").apply {
            contentDescription = "Tự cân chỉnh góc camera"
            setOnClickListener { restartAutoCalibration() }
        }
        correctionButton = actionButton("◉  AUTO SAI SỐ").apply {
            contentDescription = "Trạng thái tự hiệu chỉnh khoảng cách"
            setOnClickListener { showAutoDistanceCalibrationDialog() }
        }
        val settingsButton = actionButton("☰  CÀI ĐẶT").apply {
            contentDescription = "Mở bảng chức năng"
            setOnClickListener { showQuickSettings() }
        }
        val actionLp = LinearLayout.LayoutParams(0, dp(46), 1f)
        actionRow.addView(autoButton, LinearLayout.LayoutParams(actionLp).apply { rightMargin = dp(4) })
        actionRow.addView(correctionButton, LinearLayout.LayoutParams(actionLp).apply { leftMargin = dp(2); rightMargin = dp(2) })
        actionRow.addView(settingsButton, LinearLayout.LayoutParams(actionLp).apply { leftMargin = dp(4) })

        panel.addView(mainMetrics, LinearLayout.LayoutParams(-1, -2))
        panel.addView(motionText, LinearLayout.LayoutParams(-1, -2))
        panel.addView(laneText, LinearLayout.LayoutParams(-1, -2))
        panel.addView(noticeRow, LinearLayout.LayoutParams(-1, -2))
        panel.addView(signButton, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(5) })
        panel.addView(actionRow, LinearLayout.LayoutParams(-1, -2))

        root.addView(panel, FrameLayout.LayoutParams(if (portrait) -1 else dp(520), -2).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
            if (portrait) {
                leftMargin = dp(10)
                rightMargin = dp(10)
            }
        })

        setContentView(root)
        refreshCompactStatus()
    }

    private fun showQuickSettings() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        fun section(title: String, subtitle: String) {
            content.addView(TextView(this).apply {
                text = title
                setTextColor(Color.rgb(28, 28, 30))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
                setPadding(dp(2), dp(12), dp(2), 0)
            })
            content.addView(TextView(this).apply {
                text = subtitle
                setTextColor(Color.rgb(112, 112, 117))
                textSize = 12f
                setPadding(dp(2), dp(2), dp(2), dp(7))
            })
        }

        var dialog: AlertDialog? = null
        fun addAction(title: String, detail: String, action: () -> Unit) {
            val button = settingsActionButton(title, detail).apply {
                setOnClickListener {
                    dialog?.dismiss()
                    action()
                }
            }
            content.addView(button, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })
        }

        section("TRẠNG THÁI & TỰ ĐỘNG", "Các chức năng dùng thường xuyên khi gắn điện thoại lên xe")
        addAction("◈  TRẠNG THÁI HỆ THỐNG", "Core • Lane • GPS • nhiệt máy • giọng cảnh báo") { showSystemStatusDialog() }
        addAction("◎  TỰ CÂN CHỈNH GÓC CAMERA", "Học lại pitch / roll / yaw theo tư thế gắn hiện tại") { restartAutoCalibration() }
        addAction(if (displayEcoMode) "☀  TẮT MÀN HÌNH TIẾT KIỆM" else "☾  MÀN HÌNH TIẾT KIỆM", if (displayEcoMode) "Trả độ sáng về tự động của hệ thống" else "Giảm độ sáng khi chạy lâu để giảm nhiệt và hao pin") { toggleDisplayEcoMode() }
        addAction(if (trafficSignStore.enabled) "◇  TẮT ĐỌC BIỂN BÁO AI" else "◇  BẬT ĐỌC BIỂN BÁO AI", "R.420 / R.421 / tốc độ tối đa • tắt hoàn toàn khi không dùng") { toggleTrafficSignReader() }

        section("HIỆU CHỈNH", "V13 tự học sai số khoảng cách và cho phép loại phần đầu xe khỏi vùng đo")
        addAction("▰  VÙNG BỎ QUA ĐẦU XE", "Kéo trực tiếp vạch giới hạn trên camera • phần dưới không đo") { startHoodEdit() }
        addAction("↺  RESET VÙNG ĐẦU XE", "Trả vạch về mức khởi tạo rồi có thể kéo chỉnh lại") { resetHoodExclusion() }
        addAction("⌖  HIỆU CHỈNH TÂM LÀN", "Căn tâm xe khi camera đặt lệch trái hoặc phải") { showLaneCalibrationDialog() }
        addAction("▣  HÌNH HỌC CAMERA", "Chiều cao camera • FOV • góc dự phòng") { showCalibrationDialog() }
        addAction("◉  TỰ HIỆU CHỈNH KHOẢNG CÁCH", "Không nhập mốc tay • xem trạng thái hoặc xóa dữ liệu tự học") { showAutoDistanceCalibrationDialog() }

        section("BẢN QUYỀN", "Bản dùng thử 5 phút • key được admin cấp theo mã thiết bị")
        addAction("🔐  BẢN QUYỀN / KEY", "Xem mã thiết bị và nhập key kích hoạt") { showLicenseDialog(blocking = false) }

        section("GIỌNG CẢNH BÁO", "Nghe thử và quản lý bộ đọc tiếng Việt")
        addAction("▶  THỬ GIỌNG VIỆT", "Phát một mẫu cảnh báo ngắn") {
            val ok = speaker.testVietnamese()
            if (!ok) Toast.makeText(this, speaker.statusText(), Toast.LENGTH_LONG).show()
        }
        addAction("≋  THỬ TOÀN BỘ CẢNH BÁO", "Kiểm tra các câu khoảng cách, nguy cơ và lấn làn") {
            val ok = speaker.testAllWarnings()
            if (!ok) Toast.makeText(this, speaker.statusText(), Toast.LENGTH_LONG).show()
        }
        addAction("⚙  CÀI ĐẶT GIỌNG NÓI ANDROID", "Chọn hoặc cài thêm giọng TTS tiếng Việt") { openAndroidTtsSettings() }
        addAction(if (speaker.muted) "🔊  BẬT CẢNH BÁO GIỌNG NÓI" else "🔇  TẮT CẢNH BÁO GIỌNG NÓI",
            if (speaker.muted) "Khôi phục cảnh báo âm thanh" else "Chỉ tắt tiếng, hệ thống vẫn tiếp tục theo dõi") {
            speaker.muted = !speaker.muted
            calibrationStore.setMuted(speaker.muted)
            updateMuteButton()
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("TRUNGKIEN V13 • ĐIỀU KHIỂN")
            .setView(scroll)
            .setNegativeButton("ĐÓNG", null)
            .create()
        dialog?.setOnShowListener {
            dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(Color.rgb(34, 86, 180))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        }
        dialog?.show()
    }


    private fun startHoodEdit() {
        hoodEditStartBoundary = hoodStore.boundaryY
        overlay.setHoodExclusion(hoodEditStartBoundary)
        overlay.setHoodEditMode(true) { y ->
            hoodStore.boundaryY = y
        }
        hoodDoneButton.visibility = View.VISIBLE
        Toast.makeText(this, "Kéo vạch ngang tới ngay phía trên phần đầu xe. Phần dưới vạch sẽ không được đo.", Toast.LENGTH_LONG).show()
    }

    private fun finishHoodEdit() {
        overlay.setHoodEditMode(false)
        hoodDoneButton.visibility = View.GONE
        overlay.setHoodExclusion(hoodStore.boundaryY)
        if (kotlin.math.abs(hoodStore.boundaryY - hoodEditStartBoundary) >= 0.012f) {
            resetRangeLearningAfterGeometryChange("Đã lưu vùng bỏ qua đầu xe; hệ thống sẽ tự học lại sai số khoảng cách.")
        } else {
            Toast.makeText(this, "Đã lưu vùng bỏ qua đầu xe.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetHoodExclusion() {
        hoodStore.reset()
        overlay.setHoodExclusion(hoodStore.boundaryY)
        resetRangeLearningAfterGeometryChange("Đã reset vùng đầu xe; hệ thống sẽ tự học lại sai số khoảng cách.")
    }

    private fun resetRangeLearningAfterGeometryChange(message: String) {
        correctionStore.clear()
        autoDistanceCalibrator.reset()
        refreshCorrector(resetTracker = true)
        sideCollisionMonitor.reset()
        targetSelector.reset()
        riskStabilizer.reset()
        lastRangeTrackId = -1
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun toggleDisplayEcoMode() {
        displayEcoMode = !displayEcoMode
        getSharedPreferences("display_power_v12", android.content.Context.MODE_PRIVATE).edit().putBoolean("eco_mode", displayEcoMode).apply()
        applyDisplayPowerPolicy()
        Toast.makeText(
            this,
            if (displayEcoMode) "Đã bật màn hình tiết kiệm để giảm nhiệt." else "Đã trả độ sáng về chế độ hệ thống.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun applyDisplayPowerPolicy() {
        val requested = when {
            thermalGuard.mode == ThermalGuard.Mode.VERY_HOT -> 0.26f
            thermalGuard.mode == ThermalGuard.Mode.HOT -> 0.40f
            thermalGuard.mode == ThermalGuard.Mode.BALANCED -> 0.62f
            displayEcoMode -> 0.22f
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        val params = window.attributes
        if (kotlin.math.abs(params.screenBrightness - requested) > 0.01f) {
            params.screenBrightness = requested
            window.attributes = params
        }
    }

    private fun openAndroidTtsSettings() {
        val intents = listOf(
            android.content.Intent("com.android.settings.TTS_SETTINGS"),
            android.content.Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        val opened = intents.any { intent ->
            runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
        }
        if (!opened) {
            Toast.makeText(this, "Không mở được cài đặt TTS trên thiết bị này.", Toast.LENGTH_LONG).show()
        }
    }

    private fun restartAutoCalibration() {
        val current = estimator.calibration.copy(autoCameraCalibrationEnabled = true)
        estimator.calibration = current
        calibrationStore.save(current)
        correctionStore.clear()
        autoDistanceCalibrator.reset()
        refreshCorrector(resetTracker = true)
        autoCalibrator.reset(current)
        laneCoreEnginePreprocessor.resetTemporalState()
        laneCoreEngineInterpreter.reset()
        laneDetector.reset()
        sideCollisionMonitor.reset()
        lastAutoCalibrationState = AutoCalibrationState.CALIBRATING
        overlay.setCalibration(current)
        refreshCompactStatus()
        Toast.makeText(this, "Đang tự cân chỉnh góc camera. Giữ điện thoại cố định và hướng thẳng theo đường vài giây.", Toast.LENGTH_LONG).show()
    }

    private fun showSystemStatusDialog() {
        val error = visionErrorDetail
        val message = buildString {
            append("Xe: ").append(modelStatus.text).append("\n")
            append("Làn: ").append(laneModelStatus.text).append("\n")
            append("Vật thể: ").append(pedestrianStatus.text).append("\n")
            append("AUTO góc: ").append(lastAutoCalibrationState.name).append("\n")
            val rangeStats = corrector.stats()
            append("AUTO khoảng cách: ").append(rangeStats.sampleCount).append(" mẫu")
                .append(" • ").append(String.format(Locale.US, "%.3f×", rangeStats.meanRatio)).append("\n")
            append("Pitch/roll/yaw: ")
                .append(String.format(Locale.US, "%.1f° / %.1f° / %.1f°", estimator.calibration.pitchDownDeg, estimator.calibration.rollDeg, estimator.calibration.yawDeg))
                .append("\n")
            append("GPS: ").append(gpsStatus.text).append("\n")
            append("Vùng bỏ đầu xe: dưới ").append(((1f - hoodStore.boundaryY) * 100f).roundToInt()).append("% khung hình\n")
            append("Nhiệt máy: ").append(when (thermalGuard.mode) {
                ThermalGuard.Mode.NORMAL -> "BÌNH THƯỜNG"
                ThermalGuard.Mode.BALANCED -> "ẤM • cân bằng tải"
                ThermalGuard.Mode.HOT -> "NÓNG • giảm tải"
                ThermalGuard.Mode.VERY_HOT -> "RẤT NÓNG • ưu tiên an toàn"
            })
            thermalGuard.batteryTemperatureC?.let { append(String.format(Locale.US, " • %.1f°C", it)) }
            append("\n")
            append("Màn hình tiết kiệm: ").append(if (displayEcoMode) "BẬT" else "TẮT").append("\n")
            val signState = trafficSignStore.refreshRuntimeRules().also { trafficSignState.set(it) }
            append("Đọc biển báo AI: ").append(if (trafficSignStore.enabled) "BẬT" else "TẮT")
            signState.currentSpeedLimitKmh?.let { append(" • giới hạn ").append(it).append(" km/h") }
            signState.inPopulatedArea?.let { append(if (it) " • trong khu đông dân cư" else " • ngoài khu đông dân cư") }
            append("\n")
            val access = licenseGate.status()
            append("Bản quyền: ").append(when (access.state) { LicenseGate.AccessState.LICENSED -> "ĐÃ KÍCH HOẠT"; LicenseGate.AccessState.TRIAL -> "DÙNG THỬ ${formatTrial(access.remainingTrialMs)}"; LicenseGate.AccessState.EXPIRED -> "HẾT DÙNG THỬ" }).append("\n")
            append("Mã thiết bị: ").append(access.deviceCode).append("\n")
            append("Giọng cảnh báo: ").append(speaker.statusText()).append("\n")
            if (debugMode) append("DEBUG log: ").append(debugLogger.path()).append("\n")
            append("Màn hình: ")
            append(if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) "DỌC" else "NGANG")
            append(" • camera tự xoay theo máy")
            if (!error.isNullOrBlank()) {
                append("\n\nChi tiết module người:\n").append(error)
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Trạng thái hệ thống")
            .setMessage(message)
            .setPositiveButton("OK", null)
        if (visionEngine == null) {
            builder.setNeutralButton("KHỞI TẠO LẠI") { _, _ -> loadVisionCore() }
        }
        builder.show()
    }

    private fun refreshCompactStatus() {
        if (!::summaryStatus.isInitialized) return
        val laneCore = if (laneCoreEngine != null) "L ✓" else "L …"
        val vision = if (visionEngine != null) "V ✓" else "V —"
        val gps = when (lastGpsSnapshot.status) {
            GpsStatus.OK -> lastGpsSnapshot.speedKmh?.let { "GPS ${it.roundToInt()}" } ?: "GPS ✓"
            GpsStatus.SEARCHING -> "GPS …"
            GpsStatus.STALE -> "GPS !"
            GpsStatus.DISABLED -> "GPS OFF"
            GpsStatus.COARSE_ONLY, GpsStatus.NO_PERMISSION -> "GPS ?"
        }
        val thermal = when (thermalGuard.mode) {
            ThermalGuard.Mode.NORMAL -> "T✓"
            ThermalGuard.Mode.BALANCED -> "T~"
            ThermalGuard.Mode.HOT -> "T!"
            ThermalGuard.Mode.VERY_HOT -> "T‼"
        }
        val signs = if (trafficSignStore.enabled) "B✓" else "B—"
        val access = licenseGate.status()
        val license = when (access.state) {
            LicenseGate.AccessState.LICENSED -> "KEY✓"
            LicenseGate.AccessState.TRIAL -> "TRIAL ${formatTrial(access.remainingTrialMs)}"
            LicenseGate.AccessState.EXPIRED -> "KEY?"
        }
        summaryStatus.text = "$vision • $laneCore • $gps • $signs • $thermal • $license"
        summaryStatus.setTextColor(if (licenseGate.status().allowed) Color.WHITE else Color.rgb(255, 193, 7))
    }

    private fun loadVisionCore() {
        visionErrorDetail = null
        consecutiveVisionFailures = 0
        pedestrianStatus.text = "ROAD USERS: đang nạp ROAD CORE…"
        refreshCompactStatus()
        roadUserExecutor.execute {
            val created = BundledModelStores.roadUsers(this).mapCatching { file ->
                RoadSenseEngine.create(file)
            }
            runOnUiThread {
                created.onSuccess {
                    visionEngine?.close()
                    visionEngine = it
                    visionErrorDetail = null
                    modelStatus.text = "XE PHÍA TRƯỚC: ROAD CORE ${it.acceleratorName} • sẵn sàng"
                    pedestrianStatus.text = "ROAD USERS: ${it.acceleratorName} • sẵn sàng"
                }.onFailure { error ->
                    visionEngine?.close()
                    visionEngine = null
                    visionErrorDetail = "${error.javaClass.simpleName}: ${error.message ?: "không rõ nguyên nhân"}"
                    modelStatus.text = "XE PHÍA TRƯỚC: ROAD CORE chưa sẵn sàng"
                    pedestrianStatus.text = "ROAD USERS: CORE offline chưa sẵn sàng"
                    Toast.makeText(this, "ROAD CORE chưa sẵn sàng; nhận diện làn dự phòng vẫn hoạt động.", Toast.LENGTH_SHORT).show()
                }
                refreshCompactStatus()
            }
        }
    }

    private fun loadLaneCore() {
        if (laneCoreEngine != null || !laneModelLoading.compareAndSet(false, true)) return
        laneModelStatus.text = "LÀN: đang nạp LANE CORE…"
        refreshCompactStatus()
        ioExecutor.execute {
            val model = BundledModelStores.laneCore(this)
            model.onSuccess { file ->
                laneExecutor.execute {
                    val created = runCatching { LaneSenseEngine.create(file) }
                    runOnUiThread {
                        created.onSuccess {
                            laneCoreEngine?.close()
                            laneCoreEngine = it
                            laneModelLoading.set(false)
                            laneModelStatus.text = "LÀN: LANE CORE ${it.acceleratorName} • sẵn sàng"
                            refreshCompactStatus()
                        }.onFailure { error ->
                            laneModelLoading.set(false)
                            laneModelStatus.text = "LÀN: LANE CORE lỗi • CV dự phòng"
                            refreshCompactStatus()
                            Toast.makeText(this, error.message ?: "Không khởi tạo được LANE CORE", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    laneModelLoading.set(false)
                    laneModelStatus.text = "LÀN: CV dự phòng • model offline thiếu"
                    refreshCompactStatus()
                    Toast.makeText(this, error.message ?: "APK thiếu LANE CORE", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startCamera() {
        if (!licenseGate.status().allowed) return
        if (!hasPermission(Manifest.permission.CAMERA) || !cameraStarting.compareAndSet(false, true)) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                val previewResolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                    )
                    .build()
                val preview = Preview.Builder()
                    .setResolutionSelector(previewResolution)
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val analysisResolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolution)
                    .setTargetRotation(rotation)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(inferenceExecutor, ::analyzeFrame)

                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                CameraFovResolver.landscapeVerticalFovDeg(camera.cameraInfo)?.let { measuredFov ->
                    val current = estimator.calibration
                    if (kotlin.math.abs(measuredFov - current.verticalFovDeg) >= 0.5f) {
                        val updated = current.copy(verticalFovDeg = measuredFov)
                        estimator.calibration = updated
                        calibrationStore.save(updated)
                        autoCalibrator.reset(updated)
                        overlay.setCalibration(updated)
                    }
                }
            } catch (t: Throwable) {
                modelStatus.text = "Không mở được camera"
                Toast.makeText(this, t.message ?: "Camera error", Toast.LENGTH_LONG).show()
            } finally {
                cameraStarting.set(false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (!licenseGate.status().allowed) {
            image.close()
            return
        }
        if (!aiThreadPrioritySet) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE) }
            aiThreadPrioritySet = true
        }

        val started = System.nanoTime()
        val frameTimestamp = image.imageInfo.timestamp.takeIf { it > 0L } ?: started
        val thermalNow = SystemClock.elapsedRealtime()
        val thermalProfile = thermalGuard.profile(
            urgent = thermalNow <= urgentVisionUntilElapsedMs,
            egoSpeedMps = lastGpsSnapshot.usableSpeedMps(thermalNow),
        )
        if (lastProtectedFrameNs != 0L && frameTimestamp - lastProtectedFrameNs < thermalProfile.frameIntervalNs) {
            image.close()
            return
        }
        lastProtectedFrameNs = frameTimestamp
        try {
            val input = preprocessor.preprocess(image)
            estimator.displayAspect = preprocessor.displayAspect
            val timestamp = frameTimestamp
            autoCalibrator.setImageRotation(image.imageInfo.rotationDegrees)
            val nowElapsed = SystemClock.elapsedRealtime()
            val gps = gpsProvider.snapshot(nowElapsed)
            val egoSpeed = gps.usableSpeedMps(nowElapsed)

            // Image-space CV lane is cheap and independent of pinhole pitch. Use it as an observation
            // for auto camera calibration and as a fallback only when Lane Core has no credible lane.
            val cvLane = laneDetector.analyze(input, timestamp, egoSpeed)
            val autoResult = autoCalibrator.refine(
                base = estimator.calibration,
                imageLane = cvLane,
                displayAspect = preprocessor.displayAspect,
                timestampNs = timestamp,
            )
            estimator.calibration = autoResult.calibration
            lastAutoCalibrationState = autoResult.state
            if (timestamp - lastCalibrationPersistNs >= 2_000_000_000L) {
                lastCalibrationPersistNs = timestamp
                calibrationStore.save(autoResult.calibration)
            }
            val calibrationSnapshot = autoResult.calibration

            // Lane Core path uses a horizon/roll-normalized 2:1 road crop instead of
            // stretching the entire 4:3 or portrait image into the model input.
            val laneEngine = laneCoreEngine
            if (laneEngine != null && timestamp - lastLaneSubmitNs >= thermalProfile.laneIntervalNs && laneInferenceBusy.compareAndSet(false, true)) {
                lastLaneSubmitNs = timestamp
                val laneInput = laneCoreEnginePreprocessor.preprocess(image, calibrationSnapshot)
                val aspect = laneCoreEnginePreprocessor.displayAspect
                laneExecutor.execute {
                    try {
                        val road = laneEngine.infer(laneInput)
                        val state = laneCoreEngineInterpreter.interpret(
                            road, calibrationSnapshot, aspect, timestamp, egoSpeed,
                        )
                        latestLaneSenseLane.set(StampedLane(state, timestamp))
                        latestMetricLead.set(StampedLead(road.lead, timestamp))
                        runOnUiThread {
                            laneModelStatus.text = if (state.confidence >= 0.25f) {
                                "LÀN: CORE ${laneEngine.acceleratorName} • ${(state.confidence * 100f).roundToInt()}%"
                            } else {
                                "LÀN: CORE • chưa xác định vạch"
                            }
                        }
                    } catch (t: Throwable) {
                        latestMetricLead.set(StampedLead(null, timestamp))
                        runOnUiThread { laneModelStatus.text = "LÀN: CORE tạm lỗi • CV dự phòng" }
                    } finally {
                        laneInferenceBusy.set(false)
                    }
                }
            }

            // Vision Core is now the PRIMARY object detector for vehicles as well as people/bikes.
            // Vehicle recognition is independent from the metric lane-core lead head.
            val roadUserDetector = visionEngine
            if (roadUserDetector != null && timestamp - lastRoadUserSubmitNs >= thermalProfile.visionIntervalNs && roadUserInferenceBusy.compareAndSet(false, true)) {
                lastRoadUserSubmitNs = timestamp
                val longRangeAllowed = thermalGuard.mode == ThermalGuard.Mode.NORMAL || thermalGuard.mode == ThermalGuard.Mode.BALANCED
                val longRangeStride = if (thermalGuard.mode == ThermalGuard.Mode.NORMAL) 3 else 4
                val longRangeFrontPass = longRangeAllowed &&
                    (egoSpeed ?: 0f) >= 16.5f && (++roadUserPassCounter % longRangeStride == 0)
                val roadUserInput = roadSensePreprocessor.preprocess(image, longRangeFront = longRangeFrontPass)
                roadUserExecutor.execute {
                    val detectorStarted = System.nanoTime()
                    try {
                        val rawThreshold = when {
                            roadUserInput.longRangeFront && roadUserInput.nightMode -> 0.082f
                            roadUserInput.longRangeFront -> 0.100f
                            roadUserInput.nightMode -> 0.10f
                            else -> 0.13f
                        }
                        val rawDetections = roadUserDetector.detect(roadUserInput, rawThreshold)
                        val detections = roadUserTemporalFilter.update(
                            rawDetections, timestamp, roadUserInput.nightMode,
                        )
                        latestRoadUsers.set(
                            StampedPedestrians(
                                detections = detections,
                                timestampNs = timestamp,
                                nightMode = roadUserInput.nightMode,
                                meanLuma = roadUserInput.meanLuma,
                            )
                        )
                        consecutiveVisionFailures = 0
                        visionErrorDetail = null
                        val latency = (System.nanoTime() - detectorStarted) / 1_000_000f
                        runOnUiThread {
                            val people = detections.count { it.classId == VehicleClasses.PERSON }
                            val vehicles = detections.count { it.classId in VehicleClasses.roadVehicles }
                            val mode = buildString {
                                if (roadUserInput.nightMode) append(" • NIGHT AUTO")
                                if (roadUserInput.longRangeFront) append(" • LONG 100m")
                            }
                            modelStatus.text = if (vehicles > 0) {
                                "ROAD CORE: phát hiện $vehicles phương tiện$mode"
                            } else {
                                "ROAD CORE: chưa thấy xe$mode"
                            }
                            pedestrianStatus.text = buildString {
                                append("ROAD USERS: ").append(roadUserDetector.acceleratorName)
                                if (people > 0) append(" • người ").append(people)
                                if (roadUserInput.nightMode) append(" • NIGHT")
                                append(" • ").append(latency.roundToInt()).append(" ms")
                            }
                            refreshCompactStatus()
                        }
                    } catch (t: Throwable) {
                        consecutiveVisionFailures++
                        visionErrorDetail = "${t.javaClass.simpleName}: ${t.message ?: "lỗi ROAD CORE"}"
                        if (consecutiveVisionFailures >= 3) {
                            runCatching { roadUserDetector.close() }
                            visionEngine = null
                            latestRoadUsers.set(null)
                            runOnUiThread {
                                modelStatus.text = "ROAD CORE: tạm dừng • xem ⚙"
                                pedestrianStatus.text = "ROAD USERS: tạm dừng • xem ⚙"
                                refreshCompactStatus()
                            }
                        }
                    } finally {
                        roadUserInferenceBusy.set(false)
                    }
                }
            }

            // Optional Vietnamese sign reader. No snapshots/OCR are created while the button is OFF.
            if (trafficSignStore.enabled) {
                ensureSignSenseEngine()
                val signIntervalNs = thermalProfile.signIntervalNs
                if (timestamp - lastSignSubmitNs >= signIntervalNs) {
                    val submitted = signSenseEngine?.submit(image, timestamp) == true
                    if (submitted) lastSignSubmitNs = timestamp
                }
            }

            val freshLaneSense = latestLaneSenseLane.get()?.takeIf {
                timestamp >= it.timestampNs && timestamp - it.timestampNs <= 1_000_000_000L
            }?.state
            val lane = laneHybridFusion.update(freshLaneSense, cvLane, timestamp)
            latestLaneForCalibration.set(lane)

            val freshLeadStamp = latestMetricLead.get()?.takeIf {
                timestamp >= it.timestampNs && timestamp - it.timestampNs <= 1_000_000_000L
            }
            val freshLead = freshLeadStamp?.lead
            val roadUserStamp = latestRoadUsers.get()?.takeIf {
                timestamp >= it.timestampNs && timestamp - it.timestampNs <= 1_500_000_000L
            }
            val roadUsersRaw = roadUserStamp?.detections ?: emptyList()
            val roadUsers = HoodExclusionFilter.filter(
                roadUsersRaw,
                hoodStore.boundaryY,
                targetSelector.activeTrackId,
            )

            // V3 range pipeline: select the visible front vehicle, robustly stabilize several bbox
            // measurements, then use the metric lane-core lead only as a bounded cross-check. This keeps
            // partial/rear vehicle detections useful while reducing the large long-range jumps that
            // a few bbox pixels can create with monocular pinhole geometry.
            val detectionTimestamp = roadUserStamp?.timestampNs ?: timestamp
            val rawVisionTarget = targetSelector.select(roadUsers, detectionTimestamp, lane)
            val rawTrackId = rawVisionTarget?.detection?.trackId ?: -1
            if (rawTrackId > 0 && lastRangeTrackId > 0 && rawTrackId != lastRangeTrackId) {
                // A confirmed target-ID handover starts a fresh range/TTC history. Without this, the
                // new vehicle inherits the previous vehicle's closing speed and can trigger a false alert.
                vehicleRangeFusion.reset()
                tracker.reset()
                lastVehicleMeasurementNs = 0L
            }
            if (rawTrackId > 0) lastRangeTrackId = rawTrackId
            val fusedRange = vehicleRangeFusion.update(rawVisionTarget, freshLead, detectionTimestamp)

            // V13 automatic distance self-calibration. Use a fresh independent metric lane-core lead
            // as the reference for the raw camera geometry only after the same tracked vehicle has
            // remained stable for several observations. No user-entered distance marker is required.
            val learningLead = freshLeadStamp?.takeIf {
                kotlin.math.abs(detectionTimestamp - it.timestampNs) <= 500_000_000L
            }?.lead
            val cameraGeometryReady = autoResult.state == AutoCalibrationState.READY ||
                autoResult.state == AutoCalibrationState.LANE_ASSISTED
            val learnedSample = if (cameraGeometryReady) autoDistanceCalibrator.observe(
                target = rawVisionTarget,
                lead = learningLead,
                lane = lane,
                rangeQuality = fusedRange?.quality,
                timestampNs = detectionTimestamp,
            ) else null
            if (learnedSample != null) {
                correctionStore.add(learnedSample.rawM, learnedSample.referenceM)
                refreshCorrector(resetTracker = false)
            }

            val visionTarget = fusedRange?.measurement
            val laneCoreEngineFallbackTarget = if (visionTarget == null) leadProjector.project(freshLead, lane) else null
            val target = visionTarget ?: laneCoreEngineFallbackTarget
            val rangeQuality = fusedRange?.quality ?: laneCoreEngineFallbackTarget?.let {
                if ((freshLead?.confidence ?: 0f) >= 0.62f) RangeQuality.MEDIUM else RangeQuality.APPROXIMATE
            }
            val rangeSource = fusedRange?.source ?: laneCoreEngineFallbackTarget?.let { RangeSource.LANE_CORE }
            val rangeUncertaintyM = fusedRange?.uncertaintyM
            latestTargetForCalibration.set(target)
            val track = when {
                visionTarget != null && detectionTimestamp > lastVehicleMeasurementNs -> {
                    lastVehicleMeasurementNs = detectionTimestamp
                    tracker.update(visionTarget.correctedDistanceM, detectionTimestamp)
                }
                laneCoreEngineFallbackTarget != null && (freshLeadStamp?.timestampNs ?: timestamp) > lastVehicleMeasurementNs -> {
                    val leadTimestamp = freshLeadStamp?.timestampNs ?: timestamp
                    lastVehicleMeasurementNs = leadTimestamp
                    tracker.update(laneCoreEngineFallbackTarget.correctedDistanceM, leadTimestamp)
                }
                target != null -> tracker.current(timestamp)
                else -> {
                    tracker.targetMissing(timestamp)
                    null
                }
            }
            val metrics = DrivingMetrics.from(track, egoSpeed)
            val rawRisk = RiskLevel.from(track, metrics)
            val risk = riskStabilizer.update(
                raw = rawRisk,
                trackId = target?.detection?.trackId ?: -1,
                nowNs = timestamp,
            )
            val adviceUncertainty = track?.distanceM?.let { d ->
                maxOf(rangeUncertaintyM ?: 0f, FollowingDistanceAdvisor.minimumLongRangeUncertainty(d))
            }
            val followingAdvice = followingDistanceAdvisor.update(
                speedKmh = gps.speedKmh,
                distanceM = track?.distanceM,
                uncertaintyM = adviceUncertainty,
                nowNs = timestamp,
            )

            val rawPedestrianHazard = pedestrianSelector.select(roadUsers, lane)
            val stabilizedPedestrian = pedestrianRangeStabilizer.update(rawPedestrianHazard?.measurement, detectionTimestamp)
            val pedestrianHazard = if (rawPedestrianHazard != null && stabilizedPedestrian != null) {
                rawPedestrianHazard.copy(measurement = stabilizedPedestrian.measurement)
            } else rawPedestrianHazard
            val pedestrianTrack = when {
                pedestrianHazard != null && detectionTimestamp > lastPedestrianMeasurementNs -> {
                    lastPedestrianMeasurementNs = detectionTimestamp
                    pedestrianTracker.update(pedestrianHazard.measurement.correctedDistanceM, detectionTimestamp)
                }
                pedestrianHazard != null -> pedestrianTracker.current(timestamp)
                else -> {
                    pedestrianTracker.targetMissing(timestamp)
                    null
                }
            }
            val pedestrianRangeQuality = stabilizedPedestrian?.quality
            val pedestrianRisk = PedestrianRiskLevel.from(pedestrianHazard, pedestrianTrack, egoSpeed)
            val sideHazards = sideCollisionMonitor.update(roadUsers, lane, detectionTimestamp, egoSpeed)
            if (risk >= RiskLevel.DANGER || pedestrianRisk >= PedestrianRiskLevel.WARNING ||
                sideHazards.any { it.motionState == SideMotionState.CUT_IN_PREDICTED || it.motionState == SideMotionState.CUT_IN_IMMINENT || it.level >= SideCollisionLevel.WARNING }) {
                urgentVisionUntilElapsedMs = SystemClock.elapsedRealtime() + 2_500L
            }

            val detections = buildList {
                addAll(roadUsers)
                val t = target?.detection
                if (t != null && none { it == t }) add(t)
            }

            val elapsedMs = (System.nanoTime() - started) / 1_000_000f
            val now = System.nanoTime()
            if (lastFrameNs != 0L) {
                val instFps = (1_000_000_000.0 / (now - lastFrameNs).coerceAtLeast(1L)).toFloat()
                smoothedFps = if (smoothedFps == 0f) instFps else smoothedFps * 0.86f + instFps * 0.14f
            }
            lastFrameNs = now

            postConflatedUi(
                FrameUi(
                    detections = detections,
                    target = target,
                    track = track,
                    rangeQuality = rangeQuality,
                    rangeSource = rangeSource,
                    rangeUncertaintyM = adviceUncertainty,
                    followingAdvice = followingAdvice,
                    gps = gps,
                    metrics = metrics,
                    risk = risk,
                    pedestrianHazard = pedestrianHazard,
                    pedestrianTrack = pedestrianTrack,
                    pedestrianRangeQuality = pedestrianRangeQuality,
                    pedestrianRisk = pedestrianRisk,
                    sideHazards = sideHazards,
                    lane = lane,
                    sourceAspect = preprocessor.displayAspect,
                    inferenceMs = elapsedMs,
                    fps = smoothedFps,
                    calibration = calibrationSnapshot,
                    autoCalibrationState = autoResult.state,
                )
            )
        } catch (t: Throwable) {
            runOnUiThread { modelStatus.text = "Xử lý camera lỗi • ${t.javaClass.simpleName}" }
        } finally {
            image.close()
        }
    }

    private fun postConflatedUi(frame: FrameUi) {
        latestUi.set(frame)
        if (!uiPosted.compareAndSet(false, true)) return
        mainHandler.post {
            try {
                latestUi.getAndSet(null)?.let(::renderFrame)
            } finally {
                uiPosted.set(false)
                latestUi.get()?.let { pending -> postConflatedUi(pending) }
            }
        }
    }

    private fun renderFrame(frame: FrameUi) {
        if (!licenseGate.status().allowed) return
        lastAutoCalibrationState = frame.autoCalibrationState
        overlay.setCalibration(frame.calibration)
        refreshCompactStatus()
        overlay.update(
            frame.detections, frame.target, frame.track, frame.risk,
            frame.rangeQuality,
            frame.pedestrianHazard, frame.pedestrianTrack, frame.pedestrianRangeQuality, frame.pedestrianRisk,
            frame.sideHazards, frame.lane, frame.sourceAspect,
        )
        if (debugMode) {
            val confidence = AdasConfidenceEngine.snapshot(frame.lane, frame.target, frame.track, frame.rangeQuality)
            val targetId = frame.target?.detection?.trackId ?: -1
            val range = frame.track?.distanceM?.let { String.format(Locale.US, "%.1fm", it) } ?: "—"
            val ttc = frame.track?.ttcSeconds?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1fs", it) } ?: "∞"
            overlay.setDebugStatus(
                true,
                listOf(
                    "V13.2 FULL • FPS ${frame.fps.roundToInt()} • ${thermalGuard.mode}",
                    "LANE ${confidence.laneLock} ${(frame.lane.confidence * 100f).roundToInt()}% • ${frame.lane.source}",
                    "LEAD #$targetId • ${confidence.rangeBand ?: "—"} • ${frame.rangeQuality ?: "—"}",
                    "RANGE $range • TTC $ttc • RISK ${frame.risk}",
                    "SIDE ${frame.sideHazards.size} • GPS ${frame.gps.speedKmh?.roundToInt() ?: -1}",
                    "THERM ${thermalGuard.batteryTemperatureC?.let { String.format(Locale.US, "%.1fC", it) } ?: "—"}",
                ),
            )
            debugLogger.log(
                AdasDebugLogger.DebugFrame(
                    speedKmh = frame.gps.speedKmh,
                    leadTrackId = targetId,
                    distanceM = frame.track?.distanceM,
                    ttcSeconds = frame.track?.ttcSeconds?.takeIf { it.isFinite() },
                    laneConfidence = frame.lane.confidence,
                    risk = frame.risk,
                    thermal = thermalGuard.mode.name,
                    sideCount = frame.sideHazards.size,
                )
            )
        } else {
            overlay.setDebugStatus(false)
        }
        updateGpsChip(frame.gps)

        val speedKmh = frame.metrics.egoSpeedMps?.times(3.6f)
        val signState = trafficSignStore.refreshRuntimeRules().also { trafficSignState.set(it) }
        val speedLimitState = speedLimitMonitor.update(speedKmh, signState.currentSpeedLimitKmh, SystemClock.elapsedRealtime())
        speedText.text = speedKmh?.let { speed ->
            signState.currentSpeedLimitKmh?.let { limit -> "GPS ${speed.roundToInt()} • MAX $limit km/h" }
                ?: "GPS ${speed.roundToInt()} km/h"
        } ?: "GPS — km/h"
        renderLane(frame.lane, speedKmh)
        speaker.onFollowingDistance(frame.followingAdvice)
        speaker.onSpeedLimitState(speedLimitState, signState.currentSpeedLimitKmh)

        // Pedestrians in/near the vehicle path take priority over ordinary following-distance UI/TTS.
        speaker.onPedestrian(frame.pedestrianHazard, frame.pedestrianTrack, frame.pedestrianRisk)
        if (frame.pedestrianRisk >= PedestrianRiskLevel.WARNING && frame.pedestrianTrack != null) {
            val pTrack = frame.pedestrianTrack
            distanceText.text = formatRangeForDisplay(pTrack.distanceM, frame.pedestrianRangeQuality)
            distanceText.setTextColor(Color.rgb(255, 75, 75))
            val pathText = if (frame.pedestrianHazard?.inVehiclePath == true) "NGƯỜI TRONG HƯỚNG XE" else "NGƯỜI SÁT HƯỚNG XE"
            val parts = mutableListOf(pathText)
            if (pTrack.closingSpeedMps > 0.5f && pTrack.ttcSeconds.isFinite()) {
                parts += String.format(Locale.US, "TTC %.1f s", pTrack.ttcSeconds)
            }
            motionText.text = parts.joinToString("  •  ")
            riskText.setTextColor(Color.rgb(255, 75, 75))
            riskText.text = if (frame.pedestrianRisk == PedestrianRiskLevel.DANGER) {
                "NGUY HIỂM • NGƯỜI PHÍA TRƯỚC"
            } else {
                "CHÚ Ý NGƯỜI PHÍA TRƯỚC"
            }
            speaker.onSideHazards(frame.sideHazards)
            speaker.onLane(frame.lane, frame.metrics.egoSpeedMps)
            return
        }

        val sideTop = frame.sideHazards.maxWithOrNull(
            compareBy<SideCollisionHazard> { it.level.ordinal }.thenBy { -it.distanceM }
        )
        val track = frame.track
        if (track == null) {
            val pTrack = frame.pedestrianTrack
            if (sideTop != null && sideTop.level >= SideCollisionLevel.WARNING) {
                val sideName = if (sideTop.side == LaneSide.LEFT) "TRÁI" else "PHẢI"
                val vehicleName = VehicleClasses.label(sideTop.detection.classId)
                val tlcText = sideTop.timeToLaneCrossingSeconds.takeIf { it.isFinite() }
                    ?.let { String.format(Locale.US, " • TLC %.1f s", it) }.orEmpty()
                distanceText.text = "— m"
                distanceText.setTextColor(if (sideTop.level == SideCollisionLevel.DANGER) Color.rgb(255, 75, 75) else Color.rgb(255, 193, 7))
                motionText.text = when (sideTop.motionState) {
                    SideMotionState.CUT_IN_IMMINENT -> "$vehicleName $sideName ĐANG VÀO LÀN$tlcText"
                    SideMotionState.CUT_IN_PREDICTED -> "$vehicleName $sideName CÓ XU HƯỚNG LẤN LÀN$tlcText"
                    else -> "$vehicleName SÁT $sideName"
                }
                riskText.text = when {
                    sideTop.motionState == SideMotionState.CUT_IN_IMMINENT -> "CẢNH BÁO XE $sideName ĐANG VÀO LÀN"
                    sideTop.motionState == SideMotionState.CUT_IN_PREDICTED -> "CHÚ Ý XE $sideName CÓ XU HƯỚNG LẤN LÀN"
                    sideTop.level == SideCollisionLevel.DANGER -> "NGUY CƠ VA CHẠM BÊN $sideName"
                    else -> "CẢNH BÁO XE SÁT BÊN $sideName"
                }
                riskText.setTextColor(if (sideTop.level == SideCollisionLevel.DANGER) Color.rgb(255, 75, 75) else Color.rgb(255, 193, 7))
            } else if (frame.pedestrianRisk == PedestrianRiskLevel.INFO && pTrack != null) {
                distanceText.text = formatRangeForDisplay(pTrack.distanceM, frame.pedestrianRangeQuality)
                distanceText.setTextColor(Color.rgb(255, 193, 7))
                motionText.text = if (frame.pedestrianHazard?.inVehiclePath == true) "NGƯỜI PHÍA TRƯỚC" else "NGƯỜI GẦN HƯỚNG XE"
                riskText.text = "CHÚ Ý NGƯỜI PHÍA TRƯỚC"
                riskText.setTextColor(Color.rgb(255, 193, 7))
            } else {
                distanceText.text = "— m"
                distanceText.setTextColor(Color.WHITE)
                motionText.text = if (frame.autoCalibrationState == AutoCalibrationState.CALIBRATING) {
                    "Đang tự cân chỉnh góc camera…"
                } else {
                    "Chưa thấy xe ô tô phía trước"
                }
                riskText.text = "HỖ TRỢ LÁI • KHÔNG THAY THẾ NGƯỜI LÁI"
                riskText.setTextColor(Color.LTGRAY)
            }
            speaker.onNoTarget()
            speaker.onSideHazards(frame.sideHazards)
            speaker.onLane(frame.lane, frame.metrics.egoSpeedMps)
            return
        }

        distanceText.text = formatRangeForDisplay(track.distanceM, frame.rangeQuality)
        val info = mutableListOf<String>()
        if (frame.metrics.timeGapSeconds.isFinite()) {
            info += String.format(Locale.US, "GAP %.1f s", frame.metrics.timeGapSeconds)
        }
        if (abs(track.signedClosingSpeedMps) > 0.45f) {
            val relativeKmh = abs(track.signedClosingSpeedMps) * 3.6f
            info += if (track.signedClosingSpeedMps > 0f) {
                String.format(Locale.US, "ÁP SÁT %.1f km/h", relativeKmh)
            } else {
                String.format(Locale.US, "TÁCH XA %.1f km/h", relativeKmh)
            }
        } else {
            info += "KHOẢNG CÁCH ỔN ĐỊNH"
        }
        if (track.closingSpeedMps > 0.5f && track.ttcSeconds.isFinite()) {
            info += String.format(Locale.US, "TTC %.1f s", track.ttcSeconds)
        }
        frame.metrics.recommendedTwoSecondDistanceM?.takeIf { it >= 5f }?.let {
            info += "2s ≈ ${it.roundToInt()} m"
        }
        frame.rangeUncertaintyM?.takeIf { track.distanceM >= 30f }?.let {
            info += "SAI SỐ ±${it.roundToInt()} m"
        }
        frame.followingAdvice.requiredM?.let { required ->
            val legalText = when (frame.followingAdvice.status) {
                FollowingDistanceStatus.SAFE -> "ĐỦ CỰ LY ${required.roundToInt()}m ✓"
                FollowingDistanceStatus.TOO_CLOSE -> "CỰ LY YÊU CẦU ${required.roundToInt()}m"
                FollowingDistanceStatus.MEASURING -> "ĐANG XÁC NHẬN CỰ LY ${required.roundToInt()}m"
                FollowingDistanceStatus.NOT_APPLICABLE -> null
            }
            if (legalText != null) info += legalText
        }
        signState.currentSpeedLimitKmh?.let { info += "GIỚI HẠN $it" }
        if (frame.pedestrianRisk == PedestrianRiskLevel.INFO && frame.pedestrianTrack != null) {
            info += "CÓ NGƯỜI ${frame.pedestrianTrack.distanceM.roundToInt()} m"
        }
        frame.sideHazards.maxByOrNull { it.level.ordinal }?.let { side ->
            val sideName = if (side.side == LaneSide.LEFT) "TRÁI" else "PHẢI"
            when {
                side.motionState == SideMotionState.CUT_IN_IMMINENT -> {
                    val tlc = side.timeToLaneCrossingSeconds.takeIf { it.isFinite() }
                        ?.let { String.format(Locale.US, " %.1fs", it) }.orEmpty()
                    info += "XE $sideName ĐANG VÀO LÀN$tlc"
                }
                side.motionState == SideMotionState.CUT_IN_PREDICTED -> {
                    val tlc = side.timeToLaneCrossingSeconds.takeIf { it.isFinite() }
                        ?.let { String.format(Locale.US, " %.1fs", it) }.orEmpty()
                    info += "DỰ ĐOÁN LẤN LÀN $sideName$tlc"
                }
                side.motionState == SideMotionState.WATCH -> info += "THEO DÕI XE $sideName"
                side.level >= SideCollisionLevel.WARNING -> info += "XE SÁT $sideName"
            }
        }
        motionText.text = info.joinToString("  •  ")

        val color = when (frame.risk) {
            RiskLevel.COLLISION, RiskLevel.DANGER -> Color.rgb(255, 75, 75)
            RiskLevel.WARNING -> Color.rgb(255, 193, 7)
            RiskLevel.INFO -> Color.rgb(90, 225, 125)
            RiskLevel.CLEAR -> Color.WHITE
        }
        distanceText.setTextColor(color)
        riskText.setTextColor(color)
        riskText.text = when (frame.risk) {
            RiskLevel.COLLISION -> "NGUY CƠ VA CHẠM"
            RiskLevel.DANGER -> "KHOẢNG CÁCH BÁM QUÁ GẦN"
            RiskLevel.WARNING -> "CHÚ Ý XE Ô TÔ PHÍA TRƯỚC"
            RiskLevel.INFO -> "XE Ô TÔ PHÍA TRƯỚC"
            RiskLevel.CLEAR -> "HỖ TRỢ LÁI • KHÔNG THAY THẾ NGƯỜI LÁI"
        }
        if (sideTop != null) {
            val sideName = if (sideTop.side == LaneSide.LEFT) "TRÁI" else "PHẢI"
            if (sideTop.motionState == SideMotionState.CUT_IN_IMMINENT && frame.risk < RiskLevel.DANGER) {
                riskText.text = "CẢNH BÁO XE $sideName ĐANG VÀO LÀN"
                riskText.setTextColor(Color.rgb(255, 75, 75))
            } else if (sideTop.motionState == SideMotionState.CUT_IN_PREDICTED && frame.risk <= RiskLevel.WARNING) {
                riskText.text = "CHÚ Ý XE $sideName CÓ XU HƯỚNG LẤN LÀN"
                riskText.setTextColor(Color.rgb(255, 193, 7))
            } else if (sideTop.level == SideCollisionLevel.DANGER && frame.risk < RiskLevel.DANGER) {
                riskText.text = "NGUY CƠ VA CHẠM BÊN $sideName"
                riskText.setTextColor(Color.rgb(255, 75, 75))
            } else if (sideTop.level == SideCollisionLevel.WARNING && frame.risk <= RiskLevel.INFO) {
                riskText.text = "CẢNH BÁO XE SÁT BÊN $sideName"
                riskText.setTextColor(Color.rgb(255, 193, 7))
            }
        }
        speaker.onTarget(track, frame.metrics, frame.risk, frame.target?.detection?.trackId ?: -1)
        speaker.onSideHazards(frame.sideHazards)
        speaker.onLane(frame.lane, frame.metrics.egoSpeedMps)
    }

    private fun formatRangeForDisplay(distanceM: Float, quality: RangeQuality?): String {
        val d = distanceM.coerceAtLeast(0f)
        return when {
            d >= 60f -> "~${(kotlin.math.round(d / 5f) * 5f).toInt()} m"
            quality == RangeQuality.APPROXIMATE -> "~${d.roundToInt()} m"
            d >= 20f -> "${d.roundToInt()} m"
            quality == RangeQuality.HIGH && d < 12f -> String.format(Locale.US, "%.1f m", d)
            else -> "${d.roundToInt()} m"
        }
    }

    private fun renderLane(lane: LaneState, speedKmh: Float?) {
        val confidencePct = (lane.confidence * 100f).roundToInt()
        val source = when (lane.source) {
            LaneSource.LANE_CORE -> "CORE"
            LaneSource.CV_FALLBACK -> "CV"
            LaneSource.HYBRID_ESTIMATED -> "ƯỚC LƯỢNG"
        }
        val debugSuffix = if (debugMode) " • $source • $confidencePct%" else ""
        when (lane.departureLevel) {
            LaneDepartureLevel.WARNING -> {
                laneText.visibility = View.VISIBLE
                val side = if (lane.departureSide == LaneSide.LEFT) "TRÁI" else "PHẢI"
                laneText.text = "⚠ LỆCH LÀN $side$debugSuffix"
                laneText.setTextColor(Color.rgb(255, 75, 75))
            }
            LaneDepartureLevel.CAUTION -> {
                laneText.visibility = View.VISIBLE
                val side = if (lane.departureSide == LaneSide.LEFT) "TRÁI" else "PHẢI"
                laneText.text = if (lane.isEstimated || lane.source == LaneSource.HYBRID_ESTIMATED) {
                    "LÀN ĐANG ƯỚC LƯỢNG$debugSuffix"
                } else {
                    "CHÚ Ý • SÁT VẠCH $side$debugSuffix"
                }
                laneText.setTextColor(Color.rgb(255, 193, 7))
            }
            LaneDepartureLevel.CENTERED -> {
                laneText.visibility = View.VISIBLE
                laneText.text = if (lane.isEstimated || lane.source == LaneSource.HYBRID_ESTIMATED) {
                    "LÀN ĐANG ƯỚC LƯỢNG$debugSuffix"
                } else {
                    "LÀN ỔN ĐỊNH$debugSuffix"
                }
                laneText.setTextColor(if (lane.isEstimated) Color.rgb(255, 193, 7) else Color.rgb(90, 225, 125))
            }
            LaneDepartureLevel.UNAVAILABLE -> {
                laneText.visibility = View.VISIBLE
                laneText.text = "LÀN: ĐANG TÌM"
                laneText.setTextColor(Color.LTGRAY)
            }
        }
    }

    private fun updateGpsChip(snapshotInput: GpsSpeedSnapshot) {
        lastGpsSnapshot = snapshotInput
        if (!::gpsStatus.isInitialized) return
        val snapshot = snapshotInput
        val panelSpeed = snapshot.speedKmh?.takeIf { snapshot.status == GpsStatus.OK }
        if (::speedText.isInitialized) speedText.text = panelSpeed?.let { "GPS ${it.roundToInt()} km/h" } ?: "GPS — km/h"
        gpsStatus.text = when (snapshot.status) {
            GpsStatus.OK -> {
                val speed = snapshot.speedKmh?.roundToInt()
                val acc = snapshot.speedAccuracyMps
                if (speed != null && acc != null) "GPS $speed km/h • ±${String.format(Locale.US, "%.1f", acc * 3.6f)}" else "GPS $speed km/h"
            }
            GpsStatus.SEARCHING -> "GPS đang bắt tín hiệu…"
            GpsStatus.STALE -> "GPS mất tín hiệu tạm thời"
            GpsStatus.DISABLED -> "GPS đang tắt"
            GpsStatus.COARSE_ONLY -> "GPS cần VỊ TRÍ CHÍNH XÁC • chạm"
            GpsStatus.NO_PERMISSION -> "GPS chưa cấp quyền • chạm"
        }
        refreshCompactStatus()
    }

    private fun showAutoDistanceCalibrationDialog() {
        val stats = corrector.stats()
        val target = latestTargetForCalibration.get()
        val currentConfidence = target?.correctionConfidence?.times(100f)?.roundToInt()
        val message = buildString {
            append("V13 tự hiệu chỉnh khoảng cách hoàn toàn tự động; không cần nhập khoảng cách thật bằng tay.\n\n")
            append("Hệ thống chỉ học khi ROAD CORE/camera và LANE CORE cùng bám một xe phía trước, Track ID ổn định, đủ hai vạch làn và confidence cao trong nhiều frame liên tiếp.\n\n")
            append("Mẫu tự học đã lưu: ${stats.sampleCount}\n")
            append("Hệ số bù trung bình: ${String.format(Locale.US, "%.3f×", stats.meanRatio)}")
            if (currentConfidence != null) append("\nTin cậy hiệu chỉnh tại khoảng cách hiện tại: $currentConfidence%")
            append("\n\nKhi dữ liệu chưa đủ chắc chắn app sẽ giữ nguyên thang đo hiện tại, không tự học cưỡng bức.")
        }

        AlertDialog.Builder(this)
            .setTitle("Tự hiệu chỉnh khoảng cách • V13")
            .setMessage(message)
            .setNegativeButton("ĐÓNG", null)
            .setNeutralButton("XÓA DỮ LIỆU TỰ HỌC") { _, _ -> confirmClearCorrectionSamples() }
            .show()
    }

    private fun confirmClearCorrectionSamples() {
        AlertDialog.Builder(this)
            .setTitle("Xóa dữ liệu học sai số?")
            .setMessage("Xóa các mẫu V13 đã tự học. Thông số chiều cao/góc/FOV vẫn được giữ và hệ thống sẽ tự học lại khi có dữ liệu đủ tin cậy.")
            .setNegativeButton("HỦY", null)
            .setPositiveButton("XÓA") { _, _ ->
                correctionStore.clear()
                refreshCorrector(resetTracker = true)
                autoDistanceCalibrator.reset()
                Toast.makeText(this, "Đã xóa dữ liệu tự học; V13 sẽ tự hiệu chỉnh lại.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun refreshCorrector(resetTracker: Boolean) {
        val updated = AdaptiveDistanceCorrector(correctionStore.load())
        corrector = updated
        runOnUiThread { updateCorrectionButton() }
        inferenceExecutor.execute {
            leadProjector.corrector = updated
            targetSelector.corrector = updated
            pedestrianSelector.corrector = updated
            vehicleRangeFusion.corrector = updated
            pedestrianRangeStabilizer.corrector = updated
            if (resetTracker) {
                vehicleRangeFusion.reset()
                pedestrianRangeStabilizer.reset()
                tracker.reset()
                pedestrianTracker.reset()
            }
        }
    }

    private fun showLaneCalibrationDialog() {
        val lane = latestLaneForCalibration.get()
        val current = estimator.calibration
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val info = TextView(this).apply {
            text = buildString {
                append("Dùng khi camera đặt lệch trái/phải hoặc hơi xoay ngang.\n\n")
                append("AUTO: đỗ xe thẳng và ở giữa làn, chờ app nhận đủ 2 vạch rồi bấm AUTO TÂM XE. ")
                append("App sẽ lấy độ lệch hiện tại làm vị trí trung tính của TÂM XE.\n\n")
                if (lane != null) {
                    append("Nguồn làn: ${if (lane.source == LaneSource.LANE_CORE) "LANE CORE" else "CV dự phòng"}\n")
                    append("Tin cậy hiện tại: ${(lane.confidence * 100f).roundToInt()}%\n")
                    append("Độ lệch camera thô: ${String.format(Locale.US, "%.2f", lane.rawVehicleOffsetFraction)} làn nửa")
                } else {
                    append("Hiện chưa có dữ liệu vạch làn ổn định.")
                }
            }
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        box.addView(info)

        val manual = seekRow(
            title = "Bù tâm làn (chỉnh tay)",
            min = 0,
            max = 200,
            initial = ((current.laneNeutralOffsetFraction + 1f) * 100f).roundToInt(),
        ) { value -> String.format(Locale.US, "%+.2f", (value - 100) / 100f) }
        box.addView(manual.container)

        AlertDialog.Builder(this)
            .setTitle("Hiệu chỉnh tâm xe / camera lệch")
            .setView(box)
            .setNegativeButton("HỦY", null)
            .setNeutralButton("AUTO TÂM XE") { _, _ ->
                val latest = latestLaneForCalibration.get()
                if (latest == null || latest.confidence < 0.55f || latest.left == null || latest.right == null) {
                    Toast.makeText(this, "Cần thấy rõ đủ 2 vạch với độ tin cậy ≥55% để tự căn tâm.", Toast.LENGTH_LONG).show()
                } else {
                    applyLaneNeutralOffset(latest.rawVehicleOffsetFraction)
                    Toast.makeText(this, "Đã tự căn tâm xe theo vị trí hiện tại.", Toast.LENGTH_LONG).show()
                }
            }
            .setPositiveButton("LƯU TAY") { _, _ ->
                applyLaneNeutralOffset((manual.seek.progress - 100) / 100f)
                Toast.makeText(this, "Đã lưu bù tâm làn thủ công.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun applyLaneNeutralOffset(value: Float) {
        val updated = estimator.calibration.copy(laneNeutralOffsetFraction = value.coerceIn(-1f, 1f))
        calibrationStore.save(updated)
        estimator.calibration = updated
        overlay.setCalibration(updated)
        inferenceExecutor.execute {
            laneDetector.neutralOffsetFraction = updated.laneNeutralOffsetFraction
            laneDetector.reset()
            laneCoreEngineInterpreter.reset()
            laneCoreEnginePreprocessor.resetTemporalState()
            latestLaneSenseLane.set(null)
            latestMetricLead.set(null)
            targetSelector.reset()
            roadUserTemporalFilter.reset()
            vehicleRangeFusion.reset()
            pedestrianRangeStabilizer.reset()
            tracker.reset()
            pedestrianTracker.reset()
            lastVehicleMeasurementNs = 0L
            lastRangeTrackId = -1
            lastPedestrianMeasurementNs = 0L
        }
    }

    private fun showCalibrationDialog() {
        val current = estimator.calibration
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val help = TextView(this).apply {
            text = "AUTO GÓC đang bật mặc định: app tự học pitch/roll từ IMU và điểm tụ của làn. Bạn chỉ cần đo đúng chiều cao camera. FOV được app ưu tiên đọc từ camera; các thanh pitch/roll/FOV dưới đây là giá trị khởi tạo/dự phòng. Lưu hình học mới sẽ xóa các mốc học sai số cũ vì thang đo đã thay đổi."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(12))
        }
        box.addView(help)

        val heightRow = seekRow("Chiều cao camera", 70, 200, (current.cameraHeightM * 100).roundToInt()) { value ->
            String.format(Locale.US, "%.2f m", value / 100f)
        }
        val pitchRow = seekRow("Góc chúi xuống", 0, 150, (current.pitchDownDeg * 10).roundToInt()) { value ->
            String.format(Locale.US, "%.1f°", value / 10f)
        }
        val rollRow = seekRow("Nghiêng camera (roll)", 0, 300, (current.rollDeg * 10 + 150).roundToInt()) { value ->
            String.format(Locale.US, "%.1f°", (value - 150) / 10f)
        }
        val yawRow = seekRow("Lệch hướng camera (yaw)", 0, 360, (current.yawDeg * 10 + 180).roundToInt()) { value ->
            String.format(Locale.US, "%.1f°", (value - 180) / 10f)
        }
        val fovRow = seekRow("FOV dọc", 350, 800, (current.verticalFovDeg * 10).roundToInt()) { value ->
            String.format(Locale.US, "%.1f°", value / 10f)
        }
        box.addView(heightRow.container)
        box.addView(pitchRow.container)
        box.addView(rollRow.container)
        box.addView(yawRow.container)
        box.addView(fovRow.container)

        AlertDialog.Builder(this)
            .setTitle("Hiệu chuẩn hình học camera")
            .setView(box)
            .setNegativeButton("HỦY", null)
            .setNeutralButton("MẶC ĐỊNH") { _, _ ->
                applyGeometryCalibration(Calibration(laneNeutralOffsetFraction = current.laneNeutralOffsetFraction, autoCameraCalibrationEnabled = true))
            }
            .setPositiveButton("LƯU") { _, _ ->
                applyGeometryCalibration(
                    Calibration(
                        cameraHeightM = heightRow.seek.progress / 100f,
                        pitchDownDeg = pitchRow.seek.progress / 10f,
                        rollDeg = (rollRow.seek.progress - 150) / 10f,
                        yawDeg = (yawRow.seek.progress - 180) / 10f,
                        verticalFovDeg = fovRow.seek.progress / 10f,
                        laneNeutralOffsetFraction = current.laneNeutralOffsetFraction,
                        autoCameraCalibrationEnabled = true,
                    )
                )
            }
            .show()
    }

    private fun applyGeometryCalibration(value: Calibration) {
        calibrationStore.save(value)
        correctionStore.clear()
        autoDistanceCalibrator.reset()
        autoCalibrator.reset(value)
        lastAutoCalibrationState = AutoCalibrationState.CALIBRATING
        val emptyCorrector = AdaptiveDistanceCorrector()
        corrector = emptyCorrector
        overlay.setCalibration(value)
        updateCorrectionButton()
        inferenceExecutor.execute {
            estimator.calibration = value
            laneDetector.neutralOffsetFraction = value.laneNeutralOffsetFraction
            laneDetector.reset()
            laneCoreEngineInterpreter.reset()
            laneCoreEnginePreprocessor.resetTemporalState()
            latestLaneSenseLane.set(null)
            latestMetricLead.set(null)
            targetSelector.reset()
            roadUserTemporalFilter.reset()
            leadProjector.corrector = emptyCorrector
            targetSelector.corrector = emptyCorrector
            pedestrianSelector.corrector = emptyCorrector
            vehicleRangeFusion.corrector = emptyCorrector
            pedestrianRangeStabilizer.corrector = emptyCorrector
            vehicleRangeFusion.reset()
            pedestrianRangeStabilizer.reset()
            tracker.reset()
            pedestrianTracker.reset()
            lastVehicleMeasurementNs = 0L
            lastRangeTrackId = -1
            lastPedestrianMeasurementNs = 0L
        }
        Toast.makeText(this, "Đã lưu hình học camera; các mốc sai số cũ đã được xóa.", Toast.LENGTH_LONG).show()
    }

    private data class SeekRow(val container: LinearLayout, val seek: SeekBar)

    private fun seekRow(
        title: String,
        min: Int,
        max: Int,
        initial: Int,
        valueText: (Int) -> String,
    ): SeekRow {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val label = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
        }
        val seek = SeekBar(this).apply {
            this.max = max
            if (android.os.Build.VERSION.SDK_INT >= 26) this.min = min
            progress = initial.coerceIn(min, max)
        }
        fun update(value: Int) { label.text = "$title: ${valueText(value)}" }
        update(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress < min) {
                    seekBar?.progress = min
                    return
                }
                update(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        container.addView(label)
        container.addView(seek)
        return SeekRow(container, seek)
    }

    private fun ensureSignSenseEngine() {
        if (!trafficSignStore.enabled || signSenseEngine != null || !licenseGate.status().allowed) return
        signSenseEngine = SignSenseEngine { observation ->
            val updated = trafficSignStore.applyObservation(observation)
            trafficSignState.set(updated)
            runOnUiThread {
                updateSignButton()
                speaker.onTrafficSign(observation)
                refreshCompactStatus()
            }
        }
    }

    private fun stopSignSenseEngine() {
        val old = signSenseEngine
        signSenseEngine = null
        runCatching { old?.close() }
    }

    private fun toggleTrafficSignReader() {
        val enabled = !trafficSignStore.enabled
        trafficSignStore.enabled = enabled
        if (!enabled) {
            trafficSignStore.clearRuntimeRules()
            speedLimitMonitor.reset()
        }
        trafficSignState.set(trafficSignStore.loadState())
        if (enabled && licenseGate.status().allowed) ensureSignSenseEngine() else stopSignSenseEngine()
        updateSignButton()
        Toast.makeText(
            this,
            if (enabled) "Đã bật đọc biển báo. Module chỉ chạy khi nút này bật." else "Đã tắt đọc biển báo để giảm tải và nhiệt máy.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun updateSignButton() {
        if (!::signButton.isInitialized || !::trafficSignStore.isInitialized) return
        val state = trafficSignState.get().copy(enabled = trafficSignStore.enabled)
        if (!trafficSignStore.enabled) {
            signButton.text = "◇  BIỂN BÁO AI: TẮT"
            signButton.alpha = 0.78f
            signButton.background = rippleBackground(
                fill = Color.argb(205, 18, 18, 20),
                stroke = Color.argb(105, 255, 255, 255),
                radiusDp = 13f,
            )
            return
        }
        val suffix = state.currentSpeedLimitKmh?.let { " • $it" } ?: when (state.inPopulatedArea) {
            true -> " • KHU DÂN CƯ"
            false -> " • NGOÀI KHU DÂN CƯ"
            null -> ""
        }
        signButton.text = "◆  BIỂN BÁO AI: BẬT$suffix"
        signButton.alpha = 1f
        signButton.background = rippleBackground(
            fill = Color.argb(220, 20, 76, 52),
            stroke = Color.argb(180, 100, 235, 160),
            radiusDp = 13f,
        )
    }

    private fun updateMuteButton() {
        if (!::muteButton.isInitialized) return
        muteButton.text = if (speaker.muted) "🔇" else "🔊"
        muteButton.alpha = if (speaker.muted) 0.72f else 1f
    }

    private fun updateCorrectionButton() {
        if (!::correctionButton.isInitialized) return
        val count = corrector.stats().sampleCount
        correctionButton.text = if (count > 0) "AUTO SAI SỐ • $count" else "AUTO SAI SỐ"
    }


    private fun actionButton(textValue: String, compact: Boolean = false): TextView = TextView(this).apply {
        text = textValue
        setTextColor(Color.WHITE)
        textSize = if (compact) 18f else 11.5f
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isAllCaps = false
        setPadding(dp(if (compact) 8 else 6), 0, dp(if (compact) 8 else 6), 0)
        background = rippleBackground(
            fill = Color.argb(if (compact) 190 else 205, 18, 18, 20),
            stroke = Color.argb(105, 255, 255, 255),
            radiusDp = 13f,
        )
    }

    private fun settingsActionButton(title: String, detail: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), 0, dp(14), 0)
        background = rippleBackground(
            fill = Color.rgb(246, 246, 248),
            stroke = Color.rgb(222, 222, 226),
            radiusDp = 14f,
            ripple = Color.argb(45, 0, 0, 0),
        )
        addView(TextView(this@MainActivity).apply {
            text = title
            setTextColor(Color.rgb(30, 30, 32))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        addView(TextView(this@MainActivity).apply {
            text = detail
            setTextColor(Color.rgb(105, 105, 110))
            textSize = 11.5f
            maxLines = 1
        })
    }

    private fun rippleBackground(
        fill: Int,
        stroke: Int,
        radiusDp: Float,
        ripple: Int = Color.argb(55, 255, 255, 255),
    ): android.graphics.drawable.RippleDrawable {
        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = dp(radiusDp)
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(ripple),
            shape,
            null,
        )
    }

    private fun chip(textValue: String): TextView = TextView(this).apply {
        text = textValue
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = roundedBackground(Color.argb(180, 0, 0, 0), 12f)
    }

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp)
    }

    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(
        this,
        permission,
    ) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDestroy() {
        mainHandler.removeCallbacks(gpsStatusTicker)
        cameraProvider?.unbindAll()
        cameraProvider = null
        gpsProvider.stop()
        speaker.close()
        stopSignSenseEngine()
        val pedestrianToClose = visionEngine
        visionEngine = null
        runCatching { pedestrianToClose?.close() }
        val laneToClose = laneCoreEngine
        laneCoreEngine = null
        runCatching { laneExecutor.execute { laneToClose?.close() } }
        inferenceExecutor.shutdown()
        laneExecutor.shutdown()
        roadUserExecutor.shutdown()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
