package com.fliptrainer.core.readiness

import com.fliptrainer.core.features.CameraView
import com.fliptrainer.core.features.Geometry
import com.fliptrainer.core.features.Point2
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.PoseFrame
import kotlin.math.abs

/**
 * Why counting is or is not allowed to start.
 *
 * Ordering in this enum is not significant; evaluation order is defined by
 * [ReadinessGate.evaluateInstantaneous] and is deliberate — the athlete should be told the most
 * useful thing, not the first thing that happens to be wrong.
 */
enum class ReadinessStatus {
    NO_PERSON,
    TOO_CLOSE,
    TOO_FAR,
    BODY_CLIPPED,
    REQUIRED_LANDMARKS_MISSING,
    LOW_CONFIDENCE,
    WRONG_ORIENTATION,
    WRONG_VIEW,
    UNSTABLE_CAMERA,
    READY,
}

/**
 * The gate's answer for one frame.
 *
 * @property details human-readable specifics, e.g. which landmarks were clipped. Shown in the
 *   debug UI and stored with the event so a readiness failure can be explained after the fact.
 * @property confidence aggregate confidence over the exercise's required landmarks, `0..1`.
 */
data class ReadinessState(
    val status: ReadinessStatus,
    val details: List<String> = emptyList(),
    val confidence: Float = 0f,
) {
    val isReady: Boolean get() = status == ReadinessStatus.READY

    /** Short spoken/displayed instruction (plan §4 task 6). */
    val instruction: String
        get() =
            when (status) {
                ReadinessStatus.NO_PERSON -> "Step into frame"
                ReadinessStatus.TOO_CLOSE -> "Move farther away"
                ReadinessStatus.TOO_FAR -> "Move closer"
                ReadinessStatus.BODY_CLIPPED -> clippedInstruction()
                ReadinessStatus.REQUIRED_LANDMARKS_MISSING -> "Show your whole body"
                ReadinessStatus.LOW_CONFIDENCE -> "Improve lighting or clear the background"
                ReadinessStatus.WRONG_ORIENTATION -> "Rotate the phone"
                ReadinessStatus.WRONG_VIEW -> "Turn slightly sideways"
                ReadinessStatus.UNSTABLE_CAMERA -> "Hold the camera still"
                ReadinessStatus.READY -> "Ready"
            }

    private fun clippedInstruction(): String {
        val joined = details.joinToString(" ").lowercase()
        return when {
            "foot" in joined || "ankle" in joined || "heel" in joined -> "Show your feet"
            "wrist" in joined || "elbow" in joined -> "Show your hands"
            "nose" in joined || "ear" in joined || "eye" in joined -> "Show your head"
            else -> "Fit your whole body in frame"
        }
    }

    companion object {
        val NO_PERSON = ReadinessState(ReadinessStatus.NO_PERSON)
    }
}

enum class FrameOrientation {
    PORTRAIT,
    LANDSCAPE,
    ANY,
    ;

    fun matches(frame: PoseFrame): Boolean =
        when (this) {
            ANY -> true
            PORTRAIT -> frame.imageHeight >= frame.imageWidth
            LANDSCAPE -> frame.imageWidth >= frame.imageHeight
        }
}

/**
 * What one exercise needs to see before it is allowed to count (plan §4 task 3).
 *
 * @property minSubjectHeightFraction fraction of frame height the required landmarks must span.
 *   Below this the athlete is too far away and landmark noise dominates the movement being
 *   measured.
 * @property maxSubjectHeightFraction above this the athlete is too close and will clip during the
 *   movement even if they fit while standing still.
 * @property frameMarginFraction required landmarks must sit at least this far inside the frame
 *   edge. Non-zero because a landmark pinned exactly at the boundary is usually the model's guess
 *   at something already out of shot.
 */
data class ReadinessRequirements(
    val requiredLandmarks: Set<LandmarkType>,
    val preferredView: CameraView = CameraView.FRONT,
    val minLandmarkConfidence: Float = 0.5f,
    val minSubjectHeightFraction: Double = 0.35,
    val maxSubjectHeightFraction: Double = 0.95,
    val frameMarginFraction: Double = 0.02,
    val requiredOrientation: FrameOrientation = FrameOrientation.ANY,
    val enforceView: Boolean = true,
) {
    init {
        require(requiredLandmarks.isNotEmpty()) { "an exercise must require at least one landmark" }
        require(minSubjectHeightFraction < maxSubjectHeightFraction) {
            "minSubjectHeightFraction must be below maxSubjectHeightFraction"
        }
    }
}

