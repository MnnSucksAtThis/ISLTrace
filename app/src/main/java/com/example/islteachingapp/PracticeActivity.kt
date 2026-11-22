package com.example.islteachingapp

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Practice screen for one word.
 *
 *   DEMO      - the guide plays the sign once while the camera is dimmed. "Skip" ends it early.
 *   ALIGNING  - the first pose is shown, sized to fit both the sign and the user's hand. The user
 *               places their hand on it, with the right side facing the camera.
 *   FOLLOWING - the guide moves at the chosen speed, the user signs along, and their hand is recorded.
 *   RESULT    - three technique stars, a separate sign-recognition status, and one useful tip.
 *
 * Everything is in selfie view: the camera frame is rotated and mirrored before MediaPipe sees it, so
 * it matches the mirrored preview. Hands are assigned by position, see [SlotAssigner].
 */
class PracticeActivity : AppCompatActivity() {

    private enum class Phase { ALIGNING, DEMO, TRACING, RESULT }

    private lateinit var previewView: PreviewView
    private lateinit var guideView: HandGuideView
    private lateinit var tvSide: TextView
    private lateinit var tvFeedback: TextView
    private lateinit var resultCard: View
    private lateinit var tvStars: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvStarDetail: TextView
    private lateinit var tvRecognition: TextView
    private lateinit var tvTip: TextView
    private lateinit var tvScore: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnDemo: Button
    private lateinit var btnSpeed: Button
    private lateinit var videoPanel: View
    private lateinit var btnVideoSpeed: Button
    private var videoPlayer: ReferenceVideoPlayer? = null

    private lateinit var word: String
    private lateinit var classifierLabel: String
    private lateinit var referenceAssetPath: String
    private lateinit var videoAssetPath: String
    private var reference: ReferenceSign? = null
    private lateinit var researchTaskId: String
    private var completedAttempts = 0
    private var activeAttemptNumber = 0
    private var attemptStartedElapsedMs = 0L
    private var referenceReplays = 0

    @Volatile private var handLandmarker: HandLandmarker? = null
    @Volatile private var classifier: SignClassifier? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null

