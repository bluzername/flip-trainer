package com.fliptrainer.core.exercise

import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.readiness.ReadinessState
import com.fliptrainer.core.session.EventPayload
import com.fliptrainer.core.session.ExerciseSnapshot
import com.fliptrainer.core.session.Quantize
import com.fliptrainer.core.session.SequenceSource
import com.fliptrainer.core.session.SessionManifest
import com.fliptrainer.core.session.WorkoutEvent

/**
 * The contract every exercise implements (plan §5 task 5).
 *
 * Implementations must be deterministic: identical `(frame, readiness)` sequences produce
 * identical event sequences. That is what makes golden replay tests a regression suite rather
 * than a smoke test.
 */
interface ExerciseEvaluator {
    fun reset(manifest: SessionManifest)

    fun accept(
        frame: PoseFrame,
        readiness: ReadinessState,
    ): List<WorkoutEvent>

    fun snapshot(): ExerciseSnapshot
}

/**
 * What a cue observed on one frame.
 *
 * @property abstain the cue could not be evaluated — a landmark it depends on was not visible
 *   enough. Distinct from `violated = false`, which is a positive statement that the movement was
 *   fine. Conflating the two is how a cue ends up silently clearing itself whenever the athlete
 *   turns away from the camera.
 */
data class CueObservation(
    val id: String,
    val violated: Boolean,
    val evidence: Map<String, Int> = emptyMap(),
    val abstain: Boolean = false,
) {
    companion object {
        fun abstained(id: String): CueObservation = CueObservation(id, violated = false, abstain = true)
    }
}

/**
 * Debounces one cue so it does not flicker (plan §7.4).
 *
 * Asymmetric with respect to abstention: an abstained frame **resets** the pending raise, because
 * a cue must hold across a continuously observed window to be trustworthy; but it does not clear
 * an already-raised cue, because losing sight of a limb is not evidence the movement improved.
 */
internal class CueDebouncer(
    private val spec: CueSpec,
) {
    private var raised = false
    private var violatedSinceMs: Long? = null
    private var clearSinceMs: Long? = null
    private var lastEvidence: Map<String, Int> = emptyMap()

    val isRaised: Boolean get() = raised

    fun reset() {
        raised = false
        violatedSinceMs = null
        clearSinceMs = null
        lastEvidence = emptyMap()
    }

    /** @return a payload to emit, or null when nothing changed. */
    fun update(
        observation: CueObservation,
        nowMs: Long,
    ): EventPayload? {
        if (!spec.enabled) return null

        if (observation.abstain) {
            violatedSinceMs = null
            return null
        }

        if (observation.violated) {
            lastEvidence = observation.evidence
            clearSinceMs = null
            val since = violatedSinceMs ?: nowMs.also { violatedSinceMs = it }
            if (!raised && nowMs - since >= spec.debounceMs) {
                raised = true
                return EventPayload.CueRaised(spec.id, spec.message, observation.evidence)
            }
            return null
        }

        violatedSinceMs = null
        val since = clearSinceMs ?: nowMs.also { clearSinceMs = it }
        if (raised && nowMs - since >= spec.debounceMs) {
            raised = false
            return EventPayload.CueCleared(spec.id)
        }
        return null
    }

    /** Force-clear without waiting for the debounce — used when a set stops or readiness is lost. */
    fun forceClear(): EventPayload? {
        violatedSinceMs = null
        clearSinceMs = null
        if (!raised) return null
        raised = false
        return EventPayload.CueCleared(spec.id)
    }
}

/**
 * Shared machinery for every exercise: readiness gating, filtering, progress mapping, the
 * repetition cycle, and cue debouncing.
 *
 * A concrete evaluator supplies three things — the driving feature, the range-of-motion score,
 * and the cue observations. Everything else is identical across the catalog and is implemented
 * once, here.
 */
