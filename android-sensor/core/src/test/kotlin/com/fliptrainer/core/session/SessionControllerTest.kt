package com.fliptrainer.core.session

import com.fliptrainer.core.exercise.ExerciseCatalog
import com.fliptrainer.core.exercise.StandingCurlEvaluator
import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.synthetic.SyntheticMotion
import com.fliptrainer.core.synthetic.SyntheticPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SessionStateTest {
    @Test
    fun `terminal states admit no further transitions`() {
        for (terminal in listOf(SessionState.COMPLETED, SessionState.ABORTED, SessionState.ERROR)) {
            for (next in SessionState.entries) {
                assertFalse(terminal.canTransitionTo(next), "$terminal must not reach $next")
            }
            assertTrue(terminal.isTerminal)
            assertFalse(terminal.isActive)
        }
    }

    @Test
    fun `a set cannot start counting before it is armed`() {
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.RUNNING))
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.READY))
        assertTrue(SessionState.IDLE.canTransitionTo(SessionState.ARMED))
    }

    @Test
    fun `every active state can be aborted`() {
        for (state in SessionState.entries.filter { it.isActive }) {
            assertTrue(state.canTransitionTo(SessionState.ABORTED), "$state must be abortable")
        }
    }

    @Test
    fun `readiness can be lost and regained while armed`() {
        assertTrue(SessionState.READY.canTransitionTo(SessionState.NOT_READY))
        assertTrue(SessionState.NOT_READY.canTransitionTo(SessionState.READY))
    }
}

class WorkoutEventLogTest {
    private fun event(
        sessionId: String,
        seq: Long,
        payload: EventPayload = EventPayload.SetStarted,
    ) = WorkoutEvent(sessionId, seq, seq * 100, payload)

    @Test
    fun `appending the same key twice is a no-op`() {
        val log = InMemoryWorkoutEventLog()
        assertTrue(log.append(event("s", 0)))
        assertFalse(log.append(event("s", 0)), "a duplicate key must not create a second row")
        assertEquals(1, log.allEvents("s").size)
    }

    @Test
    fun `events are returned in sequence order regardless of insertion order`() {
        val log = InMemoryWorkoutEventLog()
        listOf(2L, 0L, 1L).forEach { log.append(event("s", it)) }
        assertEquals(listOf(0L, 1L, 2L), log.allEvents("s").map { it.sequenceNumber })
    }

    @Test
    fun `acknowledgement never rewinds`() {
        // A reconnecting client can send a stale ack. Honouring it would re-send events the
        // controller already stored, and worse, make "unacknowledged" grow after a successful sync.
        val log = InMemoryWorkoutEventLog()
        (0L..5L).forEach { log.append(event("s", it)) }
        log.acknowledge("s", 4)
        log.acknowledge("s", 1)
        assertEquals(4L, log.highestAcknowledged("s"))
        assertEquals(listOf(5L), log.unacknowledged("s").map { it.sequenceNumber })
    }

    @Test
    fun `unacknowledged is everything after the acknowledgement point`() {
        val log = InMemoryWorkoutEventLog()
        (0L..9L).forEach { log.append(event("s", it)) }
        assertEquals(10, log.unacknowledged("s").size)
        log.acknowledge("s", 6)
        assertEquals(listOf(7L, 8L, 9L), log.unacknowledged("s").map { it.sequenceNumber })
    }

    @Test
    fun `a session is open until it has a terminal event`() {
        val log = InMemoryWorkoutEventLog()
        log.append(event("s", 0))
        assertEquals(listOf("s"), log.openSessions())
        log.append(event("s", 1, EventPayload.SetCompleted(5)))
        assertTrue(log.openSessions().isEmpty())
    }

    @Test
    fun `an aborted session also counts as closed`() {
        val log = InMemoryWorkoutEventLog()
        log.append(event("s", 0))
        log.append(event("s", 1, EventPayload.SetAborted("cancelled")))
        assertTrue(log.openSessions().isEmpty())
    }

    @Test
    fun `recovery closes an interrupted session explicitly`() {
        // Plan §8: an interrupted active set is recovered or explicitly marked interrupted.
        val log = InMemoryWorkoutEventLog()
        log.append(event("crashed", 0))
        log.append(event("crashed", 1, EventPayload.RepCompleted(1, 1500, 700, 800, 980, 950)))
        log.append(event("finished", 0))
        log.append(event("finished", 1, EventPayload.SetCompleted(3)))

        val result = SessionRecovery.recoverOpenSessions(log, nowMs = 99_999)

        assertEquals(listOf("crashed"), result.recoveredSessions, "a completed session must be left alone")
        val closing = log.allEvents("crashed").last().payload
        assertTrue(closing is EventPayload.SetAborted)
        assertEquals(SessionRecovery.INTERRUPTED_REASON, (closing as EventPayload.SetAborted).reason)
    }

