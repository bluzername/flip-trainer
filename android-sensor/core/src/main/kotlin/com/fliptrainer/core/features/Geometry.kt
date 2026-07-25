package com.fliptrainer.core.features

import com.fliptrainer.core.pose.Landmark
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.pose.Side
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A point in **aspect-corrected** space: both axes in units of image *height*.
 *
 * This correction is not cosmetic. Landmarks arrive normalized independently on each axis, so on a
 * 16:9 frame one unit of x is 1.78× longer than one unit of y. Computing an angle directly from
 * raw normalized coordinates produces a number that is wrong by tens of degrees and varies with
 * the camera's aspect ratio — an error that survives every visual check because the skeleton still
 * looks right. Everything in this file works in corrected space.
 */
data class Point2(
    val x: Double,
    val y: Double,
) {
    operator fun minus(other: Point2): Point2 = Point2(x - other.x, y - other.y)

    operator fun plus(other: Point2): Point2 = Point2(x + other.x, y + other.y)

    operator fun times(scale: Double): Point2 = Point2(x * scale, y * scale)

    val length: Double get() = hypot(x, y)

    fun distanceTo(other: Point2): Double = hypot(x - other.x, y - other.y)

    fun midpointWith(other: Point2): Point2 = Point2((x + other.x) / 2.0, (y + other.y) / 2.0)

    companion object {
        val ORIGIN = Point2(0.0, 0.0)
    }
}

/**
 * Stateless geometric primitives (plan §7.2).
 *
 * Every function returns null rather than a fabricated number when the landmarks it needs are
 * absent or below the confidence floor. That is the abstention rule expressed in code: an
 * evaluator that wants a value must handle not getting one.
 */
object Geometry {
    /** Corrected-space position of a landmark, or null if it is missing or under-confident. */
    fun point(
        frame: PoseFrame,
        type: LandmarkType,
        minConfidence: Float = 0f,
    ): Point2? {
        val lm = frame.confident(type, minConfidence) ?: return null
        return point(lm, frame.aspectRatio)
    }

    fun point(
        landmark: Landmark,
        aspectRatio: Float,
    ): Point2 = Point2(landmark.x.toDouble() * aspectRatio, landmark.y.toDouble())

    fun midpoint(
        frame: PoseFrame,
        a: LandmarkType,
        b: LandmarkType,
        minConfidence: Float = 0f,
    ): Point2? {
        val pa = point(frame, a, minConfidence) ?: return null
        val pb = point(frame, b, minConfidence) ?: return null
        return pa.midpointWith(pb)
    }

    /**
     * Interior angle at [vertex] in the path [a] → [vertex] → [c], in degrees, `0..180`.
     *
     * 180° is a fully extended joint. For an elbow: ~170° arm hanging straight, ~40° fully curled.
     */
    fun jointAngle(
        frame: PoseFrame,
        a: LandmarkType,
        vertex: LandmarkType,
        c: LandmarkType,
        minConfidence: Float = 0f,
    ): Double? {
        val pa = point(frame, a, minConfidence) ?: return null
        val pv = point(frame, vertex, minConfidence) ?: return null
        val pc = point(frame, c, minConfidence) ?: return null
        return jointAngle(pa, pv, pc)
    }

