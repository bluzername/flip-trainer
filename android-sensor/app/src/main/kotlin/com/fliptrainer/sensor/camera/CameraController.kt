package com.fliptrainer.sensor.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.fliptrainer.core.metrics.MonotonicClock
import com.fliptrainer.core.metrics.PipelineStats
import com.fliptrainer.core.metrics.StatsSnapshot
import com.fliptrainer.core.pose.CameraFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * One rear camera. No enumeration of a second camera, no ultrawide selection, no concurrent
 * capture (plan §2.1). Changing that requires an ADR backed by a measured failure.
 *
 * Responsibilities stop at "deliver frames and count them". Everything downstream of a frame —
 * pose, readiness, reps — is core's job.
 */
class CameraController(
    private val context: Context,
    private val clock: MonotonicClock = MonotonicClock.system(),
    val stats: PipelineStats = PipelineStats(),
) {
    private var provider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val firstFrameSeen = AtomicBoolean(false)
    private var startRequestedAtMs = 0L

    private val _snapshots = MutableStateFlow(stats.snapshot(0))

    /** Latest measured pipeline statistics, for the debug overlay and for DEVICE_NOTES. */
    val snapshots: StateFlow<StatsSnapshot> = _snapshots

    /**
     * Bind preview + analysis to [lifecycleOwner].
     *
     * @param onFrame invoked on the analysis executor. The [ImageProxy] is closed by this class
     *   the instant the callback returns — the callback must not retain it. Blocking here is the
     *   backpressure mechanism and is intended: with [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST],
     *   a slow consumer drops frames rather than queueing them.
     */
    suspend fun start(
        lifecycleOwner: LifecycleOwner,
        preview: Preview? = null,
        onFrame: (AnalysisFrame) -> Unit,
    ) {
        check(running.compareAndSet(false, true)) { "CameraController already started" }
        startRequestedAtMs = clock.nowMs()
        firstFrameSeen.set(false)

        val cameraProvider = awaitProvider()
        provider = cameraProvider

        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "flip-analysis").apply { priority = Thread.NORM_PRIORITY + 1 }
        }
        analysisExecutor = executor

        val analysis =
            ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(TARGET_RESOLUTION, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

        analysis.setAnalyzer(executor) { proxy ->
            // Single exit point: whatever happens, the proxy is closed. An unclosed ImageProxy
            // stalls the whole pipeline after a handful of frames, and it is the exact failure
            // Session 2's ten-minute soak test exists to catch.
            try {
                dispatch(proxy, onFrame)
            } catch (t: Throwable) {
                Log.e(TAG, "analysis callback threw", t)
                stats.onInferenceFailure()
            } finally {
                proxy.close()
            }
        }

        cameraProvider.unbindAll()
        val useCases = listOfNotNull(preview, analysis).toTypedArray()
        cameraProvider.bindToLifecycle(lifecycleOwner, REAR_WIDE, *useCases)

        Log.i(TAG, "camera bound: rear wide, target $TARGET_RESOLUTION, KEEP_ONLY_LATEST")
    }

    private fun dispatch(
        proxy: ImageProxy,
        onFrame: (AnalysisFrame) -> Unit,
    ) {
        val now = clock.nowMs()
        if (firstFrameSeen.compareAndSet(false, true)) {
            stats.onCameraStarted(now - startRequestedAtMs)
        }
        stats.onCameraFrame(
            timestampMs = now,
            width = proxy.width,
            height = proxy.height,
            rotationDegrees = proxy.imageInfo.rotationDegrees,
        )
        onFrame(AnalysisFrame(proxy, now))
        _snapshots.value = stats.snapshot(now)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        provider?.unbindAll()
        provider = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        Log.i(TAG, "camera unbound")
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }

    private companion object {
        const val TAG = "FlipTrainer"

        /** Plan §5.1: 1280x720 input. Never request 4K for pose analysis. */
        val TARGET_RESOLUTION = android.util.Size(1280, 720)

        val REAR_WIDE: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    }
}

/**
 * [CameraFrame] over a CameraX [ImageProxy].
 *
 * Valid only for the duration of the analyzer callback. Core never sees this type — it only sees
 * the [CameraFrame] interface, which carries geometry and a timestamp and nothing platform-shaped.
 */
class AnalysisFrame(
    val proxy: ImageProxy,
    override val timestampMs: Long,
) : CameraFrame {
    override val widthPx: Int get() = proxy.width
    override val heightPx: Int get() = proxy.height
    override val rotationDegrees: Int get() = proxy.imageInfo.rotationDegrees
}
