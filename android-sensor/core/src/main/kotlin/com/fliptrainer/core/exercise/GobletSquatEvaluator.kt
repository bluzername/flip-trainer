package com.fliptrainer.core.exercise

import com.fliptrainer.core.features.CameraView
import com.fliptrainer.core.features.Geometry
import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.pose.Side
import com.fliptrainer.core.session.Quantize
import com.fliptrainer.core.session.SessionManifest
import kotlin.math.abs

/**
 * Goblet squat.
 *
 * Driving feature: the interior knee angle, averaged across both legs. ~172° standing, ~85° at
 * depth. The descent is the *eccentric* half, so the definition sets `outboundIsConcentric` to
 * false and the phase labels come out as top → eccentric → bottom → concentric.
 *
 * Cues:
 *  - `incomplete_depth` — the repetition in flight peaked short of the configured depth.
 *  - `lateral_asymmetry` — the knees are bending by noticeably different amounts. Only meaningful
 *    when the athlete is at an angle to the camera, so it abstains outside the front-diagonal
 *    view rather than reporting a foreshortening artefact as a movement fault.
 *
 * **Not validated.** Both cues are hypotheses (plan §7).
 */
class GobletSquatEvaluator(
    definition: ExerciseDefinition,
) : BaseExerciseEvaluator(definition) {
    private val minConfidence = definition.readiness.minLandmarkConfidence

    private val sideSelector =
        SideSelector(definition.laterality) { side ->
            setOf(landmark(side, "HIP"), landmark(side, "KNEE"), landmark(side, "ANKLE"))
        }

    override fun reset(manifest: SessionManifest) {
        super.reset(manifest)
        sideSelector.reset()
    }

    override fun primaryFeature(frame: PoseFrame): Double? {
        val sides = sideSelector.sides(frame, minConfidence) ?: return null
        val angles = sides.mapNotNull { kneeAngle(frame, it, minConfidence) }
        if (angles.size != sides.size) return null
        return angles.average()
    }

    override fun observeCues(
        frame: PoseFrame,
        phase: MovementPhase,
        progress: Double,
    ): List<CueObservation> =
        listOf(
            observeRangeShortfall(CUE_INCOMPLETE_DEPTH, phase, judgeDuring = MovementPhase.RETURN),
            observeLateralAsymmetry(frame, phase),
        )

    /**
     * Knee-angle difference between the legs.
     *
     * Gated on the estimated view: square-on, knee flexion is foreshortened by an amount that
     * differs between the legs whenever the athlete is even slightly rotated, and that artefact
     * is indistinguishable from a real asymmetry. Abstaining is the honest answer.
     */
    private fun observeLateralAsymmetry(
        frame: PoseFrame,
        phase: MovementPhase,
    ): CueObservation {
        val spec = definition.cue(CUE_LATERAL_ASYMMETRY) ?: return CueObservation.abstained(CUE_LATERAL_ASYMMETRY)
        if (phase != MovementPhase.OUTBOUND && phase != MovementPhase.APEX) {
            return CueObservation.abstained(spec.id)
        }

        val view = Geometry.estimateView(frame, spec.minConfidence) ?: return CueObservation.abstained(spec.id)
        if (view.view != CameraView.FRONT_DIAGONAL) return CueObservation.abstained(spec.id)

        val left = kneeAngle(frame, Side.LEFT, spec.minConfidence) ?: return CueObservation.abstained(spec.id)
        val right = kneeAngle(frame, Side.RIGHT, spec.minConfidence) ?: return CueObservation.abstained(spec.id)
        val difference = Geometry.symmetry(left, right) ?: return CueObservation.abstained(spec.id)

        return CueObservation(
            id = spec.id,
            violated = difference > spec.threshold,
            evidence =
                mapOf(
                    "differenceDeciDegrees" to Quantize.deciDegrees(difference),
                    "leftDeciDegrees" to Quantize.deciDegrees(left),
                    "rightDeciDegrees" to Quantize.deciDegrees(right),
                ),
        )
    }

    private fun kneeAngle(
        frame: PoseFrame,
        side: Side,
        confidenceFloor: Float,
    ): Double? =
        Geometry.jointAngle(
            frame,
            landmark(side, "HIP"),
            landmark(side, "KNEE"),
            landmark(side, "ANKLE"),
            confidenceFloor,
        )

    companion object {
        const val ID: String = "goblet_squat"
        const val CUE_INCOMPLETE_DEPTH: String = "incomplete_depth"
        const val CUE_LATERAL_ASYMMETRY: String = "lateral_asymmetry"
    }
}

