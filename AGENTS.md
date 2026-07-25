# AGENTS.md — Flip Trainer operating protocol

Authoritative plan: `docs/IMPLEMENTATION_PLAN.md`. This file is the short form that must be
obeyed on every session. Where the two disagree, the implementation plan wins.

## Fixed scope decisions

These stay in force until the user explicitly changes them.

**Camera.** One rear camera, standard wide. No simultaneous dual-camera capture. No stereo
reconstruction. Manual ultrawide only if an athlete cannot fit in frame, and only per §12 Step 4.

**Connectivity.** BLE is the only Galaxy↔iPhone transport. Pairing bootstraps from a short-lived,
single-use QR code shown on the Galaxy. One paired controller. Both devices persist their own
state. No login, backend, Firebase, Supabase, remote config, or cloud sync. Raw video is never
uploaded. Cloud is Session 15 and requires explicit user approval.

**Exercise behavior.** The user selects the exercise. One athlete in frame. Weight is typed in by
hand. No automatic exercise recognition. No visual weight recognition. No medical or safety
classification. Cues are conservative and confidence-gated. **If the required landmarks are not
reliable, abstain — never guess.**

**Android device management.** No root. No Device Owner provisioning initially. Ordinary app,
user-started camera foreground service, manual arming. Device Owner is reconsidered only in
Session 14 and needs explicit user approval.

**Implementation.** Native only: Kotlin on Android, Swift/SwiftUI on iOS. No Flutter, no React
Native. One pose engine (MediaPipe) behind an abstraction boundary — a second engine requires a
documented failure. Four representative exercises before any general exercise language.

## Layering

Advance by proving one narrow layer at a time. Do not skip a validation gate.

```
Camera → Pose → Readiness → Deterministic features → One reliable exercise →
Four reliable exercises → Durable Galaxy sessions → BLE reliability → iPhone experience →
Local history → Placement usability → Exercise expansion → Hardening → Optional cloud
```

## Where code goes

The single most important structural rule in this repository:

> **Anything that decides something lives in a pure JVM module and is unit-tested there.**

Geometry, readiness, phase machines, rep counting, session lifecycle, event sequencing, protocol
encoding, pairing crypto — all of it belongs in `:android-sensor:core` or `:protocol`, neither of
which may depend on the Android SDK. `:android-sensor:app` is a thin shell: CameraX, MediaPipe,
Room, the BLE GATT server, Compose. Business logic must not live in an activity, a view, a camera
callback, or a BLE callback.

This is what makes the evaluators reviewable offline via trace replay, and it is why
`./gradlew verify` is meaningful even on a machine with no Android SDK. See ADR-0001.

## At the start of every session

1. Read `docs/IMPLEMENTATION_PLAN.md` in full.
2. Read `docs/STATUS.md`, `docs/DECISIONS.md`, `docs/DEVICE_NOTES.md`, `docs/TEST_MATRIX.md`.
3. Inspect repository status; preserve unrelated user changes.
4. Run the smallest relevant test suite.
5. Pick the earliest incomplete session whose prerequisites are satisfied.
6. State the exact objective before editing anything.

## During a session

- Work only on the current session's deliverables. Do not build later-phase infrastructure
  because it would be convenient.
- Add tests alongside the production change.
- Record **measured** performance. "Looks fast" is not a measurement.
- **Never claim physical-device validation that was not performed on the actual Flip5.** When
  human movement or device interaction is required, write a concise test script and stop for the
  result.

## At the end of every session

1. Run `./gradlew verify`.
2. Update `docs/STATUS.md`.
3. Update `docs/DECISIONS.md` if a decision changed.
4. Update `docs/DEVICE_NOTES.md` with real hardware observations.
5. Update `docs/TEST_MATRIX.md` for physical exercise tests.
6. Summarize: what changed, what was verified, what is still uncertain, next exact step.
7. One logical commit.

## Session gate

Do not start the next session while the current acceptance criteria are failing. Diagnose,
document, repair.

Where an acceptance criterion cannot be evaluated in the current environment (no Flip5, no
iPhone, no Android SDK), mark it **BLOCKED** in `STATUS.md` with the exact command or human
protocol needed to close it. Do not silently downgrade it to "done", and do not invent a result.

## Ground truth

The agent may build tools, collect logs, and compute metrics. It may **not** invent ground truth.
Every physical validation row in `TEST_MATRIX.md` comes from a human who watched the set.
