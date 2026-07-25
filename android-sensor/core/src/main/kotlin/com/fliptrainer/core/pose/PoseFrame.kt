package com.fliptrainer.core.pose

import kotlin.math.min

/**
 * One landmark in **upright normalized image space**: origin top-left, x rightwards, y downwards,
 * both in `[0, 1]` after rotation has been applied. Values outside `[0, 1]` mean the model placed
 * the landmark off-frame, which is meaningful and is deliberately not clamped.
 *
 * @property z the model's depth estimate, relative to the hip midpoint in units of roughly torso
 *   width. **Never present this to a user as physical depth** (plan §6.1) and never use it for a
 *   metric a user sees. It is admissible only as a weak tiebreaker.
 */
data class Landmark(
    val type: LandmarkType,
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
    val presence: Float,
) {
    /**
     * Single confidence number for gating. The pessimistic combination is intentional: a landmark
     * the model is confident is *present but occluded* is not a landmark we may measure against.
     */
    val confidence: Float get() = min(visibility, presence)

    /** True when the landmark sits inside the frame at all. */
    val inFrame: Boolean get() = x in 0f..1f && y in 0f..1f
}

/**
 * One inference result, normalized to upright space.
 *
 * An empty [landmarks] list means no person was detected — that is a valid frame, not an error,
 * and the readiness gate depends on receiving it.
 */
data class PoseFrame(
    val monotonicTimestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val landmarks: List<Landmark>,
    val inferenceDurationMs: Int,
) {
    private val byType: Map<LandmarkType, Landmark> = landmarks.associateBy { it.type }

    val hasPerson: Boolean get() = landmarks.isNotEmpty()

    /** Aspect ratio of the upright image. Used to convert normalized deltas into square space. */
    val aspectRatio: Float get() = if (imageHeight == 0) 1f else imageWidth.toFloat() / imageHeight

    operator fun get(type: LandmarkType): Landmark? = byType[type]

    /** The landmark, but only if it clears [minConfidence]. Callers must handle null by abstaining. */
    fun confident(
        type: LandmarkType,
        minConfidence: Float,
    ): Landmark? = byType[type]?.takeIf { it.confidence >= minConfidence }

    fun confidenceOf(type: LandmarkType): Float = byType[type]?.confidence ?: 0f

    /** Lowest confidence across [types]; `0` if any is missing entirely. */
    fun minConfidenceOf(types: Iterable<LandmarkType>): Float {
        var lowest = Float.MAX_VALUE
        var any = false
        for (t in types) {
            any = true
            val c = byType[t]?.confidence ?: return 0f
            if (c < lowest) lowest = c
        }
        return if (any) lowest else 0f
    }

    fun allPresent(types: Iterable<LandmarkType>): Boolean = types.all { byType.containsKey(it) }

    companion object {
        /** A detected-nothing frame. */
        fun empty(
            monotonicTimestampMs: Long,
            imageWidth: Int,
            imageHeight: Int,
            inferenceDurationMs: Int = 0,
        ): PoseFrame =
            PoseFrame(
                monotonicTimestampMs = monotonicTimestampMs,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                landmarks = emptyList(),
                inferenceDurationMs = inferenceDurationMs,
            )
    }
}
