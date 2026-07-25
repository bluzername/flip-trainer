package com.fliptrainer.core.exercise

import com.fliptrainer.core.features.DirectionTracker
import kotlinx.serialization.Serializable

/**
 * Phases named neutrally, because "concentric" is not a fixed direction.
 *
 * In a curl the muscle shortens on the way *out* from rest; in a squat it shortens on the way
 * *back*. Encoding "concentric = outbound" would silently mislabel every hinge and squat pattern,
 * so the cycle detector speaks in outbound/return and each exercise definition declares which one
 * is the concentric half (see [RepCycleConfig.outboundIsConcentric]).
 */
enum class MovementPhase {
    /** No usable signal — before the first valid frame, or after an aborted cycle. */
    IDLE,

    /** At rest, ready to begin a repetition. */
    START,

    /** Travelling away from rest. */
    OUTBOUND,

    /** At the far end of the movement. */
    APEX,

    /** Travelling back towards rest. */
    RETURN,
}

/**
 * Tuning for one exercise's repetition cycle. Stored in versioned JSON, never hard-coded in an
 * evaluator.
 *
 * @property restBelow progress at or below which the athlete counts as being at rest.
 * @property outboundAbove progress above which the athlete counts as having left rest. Strictly
 *   greater than [restBelow]: the gap between them is the hysteresis band, and it is the reason a
 *   signal sitting on a threshold does not produce an endless stream of reps.
 * @property apexAbove progress above which the movement counts as having reached its far end.
 * @property minPeakProgress the peak a cycle must reach to be counted. A cycle that turns around
 *   below it is a partial repetition and is rejected rather than counted.
 * @property minReversalDelta how far the signal must actually turn around before a reversal is
 *   believed. This is what stops noise at the turnaround from registering as two reps.
 * @property minPhaseDurationMs floor on how long OUTBOUND and RETURN must last.
 * @property minRepDurationMs floor on total cycle duration. Below it, the "repetition" is noise.
 * @property maxRepDurationMs a cycle that runs longer than this is abandoned — the athlete
 *   stopped mid-rep, and carrying the cycle forever would attach a stale start time to whatever
 *   they did next.
 * @property repCooldownMs quiet period after a counted rep, so the settle at the bottom does not
 *   immediately trigger the next outbound.
 * @property maxDropoutMs how long the signal may be unavailable before the cycle is abandoned.
 * @property outboundIsConcentric whether the outbound half is the concentric (muscle-shortening)
 *   one. True for a curl and a press, false for a squat and a hinge.
 */
@Serializable
data class RepCycleConfig(
    val restBelow: Double = 0.15,
    val outboundAbove: Double = 0.25,
    val apexAbove: Double = 0.80,
    val minPeakProgress: Double = 0.80,
    val minReversalDelta: Double = 0.06,
    val minPhaseDurationMs: Long = 120,
    val minRepDurationMs: Long = 500,
    val maxRepDurationMs: Long = 15_000,
    val repCooldownMs: Long = 200,
    val maxDropoutMs: Long = 400,
    val outboundIsConcentric: Boolean = true,
) {
    init {
        require(restBelow < outboundAbove) {
            "restBelow ($restBelow) must be below outboundAbove ($outboundAbove) — the gap is the hysteresis band"
        }
        require(outboundAbove < apexAbove) { "outboundAbove must be below apexAbove" }
        require(minPeakProgress > outboundAbove) { "minPeakProgress must exceed outboundAbove" }
        require(minReversalDelta > 0) { "minReversalDelta must be positive" }
        require(minRepDurationMs in 0..maxRepDurationMs) { "minRepDurationMs must not exceed maxRepDurationMs" }
    }

    /** Human label for a phase, given which half is concentric. */
    fun label(phase: MovementPhase): String =
        when (phase) {
            MovementPhase.IDLE -> "idle"
            MovementPhase.START -> if (outboundIsConcentric) "bottom" else "top"
            MovementPhase.APEX -> if (outboundIsConcentric) "top" else "bottom"
            MovementPhase.OUTBOUND -> if (outboundIsConcentric) "concentric" else "eccentric"
            MovementPhase.RETURN -> if (outboundIsConcentric) "eccentric" else "concentric"
        }
}

/**
 * What one completed repetition measured. Durations are milliseconds.
 *
 * **Durations are threshold-to-threshold, not excursion-to-excursion.** The clock starts when
 * progress crosses [RepCycleConfig.outboundAbove] and stops when it falls back under
 * [RepCycleConfig.restBelow], so a reported duration is shorter than the athlete's full movement
 * time by however long they spent inside the hysteresis band at each end.
 *
 * That is deliberate. Timing the true endpoints means detecting zero-velocity instants in a noisy
 * signal, which is unstable exactly where it matters — at the turnaround. Threshold-to-threshold
 * is reproducible, and for comparing tempo between repetitions and between sets, reproducible
 * beats nominally-correct-but-jittery. Any user-facing tempo copy must describe it as such rather
 * than implying a stopwatch on the whole movement.
 */