/**
 * Debounce behaviour. Session 4 acceptance: short landmark dropouts must not cause rapid state
 * oscillation, and readiness transitions must be deterministic under trace replay.
 *
 * Asymmetric on purpose. Becoming ready takes sustained evidence; losing readiness takes less,
 * but not zero — a single dropped frame mid-set must not abort counting.
 */
data class ReadinessConfig(
    val enterReadyHoldMs: Long = 600,
    val leaveReadyHoldMs: Long = 400,
    val statusChangeHoldMs: Long = 250,
    /**
     * Whole-body translation above this, in body-scale units per second, counts as camera motion
     * — but only when it is *coherent* (see [motionCoherence]).
     */
    val maxCoherentMotionPerSecond: Double = 0.9,
    /**
     * How uniformly landmarks must move together before motion is attributed to the camera rather
     * than to the athlete. 1.0 is perfectly rigid translation.
     */
    val motionCoherence: Double = 0.65,
    /** Optional IMU magnitude (m/s²) above which the camera is considered to be moving. */
    val maxDeviceAccelerationMs2: Double = 1.5,
) {
    init {
        require(enterReadyHoldMs >= 0 && leaveReadyHoldMs >= 0 && statusChangeHoldMs >= 0) {
            "hold durations must not be negative"
        }
        require(motionCoherence in 0.0..1.0) { "motionCoherence must be in [0,1]" }
    }
}

/** Result of feeding one frame to the gate. */
data class ReadinessTransition(
    val state: ReadinessState,
    val changed: Boolean,
)

/**
 * The framing gate.
 *
 * Counting is disabled until this says READY, and the exercise evaluator is not even offered a
 * frame otherwise. That is the mechanism behind "abstain rather than guess" (plan §2.3): a
 * fabricated rep from a half-visible athlete is worse than no rep at all.
 *
 * Deterministic: fed the same frames with the same timestamps it produces the same transitions,
 * which is what makes trace replay a meaningful test.
 */
