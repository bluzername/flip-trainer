package com.fliptrainer.core.trace

import com.fliptrainer.core.pose.Landmark
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.PoseFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PoseTraceTest {
    private fun frame(
        t: Long,
        vararg landmarks: Pair<LandmarkType, Pair<Float, Float>>,
    ) = PoseFrame(
        monotonicTimestampMs = t,
        imageWidth = 720,
        imageHeight = 1280,
        inferenceDurationMs = 17,
        landmarks =
            landmarks.map { (type, xy) ->
                Landmark(type, xy.first, xy.second, z = 0.1f, visibility = 0.95f, presence = 0.9f)
            },
    )

    private val header = TraceHeader.synthetic(exerciseId = "standing_dumbbell_curl", notes = "unit test")

    @Test
    fun `round trips through render and parse`() {
        val frames =
            listOf(
                frame(0, LandmarkType.LEFT_SHOULDER to (0.4f to 0.3f), LandmarkType.LEFT_ELBOW to (0.42f to 0.5f)),
                frame(33, LandmarkType.LEFT_SHOULDER to (0.4f to 0.3f), LandmarkType.LEFT_ELBOW to (0.43f to 0.48f)),
                frame(66),
            )

        val loaded = PoseTrace.parse(PoseTrace.render(header, frames))

        assertEquals(3, loaded.frames.size)
        assertEquals(header.exerciseId, loaded.header.exerciseId)
        assertEquals(720, loaded.frames[0].imageWidth)
        assertEquals(1280, loaded.frames[0].imageHeight)
        assertEquals(17, loaded.frames[0].inferenceDurationMs)

        val elbow = loaded.frames[0][LandmarkType.LEFT_ELBOW]!!
        assertEquals(0.42f, elbow.x)
        assertEquals(0.5f, elbow.y)
        assertEquals(0.95f, elbow.visibility)
        assertEquals(0.9f, elbow.presence)

        assertFalse(loaded.frames[2].hasPerson, "a frame with no landmarks means no person")
    }

    @Test
    fun `rendering is stable so goldens do not churn`() {
        val frames = listOf(frame(0, LandmarkType.NOSE to (0.123456789f to 0.987654321f)))
        val once = PoseTrace.render(header, frames)
        val twice = PoseTrace.render(header, PoseTrace.parse(once).frames)
        assertEquals(once, twice, "render→parse→render must be a fixed point")
    }

    @Test
    fun `coordinates are rounded to the documented precision`() {
        val text = PoseTrace.render(header, listOf(frame(0, LandmarkType.NOSE to (0.123456789f to 0.5f))))
        assertTrue(text.contains("0.1235"), "expected 4-decimal rounding in:\n$text")
    }

    @Test
    fun `synthetic traces are self-identifying`() {
        assertTrue(TraceHeader.synthetic().synthetic)
        val real = header.copy(deviceId = "flip5-001", poseModelVersion = "pose_landmarker_lite@1")
        assertFalse(real.synthetic, "a real recording must never look synthetic")
    }

    @Test
    fun `duration and mean fps are computed from timestamps`() {
        val frames = (0 until 31).map { frame(it * 33L) }
        val loaded = PoseTrace.parse(PoseTrace.render(header, frames))
        assertEquals(990L, loaded.durationMs)
        assertTrue(loaded.meanFps in 29.0..31.0, "meanFps was ${loaded.meanFps}")
    }

    @Test
    fun `empty input is rejected`() {
        assertThrows<TraceFormatException> { PoseTrace.parse("") }
    }

    @Test
    fun `a foreign schema is rejected`() {
        val foreign =
            """{"schema":"something.else","version":1,"poseModelVersion":"x","appVersion":"x",""" +
                """"deviceId":"x","imageWidth":720,"imageHeight":1280}"""
        val e = assertThrows<TraceFormatException> { PoseTrace.parse(foreign) }
        assertTrue(e.message!!.contains("not a Flip Trainer pose trace"), e.message)
    }

    @Test
    fun `a future schema version is rejected with an actionable message`() {
        val future =
            """{"schema":"fliptrainer.pose-trace","version":99,"poseModelVersion":"x",""" +
                """"appVersion":"x","deviceId":"x","imageWidth":720,"imageHeight":1280}"""
        val e = assertThrows<TraceFormatException> { PoseTrace.parse(future) }
        assertTrue(e.message!!.contains("Re-record"), e.message)
    }

    @Test
    fun `unknown fields are tolerated for forward compatibility`() {
        val text =
            PoseTrace
                .render(header, listOf(frame(0)))
                .replaceFirst("\"schema\"", "\"futureField\":42,\"schema\"")
        assertEquals(1, PoseTrace.parse(text).frames.size)
    }

    @Test
    fun `a trace that goes backwards in time is rejected`() {
        val text = PoseTrace.render(header, listOf(frame(100), frame(50)))
        val e = assertThrows<TraceFormatException> { PoseTrace.parse(text) }
        assertTrue(e.message!!.contains("backwards in time"), e.message)
    }

    @Test
    fun `a corrupt frame line names the line number`() {
        val text = PoseTrace.render(header, listOf(frame(0))) + "{not json}\n"
        val e = assertThrows<TraceFormatException> { PoseTrace.parse(text) }
        assertTrue(e.message!!.contains("line 3"), e.message)
    }

    @Test
    fun `blank lines are ignored`() {
        val text = PoseTrace.render(header, listOf(frame(0), frame(33))).replace("\n", "\n\n")
        assertEquals(2, PoseTrace.parse(text).frames.size)
    }

    @Test
    fun `no landmark data means no image data ever reaches the file`() {
        // Guard against someone adding a base64 frame field to "make debugging easier".
        // `imageWidth`/`imageHeight` are geometry, not pixels, so match payload markers only.
        val text = PoseTrace.render(header, listOf(frame(0, LandmarkType.NOSE to (0.5f to 0.5f))))
        val banned = listOf("jpeg", "jpg", "png", "base64", "bitmap", "imageData", "frameData", "pixels")
        for (marker in banned) {
            assertFalse(text.contains(marker, ignoreCase = true), "trace must not carry '$marker':\n$text")
        }
    }
}
