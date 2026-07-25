package com.fliptrainer.sensor.pose

import android.content.Context
import android.util.Log
import com.fliptrainer.core.metrics.MonotonicClock
import com.fliptrainer.core.pose.CameraFrame
import com.fliptrainer.core.pose.ImageTransform
import com.fliptrainer.core.pose.Landmark
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.PoseEstimator
import com.fliptrainer.core.pose.PoseFrame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.fliptrainer.sensor.camera.AnalysisFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * The only [PoseEstimator] implementation (ADR-0003).
 *
 * **Live-stream mode with a synchronous facade.** MediaPipe's LIVE_STREAM mode is asynchronous:
 * you call `detectAsync` and a listener fires later. The [PoseEstimator] contract is
 * request/response. The two are bridged with a single-slot deferred, which is safe here because
 * the caller is a single analysis thread that awaits each result before submitting the next
 * frame. Serializing costs nothing in this pipeline: `STRATEGY_KEEP_ONLY_LATEST` means a frame
 * that arrives while inference is in flight is dropped rather than queued, which is exactly the
 * backpressure behaviour we want.
 *
 * Rotation is deliberately **not** delegated to MediaPipe's `ImageProcessingOptions`. Landmarks
 * come back in buffer space and are rotated by [ImageTransform] in core, where the transform has
 * unit tests covering all four orientations and the left/right relabelling rule.
 */
class MediaPipePoseEstimator(
    private val context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL,
    private val clock: MonotonicClock = MonotonicClock.system(),
    private val useGpu: Boolean = true,
) : PoseEstimator {
    private var landmarker: PoseLandmarker? = null
    private val pending = AtomicReference<CompletableDeferred<PoseLandmarkerResult?>?>(null)
    private var lastSubmittedTimestamp = Long.MIN_VALUE

    override suspend fun start() {
        if (landmarker != null) return

        val base =
            BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
                .build()

        val options =
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                // One athlete in frame (plan §2.3). More poses means more inference cost and an
                // ambiguity the evaluators have no way to resolve.
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(MIN_DETECTION_CONFIDENCE)
                .setMinPosePresenceConfidence(MIN_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                .setOutputSegmentationMasks(false)
                .setResultListener { result, _ -> complete(result) }
                .setErrorListener { error ->
                    Log.e(TAG, "pose inference error", error)
                    complete(null)
                }
                .build()

        landmarker = PoseLandmarker.createFromOptions(context, options)
        Log.i(TAG, "pose landmarker ready (model=$modelAssetPath gpu=$useGpu)")
    }

    private fun complete(result: PoseLandmarkerResult?) {
        pending.getAndSet(null)?.complete(result)
    }

    override suspend fun estimate(frame: CameraFrame): PoseFrame? {
        val detector = landmarker ?: return null
        val analysis = frame as? AnalysisFrame ?: return null

        // MediaPipe requires strictly increasing timestamps in live-stream mode.
        val timestamp = maxOf(frame.timestampMs, lastSubmittedTimestamp + 1)
        lastSubmittedTimestamp = timestamp

        val slot = CompletableDeferred<PoseLandmarkerResult?>()
        if (!pending.compareAndSet(null, slot)) {
            Log.w(TAG, "estimate() called re-entrantly; dropping frame")
            return null
        }

        val startedAt = clock.nowMs()
        val bitmap = analysis.proxy.toBitmap()
        detector.detectAsync(BitmapImageBuilder(bitmap).build(), timestamp)

        val result = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) { slot.await() }
        if (result == null) {
            pending.compareAndSet(slot, null)
            Log.w(TAG, "inference produced no result within ${INFERENCE_TIMEOUT_MS}ms")
            return null
        }

        val durationMs = (clock.nowMs() - startedAt).toInt()
        val transform = ImageTransform(rotationDegrees = frame.rotationDegrees, horizontallyMirrored = false)

        return transform.apply(
            landmarks = result.toLandmarks(),
            bufferWidth = frame.widthPx,
            bufferHeight = frame.heightPx,
            monotonicTimestampMs = frame.timestampMs,
            inferenceDurationMs = durationMs,
        )
    }

    override suspend fun stop() {
        pending.getAndSet(null)?.complete(null)
        landmarker?.close()
        landmarker = null
    }

    /**
     * An empty landmark list means "no person", which is a meaningful observation the readiness
     * gate consumes. It is never converted into a null frame.
     */
    private fun PoseLandmarkerResult.toLandmarks(): List<Landmark> {
        val poses = landmarks()
        if (poses.isEmpty()) return emptyList()
        return poses[0].mapIndexedNotNull { index, nl ->
            val type = LandmarkType.fromIndex(index) ?: return@mapIndexedNotNull null
            Landmark(
                type = type,
                x = nl.x(),
                y = nl.y(),
                z = nl.z(),
                visibility = nl.visibility().orElse(0f),
                presence = nl.presence().orElse(0f),
            )
        }
    }

    private companion object {
        const val TAG = "FlipTrainer"

        /**
         * See `src/main/assets/README.md` — the .task bundle is not committed and must be
         * downloaded once during setup.
         */
        const val DEFAULT_MODEL = "pose_landmarker_lite.task"

        const val MIN_DETECTION_CONFIDENCE = 0.5f
        const val MIN_PRESENCE_CONFIDENCE = 0.5f
        const val MIN_TRACKING_CONFIDENCE = 0.5f

        /** Generous: a missed result costs one frame, a hung analysis thread costs the set. */
        const val INFERENCE_TIMEOUT_MS = 1_000L
    }
}
