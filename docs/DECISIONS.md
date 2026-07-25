# DECISIONS.md

Architecture decision records. One entry per decision, newest last.

Do not reopen a settled decision without hitting its documented revisit trigger.

---

## ADR-0001 — All decision logic lives in pure JVM modules

**Date** 2026-07-25

**Decision**
`:android-sensor:core` and `:protocol` are plain Kotlin/JVM Gradle modules with no dependency on
the Android SDK. They hold every piece of code that decides something: coordinate normalization,
geometric features, temporal filters, the readiness gate, exercise phase machines, rep counting,
session lifecycle, event sequencing, protocol encoding, pairing crypto, and the ack/replay engine.

`:android-sensor:app` is a thin platform shell — CameraX, MediaPipe, Room, the BLE GATT server,
Compose — and is **excluded from the build entirely** unless an Android SDK is configured.

**Evidence**
The plan requires deterministic trace replay (§5, §7.1), byte-equivalent repeated output (§5
acceptance), and golden expected-event tests (§6, §7). None of that is achievable if the logic is
entangled with `ImageProxy`, `Context`, or `BluetoothGattServer`. Separately, the development
environment used for the first sessions has a JDK but no Android SDK, no emulator and no device;
a design that requires an SDK to run a rep-counting unit test would have blocked all verification.

**Alternatives considered**
- *One Android library module for everything.* Rejected: unit tests need Robolectric or an
  instrumented device, replay tests get slow and flaky, and CI needs an SDK.
- *Kotlin Multiplatform core.* Rejected as premature. It buys sharing with iOS that the plan does
  not ask for (iOS receives decisions over BLE; it does not re-run them), and it costs build
  complexity now. Revisit only if iOS ever needs to evaluate an exercise locally.

**Consequences**
- `./gradlew verify` is meaningful with no Android SDK present.
- Evaluator behavior is reviewable offline through `tools/trace-inspector`.
- The Android shell cannot be compiled in an SDK-less environment, so its correctness rests on
  review plus on-device testing. That is an accepted, documented gap — tracked in `STATUS.md`.
- Core must never gain an Android dependency. If one seems necessary, the abstraction is wrong.

**Revisit trigger**
iOS needs to evaluate exercises locally, or an unavoidable core dependency on Android APIs appears.

---

## ADR-0002 — Bytecode target 17, Kotlin 2.1.21, pinned version catalog

**Date** 2026-07-25

**Decision**
All JVM modules compile to Java 17 bytecode with Kotlin 2.1.21. Every dependency version is
pinned in `gradle/libs.versions.toml`. `allWarningsAsErrors` is on. ktlint 1.5.0 runs as part of
`check`.

**Evidence**
Java 17 is what current AGP consumes without a second compilation path, so the same `core` jar is
usable by the Android module unchanged. Pinning is required by the plan (§5.1).

**Alternatives considered**
Java 11 target (unnecessarily old); unpinned/dynamic versions (rejected — non-reproducible builds
make an "it worked yesterday" regression untraceable).

**Consequences** Bumps are deliberate and reviewable. Warnings cannot accumulate.

**Revisit trigger** AGP requires a newer bytecode target, or a needed library drops Java 17.

---

## ADR-0003 — MediaPipe Pose Landmarker is the only pose engine

**Date** 2026-07-25

**Decision**
Implement `PoseEstimator` once, backed by MediaPipe Pose Landmarker in live-stream mode. Do not
add an ML Kit adapter as speculative insurance.

**Evidence** Plan §5.4 and the §3 decision gate.

**Alternatives considered** ML Kit Pose Detection; a bundled TFLite MoveNet. Both deferred.

**Consequences** One integration path to debug and tune. A swap costs one class.

**Revisit trigger** MediaPipe misses a specific, written latency, accuracy, lifecycle, platform,
or licensing requirement — recorded here with the measurement that showed it.

---

## ADR-0004 — Landmark traces, never video, are the regression fixture

**Date** 2026-07-25

**Decision**
Physical validation records versioned JSONL landmark traces (coordinates, visibility, presence,
timestamps) under `test-fixtures/pose-traces/`, paired with golden event files under
`test-fixtures/expected-events/`. Raw video is never a fixture and never leaves the device.

**Evidence**
Plan §3 task 9 and §6.5 privacy posture. Traces make evaluator changes reviewable offline and
make regressions reproducible; video would be both a privacy liability and unusable in CI.

**Alternatives considered**
Storing short video clips for re-inference. Rejected: privacy, size, and it couples fixtures to a
specific model version. A trace is what the evaluator actually consumes.

**Consequences**
Traces are bound to the pose-model version that produced them; the trace header records it. A
model upgrade invalidates goldens and requires re-recording, which is the correct signal.

**Revisit trigger** A model upgrade requires mass re-recording and the cost is judged too high.

---

## ADR-0005 — Fixed-point event timestamps and integer-quantized features

**Date** 2026-07-25

