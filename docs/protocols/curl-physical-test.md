# Physical test protocol — standing dumbbell curl

**Status: NOT YET RUN.** No Flip5 has been attached to this implementation. Session 6's
human-validation gate is open until a person completes this protocol and the results are entered
in `../TEST_MATRIX.md`.

The agent cannot close this gate. Synthetic traces prove the evaluator is deterministic and that
its thresholds behave as written; they say nothing about whether those thresholds are right for a
human being. Ground truth comes from a person who watched the set (plan §11.3).

## Before you start

- [ ] Flip5 charged, on a tripod, cable reaching a socket.
- [ ] `adb devices` shows the phone; `scrcpy` gives mouse control of the main screen.
- [ ] App installed: `./gradlew :android-sensor:app:installDebug`.
- [ ] Trace recording enabled in the debug screen (off by default).
- [ ] Definition version noted: currently `standing_dumbbell_curl v1.0.0`.
- [ ] A second person, or a phone propped up recording *you* for the count. **Someone counts out
      loud and writes the number down before looking at the device output.** Reading the device
      first and then "confirming" it is not ground truth, it is anchoring.

## Setup

| Parameter | Default for this protocol |
| --- | --- |
| Camera | rear wide, 1280×720 |
| View | front (athlete square to the camera) |
| Distance | ~2.5 m, adjusted until the readiness gate says Ready |
| Height | camera at roughly hip height |
| Lighting | normal indoor, no strong backlight |
| Clothing | fitted, short sleeves for the baseline run |
| Weight | anything comfortable; the number is entered by hand and does not affect counting |

## Runs

Do each run as a separate set. Record every one in `TEST_MATRIX.md`, **including the ones that go
badly** — especially those.

| # | Run | Reps | What it tests | Expected |
| --- | --- | --- | --- | --- |
| 1 | Normal tempo | 10 | Baseline count | exactly 10 |
| 2 | Normal tempo, repeat | 10 | Run-to-run stability | exactly 10 |
| 3 | Slow (≈4 s per rep) | 8 | No cycle abandoned for being slow | exactly 8 |
| 4 | Fast (≈1 s per rep) | 12 | No missed reps at speed | exactly 12 |
| 5 | Half reps, stopping ~halfway | 10 | Partial rejection | **0** counted, 10 rejected |
| 6 | 3 s pause at the top each rep | 6 | Long apex hold survives | exactly 6 |
| 7 | Alternating arms | 10 | Documented limitation | expected to fail; record what it does |
| 8 | Idle: stand still, arms down | 0 | Phantom reps | **0**, for a full 60 s |
| 9 | Idle: scratch, adjust grip, check phone | 0 | Phantom reps from fidgeting | **0**, for a full 60 s |
| 10 | Deliberate upper-arm swing | 10 | `upper_arm_drift` cue precision | 10 counted, cue raised |
| 11 | Strict form, elbows pinned | 10 | `upper_arm_drift` false-positive rate | 10 counted, cue **not** raised |
| 12 | Deliberately shallow but past the top threshold | 8 | `incomplete_range` cue | 8 counted, cue raised |

Then repeat runs 1, 5, 8 under each of:

- Loose long-sleeved clothing
- Dim lighting
- 1.5 m and 4 m distance
- 30° off-axis (athlete turned)

## For every run, record

1. Actual exercise performed.
2. **Human-counted reps**, written down before looking at the device.
3. Whether reps were full, partial, or mixed.
4. Any intended movement error.
5. Camera view and distance.
6. Any tracking failure you saw on the debug overlay.
7. Device-reported count, ROM, tempo, and cues.
8. Trace filename, pulled with:
   ```bash
   adb pull /sdcard/Android/data/com.fliptrainer.sensor/files/traces/<file> \
     test-fixtures/pose-traces/
   ```
   Then add `humanRepCount` and `notes` to the trace header. A trace without human ground truth
   is an input, not evidence.

## Pass criteria (plan §6)

- No phantom reps during 60 s of standing idle — runs 8 and 9.
- Partial curls are not counted — run 5.
- **At least 9 of 10 controlled clean sets have the exact expected count** in the supported setup.
- Cues abstain when shoulder, elbow, or wrist confidence is inadequate.
- Every failure is added to `TEST_MATRIX.md`.

## If a run fails

Do not tune a threshold to make one run pass. That is how a system becomes fitted to a single
person in a single room.

1. Pull the trace and replay it offline:
   ```bash
   ./gradlew :tools:trace-inspector:installDist
   ./tools/trace-inspector/build/install/trace-inspector/bin/trace-inspector \
     --trace test-fixtures/pose-traces/<file>
   ```
2. Work out whether the fault is in framing, in the pose model, in the filter, or in a threshold.
3. If it is a threshold: change it in `exercise-definitions/`, **bump the version**, regenerate
   goldens, read the diff, and re-run the whole protocol — not just the failing run.
4. If a cue cannot be made precise, delete it (plan §7.4). A cue at 70 % precision is worse than
   no cue: it teaches the athlete to ignore the system.
