package com.fliptrainer.core.session

import com.fliptrainer.core.exercise.BaseExerciseEvaluator
import com.fliptrainer.core.exercise.ExerciseDefinition
import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.readiness.ReadinessConfig
import com.fliptrainer.core.readiness.ReadinessGate

/**
 * Owns one set, end to end, on the Galaxy.
 *
 * This is the object plan §4.3 is about: **the Galaxy owns the real-time loop.** Once a manifest
 * is accepted, this controller can carry the set to completion with no iPhone attached — through
 * a BLE drop, a locked phone, or an app that iOS decided to suspend. Nothing here asks a
 * controller for permission mid-set.
 *
 * Every event is appended to the durable log *before* it is returned to the caller, so the
 * "persist before publish" rule cannot be violated by a forgetful call site.
 */
class SessionController(
    private val log: WorkoutEventLog,
    private val readinessConfig: ReadinessConfig = ReadinessConfig(),
) {
    private var manifest: SessionManifest? = null
    private var definition: ExerciseDefinition? = null
    private var evaluator: BaseExerciseEvaluator? = null
    private var gate: ReadinessGate? = null
    private var sequence = SequenceSource()

    var state: SessionState = SessionState.IDLE
        private set

    val activeSessionId: String? get() = manifest?.sessionId?.takeIf { state.isActive }

    val currentManifest: SessionManifest? get() = manifest

    /**
     * Accept a manifest and arm the set.
     *
     * @throws IllegalSessionTransition if a set is already live. Replacing a running set silently
     *   would discard reps the athlete actually performed.
     */
    fun arm(
        manifest: SessionManifest,
        definition: ExerciseDefinition,
        evaluator: BaseExerciseEvaluator,
    ): List<WorkoutEvent> {
        transition(SessionState.ARMED)

        this.manifest = manifest
        this.definition = definition
        this.evaluator = evaluator.also { it.reset(manifest) }
        this.gate = ReadinessGate(definition.readinessRequirements(), readinessConfig)
        this.sequence = SequenceSource()

        return publish(
            manifest.sessionId,
            manifest.createdAtEpochMs,
            EventPayload.SessionAccepted(
                exerciseId = manifest.exerciseId,
                exerciseDefinitionVersion = manifest.exerciseDefinitionVersion,
                targetReps = manifest.targetReps,
                setNumber = manifest.setNumber,
            ),
        )
    }

    /**
     * Feed one inference result.
     *
     * Frames are ignored while paused or terminal — deliberately, and without complaint. A paused
     * set still receives frames because the camera is still running; treating that as an error
     * would make pause unusable.
     */
    fun onFrame(frame: PoseFrame): List<WorkoutEvent> {
        val m = manifest ?: return emptyList()
        val g = gate ?: return emptyList()
        val e = evaluator ?: return emptyList()
        if (state.isTerminal || state == SessionState.PAUSED) return emptyList()

        val events = mutableListOf<WorkoutEvent>()
        val transition = g.accept(frame)

        if (transition.changed) {
            events +=
                publish(
                    m.sessionId,
                    frame.monotonicTimestampMs,
                    EventPayload.ReadinessChanged(
                        status = transition.state.status.name,
                        details = transition.state.details,
                        confidencePerMille = Quantize.perMille(transition.state.confidence),
                    ),
                )
            moveTo(if (transition.state.isReady) SessionState.READY else SessionState.NOT_READY)
        }

        for (event in e.accept(frame, transition.state)) {
            val stamped = event.copy(sequenceNumber = sequence.next())
            if (log.append(stamped)) events += stamped
            if (stamped.payload is EventPayload.SetStarted) moveTo(SessionState.RUNNING)
        }

        return events
    }

    fun pause(nowMs: Long): List<WorkoutEvent> {
        val m = manifest ?: return emptyList()
        if (state == SessionState.PAUSED || state.isTerminal) return emptyList()
        transition(SessionState.PAUSED)
        return publish(m.sessionId, nowMs, EventPayload.SetPaused)
    }

    fun resume(nowMs: Long): List<WorkoutEvent> {
        val m = manifest ?: return emptyList()
        if (state != SessionState.PAUSED) return emptyList()
        // Back to NOT_READY, not RUNNING: the athlete may have moved during the pause, so the
        // framing gate has to re-establish readiness before counting resumes.
        transition(SessionState.NOT_READY)
        gate?.reset()
        return publish(m.sessionId, nowMs, EventPayload.SetResumed)
    }

    fun complete(nowMs: Long): List<WorkoutEvent> {
        val m = manifest ?: return emptyList()
        val e = evaluator ?: return emptyList()
        if (state.isTerminal) return emptyList()

        val events = mutableListOf<WorkoutEvent>()
        for (event in e.complete(nowMs)) {
            val stamped = event.copy(sequenceNumber = sequence.next())
            if (log.append(stamped)) events += stamped
        }
        transition(SessionState.COMPLETED)
        check(m.sessionId.isNotEmpty())
        return events
    }

    fun abort(
        nowMs: Long,
        reason: String,
    ): List<WorkoutEvent> {
        val m = manifest ?: return emptyList()
        val e = evaluator ?: return emptyList()
        if (state.isTerminal) return emptyList()

        val events = mutableListOf<WorkoutEvent>()
        for (event in e.abort(nowMs, reason)) {
            val stamped = event.copy(sequenceNumber = sequence.next())
            if (log.append(stamped)) events += stamped
        }
        transition(SessionState.ABORTED)
        check(m.sessionId.isNotEmpty())
        return events
    }

    /** Unrecoverable fault. Terminal, and recorded so a diagnostic export can explain it. */
    fun fail(
        nowMs: Long,
        code: String,
        message: String,
    ): List<WorkoutEvent> {
        val m = manifest ?: return emptyList()
        if (state.isTerminal) return emptyList()
        val events = publish(m.sessionId, nowMs, EventPayload.DeviceWarning(code, message))
        transition(SessionState.ERROR)
        return events
    }

    fun snapshot(): ExerciseSnapshot = evaluator?.snapshot() ?: ExerciseSnapshot.IDLE

    /** Everything the controller has not confirmed persisted. The BLE replay payload. */
    fun pendingForController(limit: Int = Int.MAX_VALUE): List<WorkoutEvent> =
        manifest?.let { log.unacknowledged(it.sessionId, limit) } ?: emptyList()

    fun acknowledge(upTo: Long) {
        manifest?.let { log.acknowledge(it.sessionId, upTo) }
    }

    private fun publish(
        sessionId: String,
        nowMs: Long,
        payload: EventPayload,
    ): List<WorkoutEvent> {
        val event =
            WorkoutEvent(
                sessionId = sessionId,
                sequenceNumber = sequence.next(),
                monotonicTimestampMs = nowMs,
                payload = payload,
            )
        return if (log.append(event)) listOf(event) else emptyList()
    }

    /** Strict: an illegal transition is a caller bug and is thrown, not coerced. */
    private fun transition(next: SessionState) {
        if (!state.canTransitionTo(next)) throw IllegalSessionTransition(state, next)
        state = next
    }

    /** Lenient: readiness churn moves between NOT_READY/READY/RUNNING without complaint. */
    private fun moveTo(next: SessionState) {
        if (state == next) return
        // Once running, readiness flapping must not knock the set out of RUNNING and lose the
        // athlete's place; only an explicit command or a terminal event leaves RUNNING.
        if (state == SessionState.RUNNING && (next == SessionState.READY || next == SessionState.NOT_READY)) return
        if (state.canTransitionTo(next)) state = next
    }
}
