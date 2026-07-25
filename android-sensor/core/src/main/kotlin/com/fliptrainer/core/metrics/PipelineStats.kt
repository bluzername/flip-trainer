package com.fliptrainer.core.metrics

/**
 * Everything Sessions 2, 3 and 14 ask to be *measured* rather than asserted, in one place.
 *
 * Lives in core, not in the camera callback, so the numbers that end up in `DEVICE_NOTES.md` come
 * from code that has unit tests rather than from a log line someone eyeballed.
 */
class PipelineStats(
    windowMs: Long = FrameRateMeter.DEFAULT_WINDOW_MS,
    sampleCapacity: Int = SampleWindow.DEFAULT_CAPACITY,
) {
    private val cameraRate = FrameRateMeter(windowMs)
    private val analysisRate = FrameRateMeter(windowMs)
    private val inferenceDurations = SampleWindow(sampleCapacity)
    private val endToEndLatency = SampleWindow(sampleCapacity)

    private var droppedByBackpressure = 0L
    private var inferenceFailures = 0L
    private var cameraStartLatencyMs: Long? = null
    private var lastGeometry: FrameGeometry? = null

    /** Camera delivered a frame to `ImageAnalysis`. */
    fun onCameraFrame(
        timestampMs: Long,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ) {
        cameraRate.record(timestampMs)
        val geometry = FrameGeometry(width, height, rotationDegrees)
        if (geometry != lastGeometry) lastGeometry = geometry
    }

    /**
     * A delivered frame was discarded without inference.
     *
     * With `STRATEGY_KEEP_ONLY_LATEST` this is expected and healthy — it is the backpressure
     * working. It becomes a finding only when the *analysis* rate falls below the target.
     */
    fun onFrameDropped() {
        droppedByBackpressure++
    }

    /** Inference completed. [inferenceDurationMs] is model time only. */
    fun onInference(
        completedAtMs: Long,
        inferenceDurationMs: Long,
        frameTimestampMs: Long? = null,
    ) {
        analysisRate.record(completedAtMs)
        inferenceDurations.add(inferenceDurationMs)
        if (frameTimestampMs != null) {
            endToEndLatency.add((completedAtMs - frameTimestampMs).coerceAtLeast(0L))
        }
    }

    fun onInferenceFailure() {
        inferenceFailures++
    }

    /** Time from "start the camera" to the first delivered frame. Recorded once. */
    fun onCameraStarted(latencyMs: Long) {
        if (cameraStartLatencyMs == null) cameraStartLatencyMs = latencyMs
    }

    fun reset() {
        cameraRate.clear()
        analysisRate.clear()
        inferenceDurations.clear()
        endToEndLatency.clear()
        droppedByBackpressure = 0L
        inferenceFailures = 0L
        cameraStartLatencyMs = null
        lastGeometry = null
    }

    fun snapshot(nowMs: Long): StatsSnapshot =
        StatsSnapshot(
            cameraFps = cameraRate.fps(nowMs),
            analyzedFps = analysisRate.fps(nowMs),
            cameraFramesTotal = cameraRate.total,
            analyzedFramesTotal = analysisRate.total,
            droppedFramesTotal = droppedByBackpressure,
            inferenceFailures = inferenceFailures,
            inferenceP50Ms = inferenceDurations.percentile(0.50),
            inferenceP95Ms = inferenceDurations.percentile(0.95),
            inferenceMaxMs = inferenceDurations.max(),
            endToEndP95Ms = endToEndLatency.percentile(0.95),
            cameraStartLatencyMs = cameraStartLatencyMs,
            geometry = lastGeometry,
        )
}

data class FrameGeometry(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
)

data class StatsSnapshot(
    val cameraFps: Double,
    val analyzedFps: Double,
    val cameraFramesTotal: Long,
    val analyzedFramesTotal: Long,
    val droppedFramesTotal: Long,
    val inferenceFailures: Long,
    val inferenceP50Ms: Long,
    val inferenceP95Ms: Long,
    val inferenceMaxMs: Long,
    val endToEndP95Ms: Long,
    val cameraStartLatencyMs: Long?,
    val geometry: FrameGeometry?,
) {
    /**
     * Session 3 acceptance: sustain at least 15 analyzed pose frames per second, unless a lower
     * rate is measured to be sufficient and the reason is written down.
     */
    fun meetsAnalysisTarget(targetFps: Double = MIN_ANALYZED_FPS): Boolean = analyzedFps >= targetFps

    /** One line for the debug overlay and for `adb logcat`. */
    fun format(): String =
        buildString {
            val g = geometry
            if (g != null) append("${g.width}x${g.height}@${g.rotationDegrees}° ")
            append("cam=%.1f ana=%.1f fps ".format(cameraFps, analyzedFps))
            append("inf p50=${inferenceP50Ms}ms p95=${inferenceP95Ms}ms ")
            append("drop=$droppedFramesTotal/$cameraFramesTotal")
            if (inferenceFailures > 0) append(" fail=$inferenceFailures")
            cameraStartLatencyMs?.let { append(" start=${it}ms") }
        }

    companion object {
        const val MIN_ANALYZED_FPS: Double = 15.0
    }
}
