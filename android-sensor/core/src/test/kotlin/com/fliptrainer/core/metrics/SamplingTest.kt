package com.fliptrainer.core.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SampleWindowTest {
    @Test
    fun `empty window reports zeros rather than throwing`() {
        val w = SampleWindow(8)
        assertEquals(0L, w.percentile(0.5))
        assertEquals(0.0, w.mean())
        assertEquals(0, w.size)
    }

    @Test
    fun `nearest-rank percentiles on a known distribution`() {
        val w = SampleWindow(200)
        (1..100).forEach { w.add(it.toLong()) }
        assertEquals(50L, w.percentile(0.50))
        assertEquals(95L, w.percentile(0.95))
        assertEquals(1L, w.percentile(0.0))
        assertEquals(100L, w.percentile(1.0))
        assertEquals(50.5, w.mean())
    }

    @Test
    fun `window evicts oldest samples once full`() {
        val w = SampleWindow(3)
        listOf(10L, 20L, 30L, 40L).forEach(w::add)
        assertEquals(3, w.size)
        assertEquals(4L, w.recorded)
        assertEquals(20L, w.min())
        assertEquals(40L, w.max())
        assertEquals(30.0, w.mean())
    }

    @Test
    fun `mean stays correct across many evictions`() {
        val w = SampleWindow(4)
        repeat(1000) { w.add(7L) }
        assertEquals(7.0, w.mean())
        assertEquals(4, w.size)
        assertEquals(1000L, w.recorded)
    }

    @Test
    fun `clear resets everything`() {
        val w = SampleWindow(4)
        listOf(1L, 2L, 3L).forEach(w::add)
        w.clear()
        assertEquals(0, w.size)
        assertEquals(0L, w.recorded)
        assertEquals(0.0, w.mean())
    }

    @Test
    fun `invalid construction and queries are rejected`() {
        assertThrows<IllegalArgumentException> { SampleWindow(0) }
        assertThrows<IllegalArgumentException> { SampleWindow(4).percentile(1.5) }
    }
}

class FrameRateMeterTest {
    @Test
    fun `steady 30fps input measures about 30fps`() {
        val m = FrameRateMeter(windowMs = 1_000)
        var t = 0L
        repeat(120) {
            m.record(t)
            t += 33
        }
        val fps = m.fps(t)
        assertTrue(fps in 28.0..32.0, "expected ~30 fps, got $fps")
    }

    @Test
    fun `a single frame does not imply a frame rate`() {
        val m = FrameRateMeter(windowMs = 1_000)
        m.record(0)
        assertEquals(0.0, m.fps(0))
    }

    @Test
    fun `two frames milliseconds apart do not report a huge rate`() {
        // The naive "span between first and last" denominator would report 100 fps here.
        val m = FrameRateMeter(windowMs = 1_000)
        m.record(0)
        m.record(10)
        assertTrue(m.fps(1_000) < 5.0, "a just-started camera must not read as high frame rate")
    }

    @Test
    fun `rate decays to zero when frames stop`() {
        val m = FrameRateMeter(windowMs = 1_000)
        (0 until 30).forEach { m.record(it * 33L) }
        assertTrue(m.fps(990) > 20.0)
        assertEquals(0.0, m.fps(10_000), "stale samples must be evicted")
    }

    @Test
    fun `total counts every frame ever recorded`() {
        val m = FrameRateMeter(windowMs = 100)
        repeat(500) { m.record(it * 50L) }
        assertEquals(500L, m.total)
    }
}

