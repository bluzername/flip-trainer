package com.fliptrainer.core.exercise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private const val STEP_MS = 33L

/**
 * The four synthetic scenarios Session 5 requires — oscillating signal, partial excursion, noisy
 * reversal, confidence dropout — plus the determinism guarantee everything else rests on.
 *
 * Signals are fed as bare scalars rather than through a pose. At this layer the question is not
 * "does a curl look like this" but "does a threshold crossing become a repetition", and a scalar
 * makes the answer unambiguous.
 */
class RepCycleDetectorTest {
    private val config = RepCycleConfig()

    private class Harness(
        config: RepCycleConfig,
    ) {
        val detector = RepCycleDetector(config)
        val signals = mutableListOf<CycleSignal>()
        var t = 0L

        fun feed(
            values: List<Double?>,
            confidence: Double = 0.9,
        ) {
            for (v in values) {
                signals += detector.accept(v, t, confidence)
                t += STEP_MS
            }
        }

        val reps: List<CycleSignal.RepCompleted> get() = signals.filterIsInstance<CycleSignal.RepCompleted>()
        val rejects: List<CycleSignal.RepRejected> get() = signals.filterIsInstance<CycleSignal.RepRejected>()
        val aborts: List<CycleSignal.CycleAborted> get() = signals.filterIsInstance<CycleSignal.CycleAborted>()
        val phases: List<MovementPhase>
            get() = signals.filterIsInstance<CycleSignal.PhaseEntered>().map { it.phase }
    }

    private fun ramp(
        from: Double,
        to: Double,
        steps: Int,
    ): List<Double> = (0..steps).map { from + (to - from) * it / steps }

    private fun cleanRep(steps: Int = 15): List<Double> = ramp(0.0, 1.0, steps) + ramp(1.0, 0.0, steps)