class ReadinessGate(
    private val requirements: ReadinessRequirements,
    private val config: ReadinessConfig = ReadinessConfig(),
) {
    private var stable: ReadinessState = ReadinessState.NO_PERSON
    private var candidate: ReadinessState? = null
    private var candidateSinceMs: Long = 0

    private var previousPoints: Map<LandmarkType, Point2>? = null
    private var previousTimestampMs: Long = Long.MIN_VALUE
    private var previousScale: Double = 0.0

    val current: ReadinessState get() = stable

    fun reset() {
        stable = ReadinessState.NO_PERSON
        candidate = null
        candidateSinceMs = 0
        previousPoints = null
        previousTimestampMs = Long.MIN_VALUE
        previousScale = 0.0
    }

    /**
     * @param deviceAccelerationMs2 optional IMU linear-acceleration magnitude. When supplied it
     *   takes precedence over the landmark-based motion estimate, because an accelerometer can
     *   tell a shaking tripod from a moving athlete and pixels cannot.
     */
    fun accept(
        frame: PoseFrame,
        deviceAccelerationMs2: Double? = null,
    ): ReadinessTransition {
        val instantaneous = evaluateInstantaneous(frame, deviceAccelerationMs2)
        rememberMotionBaseline(frame)
        return commit(instantaneous, frame.monotonicTimestampMs)
    }

    private fun commit(
        instantaneous: ReadinessState,
        nowMs: Long,
    ): ReadinessTransition {
        if (instantaneous.status == stable.status) {
            // Same status: refresh the details in place, but this is not a transition.
            stable = instantaneous
            candidate = null
            return ReadinessTransition(stable, changed = false)
        }

        val pending = candidate
        if (pending == null || pending.status != instantaneous.status) {
            candidate = instantaneous
            candidateSinceMs = nowMs
            return ReadinessTransition(stable, changed = false)
        }

        candidate = instantaneous
        val holdMs =
            when {
                instantaneous.status == ReadinessStatus.READY -> config.enterReadyHoldMs
                stable.status == ReadinessStatus.READY -> config.leaveReadyHoldMs
                else -> config.statusChangeHoldMs
            }

        if (nowMs - candidateSinceMs >= holdMs) {
            stable = instantaneous
            candidate = null
            return ReadinessTransition(stable, changed = true)
        }
        return ReadinessTransition(stable, changed = false)
    }

    /**
     * Order is chosen so the athlete hears the most actionable instruction. Orientation comes
     * first because if the phone is on its side nothing else is worth saying; distance comes
     * before clipping because "move back" is more useful than "show your feet" when the reason
     * the feet are missing is that the athlete is standing on top of the camera.
     */
    private fun evaluateInstantaneous(
        frame: PoseFrame,
        deviceAccelerationMs2: Double?,
    ): ReadinessState {
        if (!requirements.requiredOrientation.matches(frame)) {
            return ReadinessState(
                ReadinessStatus.WRONG_ORIENTATION,
                listOf("frame is ${frame.imageWidth}x${frame.imageHeight}, need ${requirements.requiredOrientation}"),
            )
        }

        if (!frame.hasPerson) return ReadinessState.NO_PERSON

        val missing = requirements.requiredLandmarks.filter { frame[it] == null }
        if (missing.isNotEmpty()) {
            return ReadinessState(
                ReadinessStatus.REQUIRED_LANDMARKS_MISSING,
                missing.sortedBy { it.index }.map { it.name },
                confidence = aggregateConfidence(frame),
            )
        }

        val confidence = aggregateConfidence(frame)

        val box = Geometry.boundingBox(frame, requirements.requiredLandmarks)
        if (box != null) {
            if (box.height < requirements.minSubjectHeightFraction) {
                return ReadinessState(
                    ReadinessStatus.TOO_FAR,
                    listOf(
                        "subject spans %.2f of frame height, need %.2f".format(
                            box.height,
                            requirements.minSubjectHeightFraction,
                        ),
                    ),
                    confidence,
                )
            }
            if (box.height > requirements.maxSubjectHeightFraction) {
                return ReadinessState(
                    ReadinessStatus.TOO_CLOSE,
                    listOf(
                        "subject spans %.2f of frame height, max %.2f".format(
                            box.height,
                            requirements.maxSubjectHeightFraction,
                        ),
                    ),
                    confidence,
                )
            }
        }

        val clipped = clippedLandmarks(frame)
        if (clipped.isNotEmpty()) {
            return ReadinessState(ReadinessStatus.BODY_CLIPPED, clipped.map { it.name }, confidence)
        }

        val underConfident =
            requirements.requiredLandmarks
                .filter { frame.confidenceOf(it) < requirements.minLandmarkConfidence }
                .sortedBy { it.index }
        if (underConfident.isNotEmpty()) {
            return ReadinessState(
                ReadinessStatus.LOW_CONFIDENCE,
                underConfident.map { "${it.name}=%.2f".format(frame.confidenceOf(it)) },
                confidence,
            )
        }

        if (requirements.enforceView) {
            val view = Geometry.estimateView(frame, requirements.minLandmarkConfidence)
            if (view != null && !requirements.preferredView.isSatisfiedBy(view.view)) {
                return ReadinessState(
                    ReadinessStatus.WRONG_VIEW,
                    listOf(
                        "looks like ${view.view}, need ${requirements.preferredView} (openness %.2f)".format(
                            view.openness,
                        ),
                    ),
                    confidence,
                )
            }
        }

        val motion = cameraMotion(frame, deviceAccelerationMs2)
        if (motion != null) {
            return ReadinessState(ReadinessStatus.UNSTABLE_CAMERA, listOf(motion), confidence)
        }

        return ReadinessState(ReadinessStatus.READY, emptyList(), confidence)
    }

    private fun aggregateConfidence(frame: PoseFrame): Float =
        Geometry.visibilityAggregate(frame, requirements.requiredLandmarks)?.toFloat() ?: 0f

    private fun clippedLandmarks(frame: PoseFrame): List<LandmarkType> {
        val m = requirements.frameMarginFraction
        return requirements.requiredLandmarks
            .filter { type ->
                val lm = frame[type] ?: return@filter true
                lm.x < m || lm.x > 1.0 - m || lm.y < m || lm.y > 1.0 - m
            }.sortedBy { it.index }
    }

    /**
     * Distinguish a shaking camera from a moving athlete.
     *
     * When the camera moves, every landmark shifts by the same vector. When the athlete moves,
     * some landmarks shift and others do not. Coherence — the length of the mean displacement
     * divided by the RMS displacement — separates the two: rigid translation gives ~1, a curl
     * gives a small number because most of the body stayed put.
     *
     * @return a description when the camera looks unstable, null when it looks fine.
     */
    private fun cameraMotion(
        frame: PoseFrame,
        deviceAccelerationMs2: Double?,
    ): String? {
        if (deviceAccelerationMs2 != null) {
            return if (deviceAccelerationMs2 > config.maxDeviceAccelerationMs2) {
                "device acceleration %.2f m/s²".format(deviceAccelerationMs2)
            } else {
                null
            }
        }

        val previous = previousPoints ?: return null
        val dtMs = frame.monotonicTimestampMs - previousTimestampMs
        if (dtMs <= 0) return null

        val scale = previousScale
        if (scale <= Geometry.EPSILON) return null

        var sumX = 0.0
        var sumY = 0.0
        var n = 0
        val magnitudes = ArrayList<Double>()
        for ((type, before) in previous) {
            val now = Geometry.point(frame, type, requirements.minLandmarkConfidence) ?: continue
            val dx = now.x - before.x
            val dy = now.y - before.y
            sumX += dx
            sumY += dy
            magnitudes += kotlin.math.hypot(dx, dy)
            n++
        }
        if (n < MIN_MOTION_LANDMARKS) return null

        val meanX = sumX / n
        val meanY = sumY / n
        val meanMagnitude = kotlin.math.hypot(meanX, meanY)
        val rms = Geometry.rms(magnitudes.toDoubleArray())
        if (rms <= Geometry.EPSILON) return null

        val coherence = meanMagnitude / rms
        val speed = (meanMagnitude / scale) / (dtMs / 1000.0)

        return if (coherence >= config.motionCoherence && speed >= config.maxCoherentMotionPerSecond) {
            "whole-frame drift %.2f body-lengths/s (coherence %.2f)".format(speed, coherence)
        } else {
            null
        }
    }

    private fun rememberMotionBaseline(frame: PoseFrame) {
        if (!frame.hasPerson) {
            previousPoints = null
            previousTimestampMs = frame.monotonicTimestampMs
            previousScale = 0.0
            return
        }
        val scale = Geometry.bodyScale(frame, requirements.minLandmarkConfidence)
        if (scale == null || scale <= Geometry.EPSILON) {
            previousPoints = null
            previousTimestampMs = frame.monotonicTimestampMs
            previousScale = 0.0
            return
        }
        val points = HashMap<LandmarkType, Point2>(requirements.requiredLandmarks.size)
        for (type in requirements.requiredLandmarks) {
            Geometry.point(frame, type, requirements.minLandmarkConfidence)?.let { points[type] = it }
        }
        previousPoints = points
        previousTimestampMs = frame.monotonicTimestampMs
        previousScale = scale
    }

    private companion object {
        /** Below this many tracked points, a coherence estimate is noise. */
        const val MIN_MOTION_LANDMARKS = 4
    }
}