/**
 * Dumbbell Romanian deadlift.
 *
 * Driving feature: torso inclination away from vertical, as a magnitude. ~6° standing, ~72° at the
 * bottom of a hinge. Like the squat, the outbound half is the eccentric one.
 *
 * Cues:
 *  - `insufficient_hip_excursion` — the hinge stopped early.
 *  - `excessive_knee_travel` — the knees bent substantially more than they were bent when the
 *    repetition started. Calibrated per repetition rather than against an absolute angle, because
 *    "soft knees" is a different number for different people.
 *
 * **Not validated.** Both cues are hypotheses (plan §7).
 */
class RomanianDeadliftEvaluator(
    definition: ExerciseDefinition,
) : BaseExerciseEvaluator(definition) {
    private val minConfidence = definition.readiness.minLandmarkConfidence

    private val sideSelector =
        SideSelector(definition.laterality) { side ->
            setOf(landmark(side, "HIP"), landmark(side, "KNEE"), landmark(side, "ANKLE"))
        }

    /** Knee angle captured at the start of the repetition currently in flight. */
    private var calibratedKneeAngle: Double? = null
    private var previousPhase = MovementPhase.IDLE

    override fun reset(manifest: SessionManifest) {
        super.reset(manifest)
        sideSelector.reset()
        calibratedKneeAngle = null
        previousPhase = MovementPhase.IDLE
    }

    override fun primaryFeature(frame: PoseFrame): Double? {
        val inclination = Geometry.torsoInclination(frame, minConfidence) ?: return null
        // Magnitude only: hinging towards or away from the camera's left is the same movement.
        return abs(inclination)
    }

    override fun observeCues(
        frame: PoseFrame,
        phase: MovementPhase,
        progress: Double,
    ): List<CueObservation> {
        calibrate(frame, phase)
        val observations =
            listOf(
                observeRangeShortfall(CUE_INSUFFICIENT_HIP_EXCURSION, phase, judgeDuring = MovementPhase.RETURN),
                observeKneeTravel(frame, phase),
            )
        previousPhase = phase
        return observations
    }

    /** Capture the reference knee angle on the frame the hinge begins. */
    private fun calibrate(
        frame: PoseFrame,
        phase: MovementPhase,
    ) {
        if (phase == MovementPhase.OUTBOUND && previousPhase != MovementPhase.OUTBOUND) {
            calibratedKneeAngle = meanKneeAngle(frame, minConfidence)
        }
        if (phase == MovementPhase.IDLE) calibratedKneeAngle = null
    }

    private fun observeKneeTravel(
        frame: PoseFrame,
        phase: MovementPhase,
    ): CueObservation {
        val spec =
            definition.cue(CUE_EXCESSIVE_KNEE_TRAVEL) ?: return CueObservation.abstained(CUE_EXCESSIVE_KNEE_TRAVEL)
        if (phase != MovementPhase.OUTBOUND && phase != MovementPhase.APEX) {
            return CueObservation.abstained(spec.id)
        }

        val reference = calibratedKneeAngle ?: return CueObservation.abstained(spec.id)
        val current = meanKneeAngle(frame, spec.minConfidence) ?: return CueObservation.abstained(spec.id)

        // Only extra *flexion* counts. Straightening the knees is not the fault this cue names.
        val extraFlexion = reference - current

        return CueObservation(
            id = spec.id,
            violated = extraFlexion > spec.threshold,
            evidence =
                mapOf(
                    "extraFlexionDeciDegrees" to Quantize.deciDegrees(extraFlexion),
                    "referenceDeciDegrees" to Quantize.deciDegrees(reference),
                ),
        )
    }

    private fun meanKneeAngle(
        frame: PoseFrame,
        confidenceFloor: Float,
    ): Double? {
        val sides = sideSelector.sides(frame, confidenceFloor) ?: return null
        val angles =
            sides.mapNotNull { side ->
                Geometry.jointAngle(
                    frame,
                    landmark(side, "HIP"),
                    landmark(side, "KNEE"),
                    landmark(side, "ANKLE"),
                    confidenceFloor,
                )
            }
        if (angles.size != sides.size) return null
        return angles.average()
    }

    companion object {
        const val ID: String = "dumbbell_rdl"
        const val CUE_INSUFFICIENT_HIP_EXCURSION: String = "insufficient_hip_excursion"
        const val CUE_EXCESSIVE_KNEE_TRAVEL: String = "excessive_knee_travel"
    }
}