    /**
     * Turning directly from landscape to reverse landscape doesn't recreate the activity, so the
     * camera has to be told. Otherwise the hand tracker would receive upside-down frames.
     */
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val rotation = displayRotation()
            previewUseCase?.targetRotation = rotation
            analysisUseCase?.targetRotation = rotation
        }
    }
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lastTimestampMs = 0L

    /** Playback speed of the guide (1.0 = speed of the original video). */
    @Volatile private var speed = DEFAULT_SPEED

    // ---- state machine (only touched on the camera thread, except the @Volatile flags) ----
    private var stage: GuideStage? = null
    private var session: AlignSession? = null
    private var assigner: SlotAssigner? = null
    private var phase = Phase.ALIGNING
    private var phaseStartMs = 0L
    private var followFrames: Array<FloatArray>? = null
    private var plan: SignPlan? = null
    private var rise: RiseRecorder? = null
    private var turn: TraceSession? = null
    private var trail: HandGuideView.Trail? = null
    private val turnRows = ArrayList<FloatArray>()
    private var lastFeedback = ""
    private var lastSideText = ""

    @Volatile private var resetRequested = true   // set from the UI thread, handled on the camera thread
    @Volatile private var demoRequested = true    // true at start: the user watches the sign first
    @Volatile private var skipRequested = false
    @Volatile private var demoPlaying = false
    @Volatile private var videoOpen = false       // practice is on hold while the signer's clip is showing

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else tvFeedback.text = getString(R.string.camera_permission_needed)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_practice)

        previewView = findViewById(R.id.previewView)
        guideView = findViewById(R.id.guideView)
        tvSide = findViewById(R.id.tvSide)
        tvFeedback = findViewById(R.id.tvFeedback)
        resultCard = findViewById(R.id.resultCard)
        tvStars = findViewById(R.id.tvStars)
        tvResult = findViewById(R.id.tvResult)
        tvStarDetail = findViewById(R.id.tvStarDetail)
        tvRecognition = findViewById(R.id.tvRecognition)
        tvTip = findViewById(R.id.tvTip)
        tvScore = findViewById(R.id.tvScore)
        progressBar = findViewById(R.id.progressBar)
        btnDemo = findViewById(R.id.btnDemo)
        btnSpeed = findViewById(R.id.btnSpeed)
        videoPanel = findViewById(R.id.videoPanel)
        btnVideoSpeed = findViewById(R.id.btnVideoSpeed)
        val btnRetry = findViewById<Button>(R.id.btnRetry)

        word = intent.getStringExtra(Extras.WORD) ?: "Absent"
        classifierLabel = intent.getStringExtra(Extras.CLASSIFIER_LABEL) ?: word
        referenceAssetPath = intent.getStringExtra(Extras.REFERENCE_ASSET) ?: ReferenceSign.assetPath(word)
        videoAssetPath = intent.getStringExtra(Extras.VIDEO_ASSET) ?: ""
        researchTaskId = savedInstanceState?.getString(KEY_TASK_ID)
            ?: intent.getStringExtra(Extras.TASK_ID) ?: UUID.randomUUID().toString()
        completedAttempts = savedInstanceState?.getInt(KEY_COMPLETED_ATTEMPTS, 0) ?: 0
        referenceReplays = savedInstanceState?.getInt(KEY_REFERENCE_REPLAYS, 0) ?: 0
        val leftHanded = intent.getBooleanExtra(Extras.LEFT_HANDED, false)
        findViewById<TextView>(R.id.tvTitle).text = word
        findViewById<TextView>(R.id.tvVideoTitle).text = word
        setupVideo(leftHanded, videoAssetPath)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (videoOpen) closeVideo() else requestExitToWords()
            }
        })
        if (savedInstanceState != null) {
            val previousOrientation = savedInstanceState.getString(KEY_SAVED_ORIENTATION)
            val currentOrientation = orientationLabel()
            val event = if (previousOrientation != null && previousOrientation != currentOrientation) {
                "ORIENTATION_CHANGED"
            } else {
                "ACTIVITY_RECREATED"
            }
            StudyLogger.logInteraction(
                this, event, word, "$previousOrientation->$currentOrientation", researchTaskId
            )
        }

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        (getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .registerDisplayListener(displayListener, null)

        // Rotating recreates this screen. Someone who has already watched shouldn't have to again.
        val alreadyWatched = savedInstanceState?.getBoolean(KEY_WATCHED) == true
        if (alreadyWatched) demoRequested = false

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getFloat(KEY_SPEED, DEFAULT_SPEED)
        speed = SPEEDS.firstOrNull { abs(it - saved) < 0.01f } ?: DEFAULT_SPEED
        updateSpeedButton()

        findViewById<Button>(R.id.btnBack).setOnClickListener { requestExitToWords() }

        reference = try {
            val text = assets.open(referenceAssetPath).bufferedReader().use { it.readText() }
            ReferenceSign.parse(text, leftHanded)
        } catch (e: Exception) {
            Log.e(TAG, "Could not load reference for $word", e)
            null
        }

        if (reference == null) {
            tvFeedback.text = getString(R.string.reference_not_found, word)
            btnDemo.isEnabled = false
            btnSpeed.isEnabled = false
            btnRetry.isEnabled = false
            return
        }

        btnRetry.setOnClickListener {
            StudyLogger.logInteraction(this, "RETRY_SELECTED", word, completedAttempts.toString(), researchTaskId)
            resetRequested = true
            resultCard.visibility = View.GONE
            progressBar.progress = 0
            setFeedbackNow(getString(R.string.lower_hands_prompt))
        }

        btnDemo.setOnClickListener {
            if (demoPlaying) skipRequested = true else restartWithDemo()
        }

        btnSpeed.setOnClickListener {
            nextSpeed()
            restartWithDemo()          // show the sign at the new speed straight away
        }

        setFeedbackNow(if (alreadyWatched) getString(R.string.lower_hands_prompt) else getString(R.string.watch_sign_first))
        if (savedInstanceState?.getBoolean(KEY_VIDEO_OPEN) == true && videoPlayer != null) openVideo()
        // Loading the model can take seconds on a slow phone. Its own thread keeps the UI
        // responsive and lets the Watch demo, which doesn't need the model, start straight away.
        Thread({ setupHandLandmarker() }, "hand-model-loader").start()
        Thread({ setupClassifier() }, "sign-model-loader").start()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ------------------------------------------------------------------ video

    private fun setupVideo(leftHanded: Boolean, configuredVideoAsset: String) {
        val btnVideo = findViewById<Button>(R.id.btnVideo)
        val hasClip = configuredVideoAsset.isNotBlank() && try {
            assets.openFd(configuredVideoAsset).close()
            true
        } catch (e: java.io.IOException) {
            Log.w(TAG, "No clip configured for $word ($configuredVideoAsset)", e)
            false
        }
        btnVideo.isEnabled = hasClip
        if (!hasClip) return

        val surface = findViewById<TextureView>(R.id.videoSurface)
        videoPlayer = ReferenceVideoPlayer(this, surface, configuredVideoAsset, leftHanded) {
            closeVideo()
            Toast.makeText(this, R.string.video_error, Toast.LENGTH_LONG).show()
        }
        btnVideo.setOnClickListener { openVideo() }
        findViewById<Button>(R.id.btnVideoClose).setOnClickListener { closeVideo() }
        btnVideoSpeed.setOnClickListener {
            nextSpeed()
            videoPlayer?.setSpeed(speed)
        }
    }

    /** Puts the practice on hold. Any attempt in progress is dropped, as the user stopped following. */
    private fun openVideo() {
        if (activeAttemptNumber > completedAttempts) {
            StudyLogger.logInteraction(this, "ATTEMPT_INTERRUPTED_BY_REFERENCE_VIDEO", word, activeAttemptNumber.toString(), researchTaskId)
        }
        videoOpen = true
        referenceReplays++
        StudyLogger.logInteraction(this, "REFERENCE_VIDEO_OPENED", word, referenceReplays.toString(), researchTaskId)
        videoPanel.visibility = View.VISIBLE
        videoPlayer?.start(speed)
    }

    /** Back to "place your hand"; the user has just watched the sign, so the demo isn't repeated. */
    private fun closeVideo() {
        StudyLogger.logInteraction(this, "REFERENCE_VIDEO_CLOSED", word, taskId = researchTaskId)
        videoPlayer?.stop()
        videoPanel.visibility = View.GONE
        // Queue the reset first: the camera thread must not see the hold lifted without it.
        resetRequested = true
        demoRequested = false
        videoOpen = false
        resultCard.visibility = View.GONE
        progressBar.progress = 0
        setFeedbackNow(getString(R.string.lower_hands_prompt))
    }

    // ------------------------------------------------------------------ setup

    /** Technique assessment remains available if this fails; sign recognition is shown as unavailable. */
    private fun setupClassifier() {
        try {
            val loaded = SignClassifier(this)
            if (isDestroyed) loaded.close() else classifier = loaded
        } catch (e: Throwable) {
            Log.e(TAG, "Could not load the sign model", e)
        }
    }

    private fun setupHandLandmarker() {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        try {
            val loaded = HandLandmarker.createFromOptions(this, options)
            if (isDestroyed) loaded.close() else handLandmarker = loaded
        } catch (e: Throwable) {
            Log.e(TAG, "Could not load the hand model", e)
            ui { tvFeedback.text = getString(R.string.hand_tracking_unavailable) }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            // Same 4:3 shape for preview and analysis, so landmarks line up with what is shown.
            val rotation = displayRotation()
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()
            previewUseCase = preview
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysisUseCase = analysis
            analysis.setAnalyzer(cameraExecutor) { imageProxy -> processFrame(imageProxy) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @Suppress("DEPRECATION")      // Activity.display needs API 30, and this app supports API 24
    private fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_WATCHED, !demoRequested && !demoPlaying)
        outState.putBoolean(KEY_VIDEO_OPEN, videoOpen)
        outState.putString(KEY_TASK_ID, researchTaskId)
        outState.putInt(KEY_COMPLETED_ATTEMPTS, completedAttempts)
        outState.putInt(KEY_REFERENCE_REPLAYS, referenceReplays)
        outState.putString(KEY_SAVED_ORIENTATION, orientationLabel())
        if (activeAttemptNumber > completedAttempts) {
            StudyLogger.logInteraction(this, "ATTEMPT_INTERRUPTED_BY_RECREATION", word, activeAttemptNumber.toString(), researchTaskId)
        }
    }

    // ------------------------------------------------------------ per-frame work

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val ref = reference ?: return
            if (videoOpen) return
            if (stage == null) {
                if (guideView.width == 0 || guideView.height == 0) return
                // The frame's real shape once upright, so the guide lines up in either orientation.
                val turned = imageProxy.imageInfo.rotationDegrees % 180 != 0
                val cameraAspect = if (turned) {
                    imageProxy.height.toFloat() / imageProxy.width
                } else {
                    imageProxy.width.toFloat() / imageProxy.height
                }
                val newStage = GuideStage(guideView.width, guideView.height, ref, cameraAspect)
                stage = newStage
                session = AlignSession(ref, newStage)
                assigner = SlotAssigner(ref.slots)
                plan = SignPlan(ref.frames, ref.slots)
                rise = RiseRecorder(ref.slots)
                ui {
                    previewView.scaleType = if (newStage.showsWholeCamera) {
                        PreviewView.ScaleType.FIT_CENTER
                    } else {
                        PreviewView.ScaleType.FILL_CENTER
                    }
                }
            }

            // Watching and the result screen don't use the hand, so don't spend time detecting it.
            val needsHands = !demoRequested && !resetRequested && (phase == Phase.ALIGNING || phase == Phase.TRACING)
            val landmarker = handLandmarker
            if (needsHands && landmarker == null) return
            var row = EMPTY_ROW
            val hands = if (needsHands && landmarker != null) {
                val prepared = rotateAndMirror(imageProxy.toBitmap(), imageProxy.imageInfo.rotationDegrees)
                var ts = System.currentTimeMillis()
                if (ts <= lastTimestampMs) ts = lastTimestampMs + 1
                lastTimestampMs = ts
                val result = landmarker.detectForVideo(BitmapImageBuilder(prepared).build(), ts)
                row = labelledRow(result)
                detectedHands(result, prepared.width.toFloat() / prepared.height)
            } else {
                emptyList()
            }
            handleFrame(ref, hands, row, SystemClock.elapsedRealtime())
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun handleFrame(ref: ReferenceSign, hands: List<FloatArray>, row: FloatArray, now: Long) {
        if (resetRequested) {
            resetRequested = false
            phase = Phase.ALIGNING
            turn = null
            turnRows.clear()
            followFrames = null
            session?.reset()
            assigner?.reset()
            rise?.reset()
            if (demoPlaying) {
                demoPlaying = false
                ui { btnDemo.setText(R.string.watch_sign) }
            }
        }

        when (phase) {
            Phase.ALIGNING -> handleAligning(ref, hands, row, now)
            Phase.DEMO -> handleDemo(ref, now)
            Phase.TRACING -> handleTracing(ref, hands, row, now)
            Phase.RESULT -> guideView.show(null)
        }
    }

    private fun handleAligning(ref: ReferenceSign, hands: List<FloatArray>, row: FloatArray, now: Long) {
        val stage = stage ?: return
        val session = session ?: return
        val assigner = assigner ?: return
        val rise = rise ?: return
        val plan = plan ?: return

        if (demoRequested) {
            demoRequested = false
            skipRequested = false
            phase = Phase.DEMO
            phaseStartMs = now
            demoPlaying = true
            referenceReplays++
            StudyLogger.logInteraction(this, "REFERENCE_DEMO_STARTED", word, referenceReplays.toString(), researchTaskId)
            ui {
                btnDemo.setText(R.string.skip)
                setFeedbackNow(getString(R.string.watch_sign_first))
            }
            return
        }

        val live = assigner.assign(hands, session.target, now)
        rise.update(live, row, session.target)

        // The sign starts from lowered hands. The sign model needs to see the hands rise.
        if (!rise.lowered) {
            guideView.show(
                HandGuideView.Scene(stage, session.target, { ref.sideAt(it, 0) }, live, null, 0f, false)
            )
            setSideText(ref, 0)
            setFeedback(getString(R.string.lower_hands_prompt))
            return
        }

        val update = session.update(live, now)

        if (update.started) {
            phase = Phase.TRACING
            phaseStartMs = now
            val placed = Array(ref.frames.size) {
                stage.place(ref.frames[it], session.scale, session.offsetX, session.offsetY)
            }
            followFrames = placed
            trail = makeTrail(plan, placed)
            turn = TraceSession(plan, placed)
            activeAttemptNumber = completedAttempts + 1
            attemptStartedElapsedMs = SystemClock.elapsedRealtime()
            StudyLogger.logInteraction(this, "ATTEMPT_STARTED", word, activeAttemptNumber.toString(), researchTaskId)
            turnRows.clear()
            ui { progressBar.progress = 0 }
            setFeedback(getString(if (plan.heldPose) R.string.hold_this_pose else R.string.your_turn_prompt))
            return
        }

        guideView.show(
            HandGuideView.Scene(stage, update.target, { ref.sideAt(it, 0) }, live, update.check, session.holdProgress, false)
        )
        setSideText(ref, 0)
        setFeedback(alignMessage(update.check))
    }

    private fun alignMessage(check: SignMatcher.PoseCheck): String {
        if (check.aligned) return getString(R.string.good_hold_still)
        if (check.hands.all { it.issue == SignMatcher.Issue.MISSING }) return getString(R.string.raise_hands_onto_shadow)
        val hand = check.hands.minByOrNull { ISSUE_ORDER.indexOf(it.issue) } ?: return ""
        val name = if (check.hands.size > 1) {
            getString(if (hand.slot == HandGeometry.SLOT_LEFT) R.string.left_hand_label else R.string.right_hand_label)
        } else getString(R.string.hand_label)
        val ref = reference ?: return ""
        return when (hand.issue) {
            SignMatcher.Issue.MISSING ->
                if (check.hands.size > 1) getString(R.string.show_both_hands) else getString(R.string.show_hand_format, slotName(hand.slot))
            SignMatcher.Issue.TOO_FAR -> getString(R.string.bring_hand_closer, name)
            SignMatcher.Issue.TOO_CLOSE -> getString(R.string.move_hand_back, name)
            SignMatcher.Issue.WRONG_SIDE -> getString(R.string.turn_hand_side, name, sideWord(ref.sideAt(hand.slot, 0)))
            SignMatcher.Issue.OFF_POSITION -> getString(R.string.move_hand_onto_shadow, name)
            SignMatcher.Issue.WRONG_SHAPE -> getString(R.string.match_fingers_red_tips)
            SignMatcher.Issue.NONE -> getString(R.string.good_hold_still)
        }
    }

    /** How long the sign plays for at the current speed. */
    private fun playMs(ref: ReferenceSign): Long =
        max((ref.durationMs / speed).toLong(), SignMatcher.MIN_FOLLOW_MS)

    private fun handleDemo(ref: ReferenceSign, now: Long) {
        val stage = stage ?: return
        val session = session ?: return
        val elapsed = now - phaseStartMs
        val play = playMs(ref)
        val total = play + DEMO_END_HOLD_MS

        if (skipRequested || elapsed >= total) {
            if (skipRequested) {
                StudyLogger.logInteraction(this, "REFERENCE_DEMO_SKIPPED", word, "", researchTaskId)
            } else {
                StudyLogger.logInteraction(this, "REFERENCE_DEMO_COMPLETED", word, "", researchTaskId)
            }
            skipRequested = false
            phase = Phase.ALIGNING
            demoPlaying = false
            session.reset()
            rise?.reset()
            ui {
                progressBar.progress = 0
                btnDemo.setText(R.string.watch_sign)
                setFeedbackNow(getString(R.string.lower_hands_prompt))
            }
            return
        }

        val idx = ref.indexAt(elapsed * ref.durationMs / play)
        val target = stage.place(ref.frames[idx], stage.maxScale, 0f, 0f)
        guideView.show(HandGuideView.Scene(stage, target, { ref.sideAt(it, idx) }, null, null, 0f, true))
        setSideText(ref, idx)
        val p = (1000L * elapsed / total).toInt().coerceIn(0, 1000)
        ui { progressBar.progress = p }
    }

    /** Each hand's path as a line, with a dot at every checkpoint. Thinned so drawing stays cheap. */
    private fun makeTrail(plan: SignPlan, placed: Array<FloatArray>): HandGuideView.Trail? {
        if (plan.heldPose) return null
        val slots = plan.slots.filter { s -> placed.any { HandGeometry.present(it, s) } }
        val lines = slots.map { s ->
            val points = ArrayList<Float>()
            for (f in placed) {
                if (!HandGeometry.present(f, s)) continue
                val c = HandGeometry.center(f, s)
                points.add(c[0])
                points.add(c[1])
            }
            points.toFloatArray()
        }
        val dots = plan.checkpoints.flatMap { frame ->
            slots.map { s ->
                if (HandGeometry.present(placed[frame], s)) HandGeometry.center(placed[frame], s)
                else HandGeometry.center(placed.last { HandGeometry.present(it, s) }, s)
            }
        }
        return HandGuideView.Trail(lines, dots, slots.size.coerceAtLeast(1))
    }

    private fun handleTracing(ref: ReferenceSign, hands: List<FloatArray>, row: FloatArray, now: Long) {
        val stage = stage ?: return
        val assigner = assigner ?: return
        val placed = followFrames ?: return
        val plan = plan ?: return
        val turn = turn ?: return

        val idx = turn.guideIndex
        val live = assigner.assign(hands, placed[idx], now)
        val check = SignMatcher.checkPose(live, placed[idx]) { ref.sideAt(it, idx) }
        turnRows.add(row)
        val end = turn.update(live, now, ref.fps * speed, check.aligned)

        guideView.show(
            HandGuideView.Scene(stage, placed[idx], { ref.sideAt(it, idx) }, live, check, 0f, false, trail, turn.reached)
        )
        setSideText(ref, idx)
        setFeedback(traceMessage(ref, plan, turn, check, idx))
        val p = (1000 * turn.progress).toInt().coerceIn(0, 1000)
        ui { progressBar.progress = p }

        if (end != null) finishTurn(ref, plan, placed, turn, end)
    }

    private fun traceMessage(
        ref: ReferenceSign,
        plan: SignPlan,
        turn: TraceSession,
        check: SignMatcher.PoseCheck,
        idx: Int
    ): String {
        check.hands.firstOrNull { it.issue == SignMatcher.Issue.MISSING }?.let {
            return getString(R.string.keep_hand_in_view)
        }
        check.hands.firstOrNull { it.issue == SignMatcher.Issue.WRONG_SIDE }?.let {
            return getString(R.string.turn_hand_side, getString(R.string.hand_label), sideWord(ref.sideAt(it.slot, idx)))
        }
        return when {
            plan.heldPose -> getString(R.string.hold_this_pose)
            !turn.started -> getString(R.string.your_turn_prompt)
            turn.reached >= turn.checkpointCount -> getString(R.string.hold_still_to_finish)
            else -> getString(R.string.keep_going_shadow_waits)
        }
    }

    private fun finishTurn(
        ref: ReferenceSign,
        plan: SignPlan,
        placed: Array<FloatArray>,
        turn: TraceSession,
        end: TraceSession.End
    ) {
        phase = Phase.RESULT
        setFeedback("")
        val scored = TraceScorer.score(plan, placed, turn.scoringAttempt) { s, i -> ref.sideAt(s, i) }

        val riseRows = rise?.frames().orEmpty()
        val modelRows = (riseRows + turnRows.subList(turn.attemptStart, turnRows.size)).filter { r -> r.any { it != 0f } }
        val model = classifier
        // Without the rise the model isn't reliable (about 69% in tests), so it doesn't get a vote.
        val check = if (model == null || riseRows.isEmpty() || modelRows.size < 2) null else {
            try {
                model.check(modelRows, classifierLabel)
            } catch (e: Exception) {
                Log.e(TAG, "Sign model failed", e)
                null
            }
        }

        val finished = end != TraceSession.End.NOT_STARTED && !(plan.heldPose && end != TraceSession.End.HELD)
        // Preserve the original combined verdict for the existing research CSV field only.
        // Learner-facing technique stars and sign-recognition status are displayed independently.
        val legacyCombinedPass = finished && scored.passed(check?.accepted)
        // The technique tip is based only on the three technique checks and movement validity.
        // The classifier must not turn a correct technique check into a technique failure.
        val techniquePassed = scored.pathOk && scored.shapeOk && scored.sideOk
        val tip = if (end == TraceSession.End.NOT_STARTED) TraceScorer.Tip.NOT_STARTED
                  else scored.tip(techniquePassed)
        val classifierStatus = when {
            model == null -> "UNAVAILABLE_NOT_LOADED"
            riseRows.isEmpty() -> "NOT_RUN_NO_RISE"
            modelRows.size < 2 -> "NOT_RUN_INSUFFICIENT_INPUT"
            check == null -> "ERROR"
            check.accepted -> "ACCEPTED"
            else -> "REJECTED"
        }
        val durationMs = if (attemptStartedElapsedMs > 0L) {
            (SystemClock.elapsedRealtime() - attemptStartedElapsedMs).coerceAtLeast(0L)
        } else 0L
        completedAttempts = max(completedAttempts, activeAttemptNumber)

        // One result preserves the three technique checks and classifier outcome as separate facts.
        val evaluation = TraceEvaluation(
            legacyCombinedPass = legacyCombinedPass,
            stars = scored.stars,
            pathOk = scored.pathOk,
            shapeOk = scored.shapeOk,
            sideOk = scored.sideOk,
            tip = tip,
            classifierStatus = classifierStatus,
            finished = finished
        )

        Log.d(
            TAG, "word=$word end=$end legacyCombinedPass=${evaluation.legacyCombinedPass} stars=${evaluation.stars} " +
                "path=${scored.pathError}/${scored.pathAllowed} reverse=${scored.reverseError} " +
                "shape=${scored.shapeError} side=${scored.sideShare} moved=${scored.movedEnough} " +
                "order=${scored.orderOk} seen=${scored.seen} rise=${riseRows.size} frames=${turnRows.size} " +
                "modelTop=${check?.topWord} target=${check?.targetProbability} conf=${check?.confidence} " +
                "margin=${check?.margin} accepted=${check?.accepted} classifierStatus=$classifierStatus"
        )

        StudyLogger.logAttempt(
            context = this,
            taskId = researchTaskId,
            word = word,
            attemptNumber = activeAttemptNumber,
            durationMs = durationMs,
            endReason = end.name,
            passed = evaluation.legacyCombinedPass,
            stars = evaluation.stars,
            pathOk = evaluation.pathOk,
            shapeOk = evaluation.shapeOk,
            sideOk = evaluation.sideOk,
            movedEnough = scored.movedEnough,
            orderOk = scored.orderOk,
            seen = scored.seen,
            pathError = scored.pathError,
            pathAllowed = scored.pathAllowed,
            reverseError = scored.reverseError,
            shapeError = scored.shapeError,
            sideShare = scored.sideShare,
            classifierStatus = evaluation.classifierStatus,
            modelTop = check?.topWord,
            targetProbability = check?.targetProbability,
            confidence = check?.confidence,
            margin = check?.margin,
            feedbackCode = tip.name,
            orientation = orientationLabel(),
            guideSpeed = speed,
            referenceReplays = referenceReplays,
            leftHanded = intent.getBooleanExtra(Extras.LEFT_HANDED, false)
        )
        StudyLogger.logInteraction(
            this, "ATTEMPT_COMPLETED", word,
            "recognition=$classifierStatus;technique_stars=${scored.stars};end=${end.name}",
            researchTaskId
        )

        ui {
            progressBar.progress = 1000
            tvStars.text = "★".repeat(evaluation.stars) + "☆".repeat(3 - evaluation.stars)
            tvResult.text = resultHeadline(evaluation)
            tvStarDetail.text = getString(
                R.string.technique_score_detail,
                evaluation.stars,
                mark(evaluation.pathOk),
                mark(evaluation.shapeOk),
                mark(evaluation.sideOk)
            )
            tvRecognition.text = recognitionLabel(evaluation.classifierStatus)
            tvRecognition.setTextColor(ContextCompat.getColor(this, when (evaluation.classifierStatus) {
                "ACCEPTED" -> R.color.success
                "REJECTED" -> R.color.warning
                else -> R.color.muted
            }))
            tvTip.text = resultMessage(evaluation)
            // Numerical diagnostics remain in the research CSV/log, not in the learner-facing result.
            tvScore.text = ""
            resultCard.visibility = View.VISIBLE
            setFeedbackNow("")
        }
    }

    private data class TraceEvaluation(
        val legacyCombinedPass: Boolean,
        val stars: Int,
        val pathOk: Boolean,
        val shapeOk: Boolean,
        val sideOk: Boolean,
        val tip: TraceScorer.Tip,
        val classifierStatus: String,
        val finished: Boolean
    )

    private fun recognitionLabel(status: String): String = when (status) {
        "ACCEPTED" -> getString(R.string.sign_recognised)
        "REJECTED" -> getString(R.string.sign_not_recognised)
        else -> getString(R.string.recognition_unavailable)
    }

    private fun resultHeadline(evaluation: TraceEvaluation): String = when {
        !evaluation.finished -> getString(R.string.attempt_incomplete)
        evaluation.classifierStatus == "ACCEPTED" && evaluation.stars == 3 -> getString(R.string.excellent_sign_recognised)
        evaluation.classifierStatus == "ACCEPTED" -> getString(R.string.sign_recognised_exclamation)
        evaluation.stars == 3 -> getString(R.string.excellent_technique)
        evaluation.classifierStatus == "REJECTED" -> getString(R.string.keep_practising)
        else -> getString(R.string.technique_assessment_complete)
    }

    private fun resultMessage(evaluation: TraceEvaluation): String {
        if (!evaluation.finished) {
            return getString(R.string.result_msg_attempt_incomplete)
        }
        val correction = tipMessage(evaluation.tip)
        val allTechniqueChecksPassed = evaluation.stars == 3
        return when (evaluation.classifierStatus) {
            "ACCEPTED" -> if (allTechniqueChecksPassed) {
                getString(R.string.result_msg_accepted_perfect, word)
            } else {
                getString(R.string.result_msg_accepted_technique_needed, word, correction)
            }
            "REJECTED" -> if (allTechniqueChecksPassed) {
                getString(R.string.result_msg_rejected_perfect, word)
            } else {
                getString(R.string.result_msg_rejected_technique_needed, word, correction)
            }
            "ERROR", "UNAVAILABLE_NOT_LOADED" ->
                getString(R.string.result_msg_unavailable_not_loaded, correction)
            else -> if (allTechniqueChecksPassed) {
                getString(R.string.result_msg_other_perfect, word)
            } else {
                getString(R.string.result_msg_other_technique_needed, correction)
            }
        }
    }

    private fun tipMessage(tip: TraceScorer.Tip): String = when (tip) {
        TraceScorer.Tip.NONE -> getString(R.string.tip_none)
        TraceScorer.Tip.NOT_STARTED -> getString(R.string.tip_not_started)
        TraceScorer.Tip.HAND_LOST -> getString(R.string.tip_hand_lost)
        TraceScorer.Tip.MOVE_MORE -> getString(R.string.tip_move_more)
        TraceScorer.Tip.WRONG_ORDER -> getString(R.string.tip_wrong_order)
        TraceScorer.Tip.WRONG_SIDE -> getString(R.string.tip_wrong_side)
        TraceScorer.Tip.SHAPE -> getString(R.string.tip_shape)
        TraceScorer.Tip.PATH -> getString(R.string.tip_path)
        TraceScorer.Tip.NOT_RECOGNIZED -> getString(R.string.tip_not_recognized)
    }

    private fun requestExitToWords() {
        if (activeAttemptNumber > completedAttempts) {
            StudyLogger.logInteraction(this, "ATTEMPT_INTERRUPTED_BY_EXIT", word, activeAttemptNumber.toString(), researchTaskId)
        }
        if (completedAttempts == 0) {
            StudyLogger.logInteraction(this, "SIGN_TASK_LEFT_WITHOUT_ATTEMPT", word, taskId = researchTaskId)
            finish()
            return
        }
        finish()
    }

    private fun orientationLabel(): String = when (resources.configuration.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
        Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
        else -> "UNKNOWN"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    // ---------------------------------------------------------------- UI helpers

    private fun restartWithDemo() {
        resetRequested = true
        demoRequested = true
        resultCard.visibility = View.GONE
        progressBar.progress = 0
        setFeedbackNow(getString(R.string.watch_sign_first))
    }

    private fun nextSpeed() {
        val idx = SPEEDS.indexOfFirst { abs(it - speed) < 0.01f }
        speed = SPEEDS[(idx + 1) % SPEEDS.size]
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_SPEED, speed).apply()
        StudyLogger.logInteraction(this, "PLAYBACK_SPEED_CHANGED", word, speed.toString(), researchTaskId)
        updateSpeedButton()
    }

    private fun updateSpeedButton() {
        val label = if (speed == 1f) "1x" else "${speed}x"
        btnSpeed.text = label
        btnVideoSpeed.text = getString(R.string.video_speed, label)
    }

    private fun slotName(slot: Int) = if (slot == HandGeometry.SLOT_LEFT) getString(R.string.left_slot) else getString(R.string.right_slot)

    private fun sideWord(side: HandSide) = when (side) {
        HandSide.PALM -> getString(R.string.side_palm_phrase)
        HandSide.BACK -> getString(R.string.side_back_phrase)
        HandSide.EDGE -> getString(R.string.side_edge_phrase)
    }

    /** Spells out, in words, which side of each hand should face the camera. */
    private fun setSideText(ref: ReferenceSign, idx: Int) {
        val present = (0..1).filter { HandGeometry.present(ref.frames[idx], it) }
        val text = if (present.size == 1) {
            getString(R.string.show_camera_side, sideWord(ref.sideAt(present[0], idx)))
        } else {
            present.joinToString("   |   ") { s ->
                val label = when (ref.sideAt(s, idx)) {
                    HandSide.PALM -> getString(R.string.side_palm_caps)
                    HandSide.BACK -> getString(R.string.side_back_caps)
                    HandSide.EDGE -> getString(R.string.side_edge_caps)
                }
                getString(R.string.hand_side_item, slotName(s).replaceFirstChar { it.uppercase() }, label)
            }
        }
        if (text == lastSideText) return
        lastSideText = text
        ui { tvSide.text = text }
    }

    /** From any thread; only posts to the UI when the text actually changed. */
    private fun setFeedback(text: String) {
        if (text == lastFeedback) return
        lastFeedback = text
        ui { tvFeedback.text = text }
    }

    /** UI thread. */
    private fun setFeedbackNow(text: String) {
        lastFeedback = text
        tvFeedback.text = text
    }

    private fun ui(block: () -> Unit) = runOnUiThread { block() }

    // ------------------------------------------------------- landmark conversion

    /** Camera frame -> upright + mirrored (selfie view), same as the preview the user sees. */
    private fun rotateAndMirror(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val m = Matrix()
        if (rotationDegrees != 0) m.postRotate(rotationDegrees.toFloat())
        m.postScale(-1f, 1f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    /**
     * This frame as the sign model expects it: raw values, left then right by MediaPipe's label.
     * The guide doesn't use this, as it assigns hands by position instead, see [SlotAssigner].
     */
    private fun labelledRow(result: HandLandmarkerResult): FloatArray {
        val row = FloatArray(HandGeometry.FRAME_FLOATS)
        val hands = result.landmarks()
        val labels = result.handedness()
        for (i in hands.indices) {
            val hand = hands[i]
            if (hand.size < HandGeometry.LANDMARKS || i >= labels.size || labels[i].isEmpty()) continue
            val offset = if (labels[i][0].categoryName() == "Left") 0 else HandGeometry.HAND_FLOATS
            for (j in 0 until HandGeometry.LANDMARKS) {
                row[offset + j * 3] = hand[j].x()
                row[offset + j * 3 + 1] = hand[j].y()
                row[offset + j * 3 + 2] = hand[j].z()
            }
        }
        return row
    }

    /** Each detected hand as 63 floats in camera units. Labels are ignored, see [SlotAssigner]. */
    private fun detectedHands(result: HandLandmarkerResult, aspect: Float): List<FloatArray> =
        result.landmarks().filter { it.size >= HandGeometry.LANDMARKS }.map { hand ->
            FloatArray(HandGeometry.HAND_FLOATS).also { out ->
                for (j in 0 until HandGeometry.LANDMARKS) {
                    out[j * 3] = hand[j].x() * aspect
                    out[j * 3 + 1] = hand[j].y()
                    out[j * 3 + 2] = hand[j].z() * aspect
                }
            }
        }

    override fun onPause() {
        super.onPause()
        videoPlayer?.stop()
    }

    override fun onResume() {
        super.onResume()
        if (videoOpen) videoPlayer?.start(speed)
    }

    override fun onDestroy() {
        super.onDestroy()
        videoPlayer?.stop()
        (getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .unregisterDisplayListener(displayListener)
        cameraProvider?.unbindAll()
        // Runs after any frame in progress, on the thread that uses the model.
        cameraExecutor.execute {
            handLandmarker?.close()
            classifier?.close()
        }
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ISL_TEACH"
        private const val PREFS = "isl_prefs"
        private const val KEY_SPEED = "speed"
        private const val KEY_WATCHED = "watched"
        private const val KEY_VIDEO_OPEN = "video_open"

        /** The clips are fast, so learners start at half speed and can work up. */
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f)
        private const val DEFAULT_SPEED = 0.5f
        private const val DEMO_END_HOLD_MS = 400L
        private val EMPTY_ROW = FloatArray(HandGeometry.FRAME_FLOATS)

        private const val KEY_TASK_ID = "research_task_id"
        private const val KEY_COMPLETED_ATTEMPTS = "completed_attempts"
        private const val KEY_REFERENCE_REPLAYS = "reference_replays"
        private const val KEY_SAVED_ORIENTATION = "saved_orientation"

        /** Which problem to mention first when several apply. */
        private val ISSUE_ORDER = listOf(
            SignMatcher.Issue.MISSING, SignMatcher.Issue.TOO_FAR, SignMatcher.Issue.TOO_CLOSE,
            SignMatcher.Issue.WRONG_SIDE, SignMatcher.Issue.OFF_POSITION, SignMatcher.Issue.WRONG_SHAPE,
            SignMatcher.Issue.NONE
        )
    }
}
