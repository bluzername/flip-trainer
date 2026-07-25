package com.fliptrainer.tools.traceinspector

import com.fliptrainer.core.exercise.ExerciseCatalog
import com.fliptrainer.core.exercise.TraceReplayRunner
import com.fliptrainer.core.features.CameraView
import com.fliptrainer.core.pose.Laterality
import com.fliptrainer.core.session.SessionManifest
import com.fliptrainer.core.session.WeightUnit
import com.fliptrainer.core.trace.PoseTrace
import com.fliptrainer.core.trace.TraceFormatException
import java.io.File
import kotlin.system.exitProcess

/**
 * Replays a landmark trace through an exercise evaluator, offline.
 *
 * This is how an evaluator change is reviewed without a phone: run the fixtures before and after,
 * diff the event streams, and look at what actually moved.
 */
private val USAGE =
    """
    flip-trainer trace-inspector — replay a landmark trace through an exercise evaluator.

      trace-inspector --trace <file.jsonl> [options]

    Options:
      --trace <file>       Trace to replay. Required unless --list.
      --exercise <id>      Exercise id. Defaults to the trace header's exerciseId.
      --format <fmt>       summary (default) | events | header
      --target-reps <n>    Target reps recorded in the manifest. Default 0.
      --list               List the exercise catalog and exit.
      --help

    Examples:
      trace-inspector --list
      trace-inspector --trace test-fixtures/pose-traces/curl_synthetic_10reps_clean.jsonl
      trace-inspector --trace <file> --format events > new-golden.jsonl
    """.trimIndent()

fun main(args: Array<String>) {
    val options =
        parseArgs(args) ?: run {
            println(USAGE)
            exitProcess(if (args.isEmpty()) 1 else 0)
        }

    if (options.list) {
        printCatalog()
        return
    }

    val traceFile = File(options.trace!!)
    if (!traceFile.isFile) {
        System.err.println("No such trace: ${traceFile.absolutePath}")
        exitProcess(1)
    }

    val trace =
        try {
            PoseTrace.parse(traceFile.readText())
        } catch (e: TraceFormatException) {
            System.err.println("Cannot read trace: ${e.message}")
            exitProcess(1)
        }

    if (options.format == "header") {
        println(trace.header)
        println("frames=${trace.frames.size} durationMs=${trace.durationMs} meanFps=%.1f".format(trace.meanFps))
        return
    }

    val exerciseId = options.exercise ?: trace.header.exerciseId
    if (exerciseId == null) {
        System.err.println("Trace has no exerciseId; pass --exercise")
        exitProcess(1)
    }

    val definition =
        try {
            ExerciseCatalog.load(exerciseId)
        } catch (e: IllegalArgumentException) {
            System.err.println("Unknown exercise '$exerciseId': ${e.message}")
            exitProcess(1)
        }

    // Provenance goes to stderr so it cannot contaminate a golden file redirected from stdout.
    if (trace.header.synthetic) {
        System.err.println("note: SYNTHETIC trace — proves determinism, not accuracy")
    }
    trace.header.humanRepCount?.let { System.err.println("note: human-counted reps = $it") }

    val manifest =
        SessionManifest(
            sessionId = traceFile.nameWithoutExtension,
            exerciseId = definition.id,
            exerciseDefinitionVersion = definition.version,
            targetReps = options.targetReps,
            setNumber = 1,
            dumbbellWeight = 0.0,
            weightUnit = WeightUnit.KG,
            laterality =
                runCatching { Laterality.valueOf(trace.header.laterality ?: "") }
                    .getOrElse { definition.laterality },
            preferredView =
                runCatching { CameraView.valueOf(trace.header.cameraView ?: "") }
                    .getOrElse { definition.preferredView },
            createdAtEpochMs = trace.header.recordedAtEpochMs,
        )

    val result = TraceReplayRunner(definition).run(trace, ExerciseCatalog.evaluatorFor(definition.id), manifest)

    when (options.format) {
        "events" -> print(result.renderEvents())
        else -> {
            print(result.summarize())
            trace.header.humanRepCount?.let { human ->
                println("  ground truth: human=$human device=${result.repCount} delta=${result.repCount - human}")
            }
        }
    }
}

private fun printCatalog() {
    println("Exercise catalog:")
    for (definition in ExerciseCatalog.all()) {
        println("  ${definition.id}  v${definition.version}  ${definition.name}")
        println(
            "      view=${definition.preferredView} laterality=${definition.laterality} " +
                "cues=${definition.cues.joinToString(",") { it.id }.ifEmpty { "none" }}",
        )
        definition.knownLimitations.forEach { println("      ! $it") }
        definition.unsupportedViews.forEach { println("      x $it") }
    }
}

private data class Options(
    val trace: String?,
    val exercise: String?,
    val format: String,
    val targetReps: Int,
    val list: Boolean,
)

private fun parseArgs(args: Array<String>): Options? {
    var trace: String? = null
    var exercise: String? = null
    var format = "summary"
    var targetReps = 0
    var list = false

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--trace" -> trace = args.getOrNull(++i) ?: return null
            "--exercise" -> exercise = args.getOrNull(++i) ?: return null
            "--format" -> format = args.getOrNull(++i) ?: return null
            "--target-reps" -> targetReps = args.getOrNull(++i)?.toIntOrNull() ?: return null
            "--list" -> list = true
            "--help", "-h" -> return null
            else -> {
                System.err.println("Unknown argument: $arg")
                return null
            }
        }
        i++
    }

    if (!list && trace == null) return null
    if (format !in setOf("summary", "events", "header")) {
        System.err.println("Unknown format: $format")
        return null
    }
    return Options(trace, exercise, format, targetReps, list)
}
