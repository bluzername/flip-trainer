package com.fliptrainer.protocol

import com.fliptrainer.core.session.WorkoutEvent
import com.fliptrainer.core.session.WorkoutEventLog

/**
 * Serves missed events to a controller that reconnects (plan §6.5, §9 task 12).
 *
 * The rule the whole design hangs on: **the Galaxy retains every event the controller has not
 * acknowledged, and the controller acknowledges only what it has durably persisted.** A set can
 * therefore run to completion with no controller present, and the full history arrives whenever
 * one comes back.
 *
 * Delivery is at-least-once, never exactly-once — no radio protocol gets exactly-once. Idempotence
 * on the receiving side is what makes that safe, and `sessionId + sequenceNumber` provides it.
 */
class EventReplayServer(
    private val log: WorkoutEventLog,
    private val maxEventsPerBatch: Int = DEFAULT_BATCH,
) {
    /**
     * Events after [afterSequence], oldest first.
     *
     * @param maxEvents caller's cap, clamped to [maxEventsPerBatch]. A large backlog is delivered
     *   in successive batches rather than one burst, so a reconnect after a long disconnection
     *   does not stall the radio for the rest of the set.
     */
    fun replay(
        sessionId: String,
        afterSequence: Long,
        maxEvents: Int = maxEventsPerBatch,
    ): ReplayBatch {
        val limit = maxEvents.coerceIn(1, maxEventsPerBatch)
        val events = log.eventsAfter(sessionId, afterSequence, limit)
        val highest = log.highestSequence(sessionId) ?: -1L
        val lastDelivered = events.lastOrNull()?.sequenceNumber ?: afterSequence
        return ReplayBatch(
            sessionId = sessionId,
            events = events,
            hasMore = lastDelivered < highest,
            highestAvailable = highest,
        )
    }

    /** Everything the controller has not confirmed. What a fresh reconnect asks for. */
    fun pending(
        sessionId: String,
        maxEvents: Int = maxEventsPerBatch,
    ): ReplayBatch = replay(sessionId, log.highestAcknowledged(sessionId), maxEvents)

    /**
     * Record an acknowledgement.
     *
     * @return false if the acknowledgement was stale or ahead of what exists. Ahead is rejected
     *   rather than trusted: a controller claiming to have persisted events the Galaxy has not
     *   even produced is confused or hostile, and honouring it would silently discard the real
     *   events when they arrive.
     */
    fun acknowledge(
        sessionId: String,
        through: Long,
    ): Boolean {
        val highest = log.highestSequence(sessionId) ?: return false
        if (through > highest) return false
        val current = log.highestAcknowledged(sessionId)
        if (through <= current) return false
        log.acknowledge(sessionId, through)
        return true
    }

    fun acknowledgedThrough(sessionId: String): Long = log.highestAcknowledged(sessionId)

    companion object {
        const val DEFAULT_BATCH: Int = 64
    }
}

data class ReplayBatch(
    val sessionId: String,
    val events: List<WorkoutEvent>,
    val hasMore: Boolean,
    val highestAvailable: Long,
) {
    val isEmpty: Boolean get() = events.isEmpty()
}

/**
 * The controller's side: absorbs events, tolerating duplicates and out-of-order arrival.
 *
 * Acknowledges the **highest contiguous** sequence, not the highest seen. If events 5 and 7 arrive
 * but 6 does not, acknowledging 7 would tell the Galaxy it may forget 6, and 6 would be lost for
 * good. Acknowledging 5 costs one redundant redelivery and loses nothing.
 */
class EventReplayClient(
    /**
     * Highest sequence this controller already has durably stored from a previous connection.
     *
     * A reconnecting controller is not starting from scratch. Without this it would report an
     * acknowledgement of -1, forcing the Galaxy to re-send the whole set on every reconnect, and
     * [gaps] would permanently claim the already-stored prefix was missing.
     */
    resumeAfter: Long = -1L,
) {
    private val seen = HashSet<Long>()
    private val accepted = mutableListOf<WorkoutEvent>()
    private var contiguous = resumeAfter
    private val resumePoint = resumeAfter

    /** Events accepted so far, in sequence order, each exactly once. */
    val events: List<WorkoutEvent> get() = accepted.sortedBy { it.sequenceNumber }

    val highestContiguous: Long get() = contiguous

    val duplicatesRejected: Int get() = duplicates

    private var duplicates = 0

    /** @return true if this event was new. */
    fun accept(event: WorkoutEvent): Boolean {
        if (!seen.add(event.sequenceNumber)) {
            duplicates++
            return false
        }
        accepted += event
        advance()
        return true
    }

    fun acceptAll(batch: Iterable<WorkoutEvent>): Int = batch.count { accept(it) }

    /** The sequence number to acknowledge. */
    fun acknowledgement(): Long = contiguous

    /** Sequence numbers missing below the highest seen — what a targeted replay should request. */
    fun gaps(): List<Long> {
        val highest = seen.maxOrNull() ?: return emptyList()
        return ((contiguous + 1)..highest).filter { it !in seen }
    }

    fun reset() {
        seen.clear()
        accepted.clear()
        contiguous = resumePoint
        duplicates = 0
    }

    private fun advance() {
        var next = contiguous + 1
        while (seen.contains(next)) {
            contiguous = next
            next++
        }
    }
}
