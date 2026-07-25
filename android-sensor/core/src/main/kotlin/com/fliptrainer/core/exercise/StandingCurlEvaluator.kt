package com.fliptrainer.core.exercise

import com.fliptrainer.core.features.Geometry
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.pose.Side
import com.fliptrainer.core.session.Quantize
import com.fliptrainer.core.session.SessionManifest
import kotlin.math.abs

/**
 * Standing dumbbell curl.
 *
 * Driving feature: the interior elbow angle, averaged across the measured sides. ~172° with the
 * arms hanging, ~45° at the top of a full curl. The angle *decreases* as the repetition
 * progresses, which [RangeSpec] handles directly rather than requiring a negation here.
 *
 * Phases map onto the neutral cycle as: START = bottom, OUTBOUND = concentric (curling up),
 * APEX = top, RETURN = eccentric (lowering).
 *
 * Two cues, both conservative and confidence-gated (plan §7.4):
 *  - `incomplete_range` — the repetition in flight peaked below the definition's threshold.
 *  - `upper_arm_drift` — the upper arm swung away from vertical while lifting.
 *
 * **Neither cue has been validated against a human judgement.** Both are hypotheses per plan §7,
 * and either is to be deleted rather than tuned if validation cannot make it precise.
 */
class StandingCurlEvaluator(
    definition: ExerciseDefinition,
) : BaseExerciseEvaluator(definition) {
    private val minConfidence = definition.readiness.minLandmarkConfidence

    private val sideSelector =
        SideSelector(definition.laterality) { side ->
            setOf(landmark(side, "SHOULDER"), landmark(side, "ELBOW"), landmark(side, "WRIST"))
        }

    override fun reset(manifest: SessionManifest) {
        super.reset(manifest)
        sideSelector.reset()
    }

    override fun primaryFeature(frame: PoseFrame): Double? {
        val sides = sideSelector.sides(frame, minConfidence) ?: return null
        val angles = sides.mapNotNull { elbowAngle(frame, it) }
        // A bilateral curl measured from one arm is not a bilateral measurement. Abstain instead.
        if (angles.size != sides.size) return null
        return angles.average()
    }

    override fun observeCues(
        frame: PoseFrame,
        phase: MovementPhase,
        progress: Double,
    ): List<CueObservation> = listOf(observeIncompleteRange(phase), observeUpperArmDrift(frame, phase))

    /**
     * Judged on the way down, once the repetition's peak is known but before it is over, so the
     * athlete hears it in time to change the next one.
     */
    private fun observeIncompleteRange(phase: MovementPhase): CueObservation {
        val spec = definition.cue(CUE_INCOMPLETE_RANGE) ?: return CueObservation.abstained(CUE_INCOMPLETE_RANGE)
        if (phase != MovementPhase.RETURN) return CueObservation.abstained(spec.id)

        val peak = currentPeakProgress
        return CueObservation(
            id = spec.id,
            violated = peak < spec.threshold,
            evidence = mapOf("peakPerMille" to Quantize.perMille(peak)),
        )
    }

    /**
     * Deviation of the upper arm from vertical while lifting.
     *
     * Measured in the image plane only — drift straight towards the camera is invisible from the
     * front, and the definition says so in `knownLimitations`. The cue reports what it can see and
     * abstains otherwise; it does not extrapolate.
     */
    private fun observeUpperArmDrift(
        frame: PoseFrame,
        phase: MovementPhase,
    ): CueObservation {
        val spec = definition.cue(CUE_UPPER_ARM_DRIFT) ?: return CueObservation.abstained(CUE_UPPER_ARM_DRIFT)
        if (phase != MovementPhase.OUTBOUND && phase != MovementPhase.APEX) {
            return CueObservation.abstained(spec.id)
        }

        val sides = sideSelector.sides(frame, spec.minConfidence) ?: return CueObservation.abstained(spec.id)
        val deviations =
            sides.mapNotNull { side ->
                upperArmDeviation(frame, side, spec.minConfidence)?.let { side to it }
            }
        if (deviations.size != sides.size) return CueObservation.abstained(spec.id)

        val worst = deviations.maxBy { it.second }
        return CueObservation(
            id = spec.id,
            violated = worst.second > spec.threshold,
            evidence =
                buildMap {
                    put("worstDeciDegrees", Quantize.deciDegrees(worst.second))
                    put("side", if (worst.first == Side.LEFT) 0 else 1)
                },
        )
    }

    private fun elbowAngle(
        frame: PoseFrame,
        side: Side,
    ): Double? =
        Geometry.jointAngle(
            frame,
            landmark(side, "SHOULDER"),
            landmark(side, "ELBOW"),
            landmark(side, "WRIST"),
            minConfidence,
        )

    /**
     * Angle between the upper arm and straight down, in degrees. 0 means the elbow hangs directly
     * beneath the shoulder.
     */
    private fun upperArmDeviation(
        frame: PoseFrame,
        side: Side,
        confidenceFloor: Float,
    ): Double? {
        val shoulder = Geometry.point(frame, landmark(side, "SHOULDER"), confidenceFloor) ?: return null
        val elbow = Geometry.point(frame, landmark(side, "ELBOW"), confidenceFloor) ?: return null
        val fromVertical = Geometry.signedAngleFromVertical(shoulder, elbow) ?: return null
        return abs(180.0 - abs(fromVertical))
    }

    companion object {
        const val ID: String = "standing_dumbbell_curl"
        const val CUE_INCOMPLETE_RANGE: String = "incomplete_range"
        const val CUE_UPPER_ARM_DRIFT: String = "upper_arm_drift"

        /** Landmarks the evaluator reads. Kept next to the code that reads them. */
        val REQUIRED_LANDMARKS: Set<LandmarkType> =
            setOf(
                LandmarkType.LEFT_SHOULDER,
                LandmarkType.RIGHT_SHOULDER,
                LandmarkType.LEFT_ELBOW,
                LandmarkType.RIGHT_ELBOW,
                LandmarkType.LEFT_WRIST,
                LandmarkType.RIGHT_WRIST,
            )
    }
}