**Decision**
Workout events carry integer millisecond timestamps derived from a monotonic clock. Metrics that
appear in golden files (range-of-motion score, durations, confidence) are quantized to fixed
precision before being emitted.

**Evidence**
Session 5 requires that replaying a trace produces *byte-equivalent* ordered events. Raw
floating-point metrics serialize differently across JVMs and optimization levels, which would make
golden files flaky for reasons unrelated to evaluator behavior.

**Alternatives considered**
Tolerance-based comparison in tests. Rejected as the primary mechanism: it hides small genuine
regressions and makes "byte-equivalent" unverifiable. Tolerance remains available for
metric-specific assertions.

**Consequences** Goldens are stable and diffable. Precision limits are explicit and documented.

**Revisit trigger** A metric needs more precision than its quantization allows.

---

## ADR-0006 — The Xcode project is generated from `project.yml`, not committed

**Date** 2026-07-25

**Decision**
`ios-app/project.yml` (XcodeGen) is the source of truth for the iOS target. The generated
`FlipTrainer.xcodeproj` is gitignored.

**Evidence**
A `.pbxproj` is effectively unreviewable in a diff and merges badly. Target settings that matter
— deployment target, strict concurrency, warnings-as-errors, bundle id — are invisible inside it.
In `project.yml` they are eight readable lines.

**Alternatives considered**
Commit the `.xcodeproj` (standard, but opaque and merge-hostile); Tuist (more capable, heavier
dependency than this one-app project justifies); Swift Package Manager alone (cannot produce an
iOS app target with an Info.plist and entitlements).

**Consequences** Contributors need `xcodegen` once. Project settings are diffable.

**Revisit trigger** The project grows targets/build phases XcodeGen cannot express, or the user
prefers a committed project.

---

## ADR-0007 — Protocol v1 authenticates but does not encrypt

**Date** 2026-07-25

**Decision**
Every post-pairing message is wrapped in an `AuthenticatedEnvelope`: truncated HMAC-SHA256 under
the paired key, covering the protocol version, a direction byte and a strictly increasing counter
as well as the payload. Contents are **not** encrypted at the application layer.

**Evidence**
The threat this protocol must stop is an unpaired device issuing workout commands or forging rep
events — plan §9 acceptance is explicit that an unknown client cannot control a set. Authentication
plus replay counters stops that completely. Confidentiality buys much less: the payloads are rep
counts, phase names and battery level, and an attacker close enough to sniff BLE can watch the
athlete directly. BLE link-layer encryption applies underneath where the platform provides it.

**Alternatives considered**
- *AEAD (AES-GCM or ChaCha20-Poly1305) over the same paired key.* Not rejected on merit — deferred.
  It is a contained change (the envelope already has the right shape), and plan §14 lists
  "decide whether application-layer encryption is required" as an explicit hardening task. Doing it
  now would be guessing at a requirement before the hardening review that exists to establish it.
- *Rely on BLE link-layer encryption alone.* Rejected: it is negotiated by the platform, varies by
  OS version and bonding state, and cannot be asserted in a test.

**Consequences**
- An observer in radio range can read rep counts and phase names. Written down here so it is a
  known position and not a discovery.
- **Nothing genuinely sensitive may be put on this channel.** No raw landmarks, no video, no
  personal data — which the plan already forbids for other reasons.
- The counter is verified only *after* the MAC, so forged envelopes cannot advance the replay
  window and lock out the real peer.

**Revisit trigger** Session 14's hardening review, or any proposal to carry a payload whose
contents would matter to an eavesdropper.

---

## ADR-0008 — Wire format is protobuf; event payloads travel as canonical JSON inside it

**Date** 2026-07-25

**Decision**
Control messages are protobuf (`protocol/src/main/proto/flip_trainer_v1.proto`, generated with
protobuf-javalite). `WorkoutEventMessage` carries its payload as the **same canonical JSON string**
used by the on-device Room log and the golden fixtures, rather than as a parallel protobuf schema.

**Evidence**
Plan §5.3 requires `.proto` definitions, and protobuf is the right fit for control messages: small,
versioned, with generated code on both platforms. But the event payload is already a versioned
sealed hierarchy with a canonical JSON encoding that the durable log and every golden file depend
on. Re-declaring it in protobuf would create two schemas that must be kept in step, and the first
time they drifted, a replayed event would differ from the stored one in a way no test would catch.
One representation means a stored event, a replayed event and a test expectation are byte-identical.

**Alternatives considered**
- *Full protobuf for event payloads.* Rejected for the dual-schema drift above.
- *JSON for everything.* Rejected: no schema enforcement on control messages, larger on the wire,
  and no generated Swift types.

**Consequences**
- The event payload is opaque to protobuf tooling — a `.proto` reader cannot introspect a rep event.
  Acceptable: the payload schema is documented in Kotlin and exercised by the golden files.
- Adding an event type requires no protobuf change, which keeps the wire schema stable.

**Revisit trigger** The payload grows large enough that JSON overhead matters on BLE, or a consumer
appears that can parse protobuf but not the payload JSON.