class PipelineStatsTest {
    @Test
    fun `reports geometry latency and inference percentiles`() {
        val stats = PipelineStats(windowMs = 1_000)
        stats.onCameraStarted(latencyMs = 412)

        var t = 0L
        repeat(60) { i ->
            stats.onCameraFrame(t, width = 1280, height = 720, rotationDegrees = 90)
            // Analyze every other frame; the rest are dropped by KEEP_ONLY_LATEST backpressure.
            if (i % 2 == 0) {
                stats.onInference(completedAtMs = t + 20, inferenceDurationMs = 18L + (i % 5))
            } else {
                stats.onFrameDropped()
            }
            t += 33
        }

        val s = stats.snapshot(t)
        assertEquals(FrameGeometry(1280, 720, 90), s.geometry)
        assertEquals(412L, s.cameraStartLatencyMs)
        assertEquals(60L, s.cameraFramesTotal)
        assertEquals(30L, s.analyzedFramesTotal)
        assertEquals(30L, s.droppedFramesTotal)
        assertTrue(s.inferenceP50Ms in 18L..22L, "p50 was ${s.inferenceP50Ms}")
        assertTrue(s.inferenceP95Ms >= s.inferenceP50Ms)
        assertEquals(22L, s.inferenceMaxMs)
    }

    @Test
    fun `analysis target reflects the measured analyzed rate`() {
        val stats = PipelineStats(windowMs = 1_000)
        var t = 0L
        repeat(40) {
            stats.onCameraFrame(t, 1280, 720, 0)
            stats.onInference(t, 15L, frameTimestampMs = t)
            t += 50 // 20 analyzed fps
        }
        val ok = stats.snapshot(t)
        assertTrue(ok.meetsAnalysisTarget(), "20 fps should clear the 15 fps target")

        val slow = PipelineStats(windowMs = 1_000)
        var st = 0L
        repeat(20) {
            slow.onCameraFrame(st, 1280, 720, 0)
            slow.onInference(st, 90L, frameTimestampMs = st)
            st += 100 // 10 analyzed fps
        }
        assertFalse(slow.snapshot(st).meetsAnalysisTarget(), "10 fps must fail the 15 fps target")
    }

    @Test
    fun `end to end latency is measured from frame capture not inference start`() {
        val stats = PipelineStats()
        stats.onCameraFrame(1_000, 1280, 720, 0)
        stats.onInference(completedAtMs = 1_080, inferenceDurationMs = 25, frameTimestampMs = 1_000)
        assertEquals(80L, stats.snapshot(1_100).endToEndP95Ms)
    }

    @Test
    fun `first camera start latency wins over later restarts`() {
        val stats = PipelineStats()
        stats.onCameraStarted(300)
        stats.onCameraStarted(999)
        assertEquals(300L, stats.snapshot(0).cameraStartLatencyMs)
    }

    @Test
    fun `reset clears every counter`() {
        val stats = PipelineStats()
        stats.onCameraFrame(0, 1280, 720, 0)
        stats.onInference(10, 10)
        stats.onFrameDropped()
        stats.onInferenceFailure()
        stats.reset()
        val s = stats.snapshot(100)
        assertEquals(0L, s.cameraFramesTotal)
        assertEquals(0L, s.droppedFramesTotal)
        assertEquals(0L, s.inferenceFailures)
        assertEquals(null, s.geometry)
    }

    @Test
    fun `format produces a single readable overlay line`() {
        val stats = PipelineStats()
        stats.onCameraStarted(250)
        stats.onCameraFrame(0, 1280, 720, 90)
        stats.onInference(20, 20, 0)
        val line = stats.snapshot(100).format()
        assertTrue(line.contains("1280x720@90"), line)
        assertTrue(line.contains("start=250ms"), line)
    }
}

class MonotonicClockTest {
    @Test
    fun `fixed clock advances only forwards`() {
        val c = MonotonicClock.fixed(100)
        assertEquals(100L, c.nowMs())
        c.advance(50)
        assertEquals(150L, c.nowMs())
        assertThrows<IllegalArgumentException> { c.advance(-1) }
        assertThrows<IllegalArgumentException> { c.set(10) }
    }

    @Test
    fun `system clock is monotonic`() {
        val c = MonotonicClock.system()
        val a = c.nowMs()
        val b = c.nowMs()
        assertTrue(b >= a)
    }
}
