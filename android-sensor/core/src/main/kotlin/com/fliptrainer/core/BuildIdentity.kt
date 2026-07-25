package com.fliptrainer.core

/**
 * Version stamps that must accompany every recorded session and every export.
 *
 * Plan §5.3: a session is only interpretable later if you know which app, protocol,
 * pose model and exercise definitions produced it.
 */
public data class BuildIdentity(
    val appVersion: String,
    val protocolVersion: Int,
    val poseModelVersion: String,
    val exerciseDefinitionVersion: String,
    val deviceId: String,
) {
    public companion object {
        /** Wire/protocol revision implemented by this source tree. */
        public const val PROTOCOL_VERSION: Int = 1

        /** Bumped whenever the exercise-definition JSON schema changes shape. */
        public const val EXERCISE_DEFINITION_SCHEMA_VERSION: Int = 1

        /** Bumped whenever the recorded landmark-trace format changes shape. */
        public const val TRACE_SCHEMA_VERSION: Int = 1
    }
}
