# EXERCISE_VALIDATION.md

Per-exercise support status. An exercise is **not** "supported" until every item in the plan's
§13.3 checklist is satisfied — including item 12, physical test results, which no exercise has.

## Checklist (plan §13.3)

1. Stable id and version · 2. User-facing name and instructions · 3. Preferred camera view ·
4. Required landmarks · 5. Readiness rules · 6. Phase state machine · 7. Rep-completion rule ·
8. Tempo and range metric · 9. Abstention rules · 10. Trace fixtures · 11. Golden expected events ·
12. **Physical test results** · 13. Known limitations · 14. Versioned thresholds

## Status

Legend: ✅ done · 🟡 synthetic only · 🚫 blocked on hardware

| Exercise | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | Supported? |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `standing_dumbbell_curl` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 🟡 | ✅ | 🚫 | ✅ | ✅ | **No** |
| `goblet_squat` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 🟡 | ✅ | 🚫 | ✅ | ✅ | **No** |
| `dumbbell_rdl` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 🟡 | ✅ | 🚫 | ✅ | ✅ | **No** |
| `standing_dumbbell_shoulder_press` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 🟡 | ✅ | 🚫 | ✅ | ✅ | **No** |

Item 10 is 🟡 for every exercise: the only traces that exist are **synthetic**. They prove the
evaluators are deterministic and that thresholds behave as written. They are not recordings of a
person and cannot become item 12. A real recording turns 🟡 into ✅.

## What the synthetic suite does establish

Verified by `./gradlew verify`, for all four exercises:

- Ten clean synthetic repetitions count as ten.
- Zero reps during 60 s of exercise-specific idle movement.
- Half repetitions are rejected, not counted.
- Unusable framing produces abstention — the readiness gate never reaches ready, and no metric is
  emitted.
- Replay is byte-identical across runs, and pinned against a committed golden.
- Each exercise's cues fire on the fault they name and stay silent on a clean repetition.

## What it does not establish

Everything that matters for a real user:

- Whether the thresholds match how a human actually moves. Every number in
  `exercise-definitions/` is an engineering guess.
- Whether MediaPipe tracks these movements reliably on the Flip5 at the distances involved.
- Cue precision against human judgement. Plan §11.2 requires ≥ 90 % or the cue is deleted, and no
  cue has been measured even once.
- Behaviour with real clothing, lighting, body proportions, or a second person in the room.
- Anything about tempo or range-of-motion accuracy in absolute terms.

## Cue inventory

Every cue is a hypothesis until validated. Two per exercise, the cap enforced in code.

| Exercise | Cue | Fires when | Abstains when | Validated? |
| --- | --- | --- | --- | --- |
| curl | `incomplete_range` | rep peaked below 0.90 progress | outside the lowering phase | No |
| curl | `upper_arm_drift` | upper arm > 30° from vertical while lifting | elbow/shoulder confidence < 0.6 | No |
| squat | `incomplete_depth` | rep peaked below 0.90 progress | outside the ascent | No |
| squat | `lateral_asymmetry` | knee angles differ by > 18° | view is not front-diagonal | No |
| RDL | `insufficient_hip_excursion` | rep peaked below 0.88 progress | outside the ascent | No |
| RDL | `excessive_knee_travel` | knees flexed > 25° past the rep's start | no calibration for this rep | No |
| press | `incomplete_overhead_range` | rep peaked below 0.92 progress | outside the lowering phase | No |
| press | `press_timing_asymmetry` | hands differ by > 0.18 body-lengths | outside the press itself | No |

## Next action

Run `protocols/curl-physical-test.md` and `protocols/catalog-physical-test.md` on the Flip5, enter
the results in `TEST_MATRIX.md`, and only then evaluate the §7 gate. Until that happens the
correct description of this catalog is "four evaluators that behave deterministically on
synthetic input", not "four supported exercises".
