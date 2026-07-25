package com.fliptrainer.core.synthetic

import com.fliptrainer.core.pose.Landmark
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.PoseFrame

/**
 * Composes [SyntheticPose] snapshots into timed movement, so an evaluator can be driven through a
 * whole set without a camera.
 *
 * Everything here is deterministic, including the noise. A fixture that changed between runs
 * would make a golden file useless.
 */
object SyntheticMotion {
    /**
     * One repetition's timing and depth.
     *
     * @property peak fraction of the full range actually reached. Below the definition's
     *   `minPeakProgress` this produces a partial repetition, which is how partial-rejection is
     *   tested.
     */
    data class RepProfile(
        val outboundMs: Long = 900,
        val apexHoldMs: Long = 120,
        val returnMs: Long = 1_100,
        val restMs: Long = 500,
        val peak: Double = 1.0,
    ) {
        val totalMs: Long get() = outboundMs + apexHoldMs + returnMs + restMs
    }

    /**
     * Render a set.
     *
     * @param poseAt maps movement fraction `u` (0 = rest, 1 = full range) to a figure.
     * @param leadInMs time standing still before the first repetition. Must comfortably exceed
     *   the readiness hold, or the set begins before the gate has settled and the first rep is
     *   silently lost.
     */
    fun set(
        profiles: List<RepProfile>,
        poseAt: (Double) -> SyntheticPose,
        leadInMs: Long = 1_500,
        tailMs: Long = 800,
        fps: Int = 30,
        startMs: Long = 0,
        imageWidth: Int = 720,
        imageHeight: Int = 1280,
    ): List<PoseFrame> {
        require(fps > 0) { "fps must be positive" }
        val stepMs = (1000.0 / fps).toLong().coerceAtLeast(1)
        val timeline = buildTimeline(profiles, leadInMs, tailMs)
        val totalMs = timeline.last().endMs

        val frames = ArrayList<PoseFrame>()
        var elapsed = 0L
        var index = 0
        while (elapsed <= totalMs) {
            while (index < timeline.size - 1 && elapsed > timeline[index].endMs) index++
            val u = timeline[index].valueAt(elapsed)
            frames +=
                poseAt(u).frame(
                    timestampMs = startMs + elapsed,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                )
            elapsed += stepMs
        }
        return frames
    }

    /** Convenience for a set of identical repetitions. */
    fun set(
        reps: Int,
        profile: RepProfile = RepProfile(),
        poseAt: (Double) -> SyntheticPose,
        leadInMs: Long = 1_500,
        tailMs: Long = 800,
        fps: Int = 30,
        startMs: Long = 0,
        imageWidth: Int = 720,
        imageHeight: Int = 1280,
    ): List<PoseFrame> = set(List(reps) { profile }, poseAt, leadInMs, tailMs, fps, startMs, imageWidth, imageHeight)

    /**
     * Blank the landmarks in `[fromMs, toMs)`, simulating the model losing the athlete.
     *
     * Landmarks are removed rather than given low confidence, so the evaluator's "missing" path
     * and its "under-confident" path can be exercised separately.
     */
    fun withLandmarksMissing(
        frames: List<PoseFrame>,
        fromMs: Long,
        toMs: Long,
        types: Set<LandmarkType>? = null,
    ): List<PoseFrame> =
        frames.map { frame ->
            if (frame.monotonicTimestampMs !in fromMs until toMs) {
                frame
            } else {
                frame.copy(landmarks = frame.landmarks.filterNot { types == null || it.type in types })
            }
        }

    /** Drop the confidence of [types] within a window, simulating occlusion rather than loss. */
    fun withLowConfidence(
        frames: List<PoseFrame>,
        fromMs: Long,
        toMs: Long,
        types: Set<LandmarkType>,
        confidence: Float = 0.2f,
    ): List<PoseFrame> =
        frames.map { frame ->
            if (frame.monotonicTimestampMs !in fromMs until toMs) {
                frame
            } else {
                frame.copy(
                    landmarks =
                        frame.landmarks.map {
                            if (it.type in types) it.copy(visibility = confidence, presence = confidence) else it
                        },
                )
            }
        }

    /**
     * Add reproducible positional noise.
     *
     * Uses an explicit linear congruential generator rather than [kotlin.random.Random], because
     * a fixture must produce byte-identical output on every machine and every JDK.
     *
     * @param amplitude maximum displacement in normalized units, applied independently per axis.
     */
    fun withJitter(
        frames: List<PoseFrame>,
        amplitude: Double,
        seed: Long = 1,
    ): List<PoseFrame> {
        var state = seed

        fun next(): Double {
            state = (state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L)
            // Top 26 bits, mapped to [-1, 1].
            val bits = (state ushr 38).toDouble() / (1L shl 26).toDouble()
            return bits * 2.0 - 1.0
        }
        return frames.map { frame ->
            frame.copy(
                landmarks =
                    frame.landmarks.map {
                        Landmark(
                            type = it.type,
                            x = (it.x + next() * amplitude).toFloat(),
                            y = (it.y + next() * amplitude).toFloat(),
                            z = it.z,
                            visibility = it.visibility,
                            presence = it.presence,
                        )
                    },
            )
        }
    }

    /** Linear interpolation, for mapping movement fraction onto a joint angle. */
    fun lerp(
        from: Double,
        to: Double,
        u: Double,
    ): Double = from + (to - from) * u

    private fun buildTimeline(
        profiles: List<RepProfile>,
        leadInMs: Long,
        tailMs: Long,
    ): List<Segment> {
        val segments = ArrayList<Segment>()
        var cursor = 0L

        fun add(
            durationMs: Long,
            from: Double,
            to: Double,
        ) {
            if (durationMs <= 0) return
            segments += Segment(cursor, cursor + durationMs, from, to)
            cursor += durationMs
        }

        add(leadInMs, 0.0, 0.0)
        for (p in profiles) {
            add(p.outboundMs, 0.0, p.peak)
            add(p.apexHoldMs, p.peak, p.peak)
            add(p.returnMs, p.peak, 0.0)
            add(p.restMs, 0.0, 0.0)
        }
        add(tailMs, 0.0, 0.0)

        if (segments.isEmpty()) segments += Segment(0, 1, 0.0, 0.0)
        return segments
    }

    /**
     * One timed ramp, eased at both ends.
     *
     * Smoothstep rather than a linear ramp: a real turnaround has near-zero velocity, and the
     * resulting shallow reversal is a strictly harder test for direction confirmation than a
     * sharp corner would be.
     */
    private data class Segment(
        val startMs: Long,
        val endMs: Long,
        val from: Double,
        val to: Double,
    ) {
        fun valueAt(elapsedMs: Long): Double {
            if (endMs <= startMs) return to
            val t = ((elapsedMs - startMs).toDouble() / (endMs - startMs)).coerceIn(0.0, 1.0)
            val eased = t * t * (3 - 2 * t)
            return from + (to - from) * eased
        }
    }
}
