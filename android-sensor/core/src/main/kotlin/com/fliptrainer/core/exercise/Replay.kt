package com.fliptrainer.core.exercise

import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.readiness.ReadinessConfig
import com.fliptrainer.core.readiness.ReadinessGate
import com.fliptrainer.core.readiness.ReadinessState
import com.fliptrainer.core.session.EventPayload
import com.fliptrainer.core.session.ExerciseSnapshot
import com.fliptrainer.core.session.Quantize
import com.fliptrainer.core.session.SessionManifest
import com.fliptrainer.core.session.WorkoutEvent
import com.fliptrainer.core.trace.LoadedTrace

/**
 * Runs a recorded or generated trace through the real pipeline — readiness gate, evaluator, cycle
 * detector — with no camera and no device (plan §5 task 7).
 *
 * This is the mechanism behind every claim of determinism in this repository. The same code path
 * that runs on the Flip5 runs in a unit test and in `tools/trace-inspector`, so a threshold change
 * can be reviewed as an event diff before anyone straps a phone to a tripod.
 */
class TraceReplayRunner(
    private val definition: ExerciseDefinition,
    private val readinessConfig: ReadinessConfig = ReadinessConfig(),
    private val emitReadinessEvents: Boolean = true,
) {
    fun run(
        frames: List<PoseFrame>,
        evaluator: BaseExerciseEvaluator,
        manifest: SessionManifest,
    ): ReplayResult {
        val gate = ReadinessGate(definition.readinessRequirements(), readinessConfig)
        evaluator.reset(manifest)

        val events = mutableListOf<WorkoutEvent>()
        val readinessLog = mutableListOf<ReadinessState>()
        var readyFrames = 0

        events +=
            WorkoutEvent(
                sessionId = manifest.sessionId,
                sequenceNumber = -1, // replaced below; the evaluator owns the real sequence
                monotonicTimestampMs = frames.firstOrNull()?.monotonicTimestampMs ?: 0L,
                payload =
                    EventPayload.SessionAccepted(
                        exerciseId = manifest.exerciseId,
                        exerciseDefinitionVersion = manifest.exerciseDefinitionVersion,
                        targetReps = manifest.targetReps,
                        setNumber = manifest.setNumber,
                    ),
            )

        for (frame in frames) {
            val transition = gate.accept(frame)
            if (transition.changed) {
                readinessLog += transition.state
                if (emitReadinessEvents) {
                    events +=
                        WorkoutEvent(
                            sessionId = manifest.sessionId,
                            sequenceNumber = -1,
                            monotonicTimestampMs = frame.monotonicTimestampMs,
                            payload =
                                EventPayload.ReadinessChanged(
                                    status = transition.state.status.name,
                                    details = transition.state.details,
                                    confidencePerMille = Quantize.perMille(transition.state.confidence),
                                ),
                        )
                }
            }
            if (transition.state.isReady) readyFrames++
            events += evaluator.accept(frame, transition.state)
        }

        val lastTimestamp = frames.lastOrNull()?.monotonicTimestampMs ?: 0L
        events += evaluator.complete(lastTimestamp)

        // Renumber so the sequence is contiguous across evaluator-owned and runner-owned events.
        // The evaluator numbers only its own; the runner interleaves readiness, so a single
        // authority has to assign the final numbers or the golden file has gaps.
        val renumbered = events.mapIndexed { index, e -> e.copy(sequenceNumber = index.toLong()) }

        return ReplayResult(
            events = renumbered,
            finalSnapshot = evaluator.snapshot(),
            readinessTransitions = readinessLog,
            frameCount = frames.size,
            readyFrameCount = readyFrames,
        )
    }

    fun run(
        trace: LoadedTrace,
        evaluator: BaseExerciseEvaluator,
        manifest: SessionManifest,
    ): ReplayResult = run(trace.frames, evaluator, manifest)
}

data class ReplayResult(
    val events: List<WorkoutEvent>,
    val finalSnapshot: ExerciseSnapshot,
    val readinessTransitions: List<ReadinessState>,
    val frameCount: Int,
    val readyFrameCount: Int,
) {
    val repCount: Int
        get() = events.count { it.payload is EventPayload.RepCompleted }

    val rejectedRepCount: Int
        get() = events.count { it.payload is EventPayload.RepRejected }

    val raisedCues: List<String>
        get() = events.mapNotNull { (it.payload as? EventPayload.CueRaised)?.cueId }

    val reps: List<EventPayload.RepCompleted>
        get() = events.mapNotNull { it.payload as? EventPayload.RepCompleted }

    /** Canonical text form. This is what a golden file contains. */
    fun renderEvents(): String = WorkoutEvent.render(events)

    /** Compact one-line-per-event summary for `trace-inspector` output. */
    fun summarize(): String =
        buildString {
            appendLine("frames=$frameCount ready=$readyFrameCount reps=$repCount rejected=$rejectedRepCount")
            for (e in events) {
                appendLine("  %7d  %s".format(e.monotonicTimestampMs, describe(e.payload)))
            }
        }

    private fun describe(payload: EventPayload): String =
        when (payload) {
            is EventPayload.SessionAccepted -> {
                "sessionAccepted ${payload.exerciseId}@${payload.exerciseDefinitionVersion}"
            }

            is EventPayload.ReadinessChanged -> {
                "readiness ${payload.status} ${payload.details.joinToString(",")}"
            }

            EventPayload.SetStarted -> {
                "setStarted"
            }

            is EventPayload.PhaseChanged -> {
                "phase ${payload.previousPhase}->${payload.phase} (${payload.label})"
            }

            is EventPayload.RepCompleted -> {
                "REP ${payload.repNumber} ${payload.repDurationMs}ms " +
                    "con=${payload.concentricDurationMs} ecc=${payload.eccentricDurationMs} " +
                    "rom=${payload.rangeOfMotionPerMille} conf=${payload.confidencePerMille} " +
                    "cues=${payload.cues.joinToString(",")}"
            }

            is EventPayload.RepRejected -> {
                "rejected ${payload.reason} peak=${payload.peakRangeOfMotionPerMille}"
            }

            is EventPayload.CueRaised -> {
                "cue+ ${payload.cueId} ${payload.evidence}"
            }

            is EventPayload.CueCleared -> {
                "cue- ${payload.cueId}"
            }

            EventPayload.SetPaused -> {
                "setPaused"
            }

            EventPayload.SetResumed -> {
                "setResumed"
            }

            is EventPayload.SetCompleted -> {
                "setCompleted reps=${payload.totalReps}"
            }

            is EventPayload.SetAborted -> {
                "setAborted ${payload.reason}"
            }

            is EventPayload.DeviceWarning -> {
                "warn ${payload.code}: ${payload.message}"
            }
        }
}