    @Test
    fun `recovery does not discard the reps that were recorded before the crash`() {
        val log = InMemoryWorkoutEventLog()
        log.append(event("crashed", 0))
        repeat(4) { i ->
            log.append(event("crashed", 1L + i, EventPayload.RepCompleted(i + 1, 1500, 700, 800, 980, 950)))
        }
        SessionRecovery.recoverOpenSessions(log, nowMs = 1)
        assertEquals(4, log.allEvents("crashed").count { it.payload is EventPayload.RepCompleted })
    }

    @Test
    fun `recovery is idempotent across repeated restarts`() {
        val log = InMemoryWorkoutEventLog()
        log.append(event("crashed", 0))
        SessionRecovery.recoverOpenSessions(log, nowMs = 1)
        val second = SessionRecovery.recoverOpenSessions(log, nowMs = 2)
        assertTrue(second.recoveredSessions.isEmpty(), "a second restart must not add another abort")
        assertEquals(1, log.allEvents("crashed").count { it.payload is EventPayload.SetAborted })
    }
}

class SessionControllerTest {
    private val definition = ExerciseCatalog.load(StandingCurlEvaluator.ID)

    private fun manifest(id: String = "s1") =
        SessionManifest(
            sessionId = id,
            exerciseId = definition.id,
            exerciseDefinitionVersion = definition.version,
            targetReps = 10,
            setNumber = 1,
            dumbbellWeight = 12.0,
            weightUnit = WeightUnit.KG,
            laterality = definition.laterality,
            preferredView = definition.preferredView,
            createdAtEpochMs = 0,
        )

    private fun curlFrames(reps: Int): List<PoseFrame> =
        SyntheticMotion.set(reps, poseAt = { u ->
            val limb = SyntheticPose.LimbState(elbowAngleDeg = SyntheticMotion.lerp(172.0, 45.0, u))
            SyntheticPose(torso = 0.25, left = limb, right = limb)
        })

    private fun armed(log: WorkoutEventLog = InMemoryWorkoutEventLog()): Pair<SessionController, WorkoutEventLog> {
        val controller = SessionController(log)
        controller.arm(manifest(), definition, StandingCurlEvaluator(definition))
        return controller to log
    }

    @Test
    fun `arming records the manifest and moves out of idle`() {
        val (controller, log) = armed()
        assertEquals(SessionState.ARMED, controller.state)
        val accepted = log.allEvents("s1").single().payload
        assertTrue(accepted is EventPayload.SessionAccepted)
        assertEquals(definition.version, (accepted as EventPayload.SessionAccepted).exerciseDefinitionVersion)
    }

    @Test
    fun `a full set runs to completion with no controller attached`() {
        // Plan §4.3: the Galaxy owns the real-time loop. Nothing here consults an iPhone.
        val (controller, log) = armed()
        curlFrames(8).forEach { controller.onFrame(it) }
        controller.complete(nowMs = 999_999)

        assertEquals(SessionState.COMPLETED, controller.state)
        assertEquals(8, log.allEvents("s1").count { it.payload is EventPayload.RepCompleted })
        val last = log.allEvents("s1").last().payload
        assertEquals(8, (last as EventPayload.SetCompleted).totalReps)
    }

    @Test
    fun `every returned event was persisted first`() {
        val (controller, log) = armed()
        val returned = curlFrames(4).flatMap { controller.onFrame(it) } + controller.complete(999_999)
        val stored = log.allEvents("s1")
        assertEquals(stored.map { it.key }.toSet(), returned.map { it.key }.toSet() + "s1#0")
    }

    @Test
    fun `sequence numbers are unique and increasing`() {
        val (controller, log) = armed()
        curlFrames(5).forEach { controller.onFrame(it) }
        controller.complete(999_999)
        val sequences = log.allEvents("s1").map { it.sequenceNumber }
        assertEquals(sequences.distinct(), sequences, "duplicate sequence numbers break replay")
        assertEquals(sequences.sorted(), sequences)
    }

    @Test
    fun `the state machine reaches running once counting starts`() {
        val (controller, _) = armed()
        val frames = curlFrames(2)
        // Ten frames is ~330ms, inside the readiness hold, so the gate has not transitioned yet
        // and the set is still exactly where arming left it.
        frames.take(10).forEach { controller.onFrame(it) }
        assertFalse(controller.state == SessionState.RUNNING, "counting must not have started")
        assertTrue(controller.state.isActive)

        frames.forEach { controller.onFrame(it) }
        assertEquals(SessionState.RUNNING, controller.state)
    }