    @Test
    fun `an oscillating signal produces one rep per cycle`() {
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0, 0.0))
        repeat(5) { h.feed(cleanRep()) }

        assertEquals(5, h.reps.size, "expected five repetitions")
        assertEquals(listOf(1, 2, 3, 4, 5), h.reps.map { it.measurement.repNumber })
        assertTrue(h.rejects.isEmpty(), "clean reps must not be rejected: ${h.rejects}")
    }

    @Test
    fun `phases follow start outbound apex return in order`() {
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0))
        h.feed(cleanRep())

        assertEquals(
            listOf(
                MovementPhase.START,
                MovementPhase.OUTBOUND,
                MovementPhase.APEX,
                MovementPhase.RETURN,
                MovementPhase.START,
            ),
            h.phases,
        )
    }

    @Test
    fun `a partial excursion is rejected rather than counted`() {
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0))
        h.feed(ramp(0.0, 0.55, 12) + ramp(0.55, 0.0, 12))

        assertEquals(0, h.reps.size, "a half repetition is not a repetition")
        assertEquals(1, h.rejects.size)
        assertEquals("incomplete range", h.rejects.single().reason)
        assertTrue(h.rejects.single().peakProgress in 0.5..0.6)
    }

    @Test
    fun `a partial rep does not block the next full rep`() {
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0))
        h.feed(ramp(0.0, 0.5, 10) + ramp(0.5, 0.0, 10))
        h.feed(cleanRep())

        assertEquals(1, h.reps.size)
        assertEquals(1, h.rejects.size)
    }

    @Test
    fun `noise at the apex does not split one rep into two`() {
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0))
        // Rise, then jitter across the apex threshold by less than the reversal delta, then fall.
        h.feed(ramp(0.0, 0.85, 12))
        h.feed(listOf(0.81, 0.79, 0.83, 0.78, 0.84, 0.80, 0.82))
        h.feed(ramp(0.82, 0.0, 12))

        assertEquals(1, h.reps.size, "apex jitter must not produce extra reps: ${h.phases}")
    }

    @Test
    fun `noise at the bottom does not produce phantom reps`() {
        // This is what the hysteresis band exists for: a signal resting near the bottom threshold
        // crosses it constantly, and a single-threshold implementation counts every crossing.
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0))
        repeat(20) { h.feed(listOf(0.13, 0.17, 0.12, 0.18, 0.14)) }

        assertEquals(0, h.reps.size, "idle jitter must not count")
        assertEquals(0, h.rejects.size, "idle jitter must not even open a cycle")
        assertTrue(
            h.phases.none { it == MovementPhase.OUTBOUND },
            "movement below the outbound threshold must not start a cycle",
        )
    }

    @Test
    fun `a brief signal dropout does not corrupt the cycle`() {
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0))
        h.feed(ramp(0.0, 1.0, 15))
        h.feed(listOf(null, null, null)) // 99ms, well under the 400ms tolerance
        h.feed(ramp(1.0, 0.0, 15))

        assertEquals(1, h.reps.size, "a three-frame dropout must not lose the rep")
        assertTrue(h.aborts.isEmpty())
    }

    @Test
    fun `a sustained dropout abandons the cycle without counting a rep`() {
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0))
        h.feed(ramp(0.0, 1.0, 15))
        h.feed(List(20) { null }) // 660ms, past the 400ms tolerance
        h.feed(ramp(1.0, 0.0, 15))

        assertEquals(0, h.reps.size, "a rep whose middle was never observed must not be counted")
        assertEquals(1, h.aborts.size)
        assertTrue(h.aborts.single().reason.contains("signal lost"))
    }

    @Test
    fun `the detector recovers and counts normally after an abandoned cycle`() {
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0))
        h.feed(ramp(0.0, 1.0, 15))
        h.feed(List(20) { null })
        h.feed(listOf(0.0, 0.0, 0.0))
        h.feed(cleanRep())

        assertEquals(1, h.reps.size)
    }

    @Test
    fun `a rep faster than the minimum duration is rejected`() {
        val h = Harness(RepCycleConfig(minRepDurationMs = 800, minPhaseDurationMs = 0))
        h.feed(listOf(0.0, 0.0))
        h.feed(ramp(0.0, 1.0, 4) + ramp(1.0, 0.0, 4)) // ~264ms
        assertEquals(0, h.reps.size)
        assertEquals("too fast", h.rejects.single().reason)
    }

    @Test
    fun `a cycle that never completes is abandoned`() {
        val h = Harness(RepCycleConfig(maxRepDurationMs = 1_000))
        h.feed(listOf(0.0, 0.0))
        h.feed(ramp(0.0, 0.9, 10))
        h.feed(List(40) { 0.9 }) // held at the top far past the limit

        assertEquals(1, h.aborts.size)
        assertTrue(h.aborts.single().reason.contains("exceeded"))
        assertEquals(0, h.reps.size)
    }

    @Test
    fun `cooldown suppresses an immediate second cycle`() {
        val slow = RepCycleConfig(repCooldownMs = 2_000)
        val h = Harness(slow)
        h.feed(listOf(0.0, 0.0))
        h.feed(cleanRep())
        h.feed(cleanRep()) // starts ~1s later, inside the cooldown

        assertEquals(1, h.reps.size, "the second cycle must be suppressed by cooldown")
    }

    @Test
    fun `measurements report outbound and return durations separately`() {
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0))
        // Deliberately asymmetric: slow up, fast down.
        h.feed(ramp(0.0, 1.0, 30) + ramp(1.0, 0.0, 10))

        val m = h.reps.single().measurement
        assertTrue(m.outboundDurationMs > m.returnDurationMs, "slow-up fast-down: $m")
        assertTrue(m.totalDurationMs >= m.outboundDurationMs + m.returnDurationMs - 2 * STEP_MS)
        assertTrue(m.peakProgress >= 0.99)
    }

    @Test
    fun `concentric and eccentric follow the exercise not the direction of travel`() {
        val m =
            RepMeasurement(
                repNumber = 1,
                totalDurationMs = 1000,
                outboundDurationMs = 400,
                returnDurationMs = 600,
                peakProgress = 1.0,
                meanConfidence = 0.9,
            )
        // A curl shortens on the way out...
        assertEquals(400, m.concentricDurationMs(outboundIsConcentric = true))
        assertEquals(600, m.eccentricDurationMs(outboundIsConcentric = true))
        // ...a squat shortens on the way back.
        assertEquals(600, m.concentricDurationMs(outboundIsConcentric = false))
        assertEquals(400, m.eccentricDurationMs(outboundIsConcentric = false))
    }

    @Test
    fun `phase labels reflect which half is concentric`() {
        val curl = RepCycleConfig(outboundIsConcentric = true)
        assertEquals("bottom", curl.label(MovementPhase.START))
        assertEquals("concentric", curl.label(MovementPhase.OUTBOUND))
        assertEquals("top", curl.label(MovementPhase.APEX))
        assertEquals("eccentric", curl.label(MovementPhase.RETURN))

        val squat = RepCycleConfig(outboundIsConcentric = false)
        assertEquals("top", squat.label(MovementPhase.START))
        assertEquals("eccentric", squat.label(MovementPhase.OUTBOUND))
        assertEquals("bottom", squat.label(MovementPhase.APEX))
        assertEquals("concentric", squat.label(MovementPhase.RETURN))
    }

    @Test
    fun `mean confidence is averaged across the cycle only`() {
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0), confidence = 0.1) // before the cycle: must not drag it down
        h.feed(cleanRep(), confidence = 0.8)
        assertEquals(0.8, h.reps.single().measurement.meanConfidence, 0.02)
    }

    @Test
    fun `replaying the same signal produces identical output`() {
        fun run(): List<CycleSignal> {
            val h = Harness(config)
            h.feed(listOf(0.0, 0.0))
            h.feed(cleanRep())
            h.feed(ramp(0.0, 0.5, 10) + ramp(0.5, 0.0, 10))
            h.feed(List(20) { null })
            h.feed(listOf(0.0, 0.0))
            h.feed(cleanRep(20))
            return h.signals
        }
        assertEquals(run(), run(), "the detector must be a pure function of its input sequence")
    }

    @Test
    fun `reset returns the detector to its initial state`() {
        val h = Harness(config)
        h.feed(listOf(0.0, 0.0))
        h.feed(cleanRep())
        assertEquals(1, h.detector.reps)

        h.detector.reset()
        assertEquals(0, h.detector.reps)
        assertEquals(MovementPhase.IDLE, h.detector.currentPhase)
    }

    @Test
    fun `nonsensical configuration is rejected at construction`() {
        assertThrows<IllegalArgumentException> { RepCycleConfig(restBelow = 0.5, outboundAbove = 0.2) }
        assertThrows<IllegalArgumentException> { RepCycleConfig(outboundAbove = 0.9, apexAbove = 0.5) }
        assertThrows<IllegalArgumentException> { RepCycleConfig(minReversalDelta = 0.0) }
        assertThrows<IllegalArgumentException> {
            RepCycleConfig(outboundAbove = 0.3, apexAbove = 0.8, minPeakProgress = 0.25)
        }
    }
}
