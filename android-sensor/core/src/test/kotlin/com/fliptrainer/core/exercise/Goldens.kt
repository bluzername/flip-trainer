package com.fliptrainer.core.exercise

import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.trace.LoadedTrace
import com.fliptrainer.core.trace.PoseTrace
import com.fliptrainer.core.trace.TraceHeader
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File

/**
 * Golden-file support.
 *
 * A golden is not a convenience here — it is the regression suite for behaviour that is otherwise
 * only observable by watching a person lift a weight. When a threshold changes, the diff on these
 * files *is* the review.
 *
 * Regenerate deliberately, never reflexively:
 * ```
 * ./gradlew :android-sensor:core:test -Dfliptrainer.updateGoldens=true
 * ```
 * and read the resulting diff before committing it.
 */
object Goldens {
    private val root: File by lazy {
        val path =
            requireNotNull(System.getProperty("fliptrainer.fixtures")) {
                "fliptrainer.fixtures system property not set; see android-sensor/core/build.gradle.kts"
            }
        File(path)
    }

    private val updating: Boolean
        get() = System.getProperty("fliptrainer.updateGoldens") == "true"

    fun traceFile(name: String): File = File(root, "pose-traces/$name")

    fun eventsFile(name: String): File = File(root, "expected-events/$name")

    /**
     * Write [frames] as a trace fixture if it does not already exist, then load it back.
     *
     * Loading back rather than using the in-memory frames is the point: it exercises the same
     * parse path a recorded trace takes, so a serialization bug cannot hide behind a test that
     * only ever used live objects.
     */
    fun materializeTrace(
        name: String,
        header: TraceHeader,
        frames: List<PoseFrame>,
    ): LoadedTrace {
        val file = traceFile(name)
        if (!file.exists() || updating) {
            file.parentFile.mkdirs()
            file.writeText(PoseTrace.render(header, frames))
        }
        return PoseTrace.parse(file.readText())
    }

    /** Compare rendered events against the stored golden, or write it on first run. */
    fun assertMatches(
        name: String,
        actual: String,
        context: () -> String = { "" },
    ) {
        val file = eventsFile(name)
        if (!file.exists() || updating) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            if (!updating) {
                println("Created golden $name (${actual.lines().size - 1} events). Review it before committing.")
            }
            return
        }
        val expected = file.readText()
        assertEquals(
            expected,
            actual,
            "Golden mismatch for $name.\n" +
                "If this change is intended, regenerate with -Dfliptrainer.updateGoldens=true and " +
                "review the diff.\n${context()}",
        )
    }
}