data class RepMeasurement(
    val repNumber: Int,
    val totalDurationMs: Int,
    val outboundDurationMs: Int,
    val returnDurationMs: Int,
    val peakProgress: Double,
    val meanConfidence: Double,
) {
    fun concentricDurationMs(outboundIsConcentric: Boolean): Int =
        if (outboundIsConcentric) outboundDurationMs else returnDurationMs

    fun eccentricDurationMs(outboundIsConcentric: Boolean): Int =
        if (outboundIsConcentric) returnDurationMs else outboundDurationMs
}

/** Everything the detector can report for one frame. */
sealed interface CycleSignal {
    data class PhaseEntered(
        val phase: MovementPhase,
        val previous: MovementPhase,
    ) : CycleSignal

    data class RepCompleted(
        val measurement: RepMeasurement,
    ) : CycleSignal

    data class RepRejected(
        val reason: String,
        val peakProgress: Double,
    ) : CycleSignal

    data class CycleAborted(
        val reason: String,
    ) : CycleSignal
}

/**
 * The shared repetition skeleton for every exercise in the catalog.
 *
 * It consumes a single scalar **progress** signal in `0..1`, where 0 is the resting position and
 * 1 is the far end of the intended movement. Each exercise is responsible for mapping its own
 * geometry onto that scale; everything about hysteresis, direction confirmation, minimum
 * excursion, dropout tolerance and cooldown is handled once, here.
 *
 * This is deliberately a *utility that typed evaluators compose*, not a general exercise
 * language. Plan §7.1 and §13.1: extract a declarative format only after four real evaluators
 * have shown which patterns actually repeat.
 *
 * Deterministic: the same `(progress, timestamp, confidence)` sequence always produces the same
 * signals in the same order.
 */
