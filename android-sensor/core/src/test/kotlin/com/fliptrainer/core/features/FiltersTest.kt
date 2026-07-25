package com.fliptrainer.core.features

import com.fliptrainer.core.exercise.RangeSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class FiltersTest {
    private fun run(
        filter: SignalFilter,
        values: List<Double>,
    ): List<Double> = values.mapIndexed { i, v -> filter.filter(v, i * 33L) }

    @Test
    fun `pass-through changes nothing`() {
        val values = listOf(1.0, 5.0, -3.0)
        assertEquals(values, run(PassThroughFilter(), values))
    }

    @Test
    fun `ema starts at the first sample rather than at zero`() {
        // Seeding from zero would make every set open with a fake ramp from the origin.
        val ema = ExponentialMovingAverage(0.3)
        assertEquals(10.0, ema.filter(10.0, 0))
    }

    @Test
    fun `ema converges towards a step`() {
        val ema = ExponentialMovingAverage(0.5)
        ema.filter(0.0, 0)
        val out = run(ema, List(10) { 1.0 })
        assertTrue(out.last() > 0.99, "expected convergence, got ${out.last()}")
        assertTrue(out.first() < 1.0, "must lag a step rather than jump to it")
    }

    @Test
    fun `median rejects an isolated spike outright`() {
        val out = run(MedianFilter(3), listOf(1.0, 1.0, 9.0, 1.0, 1.0))
        assertTrue(out.none { it > 1.01 }, "a single spike must not survive a median: $out")
    }

    @Test
    fun `ema alone smears a spike instead of rejecting it`() {
        // Documents *why* the default is median-then-ema rather than ema alone.
        val out = run(ExponentialMovingAverage(0.5), listOf(1.0, 1.0, 9.0, 1.0, 1.0))
        assertTrue(out.any { it > 2.0 }, "an EMA is expected to pass some of the spike through: $out")
    }

    @Test
    fun `median is usable before its window fills`() {
        val f = MedianFilter(5)
        assertEquals(3.0, f.filter(3.0, 0), "the first sample must produce a value, not a blank")
    }

    @Test
    fun `composite applies stages in order`() {
        val composite = CompositeFilter(MedianFilter(3), ExponentialMovingAverage(0.5))
        val out = run(composite, listOf(1.0, 1.0, 9.0, 1.0, 1.0))
        assertTrue(out.all { it < 1.5 }, "median then ema must reject the spike entirely: $out")
    }

    @Test
    fun `filters reset cleanly`() {
        val f = ExponentialMovingAverage(0.5)
        f.filter(100.0, 0)
        f.reset()
        assertNull(f.latest)
        assertEquals(1.0, f.filter(1.0, 33))
    }

    @Test
    fun `filter specs build the filter they name`() {
        assertTrue(FilterSpec.None.build() is PassThroughFilter)
        assertTrue(FilterSpec.Ema(0.5).build() is ExponentialMovingAverage)
        assertTrue(FilterSpec.Median(3).build() is MedianFilter)
        assertTrue(FilterSpec.MedianThenEma().build() is CompositeFilter)
    }

    @Test
    fun `invalid filter parameters are rejected`() {
        assertThrows<IllegalArgumentException> { ExponentialMovingAverage(0.0) }
        assertThrows<IllegalArgumentException> { ExponentialMovingAverage(1.5) }
        assertThrows<IllegalArgumentException> { MedianFilter(4) } // even window has no true median
        assertThrows<IllegalArgumentException> { MedianFilter(0) }
    }
}

class DirectionTrackerTest {
    @Test
    fun `direction is unknown until the signal moves enough to be sure`() {
        val t = DirectionTracker(minReversalDelta = 0.1)
        assertFalse(t.accept(0.50))
        assertFalse(t.accept(0.52))
        assertEquals(DirectionTracker.Direction.UNKNOWN, t.direction)
    }

    @Test
    fun `a clear rise establishes the direction`() {
        val t = DirectionTracker(minReversalDelta = 0.1)
        t.accept(0.50)
        assertTrue(t.accept(0.65))
        assertEquals(DirectionTracker.Direction.RISING, t.direction)
    }