/** Ready-made requirement sets shared by the exercise definitions. */
object ReadinessPresets {
    private val UPPER_BODY =
        setOf(
            LandmarkType.LEFT_SHOULDER,
            LandmarkType.RIGHT_SHOULDER,
            LandmarkType.LEFT_ELBOW,
            LandmarkType.RIGHT_ELBOW,
            LandmarkType.LEFT_WRIST,
            LandmarkType.RIGHT_WRIST,
            LandmarkType.LEFT_HIP,
            LandmarkType.RIGHT_HIP,
        )

    private val LOWER_BODY =
        setOf(
            LandmarkType.LEFT_SHOULDER,
            LandmarkType.RIGHT_SHOULDER,
            LandmarkType.LEFT_HIP,
            LandmarkType.RIGHT_HIP,
            LandmarkType.LEFT_KNEE,
            LandmarkType.RIGHT_KNEE,
            LandmarkType.LEFT_ANKLE,
            LandmarkType.RIGHT_ANKLE,
        )

    /**
     * Arms-and-torso exercises. Feet are deliberately *not* required: a curl is fully measurable
     * without them, and demanding them would reject perfectly good framing.
     */
    fun upperBody(
        view: CameraView = CameraView.FRONT,
        extra: Set<LandmarkType> = emptySet(),
    ): ReadinessRequirements =
        ReadinessRequirements(
            requiredLandmarks = UPPER_BODY + extra,
            preferredView = view,
            minSubjectHeightFraction = 0.22,
            maxSubjectHeightFraction = 0.80,
        )

    /** Squat/hinge patterns. Ankles are required — depth is meaningless without them. */
    fun lowerBody(
        view: CameraView = CameraView.FRONT_DIAGONAL,
        extra: Set<LandmarkType> = emptySet(),
    ): ReadinessRequirements =
        ReadinessRequirements(
            requiredLandmarks = LOWER_BODY + extra,
            preferredView = view,
            minSubjectHeightFraction = 0.40,
            maxSubjectHeightFraction = 0.94,
        )

    /** True when two states differ in a way worth emitting an event for. */
    fun meaningfullyDifferent(
        a: ReadinessState,
        b: ReadinessState,
    ): Boolean = a.status != b.status || abs(a.confidence - b.confidence) > 0.15f
}
