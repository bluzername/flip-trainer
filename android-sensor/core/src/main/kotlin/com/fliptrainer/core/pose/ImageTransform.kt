package com.fliptrainer.core.pose

/**
 * Maps landmarks from **sensor buffer space** (what the model actually saw) into **upright image
 * space** (what the athlete would call up, down, left and right).
 *
 * Getting this wrong is the single most common way a pose pipeline silently produces garbage:
 * every angle is still computable, every skeleton still renders, and every metric is meaningless.
 * Session 3's acceptance criteria call it out specifically — a skeleton must track correctly in
 * portrait *and* landscape, and left/right must not be swapped by a display transform.
 *
 * @property rotationDegrees clockwise rotation needed to make the buffer upright. This is exactly
 *   CameraX's `ImageInfo.rotationDegrees`. Must be 0, 90, 180 or 270.
 * @property horizontallyMirrored true when the frame handed to the model was flipped left-to-right
 *   (front camera, or a mirrored preview). The rear camera path used by this product is **not**
 *   mirrored; the flag exists so the mistake is representable and testable rather than latent.
 */
data class ImageTransform(
    val rotationDegrees: Int,
    val horizontallyMirrored: Boolean = false,
) {
    init {
        require(rotationDegrees in ALLOWED_ROTATIONS) {
            "rotationDegrees must be one of $ALLOWED_ROTATIONS, was $rotationDegrees"
        }
    }

    /** True when the rotation swaps the width and height of the image. */
    val swapsAxes: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    /**
     * Apply the transform to raw model output.
     *
     * @param bufferWidth width of the buffer the model consumed, before rotation.
     * @param bufferHeight height of the buffer the model consumed, before rotation.
     */
    fun apply(
        landmarks: List<Landmark>,
        bufferWidth: Int,
        bufferHeight: Int,
        monotonicTimestampMs: Long,
        inferenceDurationMs: Int,
    ): PoseFrame =
        PoseFrame(
            monotonicTimestampMs = monotonicTimestampMs,
            imageWidth = if (swapsAxes) bufferHeight else bufferWidth,
            imageHeight = if (swapsAxes) bufferWidth else bufferHeight,
            landmarks = landmarks.map(::apply),
            inferenceDurationMs = inferenceDurationMs,
        )

    /**
     * Transform one landmark.
     *
     * Order matters: un-mirror first (in buffer space, where the flip happened), then rotate.
     * Un-mirroring also swaps the lateral label, because a model shown a flipped image labels a
     * left arm as a right arm — it is reporting what the picture looks like, not anatomy.
     */
    fun apply(landmark: Landmark): Landmark {
        var x = landmark.x
        var y = landmark.y
        var type = landmark.type

        if (horizontallyMirrored) {
            x = 1f - x
            type = type.opposite
        }

        val rotated =
            when (rotationDegrees) {
                0 -> x to y
                90 -> (1f - y) to x
                180 -> (1f - x) to (1f - y)
                270 -> y to (1f - x)
                else -> error("unreachable: rotation validated in init")
            }

        return landmark.copy(type = type, x = rotated.first, y = rotated.second)
    }

    companion object {
        private val ALLOWED_ROTATIONS = setOf(0, 90, 180, 270)

        val IDENTITY = ImageTransform(rotationDegrees = 0, horizontallyMirrored = false)
    }
}