    fun jointAngle(
        a: Point2,
        vertex: Point2,
        c: Point2,
    ): Double? {
        val u = a - vertex
        val v = c - vertex
        val denom = u.length * v.length
        if (denom < EPSILON) return null // degenerate: coincident points carry no angle
        val cos = ((u.x * v.x + u.y * v.y) / denom).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    /**
     * Angle of the vector [from] → [to] measured clockwise from straight up, in degrees,
     * `(-180, 180]`.
     *
     * Straight up is 0, right is +90, down is ±180, left is −90. Screen y grows downwards, which
     * is why the y term is negated.
     */
    fun signedAngleFromVertical(
        from: Point2,
        to: Point2,
    ): Double? {
        val d = to - from
        if (d.length < EPSILON) return null
        return Math.toDegrees(atan2(d.x, -d.y))
    }

    /**
     * Absolute tilt of the line [a]–[b] away from horizontal, in degrees, `0..90`.
     *
     * Used for shoulder-line and hip-line inclination, where only the magnitude of the tilt
     * matters and the direction is handled separately by a symmetry feature.
     */
    fun lineInclinationFromHorizontal(
        a: Point2,
        b: Point2,
    ): Double? {
        val d = b - a
        if (d.length < EPSILON) return null
        return abs(Math.toDegrees(atan2(d.y, d.x))).let { if (it > 90.0) 180.0 - it else it }
    }

    /** Shoulder separation in corrected units. Shrinks towards zero in a pure side view. */
    fun shoulderWidth(
        frame: PoseFrame,
        minConfidence: Float = 0f,
    ): Double? {
        val l = point(frame, LandmarkType.LEFT_SHOULDER, minConfidence) ?: return null
        val r = point(frame, LandmarkType.RIGHT_SHOULDER, minConfidence) ?: return null
        return l.distanceTo(r)
    }

    fun hipWidth(
        frame: PoseFrame,
        minConfidence: Float = 0f,
    ): Double? {
        val l = point(frame, LandmarkType.LEFT_HIP, minConfidence) ?: return null
        val r = point(frame, LandmarkType.RIGHT_HIP, minConfidence) ?: return null
        return l.distanceTo(r)
    }

    /** Shoulder-midpoint to hip-midpoint distance. */
    fun torsoLength(
        frame: PoseFrame,
        minConfidence: Float = 0f,
    ): Double? {
        val shoulders = midpoint(frame, LandmarkType.LEFT_SHOULDER, LandmarkType.RIGHT_SHOULDER, minConfidence)
            ?: return null
        val hips = midpoint(frame, LandmarkType.LEFT_HIP, LandmarkType.RIGHT_HIP, minConfidence) ?: return null
        return shoulders.distanceTo(hips)
    }

    /**
     * The length every other distance is divided by, so metrics survive the athlete standing
     * closer or further from the camera.
     *
     * Torso length is preferred over shoulder width because it barely changes as the athlete
     * rotates, whereas shoulder width collapses in a side view — which would silently inflate
     * every normalized distance exactly when the view is hardest.
     */
    fun bodyScale(
        frame: PoseFrame,
        minConfidence: Float = 0f,
    ): Double? {
        torsoLength(frame, minConfidence)?.takeIf { it > EPSILON }?.let { return it }
        shoulderWidth(frame, minConfidence)?.takeIf { it > EPSILON }?.let { return it }
        return hipWidth(frame, minConfidence)?.takeIf { it > EPSILON }
    }

    /** Distance between two landmarks expressed in body-scale units. */
    fun normalizedDistance(
        frame: PoseFrame,
        a: LandmarkType,
        b: LandmarkType,
        minConfidence: Float = 0f,
    ): Double? {
        val pa = point(frame, a, minConfidence) ?: return null
        val pb = point(frame, b, minConfidence) ?: return null
        val scale = bodyScale(frame, minConfidence) ?: return null
        return pa.distanceTo(pb) / scale
    }

    /**
     * Signed vertical offset of [a] above [b], in body-scale units. Positive means [a] is higher
     * on screen, which is the direction a human calls "up".
     */
    fun normalizedVerticalOffset(
        frame: PoseFrame,
        a: LandmarkType,
        b: LandmarkType,
        minConfidence: Float = 0f,
    ): Double? {
        val pa = point(frame, a, minConfidence) ?: return null
        val pb = point(frame, b, minConfidence) ?: return null
        val scale = bodyScale(frame, minConfidence) ?: return null
        return (pb.y - pa.y) / scale
    }

    /** Signed horizontal offset of [a] relative to [b], in body-scale units; positive is rightwards. */
    fun normalizedHorizontalOffset(
        frame: PoseFrame,
        a: LandmarkType,
        b: LandmarkType,
        minConfidence: Float = 0f,
    ): Double? {
        val pa = point(frame, a, minConfidence) ?: return null
        val pb = point(frame, b, minConfidence) ?: return null
        val scale = bodyScale(frame, minConfidence) ?: return null
        return (pa.x - pb.x) / scale
    }

    /**
     * Torso lean away from vertical, in degrees. 0 is upright; positive is leaning right on
     * screen, negative left. A hip hinge shows up here as a large magnitude.
     */
    fun torsoInclination(
        frame: PoseFrame,
        minConfidence: Float = 0f,
    ): Double? {
        val shoulders = midpoint(frame, LandmarkType.LEFT_SHOULDER, LandmarkType.RIGHT_SHOULDER, minConfidence)
            ?: return null
        val hips = midpoint(frame, LandmarkType.LEFT_HIP, LandmarkType.RIGHT_HIP, minConfidence) ?: return null
        return signedAngleFromVertical(hips, shoulders)
    }

    fun shoulderLineInclination(
        frame: PoseFrame,
        minConfidence: Float = 0f,
    ): Double? {
        val l = point(frame, LandmarkType.LEFT_SHOULDER, minConfidence) ?: return null
        val r = point(frame, LandmarkType.RIGHT_SHOULDER, minConfidence) ?: return null
        return lineInclinationFromHorizontal(l, r)
    }

    fun hipLineInclination(
        frame: PoseFrame,
        minConfidence: Float = 0f,
    ): Double? {
        val l = point(frame, LandmarkType.LEFT_HIP, minConfidence) ?: return null
        val r = point(frame, LandmarkType.RIGHT_HIP, minConfidence) ?: return null
        return lineInclinationFromHorizontal(l, r)
    }

    /**
     * Absolute difference between the same measurement taken on each side.
     *
     * Both sides must resolve; a one-sided "symmetry" number is meaningless and returning one
     * would let an asymmetry cue fire on an occluded limb.
     */
    fun symmetry(
        left: Double?,
        right: Double?,
    ): Double? {
        if (left == null || right == null) return null
        return abs(left - right)
    }

    /** Mean confidence across [types]; null if any is absent. Aggregate gate for a cue window. */
    fun visibilityAggregate(
        frame: PoseFrame,
        types: Collection<LandmarkType>,
    ): Double? {
        if (types.isEmpty()) return null
        var sum = 0.0
        for (t in types) {
            val lm = frame[t] ?: return null
            sum += lm.confidence
        }
        return sum / types.size
    }

    /**
     * Which way the athlete is facing, inferred from how wide the shoulders and hips look relative
     * to the torso. Facing the camera the shoulder line spans a large fraction of torso length;
     * turned side-on it collapses.
     *
     * A rough classifier on purpose. It gates whether a view-dependent cue may run at all — it is
     * never used to produce a number a user sees.
     */
    fun estimateView(
        frame: PoseFrame,
        minConfidence: Float = 0f,
    ): ViewEstimate? {
        val torso = torsoLength(frame, minConfidence)?.takeIf { it > EPSILON } ?: return null
        val shoulders = shoulderWidth(frame, minConfidence) ?: return null
        val hips = hipWidth(frame, minConfidence) ?: return null
        val openness = ((shoulders + hips) / 2.0) / torso
        val view =
            when {
                openness >= FRONT_OPENNESS -> CameraView.FRONT
                openness <= SIDE_OPENNESS -> CameraView.SIDE
                else -> CameraView.FRONT_DIAGONAL
            }
        return ViewEstimate(view, openness)
    }

    /**
     * Which side of the body is closer to the camera, from the model's relative depth.
     *
     * `modelZ` is not calibrated depth and must never surface as a measurement (plan §6.1). Used
     * only as a weak tiebreaker for "which arm is nearer", and only when the gap is decisive.
     */
    fun nearerSide(
        frame: PoseFrame,
        minConfidence: Float = 0f,
    ): Side? {
        val l = frame.confident(LandmarkType.LEFT_SHOULDER, minConfidence) ?: return null
        val r = frame.confident(LandmarkType.RIGHT_SHOULDER, minConfidence) ?: return null
        val gap = abs(l.z - r.z)
        if (gap < DEPTH_TIEBREAK_MIN) return null
        return if (l.z < r.z) Side.LEFT else Side.RIGHT
    }

    /** Axis-aligned bounding box of [types] in corrected space; null if any is missing. */
    fun boundingBox(
        frame: PoseFrame,
        types: Collection<LandmarkType>,
        minConfidence: Float = 0f,
    ): BoundingBox? {
        if (types.isEmpty()) return null
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (t in types) {
            val p = point(frame, t, minConfidence) ?: return null
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        return BoundingBox(minX, minY, maxX, maxY)
    }

    /** Root-mean-square of a set of values. Used for coherent-motion detection. */
    internal fun rms(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        for (v in values) sum += v * v
        return sqrt(sum / values.size)
    }

    internal const val EPSILON: Double = 1e-9

    /** Openness at or above which the athlete is treated as facing the camera. */
    const val FRONT_OPENNESS: Double = 0.62

    /** Openness at or below which the athlete is treated as side-on. */
    const val SIDE_OPENNESS: Double = 0.32

    /** Minimum modelZ gap before the depth tiebreaker is allowed to answer at all. */
    const val DEPTH_TIEBREAK_MIN: Float = 0.10f
}

data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val centerX: Double get() = (minX + maxX) / 2.0
    val centerY: Double get() = (minY + maxY) / 2.0
}

enum class CameraView {
    FRONT,
    FRONT_DIAGONAL,
    SIDE,
    ;

    /**
     * Whether a set-up requiring `this` view is satisfied by [actual].
     *
     * A front-diagonal requirement accepts front too: the diagonal exists to make a symmetry cue
     * possible, and a straight-on view is not worse for anything else.
     */
    fun isSatisfiedBy(actual: CameraView): Boolean =
        when (this) {
            FRONT -> actual == FRONT || actual == FRONT_DIAGONAL
            FRONT_DIAGONAL -> actual == FRONT_DIAGONAL || actual == FRONT
            SIDE -> actual == SIDE
        }
}

data class ViewEstimate(
    val view: CameraView,
    val openness: Double,
)