abstract class BaseExerciseEvaluator(
    protected val definition: ExerciseDefinition,
) : ExerciseEvaluator {
    private val detector = RepCycleDetector(definition.cycle)
    private val filter = definition.filter.build()
    private val debouncers = definition.cues.associate { it.id to CueDebouncer(it) }

    private lateinit var manifest: SessionManifest
    private var sequence = SequenceSource()
    private var started = false
    private var completed = false

    private var rawFeature: Double? = null
    private var filteredFeature: Double? = null
    private var rawProgress: Double? = null
    private var filteredProgress: Double? = null
    private var lastConfidence = 0f
    private var wasReady = false

    protected val activeCueIds: Set<String>
        get() = debouncers.filterValues { it.isRaised }.keys

    /**
     * Highest progress reached so far in the repetition currently in flight.
     *
     * Cues that judge a repetition as a whole — "you stopped short of the top" — need this, and
     * they need it before the rep finishes, so that the feedback lands during the movement rather
     * than after it.
     */
    protected val currentPeakProgress: Double
        get() = detector.currentPeakProgress

    /**
     * The scalar this exercise's repetitions are measured on, in its natural units (degrees for a
     * joint angle, body-scale units for a displacement).
     *
     * Return null to abstain for this frame — the cycle detector treats that as a dropout rather
     * than as a value of zero.
     */
    protected abstract fun primaryFeature(frame: PoseFrame): Double?

    /**
     * Cue observations for this frame. Called only while a set is running and readiness holds.
     *
     * Every cue declared in the definition should be reported on every call, as violated, not
     * violated, or abstained — a cue that simply stops being reported can never clear.
     */
    protected abstract fun observeCues(
        frame: PoseFrame,
        phase: MovementPhase,
        progress: Double,
    ): List<CueObservation>

    /**
     * Range-of-motion score for a completed repetition, `0..1`.
     *
     * Defaults to the peak progress reached, which is exactly "how much of the intended range did
     * this rep cover". Override only where an exercise has a better-motivated measure.
     */
    protected open fun rangeOfMotionScore(measurement: RepMeasurement): Double = measurement.peakProgress

    override fun reset(manifest: SessionManifest) {
        require(manifest.exerciseId == definition.id) {
            "manifest is for '${manifest.exerciseId}' but this evaluator is '${definition.id}'"
        }
        this.manifest = manifest
        sequence = SequenceSource()
        detector.reset()
        filter.reset()
        debouncers.values.forEach { it.reset() }
        started = false
        completed = false
        rawFeature = null
        filteredFeature = null
        rawProgress = null
        filteredProgress = null
        lastConfidence = 0f
        wasReady = false
    }

    final override fun accept(
        frame: PoseFrame,
        readiness: ReadinessState,
    ): List<WorkoutEvent> {
        check(::manifest.isInitialized) { "accept() before reset(manifest)" }
        if (completed) return emptyList()

        val events = mutableListOf<WorkoutEvent>()
        val now = frame.monotonicTimestampMs
        lastConfidence = readiness.confidence

        if (!readiness.isReady) {
            // Not ready: the cycle sees a dropout, not a zero. Cues are force-cleared because a
            // cue left raised while the athlete is out of frame would be shown as current.
            if (wasReady) {
                debouncers.values.forEach { d -> d.forceClear()?.let { events += event(now, it) } }
            }
            wasReady = false
            rawFeature = null
            filteredFeature = null
            rawProgress = null
            filteredProgress = null
            emitCycleSignals(detector.accept(null, now, readiness.confidence.toDouble()), now, events)
            return events
        }

        if (!started) {
            started = true
            events += event(now, EventPayload.SetStarted)
        }
        wasReady = true

        val raw = primaryFeature(frame)
        rawFeature = raw
        if (raw == null) {
            rawProgress = null
            filteredProgress = null
            filteredFeature = null
            emitCycleSignals(detector.accept(null, now, readiness.confidence.toDouble()), now, events)
            return events
        }

        val filtered = filter.filter(raw, now)
        filteredFeature = filtered
        rawProgress = definition.range.progressOf(raw)
        val progress = definition.range.progressOf(filtered)
        filteredProgress = progress

        emitCycleSignals(detector.accept(progress, now, readiness.confidence.toDouble()), now, events)

        for (observation in observeCues(frame, detector.currentPhase, progress)) {
            val debouncer = debouncers[observation.id] ?: continue
            debouncer.update(observation, now)?.let { events += event(now, it) }
        }

        return events
    }

    /** Called by the session layer when the set is stopped, to flush terminal events. */
    fun complete(nowMs: Long): List<WorkoutEvent> {
        if (completed) return emptyList()
        val events = mutableListOf<WorkoutEvent>()
        debouncers.values.forEach { d -> d.forceClear()?.let { events += event(nowMs, it) } }
        events += event(nowMs, EventPayload.SetCompleted(detector.reps))
        completed = true
        return events
    }

    fun abort(
        nowMs: Long,
        reason: String,
    ): List<WorkoutEvent> {
        if (completed) return emptyList()
        val events = mutableListOf<WorkoutEvent>()
        debouncers.values.forEach { d -> d.forceClear()?.let { events += event(nowMs, it) } }
        events += event(nowMs, EventPayload.SetAborted(reason))
        completed = true
        return events
    }

    private fun emitCycleSignals(
        signals: List<CycleSignal>,
        nowMs: Long,
        into: MutableList<WorkoutEvent>,
    ) {
        for (signal in signals) {
            when (signal) {
                is CycleSignal.PhaseEntered ->
                    into +=
                        event(
                            nowMs,
                            EventPayload.PhaseChanged(
                                phase = signal.phase.name,
                                previousPhase = signal.previous.name,
                                label = definition.cycle.label(signal.phase),
                            ),
                        )

                is CycleSignal.RepCompleted -> {
                    val m = signal.measurement
                    into +=
                        event(
                            nowMs,
                            EventPayload.RepCompleted(
                                repNumber = m.repNumber,
                                repDurationMs = m.totalDurationMs,
                                concentricDurationMs = m.concentricDurationMs(definition.cycle.outboundIsConcentric),
                                eccentricDurationMs = m.eccentricDurationMs(definition.cycle.outboundIsConcentric),
                                rangeOfMotionPerMille = Quantize.perMille(rangeOfMotionScore(m)),
                                confidencePerMille = Quantize.perMille(m.meanConfidence),
                                cues = activeCueIds.sorted(),
                            ),
                        )
                }

                is CycleSignal.RepRejected ->
                    into +=
                        event(
                            nowMs,
                            EventPayload.RepRejected(signal.reason, Quantize.perMille(signal.peakProgress)),
                        )

                is CycleSignal.CycleAborted ->
                    into += event(nowMs, EventPayload.DeviceWarning("cycleAborted", signal.reason))
            }
        }
    }

    private fun event(
        nowMs: Long,
        payload: EventPayload,
    ): WorkoutEvent =
        WorkoutEvent(
            sessionId = manifest.sessionId,
            sequenceNumber = sequence.next(),
            monotonicTimestampMs = nowMs,
            payload = payload,
        )

    override fun snapshot(): ExerciseSnapshot =
        ExerciseSnapshot(
            phase = detector.currentPhase.name,
            phaseLabel = definition.cycle.label(detector.currentPhase),
            repCount = detector.reps,
            activeCues = activeCueIds,
            confidence = lastConfidence,
            rawProgress = rawProgress,
            filteredProgress = filteredProgress,
            rawPrimaryFeature = rawFeature,
            filteredPrimaryFeature = filteredFeature,
        )
}
