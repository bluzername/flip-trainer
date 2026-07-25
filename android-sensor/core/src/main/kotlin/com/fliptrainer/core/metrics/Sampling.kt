package com.fliptrainer.core.metrics

/**
 * Wall-clock is not allowed anywhere in the decision path. Every timestamp in this system is
 * monotonic milliseconds, so that an NTP correction or a timezone change mid-set cannot reorder
 * events or invent a negative rep duration.
 */
fun interface MonotonicClock {
    fun nowMs(): Long

    companion object {
        fun system(): MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L }

        /** Test clock. Not thread-safe on purpose — tests should be single-threaded. */
        fun fixed(start: Long = 0L): Mutable = Mutable(start)
    }

    class Mutable(
        private var now: Long,
    ) : MonotonicClock {
        override fun nowMs(): Long = now

        fun advance(ms: Long): Mutable {
            require(ms >= 0) { "monotonic clock cannot go backwards" }
            now += ms
            return this
        }

        fun set(ms: Long): Mutable {
            require(ms >= now) { "monotonic clock cannot go backwards ($ms < $now)" }
            now = ms
            return this
        }
    }
}

/**
 * Fixed-capacity ring of samples with exact percentiles over the retained window.
 *
 * Exact rather than approximate (no t-digest, no reservoir sampling): the windows here are small,
 * the percentiles must be reproducible in a report, and an approximation would be one more thing
 * to disbelieve when a latency number looks wrong.
 */
class SampleWindow(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val samples = LongArray(capacity)
    private var writeIndex = 0
    private var count = 0
    private var total = 0L
    private var overflowCount = 0L

    val size: Int get() = count

    /** Number of samples ever recorded, including ones evicted from the window. */
    val recorded: Long get() = overflowCount + count

    fun add(value: Long) {
        if (count == capacity) {
            total -= samples[writeIndex]
            overflowCount++
        } else {
            count++
        }
        samples[writeIndex] = value
        total += value
        writeIndex = (writeIndex + 1) % capacity
    }

    fun clear() {
        writeIndex = 0
        count = 0
        total = 0L
        overflowCount = 0L
    }

    fun mean(): Double = if (count == 0) 0.0 else total.toDouble() / count

    /**
     * Nearest-rank percentile. `percentile(0.95)` is the smallest retained sample that is ≥ 95 %
     * of them. Returns 0 for an empty window.
     */
    fun percentile(p: Double): Long {
        require(p in 0.0..1.0) { "percentile must be in [0,1], was $p" }
        if (count == 0) return 0L
        val sorted = snapshotSorted()
        val rank = Math.ceil(p * count).toInt().coerceIn(1, count)
        return sorted[rank - 1]
    }

    fun min(): Long = if (count == 0) 0L else snapshotSorted().first()

    fun max(): Long = if (count == 0) 0L else snapshotSorted().last()

    private fun snapshotSorted(): LongArray = samples.copyOf(count).also { it.sort() }

    companion object {
        const val DEFAULT_CAPACITY: Int = 2048
    }
}

/**
 * Frames per second over a sliding time window.
 *
 * Deliberately window-based rather than an exponential average: a thermal-throttling report needs
 * "what was the rate during those five seconds", not a smoothed figure that lags the event being
 * investigated.
 */
class FrameRateMeter(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    init {
        require(windowMs > 0) { "windowMs must be positive" }
    }

    private val timestamps = ArrayDeque<Long>()
    private var totalCount = 0L

    val total: Long get() = totalCount

    fun record(timestampMs: Long) {
        timestamps.addLast(timestampMs)
        totalCount++
        evictBefore(timestampMs - windowMs)
    }

    /**
     * Rate over the window ending at [nowMs].
     *
     * Uses the *window duration* as the denominator once the window is full, rather than the span
     * between first and last sample — with two samples 10 ms apart, the span form would report
     * 100 fps from a camera that just started.
     */
    fun fps(nowMs: Long): Double {
        evictBefore(nowMs - windowMs)
        if (timestamps.size < 2) return 0.0
        val elapsed = nowMs - timestamps.first()
        if (elapsed <= 0) return 0.0
        return timestamps.size * 1000.0 / elapsed
    }

    fun clear() {
        timestamps.clear()
        totalCount = 0L
    }

    private fun evictBefore(cutoff: Long) {
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            timestamps.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 5_000L
    }
}