    @Test
    fun `small wobbles never confirm a reversal`() {
        val t = DirectionTracker(minReversalDelta = 0.1)
        t.accept(0.0)
        t.accept(0.5) // RISING
        val reversals = listOf(0.48, 0.52, 0.47, 0.53, 0.49).count { t.accept(it) }
        assertEquals(0, reversals, "noise below the delta must not read as a turnaround")
        assertEquals(DirectionTracker.Direction.RISING, t.direction)
    }

    @Test
    fun `a genuine turnaround is confirmed exactly once`() {
        val t = DirectionTracker(minReversalDelta = 0.1)
        t.accept(0.0)
        t.accept(0.9) // RISING, peak 0.9
        val confirmations = listOf(0.85, 0.75, 0.60, 0.40).count { t.accept(it) }
        assertEquals(1, confirmations)
        assertEquals(DirectionTracker.Direction.FALLING, t.direction)
    }

    @Test
    fun `the anchor tracks the extreme so a reversal is measured from the peak`() {
        val t = DirectionTracker(minReversalDelta = 0.1)
        t.accept(0.0)
        t.accept(0.3) // RISING
        t.accept(0.9) // peak moves to 0.9
        assertFalse(t.accept(0.85), "0.05 off the peak is not a reversal")
        assertTrue(t.accept(0.79), "0.11 off the peak is")
    }

    @Test
    fun `reset clears the direction`() {
        val t = DirectionTracker(0.1)
        t.accept(0.0)
        t.accept(0.5)
        t.reset()
        assertEquals(DirectionTracker.Direction.UNKNOWN, t.direction)
    }
}

class ExcursionTrackerTest {
    @Test
    fun `span is null until there is something to span`() {
        val e = ExcursionTracker()
        assertNull(e.span)
        e.add(5.0)
        assertEquals(0.0, e.span)
    }

    @Test
    fun `tracks minimum and maximum`() {
        val e = ExcursionTracker()
        listOf(5.0, 2.0, 9.0, 4.0).forEach(e::add)
        assertEquals(2.0, e.min)
        assertEquals(9.0, e.max)
        assertEquals(7.0, e.span)
        assertEquals(4, e.count)
    }

    @Test
    fun `reset clears the excursion`() {
        val e = ExcursionTracker()
        e.add(1.0)
        e.reset()
        assertNull(e.min)
        assertEquals(0, e.count)
    }
}

class RangeSpecTest {
    @Test
    fun `maps an increasing feature onto progress`() {
        val spec = RangeSpec(restValue = 170.0, apexValue = 40.0) // a curl: angle decreases
        assertEquals(0.0, spec.progressOf(170.0), 1e-9)
        assertEquals(1.0, spec.progressOf(40.0), 1e-9)
        assertEquals(0.5, spec.progressOf(105.0), 1e-9)
    }

    @Test
    fun `maps a decreasing feature onto progress`() {
        val spec = RangeSpec(restValue = 0.0, apexValue = 1.0)
        assertEquals(0.25, spec.progressOf(0.25), 1e-9)
    }

    @Test
    fun `progress is clamped outside the declared range`() {
        val spec = RangeSpec(restValue = 170.0, apexValue = 40.0)
        assertEquals(0.0, spec.progressOf(180.0), 1e-9, "hyperextension is not negative progress")
        assertEquals(1.0, spec.progressOf(20.0), 1e-9, "beyond full range is still full range")
    }

    @Test
    fun `featureAt is the inverse of progressOf`() {
        val spec = RangeSpec(restValue = 170.0, apexValue = 40.0)
        for (p in listOf(0.0, 0.2, 0.5, 0.8, 1.0)) {
            assertTrue(abs(p - spec.progressOf(spec.featureAt(p))) < 1e-9)
        }
    }

    @Test
    fun `a degenerate range is rejected`() {
        assertThrows<IllegalArgumentException> { RangeSpec(restValue = 90.0, apexValue = 90.0) }
    }
}