class RepCycleDetector(
    private val config: RepCycleConfig,
) {
    private var phase = MovementPhase.IDLE
    private var phaseEnteredAtMs = 0L
    private var cycleStartedAtMs = 0L
    private var outboundStartedAtMs = 0L
    private var outboundDurationMs = 0
    private var returnStartedAtMs = 0L

    private var peakProgress = 0.0
    private var confidenceSum = 0.0
    private var confidenceCount = 0

    private var repCount = 0

    // Nullable rather than a Long.MIN_VALUE sentinel: `timestampMs - Long.MIN_VALUE` overflows to
    // a negative number, which silently makes every "has enough time passed" check false forever.
    private var lastRepCompletedAtMs: Long? = null
    private var lastValidSignalAtMs: Long? = null

    private val direction = DirectionTracker(config.minReversalDelta)

    val currentPhase: MovementPhase get() = phase
    val reps: Int get() = repCount
    val currentPeakProgress: Double get() = peakProgress

    fun reset() {
        phase = MovementPhase.IDLE
        phaseEnteredAtMs = 0
        cycleStartedAtMs = 0
        outboundStartedAtMs = 0
        outboundDurationMs = 0
        returnStartedAtMs = 0
        peakProgress = 0.0
        confidenceSum = 0.0
        confidenceCount = 0
        repCount = 0
        lastRepCompletedAtMs = null
        lastValidSignalAtMs = null
        direction.reset()
    }

    /**
     * Feed one frame.
     *
     * @param progress `0..1`, or null when the required landmarks were not reliable enough to
     *   compute it. Null is a first-class input, not an error: abstaining is the correct
     *   behaviour and the detector has to survive it without corrupting the current cycle.
     * @param confidence `0..1` aggregate landmark confidence for this frame.
     */
    fun accept(
        progress: Double?,
        timestampMs: Long,
        confidence: Double,
    ): List<CycleSignal> {
        if (progress == null) return handleDropout(timestampMs)

        val p = progress.coerceIn(0.0, 1.0)
        val signals = mutableListOf<CycleSignal>()
        lastValidSignalAtMs = timestampMs

        val reversed = direction.accept(p)

        if (phase != MovementPhase.IDLE && phase != MovementPhase.START) {
            confidenceSum += confidence
            confidenceCount++
            if (p > peakProgress) peakProgress = p

            if (timestampMs - cycleStartedAtMs > config.maxRepDurationMs) {
                signals += CycleSignal.CycleAborted("cycle exceeded ${config.maxRepDurationMs}ms")
                abandonCycle(timestampMs, signals)
                return signals
            }
        }

        when (phase) {
            MovementPhase.IDLE -> {
                if (p <= config.restBelow) transition(MovementPhase.START, timestampMs, signals)
            }

            MovementPhase.START -> {
                val cooledDown = lastRepCompletedAtMs?.let { timestampMs - it >= config.repCooldownMs } ?: true
                if (p > config.outboundAbove &&
                    cooledDown &&
                    direction.direction != DirectionTracker.Direction.FALLING
                ) {
                    beginCycle(timestampMs)
                    transition(MovementPhase.OUTBOUND, timestampMs, signals)
                }
            }

            MovementPhase.OUTBOUND -> {
                val heldLongEnough = timestampMs - phaseEnteredAtMs >= config.minPhaseDurationMs
                if (p >= config.apexAbove) {
                    outboundDurationMs = (timestampMs - outboundStartedAtMs).toInt()
                    transition(MovementPhase.APEX, timestampMs, signals)
                } else if (reversed && heldLongEnough) {
                    // Turned around before reaching the far end. The cycle continues so the
                    // partial can be reported when it gets back to rest, rather than vanishing.
                    outboundDurationMs = (timestampMs - outboundStartedAtMs).toInt()
                    returnStartedAtMs = timestampMs
                    transition(MovementPhase.RETURN, timestampMs, signals)
                }
            }

            MovementPhase.APEX -> {
                if (reversed || p < config.apexAbove - config.minReversalDelta) {
                    returnStartedAtMs = timestampMs
                    transition(MovementPhase.RETURN, timestampMs, signals)
                }
            }

            MovementPhase.RETURN -> {
                val heldLongEnough = timestampMs - phaseEnteredAtMs >= config.minPhaseDurationMs
                if (p <= config.restBelow && heldLongEnough) {
                    finishCycle(timestampMs, signals)
                }
            }
        }

        return signals
    }

    private fun handleDropout(timestampMs: Long): List<CycleSignal> {
        val lastValid = lastValidSignalAtMs ?: return emptyList()
        val gap = timestampMs - lastValid
        if (gap <= config.maxDropoutMs) return emptyList() // hold state; a brief gap is survivable

        if (phase == MovementPhase.IDLE || phase == MovementPhase.START) {
            // Nothing in flight to lose.
            if (phase != MovementPhase.IDLE) {
                val signals = mutableListOf<CycleSignal>()
                transition(MovementPhase.IDLE, timestampMs, signals)
                direction.reset()
                return signals
            }
            return emptyList()
        }

        val signals = mutableListOf<CycleSignal>()
        signals += CycleSignal.CycleAborted("signal lost for ${gap}ms")
        abandonCycle(timestampMs, signals)
        return signals
    }

    private fun beginCycle(timestampMs: Long) {
        cycleStartedAtMs = timestampMs
        outboundStartedAtMs = timestampMs
        outboundDurationMs = 0
        returnStartedAtMs = 0
        peakProgress = 0.0
        confidenceSum = 0.0
        confidenceCount = 0
    }

    private fun abandonCycle(
        timestampMs: Long,
        signals: MutableList<CycleSignal>,
    ) {
        transition(MovementPhase.IDLE, timestampMs, signals)
        direction.reset()
        peakProgress = 0.0
        confidenceSum = 0.0
        confidenceCount = 0
    }

    private fun finishCycle(
        timestampMs: Long,
        signals: MutableList<CycleSignal>,
    ) {
        val totalMs = (timestampMs - cycleStartedAtMs).toInt()
        val returnMs = if (returnStartedAtMs == 0L) 0 else (timestampMs - returnStartedAtMs).toInt()
        val peak = peakProgress

        val tooShort = totalMs < config.minRepDurationMs
        val tooShallow = peak < config.minPeakProgress

        if (tooShort || tooShallow) {
            val reason =
                when {
                    tooShallow && tooShort -> "partial and too fast"
                    tooShallow -> "incomplete range"
                    else -> "too fast"
                }
            signals += CycleSignal.RepRejected(reason, peak)
        } else {
            repCount++
            signals +=
                CycleSignal.RepCompleted(
                    RepMeasurement(
                        repNumber = repCount,
                        totalDurationMs = totalMs,
                        outboundDurationMs = outboundDurationMs,
                        returnDurationMs = returnMs,
                        peakProgress = peak,
                        meanConfidence = if (confidenceCount == 0) 0.0 else confidenceSum / confidenceCount,
                    ),
                )
            lastRepCompletedAtMs = timestampMs
        }

        peakProgress = 0.0
        confidenceSum = 0.0
        confidenceCount = 0
        transition(MovementPhase.START, timestampMs, signals)
    }

    private fun transition(
        next: MovementPhase,
        timestampMs: Long,
        signals: MutableList<CycleSignal>,
    ) {
        if (next == phase) return
        val previous = phase
        phase = next
        phaseEnteredAtMs = timestampMs
        if (next == MovementPhase.OUTBOUND) outboundStartedAtMs = timestampMs
        signals += CycleSignal.PhaseEntered(next, previous)
    }
}
