package com.fliptrainer.core.pose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

/**
 * Coordinate-transform correctness.
 *
 * This is the layer where a bug is invisible: every downstream angle still computes, every
 * skeleton still draws, and every number is wrong. Session 3 acceptance calls out portrait and
 * landscape tracking and left/right integrity specifically, so both are pinned here.
 */
class ImageTransformTest {
    private fun lm(
        type: LandmarkType,
        x: Float,
        y: Float,
    ) = Landmark(type, x, y, z = 0f, visibility = 1f, presence = 1f)

    private fun assertClose(
        expected: Float,
        actual: Float,
        what: String,
    ) {
        assertTrue(abs(expected - actual) < 1e-6f, "$what: expected $expected, was $actual")
    }

    @Test
    fun `identity transform leaves coordinates untouched`() {
        val p = lm(LandmarkType.LEFT_WRIST, 0.25f, 0.75f)
        val out = ImageTransform.IDENTITY.apply(p)
        assertEquals(p, out)
    }

    @Test
    fun `90 degree rotation moves top-left to top-right`() {
        // A point near the top-left of the buffer. Rotating the image 90° clockwise sends it to
        // the top-right of the upright image.
        val out = ImageTransform(90).apply(lm(LandmarkType.NOSE, 0.1f, 0.2f))
        assertClose(0.8f, out.x, "x")
        assertClose(0.1f, out.y, "y")
    }

    @Test
    fun `180 degree rotation inverts both axes`() {
        val out = ImageTransform(180).apply(lm(LandmarkType.NOSE, 0.3f, 0.25f))
        assertClose(0.7f, out.x, "x")
        assertClose(0.75f, out.y, "y")
    }

    @Test
    fun `270 degree rotation moves top-left to bottom-left`() {
        val out = ImageTransform(270).apply(lm(LandmarkType.NOSE, 0.1f, 0.2f))
        assertClose(0.2f, out.x, "x")
        assertClose(0.9f, out.y, "y")
    }

    @Test
    fun `four 90 degree rotations return to the original point`() {
        val start = lm(LandmarkType.RIGHT_ELBOW, 0.37f, 0.61f)
        val t = ImageTransform(90)
        var p = start
        repeat(4) { p = t.apply(p) }
        assertClose(start.x, p.x, "x")
        assertClose(start.y, p.y, "y")
        assertEquals(start.type, p.type)
    }

    @Test
    fun `rotation alone never changes the lateral label`() {
        for (deg in listOf(0, 90, 180, 270)) {
            val out = ImageTransform(deg).apply(lm(LandmarkType.LEFT_KNEE, 0.4f, 0.4f))
            assertEquals(LandmarkType.LEFT_KNEE, out.type, "rotation $deg must not relabel sides")
        }
    }

    @Test
    fun `mirroring swaps the lateral label as well as x`() {
        // A model shown a flipped image labels an anatomical left arm as a right arm. Un-mirroring
        // has to undo the label too, or every unilateral exercise silently tracks the wrong limb.
        val out =
            ImageTransform(rotationDegrees = 0, horizontallyMirrored = true)
                .apply(lm(LandmarkType.LEFT_WRIST, 0.2f, 0.5f))
        assertEquals(LandmarkType.RIGHT_WRIST, out.type)
        assertClose(0.8f, out.x, "x")
        assertClose(0.5f, out.y, "y")
    }

    @Test
    fun `mirroring is its own inverse`() {
        val t = ImageTransform(rotationDegrees = 0, horizontallyMirrored = true)
        val start = lm(LandmarkType.LEFT_ANKLE, 0.15f, 0.9f)
        val round = t.apply(t.apply(start))
        assertEquals(start.type, round.type)
        assertClose(start.x, round.x, "x")
    }

    @Test
    fun `centre landmarks keep their identity under mirroring`() {
        val out =
            ImageTransform(rotationDegrees = 0, horizontallyMirrored = true)
                .apply(lm(LandmarkType.NOSE, 0.3f, 0.3f))
        assertEquals(LandmarkType.NOSE, out.type)
    }

    @Test
    fun `90 and 270 rotations swap the reported image dimensions`() {
        val frame =
            ImageTransform(90).apply(
                landmarks = listOf(lm(LandmarkType.NOSE, 0.5f, 0.5f)),
                bufferWidth = 1280,
                bufferHeight = 720,
                monotonicTimestampMs = 42L,
                inferenceDurationMs = 7,
            )
        assertEquals(720, frame.imageWidth)
        assertEquals(1280, frame.imageHeight)
        assertEquals(42L, frame.monotonicTimestampMs)
        assertEquals(7, frame.inferenceDurationMs)
    }

    @Test
    fun `0 and 180 rotations keep the reported image dimensions`() {
        for (deg in listOf(0, 180)) {
            val frame =
                ImageTransform(deg).apply(
                    landmarks = emptyList(),
                    bufferWidth = 1280,
                    bufferHeight = 720,
                    monotonicTimestampMs = 0L,
                    inferenceDurationMs = 0,
                )
            assertEquals(1280, frame.imageWidth, "rotation $deg")
            assertEquals(720, frame.imageHeight, "rotation $deg")
        }
    }

    @Test
    fun `a left-of-frame landmark stays left of a right-of-frame landmark through rotation`() {
        // Portrait capture, landscape buffer: the athlete's shoulders must not cross over.
        val left = lm(LandmarkType.LEFT_SHOULDER, 0.4f, 0.3f)
        val right = lm(LandmarkType.RIGHT_SHOULDER, 0.6f, 0.3f)
        val t = ImageTransform(90)
        val outLeft = t.apply(left)
        val outRight = t.apply(right)

        // Under a 90° rotation, a horizontal separation becomes a vertical one — the invariant
        // that survives is the ordering along the rotated axis, not along x.
        assertEquals(outLeft.x, outRight.x)
        assertTrue(outLeft.y < outRight.y, "relative ordering must be preserved, not scrambled")
    }

    @Test
    fun `only right-angle rotations are accepted`() {
        assertThrows<IllegalArgumentException> { ImageTransform(45) }
        assertThrows<IllegalArgumentException> { ImageTransform(-90) }
        assertThrows<IllegalArgumentException> { ImageTransform(360) }
    }

    @Test
    fun `swapsAxes is true only for quarter turns`() {
        assertFalse(ImageTransform(0).swapsAxes)
        assertTrue(ImageTransform(90).swapsAxes)
        assertFalse(ImageTransform(180).swapsAxes)
        assertTrue(ImageTransform(270).swapsAxes)
    }

    @Test
    fun `off-frame coordinates are preserved rather than clamped`() {
        // A clipped limb must stay detectably clipped — the readiness gate depends on it.
        val out = ImageTransform(0).apply(lm(LandmarkType.LEFT_FOOT_INDEX, 0.5f, 1.15f))
        assertClose(1.15f, out.y, "y")
        assertFalse(out.inFrame)
    }
}
