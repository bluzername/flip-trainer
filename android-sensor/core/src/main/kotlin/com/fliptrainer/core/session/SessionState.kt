package com.fliptrainer.core.session

/**
 * The set lifecycle (plan §8 task 3).
 *
 * Modelled as an explicit transition table rather than a scattering of booleans, because the
 * Galaxy is the authority for an active set: it has to survive the iPhone disconnecting, the
 * process being killed, and the athlete walking out of frame, and still be able to say exactly
 * what state the set is in.
 */
enum class SessionState {
    /** No set configured. */
    IDLE,

    /** A manifest has been accepted. The Galaxy can now finish the set with no controller. */
    ARMED,

    /** Armed, but the framing gate is not satisfied. Counting is disabled. */
    NOT_READY,

    /** Armed and framed. Counting is enabled; the first movement starts the set. */
    READY,

    /** Counting. */
    RUNNING,

    /** Explicitly paused by the controller. Frames are ignored. */
    PAUSED,

    /** Finished normally. Terminal. */
    COMPLETED,

    /** Finished abnormally — cancelled, or recovered as interrupted after a restart. Terminal. */
    ABORTED,

    /** Unrecoverable fault. Terminal. */
    ERROR,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == ABORTED || this == ERROR

    /** True while a set is live enough that losing the process would lose data. */
    val isActive: Boolean get() =
        this == ARMED ||
            this == NOT_READY ||
            this == READY ||
            this == RUNNING ||
            this == PAUSED

    fun canTransitionTo(next: SessionState): Boolean = next in ALLOWED.getValue(this)

    private companion object {
        /**
         * Legal transitions. Anything absent here is a bug in the caller, not a state to handle
         * gracefully — the controller throws rather than silently coercing, because a set that
         * quietly slid into the wrong state produces workout history nobody can trust.
         */
        val ALLOWED: Map<SessionState, Set<SessionState>> =
            mapOf(
                IDLE to setOf(ARMED, ERROR),
                ARMED to setOf(NOT_READY, READY, PAUSED, COMPLETED, ABORTED, ERROR),
                NOT_READY to setOf(READY, PAUSED, COMPLETED, ABORTED, ERROR),
                READY to setOf(NOT_READY, RUNNING, PAUSED, COMPLETED, ABORTED, ERROR),
                RUNNING to setOf(NOT_READY, READY, PAUSED, COMPLETED, ABORTED, ERROR),
                PAUSED to setOf(RUNNING, READY, NOT_READY, COMPLETED, ABORTED, ERROR),
                COMPLETED to emptySet(),
                ABORTED to emptySet(),
                ERROR to emptySet(),
            )
    }
}

class IllegalSessionTransition(
    val from: SessionState,
    val to: SessionState,
) : IllegalStateException("illegal session transition $from -> $to")
