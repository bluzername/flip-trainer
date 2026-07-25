package com.fliptrainer.core.features

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A stateful scalar filter.
 *
 * Filters are configured per exercise from JSON, because the right amount of smoothing for a
 * squat (slow, large excursion) is the wrong amount for a fast curl (small excursion, where lag
 * costs a rep at the turnaround).
 */
interface SignalFilter {
    /** Feed a sample. Returns the filtered value. */
    fun filter(
        value: Double,
        timestampMs: Long,
    ): Double

    /** The most recent filtered value, or null before any sample. */
    val latest: Double?

    fun reset()
}

/** Declarative filter configuration, stored in the exercise definition JSON. */
@Serializable
sealed interface FilterSpec {
    fun build(): SignalFilter

    @Serializable
    @SerialName("none")
    data object None : FilterSpec {
        override fun build(): SignalFilter = PassThroughFilter()
    }

    /**
     * @property alpha weight of the newest sample, `0..1`. Lower is smoother and laggier.
     */
    @Serializable
    @SerialName("ema")
    data class Ema(
        val alpha: Double = 0.4,
    ) : FilterSpec {
        override fun build(): SignalFilter = ExponentialMovingAverage(alpha)
    }

    /** @property window number of samples; must be odd so the median is a real sample. */
    @Serializable
    @SerialName("median")
    data class Median(
        val window: Int = 3,
    ) : FilterSpec {
        override fun build(): SignalFilter = MedianFilter(window)
    }

    /**
     * Median then EMA — the default for landmark-derived signals.
     *
     * The median kills isolated spikes, which is what a momentarily mis-placed landmark looks
     * like; the EMA then removes the remaining ripple. An EMA alone smears a spike across several
     * frames instead of rejecting it, and near a phase threshold that smear is a phantom rep.
     */
    @Serializable
    @SerialName("medianEma")
    data class MedianThenEma(
        val window: Int = 3,
        val alpha: Double = 0.4,
    ) : FilterSpec {
        override fun build(): SignalFilter = CompositeFilter(MedianFilter(window), ExponentialMovingAverage(alpha))
    }
}

class PassThroughFilter : SignalFilter {
    private var last: Double? = null

    override val latest: Double? get() = last

    override fun filter(
        value: Double,
        timestampMs: Long,
    ): Double {
        last = value
        return value
    }

    override fun reset() {
        last = null
    }
}

class ExponentialMovingAverage(
    private val alpha: Double,
) : SignalFilter {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0,1], was $alpha" }
    }

    private var state: Double? = null

    override val latest: Double? get() = state

    override fun filter(
        value: Double,
        timestampMs: Long,
    ): Double {
        val previous = state
        val next = if (previous == null) value else previous + alpha * (value - previous)
        state = next
        return next
    }

    override fun reset() {
        state = null
    }
}

/**
 * Sliding median.
 *
 * Before the window is full it takes the median of what it has, so the filter is usable from the
 * first frame rather than blanking the start of a set.
 */
class MedianFilter(
    private val window: Int,
) : SignalFilter {
    init {
        require(window >= 1) { "window must be at least 1" }
        require(window % 2 == 1) { "window must be odd so the median is an actual sample" }
    }

    private val buffer = ArrayDeque<Double>()
    private var last: Double? = null

    override val latest: Double? get() = last

    override fun filter(
        value: Double,
        timestampMs: Long,
    ): Double {
        buffer.addLast(value)
        if (buffer.size > window) buffer.removeFirst()
        val sorted = buffer.sorted()
        val median = sorted[sorted.size / 2]
        last = median
        return median
    }

    override fun reset() {
        buffer.clear()
        last = null
    }
}

class CompositeFilter(
    private vararg val stages: SignalFilter,
) : SignalFilter {
    private var last: Double? = null

    override val latest: Double? get() = last

    override fun filter(
        value: Double,
        timestampMs: Long,
    ): Double {
        var v = value
        for (stage in stages) v = stage.filter(v, timestampMs)
        last = v
        return v
    }

    override fun reset() {
        stages.forEach { it.reset() }
        last = null
    }
}

/**
 * Tracks the extremes of a signal over the current repetition, for range-of-motion scoring.
 *
 * Reset at the start of each cycle. Peak and trough are the raw extremes seen, not filtered ones,
 * so a range metric is not penalised by smoothing lag at the turnaround.
 */
class ExcursionTracker {
    private var minimum: Double? = null
    private var maximum: Double? = null
    private var samples = 0

    val min: Double? get() = minimum
    val max: Double? get() = maximum
    val count: Int get() = samples

    /** Peak-to-trough span, or null before two samples. */
    val span: Double?
        get() {
            val lo = minimum ?: return null
            val hi = maximum ?: return null
            return hi - lo
        }

    fun add(value: Double) {
        samples++
        minimum = minimum?.let { if (value < it) value else it } ?: value
        maximum = maximum?.let { if (value > it) value else it } ?: value
    }

    fun reset() {
        minimum = null
        maximum = null
        samples = 0
    }
}

/**
 * Tracks the direction of travel and only reports a reversal once the signal has actually turned
 * around by [minReversalDelta].
 *
 * Without this, a signal hovering at a threshold flips direction on every noise sample and the
 * phase machine above it counts reps that did not happen.
 */
class DirectionTracker(
    private val minReversalDelta: Double,
) {
    init {
        require(minReversalDelta > 0) { "minReversalDelta must be positive" }
    }

    enum class Direction { UNKNOWN, RISING, FALLING }

    private var extreme: Double? = null
    private var currentDirection = Direction.UNKNOWN

    val direction: Direction get() = currentDirection

    /** @return true when this sample confirmed a reversal. */
    fun accept(value: Double): Boolean {
        val anchor = extreme
        if (anchor == null) {
            extreme = value
            return false
        }

        return when (currentDirection) {
            Direction.RISING -> {
                if (value > anchor) {
                    extreme = value
                    false
                } else if (anchor - value >= minReversalDelta) {
                    currentDirection = Direction.FALLING
                    extreme = value
                    true
                } else {
                    false
                }
            }

            Direction.FALLING -> {
                if (value < anchor) {
                    extreme = value
                    false
                } else if (value - anchor >= minReversalDelta) {
                    currentDirection = Direction.RISING
                    extreme = value
                    true
                } else {
                    false
                }
            }

            Direction.UNKNOWN -> {
                if (value - anchor >= minReversalDelta) {
                    currentDirection = Direction.RISING
                    extreme = value
                    true
                } else if (anchor - value >= minReversalDelta) {
                    currentDirection = Direction.FALLING
                    extreme = value
                    true
                } else {
                    // Anchor stays put until a move clears the threshold in one direction or the
                    // other. Letting it drift with the signal would make the opposite
                    // confirmation progressively harder to reach.
                    false
                }
            }
        }
    }

    fun reset() {
        extreme = null
        currentDirection = Direction.UNKNOWN
    }
}
