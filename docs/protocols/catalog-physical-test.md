# Physical test protocol — goblet squat, RDL, shoulder press

**Status: NOT YET RUN** for all three. Session 7's human-validation gate is open.

Read [`curl-physical-test.md`](curl-physical-test.md) first — the preconditions, the
ground-truth rule (a human counts out loud and writes the number down *before* looking at the
device), and the "if a run fails" procedure all apply unchanged. This file adds only what differs
per exercise.

Plan §7 is explicit that BLE work does not start because skeletons and state machines look
plausible. It starts after these three tables are filled in.

---

## Goblet squat — `goblet_squat v1.0.0`

**View: front-diagonal.** Stand at roughly 30–45° to the camera. Square-on, knee flexion is
foreshortened and depth reads shallower than it is; side-on, the far leg is occluded. Both are
listed as unsupported in the definition — confirm the gate actually refuses them.

| # | Run | Reps | Expected |
| --- | --- | --- | --- |
| 1–2 | Normal tempo, full depth | 10 | exactly 10 |
| 3 | Slow, 4 s per rep | 8 | exactly 8 |
| 4 | Quarter squats | 10 | **0** counted |
| 5 | Just above the depth threshold | 8 | 8 counted, `incomplete_depth` raised |
| 6 | Full depth | 10 | 10 counted, `incomplete_depth` **silent** |
| 7 | Deliberately favour one leg | 10 | `lateral_asymmetry` raised |
| 8 | Even, symmetrical squats | 10 | `lateral_asymmetry` **silent** ← the precision test |
| 9 | Idle: stand, shift weight, look around, 60 s | 0 | **0** |
| 10 | Square-on to the camera | 10 | gate should refuse, or results marked unsupported |
| 11 | Side-on to the camera | 10 | gate should refuse |

Specifically watch for: the whole body staying in frame at depth (a squat moves the hips back and
the shoulders forward, and the athlete can leave the frame at the bottom while looking fine
standing up).

---

## Dumbbell RDL — `dumbbell_rdl v1.0.0`

**View: side.** This is the only side-view exercise in the catalog, and the one where the
far-side landmarks are estimated rather than observed. Its confidence floor is deliberately lower
(0.4) than the front-view exercises. Watch closely for reps counted off implausible landmarks.

| # | Run | Reps | Expected |
| --- | --- | --- | --- |
| 1–2 | Normal hinge to mid-shin | 10 | exactly 10 |
| 3 | Slow, 4 s per rep | 8 | exactly 8 |
| 4 | Quarter hinge | 10 | **0** counted |
| 5 | Hinge stopping just short | 8 | 8 counted, `insufficient_hip_excursion` raised |
| 6 | Full hinge | 10 | 10 counted, cue **silent** |
| 7 | Deliberately squat the weight down | 10 | `excessive_knee_travel` raised |
| 8 | Strict hinge, soft knees held | 10 | `excessive_knee_travel` **silent** ← precision test |
| 9 | Start the set with visibly bent knees | 10 | cue silent — it calibrates per rep, confirm it does |
| 10 | Idle: stand holding the weights, 60 s | 0 | **0** |
| 11 | Facing the camera | 10 | gate should refuse |

Also record which side faced the camera. If left-facing and right-facing behave differently,
that is a finding, not noise.

---

## Standing dumbbell shoulder press — `standing_dumbbell_shoulder_press v1.0.0`

**View: front.** **This exercise is expected to be the hardest to frame.** At the mid-point of
the press the hands are roughly 2.4 torso-lengths apart, so on a portrait frame the athlete has
to stand well back — far enough that the torso is near the readiness floor. Measuring that
trade-off is a primary goal of this run, not a side effect.

| # | Run | Reps | Expected |
| --- | --- | --- | --- |
| 0 | **Framing survey** | — | Find the closest distance where no wrist clips at the mid-press. Record it, and record the torso span at that distance. |
| 1–2 | Normal tempo, full lockout | 10 | exactly 10 |
| 3 | Slow, 4 s per rep | 8 | exactly 8 |
| 4 | Half presses | 10 | **0** counted |
| 5 | Stopping just short of lockout | 8 | 8 counted, `incomplete_overhead_range` raised |
| 6 | Full lockout | 10 | 10 counted, cue **silent** |
| 7 | One arm deliberately trailing | 10 | `press_timing_asymmetry` raised |
| 8 | Even press | 10 | `press_timing_asymmetry` **silent** ← precision test |
| 9 | Alternating press | 10 | expected to trip the asymmetry cue; documented limitation, confirm |
| 10 | Idle: stand with weights racked, 60 s | 0 | **0** |
| 11 | Landscape orientation | 10 | Does the wider frame fix the framing problem? Record it. |

### Decision this run feeds

If run 0 shows there is **no** practical distance where the press fits a portrait frame and the
torso clears the readiness floor, that is the documented failure the ultrawide revisit trigger
(plan §13) requires. Write it up as an ADR with the measured numbers before changing anything.
Landscape orientation (run 11) is the cheaper fix and must be ruled out first.

---

## Pass criteria for all three (plan §7)

- Each exercise passes deterministic trace replay — already automated, `./gradlew verify`.
- Each exercise: **no phantom reps during 60 s of exercise-specific idle movement.**
- Each exercise: **exact expected count in at least 9 of 10 controlled clean sets** in its
  supported setup.
- Bad framing produces abstention, not fabricated metrics.
- All four exercises selectable in the Android debug UI.

Every cue above has a paired "must stay silent" run. That pairing is the point: a cue is only
worth shipping if it is quiet when the movement is fine (plan §7.4, precision over recall). A cue
that fails its silent run gets deleted, not retuned.