/**
 * Standing dumbbell shoulder press.
 *
 * Driving feature: wrist height above the shoulder, in body-scale units, averaged across both
 * arms. ~0.2 racked, ~1.15 at lockout. Pressing up is the concentric half.
 *
 * Cues:
 *  - `incomplete_overhead_range` — the press did not finish.
 *  - `press_timing_asymmetry` — one hand is meaningfully higher than the other during the press.
 *
 * **Not validated.** Both cues are hypotheses (plan §7).
 */
class ShoulderPressEvaluator(
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
        val heights = sides.mapNotNull { wristHeight(frame, it, minConfidence) }
        if (heights.size != sides.size) return null
        return heights.average()
    }

    override fun observeCues(
        frame: PoseFrame,
        phase: MovementPhase,
        progress: Double,
    ): List<CueObservation> =
        listOf(
            observeRangeShortfall(CUE_INCOMPLETE_OVERHEAD_RANGE, phase),
            observeTimingAsymmetry(frame, phase),
        )

    private fun observeTimingAsymmetry(
        frame: PoseFrame,
        phase: MovementPhase,
    ): CueObservation {
        val spec =
            definition.cue(CUE_PRESS_TIMING_ASYMMETRY) ?: return CueObservation.abstained(CUE_PRESS_TIMING_ASYMMETRY)
        // Judged while pressing and at the top. Not at the rack, where both hands are level by
        // definition and any difference is landmark noise. The top is included deliberately: if
        // both arms lock out the difference is ~0, and if one did not, that is exactly the
        // asymmetry worth reporting.
        if (phase != MovementPhase.OUTBOUND && phase != MovementPhase.APEX) {
            return CueObservation.abstained(spec.id)
        }

        val left = wristHeight(frame, Side.LEFT, spec.minConfidence) ?: return CueObservation.abstained(spec.id)
        val right = wristHeight(frame, Side.RIGHT, spec.minConfidence) ?: return CueObservation.abstained(spec.id)
        val difference = Geometry.symmetry(left, right) ?: return CueObservation.abstained(spec.id)

        return CueObservation(
            id = spec.id,
            violated = difference > spec.threshold,
            evidence = mapOf("differencePerMille" to Quantize.perMille(difference.coerceIn(0.0, 1.0))),
        )
    }

    /** Wrist height above the shoulder in body-scale units; positive is overhead. */
    private fun wristHeight(
        frame: PoseFrame,
        side: Side,
        confidenceFloor: Float,
    ): Double? =
        Geometry.normalizedVerticalOffset(
            frame,
            landmark(side, "WRIST"),
            landmark(side, "SHOULDER"),
            confidenceFloor,
        )

    companion object {
        const val ID: String = "standing_dumbbell_shoulder_press"
        const val CUE_INCOMPLETE_OVERHEAD_RANGE: String = "incomplete_overhead_range"
        const val CUE_PRESS_TIMING_ASYMMETRY: String = "press_timing_asymmetry"
    }
}
