package com.fliptrainer.core.session

/**
 * The append-only durable log the Galaxy owns.
 *
 * Two properties matter more than anything else about the implementation:
 *
 * 1. **Persist before publish.** An event is written here before it is sent over BLE or shown in
 *    a UI. If the process dies between the two, the event is recoverable; the other order loses
 *    it silently.
 * 2. **`sessionId + sequenceNumber` is the identity.** Appending the same key twice is a no-op,
 *    not a duplicate row. That is what makes BLE replay safe (plan §6.5) — the iPhone can be
 *    handed the same rep twice and cannot double-count it.
 *
 * The Room-backed implementation lives in the Android module; [InMemoryWorkoutEventLog] is the
 * reference implementation and the one the tests exercise.
 */
interface WorkoutEventLog {
    /** @return true if the event was new, false if this key was already stored. */
    fun append(event: WorkoutEvent): Boolean

    /** Events with `sequenceNumber > after`, ordered, at most [limit]. */
    fun eventsAfter(
        sessionId: String,
        after: Long,
        limit: Int = Int.MAX_VALUE,
    ): List<WorkoutEvent>

    fun allEvents(sessionId: String): List<WorkoutEvent>

    /** Highest sequence number stored for the session, or null when there are none. */
    fun highestSequence(sessionId: String): Long?

    /**
     * Record that the controller has durably persisted everything up to and including [upTo].
     *
     * Monotonic: a lower value is ignored rather than rewinding, so a late-arriving
     * acknowledgement from a reconnecting client cannot un-acknowledge newer events.
     */
    fun acknowledge(
        sessionId: String,
        upTo: Long,
    )

    /** Highest contiguous sequence the controller has acknowledged; -1 when nothing has. */
    fun highestAcknowledged(sessionId: String): Long

    /** Everything the controller has not confirmed, oldest first. The BLE replay payload. */
    fun unacknowledged(
        sessionId: String,
        limit: Int = Int.MAX_VALUE,
    ): List<WorkoutEvent> = eventsAfter(sessionId, highestAcknowledged(sessionId), limit)

    /** Sessions that have events but no terminal event. */
    fun openSessions(): List<String>

    fun sessionIds(): List<String>
}

/** Reference implementation. Also what the tests use — no Android, no database, same semantics. */
class InMemoryWorkoutEventLog : WorkoutEventLog {
    private val bySession = LinkedHashMap<String, MutableList<WorkoutEvent>>()
    private val keys = HashSet<String>()
    private val acknowledged = HashMap<String, Long>()

    override fun append(event: WorkoutEvent): Boolean {
        if (!keys.add(event.key)) return false
        bySession.getOrPut(event.sessionId) { mutableListOf() }.add(event)
        return true
    }

    override fun eventsAfter(
        sessionId: String,
        after: Long,
        limit: Int,
    ): List<WorkoutEvent> =
        bySession[sessionId]
            .orEmpty()
            .asSequence()
            .filter { it.sequenceNumber > after }
            .sortedBy { it.sequenceNumber }
            .take(limit)
            .toList()

    override fun allEvents(sessionId: String): List<WorkoutEvent> =
        bySession[sessionId].orEmpty().sortedBy { it.sequenceNumber }

    override fun highestSequence(sessionId: String): Long? = bySession[sessionId]?.maxOfOrNull { it.sequenceNumber }

    override fun acknowledge(
        sessionId: String,
        upTo: Long,
    ) {
        val current = acknowledged[sessionId] ?: -1L
        if (upTo > current) acknowledged[sessionId] = upTo
    }

    override fun highestAcknowledged(sessionId: String): Long = acknowledged[sessionId] ?: -1L

    override fun openSessions(): List<String> = bySession.keys.filter { !isClosed(it) }

    override fun sessionIds(): List<String> = bySession.keys.toList()

    private fun isClosed(sessionId: String): Boolean =
        bySession[sessionId].orEmpty().any {
            it.payload is EventPayload.SetCompleted || it.payload is EventPayload.SetAborted
        }
}

/**
 * Crash recovery (plan §8 task 8).
 *
 * On startup, any session with events but no terminal event was interrupted — the process died
 * mid-set. It is closed explicitly with a `setAborted` event rather than left dangling.
 *
 * Marking it beats guessing. An interrupted set whose reps are real but whose ending is unknown
 * must not silently become a completed set in the athlete's history, and it must not vanish
 * either; "aborted, interrupted by process restart" is the truthful record.
 */
object SessionRecovery {
    const val INTERRUPTED_REASON: String = "interrupted by process restart"

    data class Result(
        val recoveredSessions: List<String>,
        val events: List<WorkoutEvent>,
    )

    fun recoverOpenSessions(
        log: WorkoutEventLog,
        nowMs: Long,
    ): Result {
        val recovered = mutableListOf<String>()
        val events = mutableListOf<WorkoutEvent>()

        for (sessionId in log.openSessions()) {
            val next = (log.highestSequence(sessionId) ?: -1L) + 1
            val event =
                WorkoutEvent(
                    sessionId = sessionId,
                    sequenceNumber = next,
                    monotonicTimestampMs = nowMs,
                    payload = EventPayload.SetAborted(INTERRUPTED_REASON),
                )
            if (log.append(event)) {
                recovered += sessionId
                events += event
            }
        }
        return Result(recovered, events)
    }
}
