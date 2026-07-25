package com.fliptrainer.core.trace

import com.fliptrainer.core.BuildIdentity
import com.fliptrainer.core.pose.Landmark
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.PoseFrame
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Recorded landmark traces: the regression fixture for the whole exercise engine (ADR-0004).
 *
 * Format is JSONL — one header object, then one object per frame. Line-oriented so a recording
 * can be streamed to disk during a set and truncated safely if the process dies mid-write, and so
 * a diff on a fixture shows which frames changed rather than "the file changed".
 *
 * **Never contains image data.** A trace is coordinates, confidence and time. That is what makes
 * it safe to commit and safe to attach to a bug report.
 */
object PoseTrace {
    const val SCHEMA: String = "fliptrainer.pose-trace"
    const val VERSION: Int = BuildIdentity.TRACE_SCHEMA_VERSION

    /**
     * Coordinates are rounded to this many decimals on write.
     *
     * At 4 decimals a normalized coordinate resolves to ~0.13 px on a 1280-wide frame — far below
     * landmark noise — while making files ~3× smaller and, more importantly, making them stable:
     * a golden fixture must not churn because a float printed one ULP differently.
     */
    const val COORDINATE_DECIMALS: Int = 4

    internal val json: Json =
        Json {
            encodeDefaults = false
            ignoreUnknownKeys = true // forward compatible: a newer recorder may add fields
            prettyPrint = false
        }

    /** Parse a whole trace. Throws [TraceFormatException] on anything it cannot trust. */
    fun parse(lines: Sequence<String>): LoadedTrace {
        val iterator = lines.filter { it.isNotBlank() }.iterator()
        if (!iterator.hasNext()) throw TraceFormatException("empty trace: no header line")

        val header =
            try {
                json.decodeFromString(TraceHeader.serializer(), iterator.next())
            } catch (e: Exception) {
                throw TraceFormatException("header line is not a valid trace header", e)
            }

        if (header.schema != SCHEMA) {
            throw TraceFormatException("not a Flip Trainer pose trace: schema='${header.schema}'")
        }
        if (header.version != VERSION) {
            throw TraceFormatException(
                "trace schema version ${header.version} cannot be read by this build " +
                    "(expects $VERSION). Re-record the fixture.",
            )
        }

        val frames = ArrayList<PoseFrame>()
        var lineNumber = 1
        var lastTimestamp = Long.MIN_VALUE
        while (iterator.hasNext()) {
            lineNumber++
            val raw = iterator.next()
            val record =
                try {
                    json.decodeFromString(TraceFrameRecord.serializer(), raw)
                } catch (e: Exception) {
                    throw TraceFormatException("line $lineNumber is not a valid trace frame", e)
                }
            if (record.t < lastTimestamp) {
                throw TraceFormatException(
                    "line $lineNumber goes backwards in time (${record.t} < $lastTimestamp); " +
                        "traces must be monotonic",
                )
            }
            lastTimestamp = record.t
            frames += record.toPoseFrame(header)
        }

        return LoadedTrace(header, frames)
    }

    fun parse(text: String): LoadedTrace = parse(text.lineSequence())

    /** Render a trace as JSONL text. Round-trips through [parse]. */
    fun render(
        header: TraceHeader,
        frames: List<PoseFrame>,
    ): String =
        buildString {
            appendLine(json.encodeToString(TraceHeader.serializer(), header))
            for (frame in frames) {
                appendLine(json.encodeToString(TraceFrameRecord.serializer(), TraceFrameRecord.of(frame)))
            }
        }

    internal fun round(value: Float): Float {
        var factor = 1.0
        repeat(COORDINATE_DECIMALS) { factor *= 10 }
        return ((value * factor).roundToInt() / factor).toFloat()
    }
}

class TraceFormatException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** A parsed trace: provenance plus the frames an evaluator will consume. */
data class LoadedTrace(
    val header: TraceHeader,
    val frames: List<PoseFrame>,
) {
    val durationMs: Long
        get() = if (frames.size < 2) 0 else frames.last().monotonicTimestampMs - frames.first().monotonicTimestampMs

    /** Mean frames per second across the trace; 0 for a trace too short to have a rate. */
    val meanFps: Double
        get() = if (durationMs <= 0) 0.0 else (frames.size - 1) * 1000.0 / durationMs
}

/**
 * Provenance. A trace without a model version is not a usable fixture — you cannot tell whether a
 * behavior change came from the evaluator or from the model underneath it.
 */
@Serializable
data class TraceHeader(
    val schema: String = PoseTrace.SCHEMA,
    val version: Int = PoseTrace.VERSION,
    @SerialName("recordedAt") val recordedAtEpochMs: Long = 0,
    val poseModelVersion: String,
    val appVersion: String,
    val deviceId: String,
    val imageWidth: Int,
    val imageHeight: Int,
    /** Null for synthetic traces that are not tied to one exercise. */
    val exerciseId: String? = null,
    val exerciseDefinitionVersion: String? = null,
    val cameraView: String? = null,
    val laterality: String? = null,
    /**
     * Human ground truth, when a person recorded and counted this set. Absent on synthetic
     * traces. This is the only place a rep count may be asserted without the device agreeing.
     */
    val humanRepCount: Int? = null,
    val notes: String? = null,
) {
    val synthetic: Boolean get() = deviceId == SYNTHETIC_DEVICE_ID

    companion object {
        const val SYNTHETIC_DEVICE_ID: String = "synthetic"

        /** Header for a generated trace. Marked so it can never be mistaken for real evidence. */
        fun synthetic(
            imageWidth: Int = 720,
            imageHeight: Int = 1280,
            exerciseId: String? = null,
            notes: String? = null,
        ): TraceHeader =
            TraceHeader(
                poseModelVersion = "synthetic",
                appVersion = "synthetic",
                deviceId = SYNTHETIC_DEVICE_ID,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                exerciseId = exerciseId,
                notes = notes,
            )
    }
}

@Serializable
internal data class TraceFrameRecord(
    val t: Long,
    val d: Int = 0,
    val lm: List<TraceLandmark> = emptyList(),
) {
    fun toPoseFrame(header: TraceHeader): PoseFrame =
        PoseFrame(
            monotonicTimestampMs = t,
            imageWidth = header.imageWidth,
            imageHeight = header.imageHeight,
            inferenceDurationMs = d,
            landmarks =
                lm.mapNotNull { raw ->
                    val type = LandmarkType.fromIndex(raw.i) ?: return@mapNotNull null
                    Landmark(type, raw.x, raw.y, raw.z, raw.v, raw.p)
                },
        )

    companion object {
        fun of(frame: PoseFrame): TraceFrameRecord =
            TraceFrameRecord(
                t = frame.monotonicTimestampMs,
                d = frame.inferenceDurationMs,
                lm =
                    frame.landmarks.map {
                        TraceLandmark(
                            i = it.type.index,
                            x = PoseTrace.round(it.x),
                            y = PoseTrace.round(it.y),
                            z = PoseTrace.round(it.z),
                            v = PoseTrace.round(it.visibility),
                            p = PoseTrace.round(it.presence),
                        )
                    },
            )
    }
}

@Serializable
internal data class TraceLandmark(
    val i: Int,
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val v: Float = 1f,
    val p: Float = 1f,
)
