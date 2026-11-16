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

---

## ADR-0009 - Toolchain and dependency refresh; Android-side versions are unverified by definition

**Date** 2026-09-16

**Decision**
The pinned catalog (ADR-0002) is bumped in one reviewed pass rather than drifting for another
year. Two classes of version now exist in `gradle/libs.versions.toml` and they carry different
guarantees:

| Class | Versions | Guarantee |
| --- | --- | --- |
| JVM (verified) | Gradle 9.7.1 (wrapper, with `distributionSha256Sum`), Kotlin 2.4.20, KSP 2.3.12, coroutines 1.11.0, serialization 1.11.0, JUnit 6.1.3, ktlint 1.8.0 / ktlint-gradle 14.2.0, protobuf 4.36.1 / protobuf-gradle-plugin 0.10.0 | `./gradlew verify` passes: 274 tests, 0 failures, no Gradle deprecation warnings under `--warning-mode all` |
| Android (unverified) | AGP 9.4.0, `compileSdk` 37, androidx-core 1.19.0, lifecycle 2.11.0, activity-compose 1.13.0, compose-bom 2026.09.00, CameraX 1.6.2, Room 2.8.5, MediaPipe tasks-vision 0.10.35, ZXing 3.5.4 | None. `:android-sensor:app` has never compiled (STATUS.md), so these are the newest stable releases at bump time, not versions known to build |

Rule: **every Android-side version is unverified until the app has compiled once.** The session
that first gets `:android-sensor:app:assembleDebug` green may move any of them, up or down,
without a new ADR, provided it records in STATUS.md what actually compiled. Until then, CI runs
`assembleDebug` with `continue-on-error: true` so the first compile happens somewhere
reproducible without blocking the JVM suite.

Three Android-side choices were made deliberately:
- **AGP 9.4.0 and built-in Kotlin.** Compose 1.12.1 (BOM 2026.09.00) and androidx-core 1.19.0
  declare `minCompileSdk=37` and `minAndroidGradlePluginVersion=9.1.0` in their AAR metadata, and
  AGP 9.4 requires Gradle 9.6+, which the JVM side now satisfies. AGP 9 compiles Kotlin itself, so
  the `kotlin-android` plugin is dropped and `android.kotlinOptions` becomes `kotlin.compilerOptions`
  in the app module, per Google's migration guide.
- **`targetSdk` stays 35** while `compileSdk` moves to 37. Raising the target changes runtime
  behaviour (foreground-service and BLE permission semantics) that nobody can observe yet. Raise it
  in the session that first runs the app.
- **MediaPipe stays on 0.10.x** (0.10.35, not 1.0.0). A major bump of the one pose engine (ADR-0003)
  with no way to run it is a guess, and ADR-0004 makes a model change invalidate every golden.

**Evidence**
Each JVM bump was applied on its own and `./gradlew verify` run after it; none had to be reverted.
ktlint 1.8.0 introduced `when-entry-bracing` and `blank-line-between-when-conditions`, which
`ktlintFormat` applied mechanically to six source files, and it no longer permits KDoc inside a
`plugins {}`-level build script block, so two `build.gradle.kts` header comments became plain block
comments. The old ktlint and protobuf plugins were the source of the "incompatible with Gradle 10"
deprecation notice; after the bump `--warning-mode all` is silent. Android `minCompileSdk` values
were read from the published AAR metadata on `dl.google.com`, not guessed.

**Alternatives considered**
- *Leave everything pinned.* Rejected: KGP 2.1 already warns that Gradle 8.14 is deprecated, and
  the gap only widens. Pinning (ADR-0002) means deliberate bumps, not no bumps.
- *Bump only the JVM side.* Rejected: it would leave `compileSdk` 35 next to libraries that cannot
  possibly be that old by the time the app is compiled, and hide the migration work AGP 9 needs.
- *MediaPipe 1.0.0.* Deferred, see above.
- *Regenerate the Gradle wrapper jar.* Not needed: the 8.14.3 wrapper jar downloads and runs 9.7.1
  unchanged, so only `gradle-wrapper.properties` changed, plus the distribution checksum.

**Consequences**
- Dependabot (`.github/dependabot.yml`) opens weekly grouped minor/patch PRs for Gradle and Actions.
  A Dependabot PR that is green on the `jvm` job is verified; one that only touches Android-side
  versions is not, whatever its status says.
- The `android` CI job is informational until the module compiles. Do not read its red as a
  regression, and do not read its green (if it ever appears without a fix) as verification either:
  check the log.

**Revisit trigger**
The first session that compiles `:android-sensor:app`. That session should replace the
"unverified" class in the table above with what actually built, and remove the
`continue-on-error` from the `android` job.
