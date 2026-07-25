# TEST_MATRIX.md

Physical validation results. **Human-recorded ground truth only** (plan §11.3). The coding agent
may compute metrics from these rows; it may not create rows.

Synthetic and recorded-trace replay results are *not* recorded here — they live in the JUnit
suites and in `EXERCISE_VALIDATION.md`. This file is exclusively about a person doing reps in
front of the Flip5.

## Columns

| Column | Meaning |
| --- | --- |
| Date | ISO date of the session |
| Exercise | Exercise id |
| Def ver | `exerciseDefinitionVersion` under test |
| Person | Anonymous stable label (P1, P2, …) |
| View | front / front-diagonal / side |
| Distance | metres, camera to athlete |
| Lighting | bright / normal / dim / backlit |
| Clothing | fitted / loose / long-sleeve |
| Tempo | slow / normal / fast / paused |
| Reps | full / partial / mixed |
| Side | left / right / both |
| Expected | human-counted reps |
| Observed | device-counted reps |
| False cues | cues raised that the human judges wrong |
| Missed cues | cues the human expected that never fired |
| Readiness fails | times the gate refused to reach ready |
| Notes | anything else |

## Results

_No rows. No physical validation has been performed — no Flip5 has been attached to this
implementation environment. See `DEVICE_NOTES.md`._

| Date | Exercise | Def ver | Person | View | Distance | Lighting | Clothing | Tempo | Reps | Side | Expected | Observed | False cues | Missed cues | Readiness fails | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

## Engineering gates being measured toward

From plan §11.2. These are development gates, not public accuracy claims.

- ≥ 90 % of controlled clean sets have an exact rep count, per exercise **and per view**.
- Zero phantom reps during 60 s of exercise-specific idle movement.
- Live rep feedback within 300 ms of confirmed rep completion.
- Cue precision ≥ 90 % on held-out examples, **or the cue is deleted**.
- Abstention whenever required landmarks are unreliable.
- A 60-minute workout stays operational.

Per-exercise results are kept separate on purpose. A single aggregate accuracy number would hide a
weak exercise, which is exactly the failure this table exists to prevent.
