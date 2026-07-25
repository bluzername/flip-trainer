package com.fliptrainer.core.synthetic

import com.fliptrainer.core.pose.Landmark
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.PoseFrame
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A parametric 2-D stick figure, for building traces that exercise the evaluators deterministically.
 *
 * This is **not** a claim about how a human moves. It is a rig with the right topology and the
 * right joint angles, which is exactly what the deterministic layer consumes: hysteresis,
 * direction confirmation, minimum excursion and dropout tolerance can all be tested precisely
 * here, and none of them care whether the pose looks like a photograph.
 *
 * What it cannot do is validate real-world accuracy. Any trace produced here carries
 * `deviceId = "synthetic"` and can never satisfy a physical validation gate (plan §11.3).
 *
 * Geometry convention: normalized image coordinates, y downwards. Angles are degrees measured
 * clockwise from straight down, so `0` is a limb hanging vertically and `180` points straight up.
 */
data class SyntheticPose(
    /** Horizontal position of the body midline, `0..1`. */
    val midlineX: Double = 0.5,
    /** Ground plane — where the ankles sit, `0..1`. */
    val groundY: Double = 0.95,
    /** Shoulder-to-hip distance as a fraction of frame height. Drives apparent distance. */
    val torso: Double = 0.20,
    /** Horizontal foreshortening, `0..1`. 1 is square to the camera, 0 is a pure side view. */
    val openness: Double = 1.0,
    /** Forward torso lean in degrees; positive tilts the shoulders towards +x. */
    val torsoLeanDeg: Double = 0.0,
    val left: LimbState = LimbState(),
    val right: LimbState = LimbState(),
    /** Confidence written to every landmark unless overridden per landmark. */
    val confidence: Float = 0.95f,
    /** Landmarks to omit entirely, simulating a model that lost them. */
    val absent: Set<LandmarkType> = emptySet(),
    /** Per-landmark confidence overrides, simulating partial occlusion. */
    val confidenceOverrides: Map<LandmarkType, Float> = emptyMap(),
) {
    /**
     * One side's joint configuration.
     *
     * @property elbowAngleDeg interior angle at the elbow. ~175 is a hanging arm, ~40 a full curl.
     * @property upperArmAngleDeg direction of shoulder→elbow from straight down. 0 is against the
     *   ribs; 180 is straight overhead.
     * @property kneeAngleDeg interior angle at the knee. 180 is standing, ~80 a deep squat.
     * @property shinAngleDeg direction of knee→ankle from straight down; positive puts the ankle
     *   behind the knee.
     */
    data class LimbState(
        val elbowAngleDeg: Double = 175.0,
        val upperArmAngleDeg: Double = 0.0,
        val kneeAngleDeg: Double = 180.0,
        val shinAngleDeg: Double = 0.0,
    )

    private val proportions = Proportions(torso)

    /** Build one [PoseFrame] at [timestampMs]. */
    fun frame(
        timestampMs: Long,
        imageWidth: Int = 720,
        imageHeight: Int = 1280,
        inferenceDurationMs: Int = 15,
    ): PoseFrame {
        require(imageWidth > 0 && imageHeight > 0) { "image width and height must be positive" }
        val points = LinkedHashMap<LandmarkType, Vec>()

        // Legs are built from the ground up so that bending the knees lowers the hips, which is
        // what makes squat depth a real signal rather than a free parameter.
        val ankleL = Vec(-proportions.hipHalfWidth * openness, groundY)
        val ankleR = Vec(proportions.hipHalfWidth * openness, groundY)
        val hipL = hipFromAnkle(ankleL, left)
        val hipR = hipFromAnkle(ankleR, right)
        val kneeL = kneeFromHip(hipL, left)
        val kneeR = kneeFromHip(hipR, right)

        val hipMid = hipL.midpoint(hipR)
        val leanRad = Math.toRadians(torsoLeanDeg)
        val shoulderMid = Vec(hipMid.x + torso * sin(leanRad), hipMid.y - torso * cos(leanRad))

        val shoulderL = Vec(shoulderMid.x - proportions.shoulderHalfWidth * openness, shoulderMid.y)
        val shoulderR = Vec(shoulderMid.x + proportions.shoulderHalfWidth * openness, shoulderMid.y)

        val elbowL = shoulderL + dir(mirrorAngle(left.upperArmAngleDeg)) * proportions.upperArm
        val elbowR = shoulderR + dir(right.upperArmAngleDeg) * proportions.upperArm
        val wristL = elbowL + dir(mirrorAngle(wristAngle(left))) * proportions.forearm
        val wristR = elbowR + dir(wristAngle(right)) * proportions.forearm

        points[LandmarkType.LEFT_SHOULDER] = shoulderL
        points[LandmarkType.RIGHT_SHOULDER] = shoulderR
        points[LandmarkType.LEFT_ELBOW] = elbowL
        points[LandmarkType.RIGHT_ELBOW] = elbowR
        points[LandmarkType.LEFT_WRIST] = wristL
        points[LandmarkType.RIGHT_WRIST] = wristR
        points[LandmarkType.LEFT_HIP] = hipL
        points[LandmarkType.RIGHT_HIP] = hipR
        points[LandmarkType.LEFT_KNEE] = kneeL
        points[LandmarkType.RIGHT_KNEE] = kneeR
        points[LandmarkType.LEFT_ANKLE] = ankleL
        points[LandmarkType.RIGHT_ANKLE] = ankleR

        // Head, hands and feet hang off the joints above. They exist so that clipping and
        // visibility rules have something to act on; their exact placement is not load-bearing.
        val head = Vec(shoulderMid.x + proportions.neck * sin(leanRad), shoulderMid.y - proportions.neck)
        points[LandmarkType.NOSE] = head
        points[LandmarkType.LEFT_EYE_INNER] = head + Vec(-0.005, -0.008)
        points[LandmarkType.LEFT_EYE] = head + Vec(-0.010, -0.008)
        points[LandmarkType.LEFT_EYE_OUTER] = head + Vec(-0.015, -0.008)
        points[LandmarkType.RIGHT_EYE_INNER] = head + Vec(0.005, -0.008)
        points[LandmarkType.RIGHT_EYE] = head + Vec(0.010, -0.008)
        points[LandmarkType.RIGHT_EYE_OUTER] = head + Vec(0.015, -0.008)
        points[LandmarkType.LEFT_EAR] = head + Vec(-0.022 * openness, -0.004)
        points[LandmarkType.RIGHT_EAR] = head + Vec(0.022 * openness, -0.004)
        points[LandmarkType.MOUTH_LEFT] = head + Vec(-0.008, 0.012)
        points[LandmarkType.MOUTH_RIGHT] = head + Vec(0.008, 0.012)

        addHand(points, wristL, elbowL, Side3.LEFT)
        addHand(points, wristR, elbowR, Side3.RIGHT)
        addFoot(points, ankleL, kneeL, Side3.LEFT)
        addFoot(points, ankleR, kneeR, Side3.RIGHT)

        // The rig is authored in isotropic space — every offset above is in units of frame
        // *height*, on both axes. Converting x back to width-normalized here is what makes the
        // figure physically proportioned on any aspect ratio, and it is the exact inverse of the
        // correction Geometry applies. Author in normalized-x instead and the figure silently
        // becomes too narrow on a portrait frame, which reads as "athlete turned sideways".
        val aspectRatio = imageWidth.toDouble() / imageHeight
        val landmarks =
            points
                .filterKeys { it !in absent }
                .map { (type, v) ->
                    val c = confidenceOverrides[type] ?: confidence
                    Landmark(
                        type = type,
                        x = (midlineX + v.x / aspectRatio).toFloat(),
                        y = v.y.toFloat(),
                        z = 0f,
                        visibility = c,
                        presence = c,
                    )
                }

        return PoseFrame(
            monotonicTimestampMs = timestampMs,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            landmarks = landmarks,
            inferenceDurationMs = inferenceDurationMs,
        )
    }

    /** Translate the whole figure — used to simulate camera drift. */
    fun translated(
        dx: Double = 0.0,
        dy: Double = 0.0,
    ): SyntheticPose = copy(midlineX = midlineX + dx, groundY = groundY + dy)

    private fun hipFromAnkle(
        ankle: Vec,
        limb: LimbState,
    ): Vec {
        val thighAngle = thighAngle(limb)
        val shin = dir(limb.shinAngleDeg) * proportions.shin
        val thigh = dir(thighAngle) * proportions.thigh
        return ankle - shin - thigh
    }

    private fun kneeFromHip(
        hip: Vec,
        limb: LimbState,
    ): Vec = hip + dir(thighAngle(limb)) * proportions.thigh

    /**
     * Interior knee angle `θ` relates the thigh and shin directions by `θ = 180 − (shin − thigh)`,
     * so the thigh direction is recovered as `shin − (180 − θ)`.
     */
    private fun thighAngle(limb: LimbState): Double = limb.shinAngleDeg - (180.0 - limb.kneeAngleDeg)

    /**
     * Interior elbow angle `θ` is the angle at the elbow between the upper arm and the forearm,
     * so the forearm direction is the reversed upper arm rotated back by `θ`.
     */
    private fun wristAngle(limb: LimbState): Double = limb.upperArmAngleDeg + 180.0 - limb.elbowAngleDeg

    /** Mirror an angle across the vertical axis, so the left side folds inwards like the right. */
    private fun mirrorAngle(deg: Double): Double = -deg

    private fun addHand(
        points: MutableMap<LandmarkType, Vec>,
        wrist: Vec,
        elbow: Vec,
        side: Side3,
    ) {
        val out = (wrist - elbow).normalizedOr(Vec(0.0, 1.0)) * proportions.hand
        val base = wrist + out
        when (side) {
            Side3.LEFT -> {
                points[LandmarkType.LEFT_PINKY] = base + Vec(-0.008, 0.0)
                points[LandmarkType.LEFT_INDEX] = base + Vec(0.008, 0.0)
                points[LandmarkType.LEFT_THUMB] = base + Vec(0.0, -0.008)
            }
            Side3.RIGHT -> {
                points[LandmarkType.RIGHT_PINKY] = base + Vec(0.008, 0.0)
                points[LandmarkType.RIGHT_INDEX] = base + Vec(-0.008, 0.0)
                points[LandmarkType.RIGHT_THUMB] = base + Vec(0.0, -0.008)
            }
        }
    }

    private fun addFoot(
        points: MutableMap<LandmarkType, Vec>,
        ankle: Vec,
        knee: Vec,
        side: Side3,
    ) {
        val down = (ankle - knee).normalizedOr(Vec(0.0, 1.0))
        val heel = ankle + down * (proportions.foot * 0.3)
        val toeDirection = if (side == Side3.LEFT) -1.0 else 1.0
        when (side) {
            Side3.LEFT -> {
                points[LandmarkType.LEFT_HEEL] = heel
                points[LandmarkType.LEFT_FOOT_INDEX] = heel + Vec(toeDirection * proportions.foot, 0.004)
            }
            Side3.RIGHT -> {
                points[LandmarkType.RIGHT_HEEL] = heel
                points[LandmarkType.RIGHT_FOOT_INDEX] = heel + Vec(toeDirection * proportions.foot, 0.004)
            }
        }
    }

    private enum class Side3 { LEFT, RIGHT }

    /**
     * Segment lengths as multiples of torso length, in isotropic units.
     *
     * Shoulder and hip widths are set from adult anthropometry (biacromial ≈ 0.84 × torso,
     * bi-iliac ≈ 0.60 × torso) because [com.fliptrainer.core.features.Geometry.estimateView]
     * classifies front-versus-side from exactly that ratio. A narrower rig would classify a
     * square-on figure as turned away and make every view test meaningless.
     */
    private class Proportions(
        torso: Double,
    ) {
        val shoulderHalfWidth = 0.42 * torso
        val hipHalfWidth = 0.30 * torso
        val upperArm = 0.62 * torso
        val forearm = 0.54 * torso
        val thigh = 0.92 * torso
        val shin = 0.88 * torso
        val neck = 0.42 * torso
        val hand = 0.12 * torso
        val foot = 0.20 * torso
    }

    internal data class Vec(
        val x: Double,
        val y: Double,
    ) {
        operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)

        operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)

        operator fun times(s: Double) = Vec(x * s, y * s)

        fun midpoint(o: Vec) = Vec((x + o.x) / 2, (y + o.y) / 2)

        fun normalizedOr(fallback: Vec): Vec {
            val len = hypot(x, y)
            return if (len < 1e-9) fallback else Vec(x / len, y / len)
        }
    }

    private companion object {
        /** Unit vector at [deg] clockwise from straight down (screen y grows downwards). */
        fun dir(deg: Double): Vec {
            val r = Math.toRadians(deg)
            return Vec(sin(r), cos(r))
        }
    }
}
