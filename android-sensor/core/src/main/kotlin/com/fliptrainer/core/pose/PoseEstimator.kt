package com.fliptrainer.core.pose

/**
 * A camera frame, described without naming a platform type.
 *
 * The Android implementation carries the `ImageProxy`; core only ever needs the geometry and the
 * timestamp. Core must never see `ImageProxy` — see ADR-0001.
 */
interface CameraFrame {
    /** Buffer width, before rotation. */
    val widthPx: Int

    /** Buffer height, before rotation. */
    val heightPx: Int

    /** Clockwise rotation needed to make the buffer upright (CameraX `rotationDegrees`). */
    val rotationDegrees: Int

    /** Monotonic capture timestamp. */
    val timestampMs: Long
}

/**
 * The one abstraction boundary around pose inference (plan §5.4).
 *
 * Exactly one implementation exists — `MediaPipePoseEstimator` in the Android module. A second
 * implementation requires a documented failure of the first (ADR-0003); the interface is here to
 * keep core testable, not to host a plugin ecosystem.
 *
 * Implementations must be single-consumer: [estimate] is called from one analysis thread.
 */
interface PoseEstimator {
    /** Load the model. Cheap to call twice; expensive the first time. */
    suspend fun start()

    /**
     * Run inference on one frame.
     *
     * Returns `null` when the frame produced no usable result at all (model not ready, frame
     * unconvertible). A frame where no person was found is **not** null — it is a [PoseFrame] with
     * an empty landmark list, because the readiness gate needs to see it.
     *
     * The returned frame must already be in upright space (see [ImageTransform]).
     */
    suspend fun estimate(frame: CameraFrame): PoseFrame?

    /** Release the model. Must be idempotent. */
    suspend fun stop()
}

/**
 * Deterministic [PoseEstimator] that serves pre-recorded frames.
 *
 * This is what makes offline replay possible: the same evaluator code path that runs on the
 * Flip5 runs in a unit test, fed from a trace file instead of a camera.
 */
class ReplayPoseEstimator(
    private val frames: List<PoseFrame>,
) : PoseEstimator {
    private var index = 0
    private var started = false

    val exhausted: Boolean get() = index >= frames.size

    override suspend fun start() {
        started = true
    }

    override suspend fun estimate(frame: CameraFrame): PoseFrame? {
        check(started) { "estimate() before start()" }
        if (exhausted) return null
        return frames[index++]
    }

    override suspend fun stop() {
        started = false
    }

    fun rewind() {
        index = 0
    }
}