    @Test
    fun `readiness flapping does not knock a running set out of running`() {
        val (controller, _) = armed()
        val frames = curlFrames(3)
        frames.forEach { controller.onFrame(it) }
        assertEquals(SessionState.RUNNING, controller.state)

        // Athlete briefly leaves frame. The set must not silently un-start.
        repeat(30) { i ->
            controller.onFrame(PoseFrame.empty(200_000L + i * 33, 720, 1280))
        }
        assertEquals(SessionState.RUNNING, controller.state)
    }

    @Test
    fun `pausing ignores frames and resuming requires readiness again`() {
        val (controller, log) = armed()
        val frames = curlFrames(6)
        frames.take(120).forEach { controller.onFrame(it) }
        val repsBeforePause = log.allEvents("s1").count { it.payload is EventPayload.RepCompleted }

        controller.pause(nowMs = 500_000)
        assertEquals(SessionState.PAUSED, controller.state)
        frames.forEach { controller.onFrame(it) }
        assertEquals(
            repsBeforePause,
            log.allEvents("s1").count { it.payload is EventPayload.RepCompleted },
            "frames delivered while paused must not count",
        )

        controller.resume(nowMs = 600_000)
        assertEquals(SessionState.NOT_READY, controller.state, "resume re-establishes framing before counting")
    }

    @Test
    fun `aborting is terminal and records the reason`() {
        val (controller, log) = armed()
        curlFrames(2).forEach { controller.onFrame(it) }
        controller.abort(nowMs = 900_000, reason = "user cancelled")

        assertEquals(SessionState.ABORTED, controller.state)
        val last = log.allEvents("s1").last().payload
        assertEquals("user cancelled", (last as EventPayload.SetAborted).reason)

        assertTrue(controller.onFrame(curlFrames(1).first()).isEmpty(), "a terminal set accepts nothing further")
        assertTrue(controller.complete(999_999).isEmpty())
    }

    @Test
    fun `arming over a live set is refused`() {
        val (controller, _) = armed()
        curlFrames(1).forEach { controller.onFrame(it) }
        assertThrows<IllegalSessionTransition> {
            controller.arm(manifest("s2"), definition, StandingCurlEvaluator(definition))
        }
    }

    @Test
    fun `acknowledgement drains the replay backlog`() {
        val (controller, _) = armed()
        curlFrames(4).forEach { controller.onFrame(it) }
        val pending = controller.pendingForController()
        assertTrue(pending.isNotEmpty())

        controller.acknowledge(pending.last().sequenceNumber)
        assertTrue(controller.pendingForController().isEmpty(), "acknowledged events must not be replayed")
    }

    @Test
    fun `a partially acknowledged set replays only the missing tail`() {
        val (controller, _) = armed()
        curlFrames(6).forEach { controller.onFrame(it) }
        val all = controller.pendingForController()
        val midpoint = all[all.size / 2].sequenceNumber

        controller.acknowledge(midpoint)
        val remaining = controller.pendingForController()
        assertTrue(remaining.all { it.sequenceNumber > midpoint })
        assertEquals(all.size - all.count { it.sequenceNumber <= midpoint }, remaining.size)
    }

    @Test
    fun `no active session id once the set is terminal`() {
        val (controller, _) = armed()
        assertEquals("s1", controller.activeSessionId)
        controller.complete(nowMs = 1)
        assertNull(controller.activeSessionId)
    }

    @Test
    fun `a failure is terminal and leaves a diagnostic trail`() {
        val (controller, log) = armed()
        controller.fail(nowMs = 5_000, code = "cameraLost", message = "camera unbound unexpectedly")
        assertEquals(SessionState.ERROR, controller.state)
        val warning = log.allEvents("s1").last().payload as EventPayload.DeviceWarning
        assertEquals("cameraLost", warning.code)
    }

    @Test
    fun `a complete workout of four sets is recorded on the device alone`() {
        // Plan §8 acceptance: a complete four-exercise workout recorded entirely on the Galaxy.
        val log = InMemoryWorkoutEventLog()
        for ((index, id) in ExerciseCatalog.ids.withIndex()) {
            val exercise = ExerciseCatalog.load(id)
            val controller = SessionController(log)
            val sessionManifest =
                manifest("workout-set-$index").copy(
                    exerciseId = exercise.id,
                    exerciseDefinitionVersion = exercise.version,
                    setNumber = index + 1,
                    laterality = exercise.laterality,
                    preferredView = exercise.preferredView,
                )
            controller.arm(sessionManifest, exercise, ExerciseCatalog.evaluatorFor(id))
            controller.complete(nowMs = 1_000L * (index + 1))
        }

        assertEquals(4, log.sessionIds().size)
        assertTrue(log.openSessions().isEmpty(), "every set must be closed")
    }
}
