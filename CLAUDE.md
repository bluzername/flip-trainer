# Flip Trainer — project instructions

A two-device, local-only workout system: a **Galaxy Z Flip5** as an edge-vision appliance that
counts repetitions, and an **iPhone** controller, talking over BLE. No internet, no accounts, no
cloud. The Flip5's main-screen digitizer is dead, which drives the whole touch-free design.

## Read these first, in order

1. `AGENTS.md` — the operating protocol and the fixed scope decisions. Non-negotiable.
2. `docs/STATUS.md` — what is verified, what is written but never compiled, what is not true yet.
3. `docs/DECISIONS.md` — eight ADRs, each with a revisit trigger. Do not reopen a settled decision
   without hitting its trigger.
4. `docs/DEVICE_NOTES.md` and `docs/TEST_MATRIX.md` — hardware reality. Both are mostly
   `NOT MEASURED`, and that is the accurate state, not an oversight.

Full plan: `docs/IMPLEMENTATION_PLAN.md`. Continuing on real hardware: `docs/LOCAL_SETUP.md`.

## The one structural rule

> Anything that decides something lives in a pure JVM module and is unit-tested there.

`:android-sensor:core` and `:protocol` must never depend on the Android SDK.
`:android-sensor:app` is a thin shell — CameraX, MediaPipe, Room, BLE GATT, Compose — and is
excluded from the build unless an SDK is configured. See ADR-0001.

This is what makes `./gradlew verify` meaningful without an SDK, and what makes evaluator
behaviour reviewable offline through trace replay. If a change to the Android shell seems to
require a new Android type in `core`, the abstraction boundary is wrong — that is the signal, not
an exception to the rule.

## Commands

```bash
bash scripts/doctor.sh    # what is installed, what is missing, what to run next
./gradlew verify          # compile + ktlint + all JVM tests (274)
./gradlew ktlintFormat    # run before committing; the checks are strict
```

Needs an Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`):

```bash
./gradlew :android-sensor:app:assembleDebug
./gradlew :android-sensor:app:installDebug
```

Replay a trace through an evaluator with no phone attached:

```bash
./gradlew :tools:trace-inspector:installDist
./tools/trace-inspector/build/install/trace-inspector/bin/trace-inspector --list
./tools/trace-inspector/build/install/trace-inspector/bin/trace-inspector \
  --trace test-fixtures/pose-traces/curl_synthetic_10reps_clean.jsonl
```

## Honesty rules, specific to this project

These matter more here than code style does, because the failure mode is silent:

- **Never claim physical-device validation that did not happen.** No frame rate, latency, thermal,
  battery or rep-accuracy number goes into a document unless it came off hardware. `NOT MEASURED`
  is the correct value until then.
- **Synthetic traces prove determinism, not accuracy.** They can never close a human-validation
  gate. Only a person who watched the set can add a row to `TEST_MATRIX.md`.
- **Golden files are the review.** When a threshold changes, read the event diff before
  regenerating. A golden regenerated without reading the diff converts a caught regression into a
  recorded one.
- **Cues are deleted, not loosened.** A cue that cannot reach ≥ 90 % precision gets removed — one
  that fires wrongly teaches the athlete to ignore the system.
- Cloud is Session 15 and is **not authorized**. Nothing here may contact a network.

## Repository history

Extracted from `bluzername/bee-api-experiments`, where this was developed as a subdirectory
because that session was scoped to a single repository. All 11 original commits are preserved.
The two projects share no code.
