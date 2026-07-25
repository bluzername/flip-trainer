# Flip Trainer

## Multi-Session Implementation Plan for a Coding Agent

Status: implementation-ready plan  
Primary hardware: Samsung Galaxy Z Flip5 with nonfunctional main-screen touch  
Companion hardware: iPhone  
Current scope: local-first workout tracking, single-camera pose analysis, BLE companion experience  
Explicitly deferred: cloud synchronization, simultaneous dual-camera processing, automatic exercise recognition

---

## 1. Product objective

Build a two-device workout system in which:

- The Galaxy Z Flip5 is a dedicated edge-vision appliance. It captures video, performs pose inference, determines exercise phase and repetitions, evaluates a small number of high-confidence movement rules, and records a durable local event log.
- The iPhone is the main user-facing application. It selects exercises, starts and controls workouts, shows live progress, accepts weight and rep corrections, and stores workout history locally.
- Bluetooth Low Energy carries commands, status, and compact workout events between the devices.
- The complete system works without internet access.

The first useful product is not a general AI personal trainer. It is a reliable, extensible rep and movement-metrics system for one known exercise at a time.

---

## 2. Fixed scope decisions

These decisions remain in force unless the user explicitly changes them.

### 2.1 Camera

- Use one rear camera.
- Start with the standard wide rear camera.
- Do not implement simultaneous dual-camera capture.
- Do not spend time evaluating stereo reconstruction.
- A manual ultrawide mode may be added later only if the athlete cannot fit in frame with the wide camera.
- Concurrent-camera work requires a new architecture decision backed by a measured failure that cannot be solved through placement, framing, temporal filtering, or manual lens selection.

### 2.2 Connectivity and storage

- BLE is the only Galaxy-to-iPhone transport in the initial product.
- Initial pairing is bootstrapped by a QR code displayed on the Galaxy and scanned by the iPhone.
- The QR code is short-lived and single-use. It is not needed for normal reconnection after pairing.
- The Galaxy automatically displays pairing mode when it has no paired controller, so broken touch does not block setup.
- Support one paired controlling iPhone initially.
- Both devices persist their own local state.
- No login, backend, Firebase, Supabase, remote configuration, cloud backup, or cross-device cloud synchronization is part of the current implementation.
- Cloud is a final optional phase. The coding agent must not begin it without explicit user approval.
- Raw video is not uploaded.

### 2.3 Exercise behavior

- The user explicitly selects the exercise on the iPhone.
- Support one athlete in frame.
- Dumbbell weight is entered manually.
- Do not attempt automatic exercise recognition.
- Do not attempt visual dumbbell-weight recognition.
- Do not classify movement as medically safe or unsafe.
- Form feedback must be conservative and confidence-gated.
- If the system cannot see the required landmarks reliably, it must abstain rather than guess.

### 2.4 Android device management

- Do not root the Galaxy.
- Do not provision Device Owner mode initially.
- Begin with an ordinary app, a user-started camera foreground service, and manual arming.
- Reconsider Device Owner mode only during hardening if reboot recovery or remote camera startup remains an actual blocker.
- Device Owner provisioning is privileged and may require another factory reset. It therefore requires explicit user approval before implementation.

### 2.5 Implementation strategy

- Build native applications.
- Android: Kotlin.
- iPhone: Swift and SwiftUI.
- Do not introduce Flutter, React Native, or another cross-platform UI layer.
- Implement one pose engine first. Use an abstraction boundary, but do not build a second engine without evidence that the first is inadequate.
- Implement four representative exercises before designing a general exercise language.

---

## 3. MVP definition

The first MVP is complete when all of the following are true:

1. The Galaxy displays a pairing QR code, the iPhone scans it, and the two devices establish an authenticated BLE pairing.
2. Subsequent connections happen automatically without rescanning the QR code.
3. The user chooses one of four exercises:
   - Standing dumbbell curl
   - Goblet squat
   - Dumbbell Romanian deadlift
   - Standing dumbbell shoulder press
4. The iPhone sends a complete set manifest to the Galaxy.
5. The Galaxy verifies framing and reports whether it is ready.
6. The Galaxy counts repetitions locally and reports:
   - Current repetition
   - Movement phase
   - Rep duration or tempo
   - Exercise-specific range-of-motion metric
   - Confidence
   - A small number of experimental movement cues
7. The Galaxy continues the active set if BLE disconnects.
8. When BLE reconnects, the Galaxy replays events the iPhone missed.
9. The iPhone stores workout history locally and survives app restarts.
10. The user can correct weight and rep count after a set.
11. A 30-minute workout does not crash, lose events, or become unusable because of thermal throttling.
12. Neither device requires internet access.

The MVP is not complete merely because a pose skeleton appears on screen.

---

## 4. System architecture

### 4.1 Galaxy responsibilities

- Camera capture
- Pose inference
- Landmark confidence and readiness checks
- Feature extraction
- Temporal smoothing
- Exercise phase state machine
- Rep counting
- Conservative movement cues
- Active-session authority
- Append-only local event log
- BLE peripheral and event replay
- Pairing QR generation and paired-controller credential storage
- Device health reporting

### 4.2 iPhone responsibilities

- Device pairing and connection
- QR scanning and paired-device credential storage
- Exercise catalog and selection
- Workout and set configuration
- Manual dumbbell-weight input
- Live workout display
- Audio and haptic feedback
- Local workout history
- Manual corrections
- Local export and backup

### 4.3 Critical control principle

The Galaxy owns the real-time loop. The iPhone is not required to remain connected for a repetition to count.

Before a set begins, the iPhone sends a complete session or set manifest. Once the Galaxy accepts it, the Galaxy can finish the set autonomously. This protects the workout from iOS suspension, BLE interruptions, a locked iPhone, and transient radio failures.

### 4.4 Data that crosses BLE

Send:

- Pairing challenge and protocol negotiation
- Session manifest
- Arm, start, pause, resume, stop, and complete commands
- Readiness state
- Rep events
- Low-rate live phase and confidence
- Battery and thermal status
- Event acknowledgements and replay requests

Do not send:

- Camera frames
- Video previews
- Full-rate pose landmarks
- Model tensors
- Frame-by-frame diagnostic traces

---

## 5. Initial technology choices

### 5.1 Android

- Kotlin
- Jetpack Compose for the minimal debug and appliance UI
- CameraX with `ImageAnalysis`
- MediaPipe Pose Landmarker as the first and only implemented pose engine
- Kotlin coroutines and Flow
- Room for sessions and events
- Android foreground service with the `camera` service type
- Android BLE GATT server
- Android Keystore for the paired-controller key
- QR generation added when BLE pairing is implemented
- Gradle version catalog with pinned dependency versions

Camera defaults:

- Standard rear wide camera
- Target 1280 by 720 input
- Target 30 camera frames per second
- `STRATEGY_KEEP_ONLY_LATEST` for analysis backpressure
- Inference may analyze fewer frames than the camera produces
- Use monotonic timestamps for all pose and workout events

Do not request 4K frames for pose analysis.

### 5.2 iPhone

- Swift
- SwiftUI
- CoreBluetooth central role
- AVFoundation QR scanning
- Keychain storage for the paired-device key
- SwiftData for local workout history
- Swift Concurrency
- SwiftProtobuf once the BLE protocol is introduced
- Minimum iOS version chosen to support SwiftData and the actual test iPhone

### 5.3 Shared definitions

- Protocol messages defined in `.proto` files
- Exercise metadata and threshold configuration stored as versioned JSON
- Every recorded session includes:
  - App version
  - Protocol version
  - Pose-model version
  - Exercise-definition version
  - Device identifier

### 5.4 Pose-engine boundary

Define a narrow interface such as:

```kotlin
interface PoseEstimator {
    suspend fun start()
    suspend fun estimate(frame: CameraFrame): PoseFrame?
    suspend fun stop()
}
```

Implement only `MediaPipePoseEstimator` initially.

Do not implement an ML Kit adapter as speculative insurance. Add another estimator only if MediaPipe fails a documented accuracy, latency, licensing, or lifecycle requirement.

---

## 6. Core domain model

Keep camera code, pose code, exercise logic, persistence, BLE, and UI in separate modules.

### 6.1 Pose frame

```text
PoseFrame
  monotonicTimestamp
  imageWidth
  imageHeight
  cameraRotation
  landmarks[]
    type
    normalizedX
    normalizedY
    modelZ
    visibility
    presence
  inferenceDuration
```

`modelZ` must never be presented to the user as calibrated physical depth.

### 6.2 Readiness state

```text
ReadinessState
  status
    noPerson
    tooClose
    tooFar
    bodyClipped
    requiredLandmarksMissing
    lowConfidence
    wrongOrientation
    wrongView
    unstableCamera
    ready
  details[]
  confidence
```

Rep counting is disabled unless the exercise-specific readiness gate passes.

### 6.3 Session manifest

```text
SessionManifest
  sessionId
  exerciseId
  exerciseDefinitionVersion
  targetReps
  setNumber
  dumbbellWeight
  weightUnit
  laterality
  preferredView
  createdAt
```

### 6.4 Workout events

Every workout event has:

```text
sessionId
sequenceNumber
monotonicTimestamp
eventType
payload
```

Important event types:

- sessionAccepted
- readinessChanged
- setStarted
- phaseChanged
- repCompleted
- cueRaised
- cueCleared
- setPaused
- setResumed
- setCompleted
- setAborted
- deviceWarning

Rep events contain:

```text
repNumber
repDurationMs
concentricDurationMs
eccentricDurationMs
rangeOfMotionScore
confidence
cues[]
```

### 6.5 Event reliability

- Galaxy assigns sequence numbers.
- Galaxy commits each event to Room before sending it.
- iPhone acknowledges the highest contiguous sequence number persisted locally.
- Galaxy retains unacknowledged events.
- Reconnection starts with protocol negotiation and acknowledgement exchange.
- Replayed events are idempotent on the iPhone.
- `sessionId + sequenceNumber` is the unique event key.

### 6.6 QR pairing bootstrap

When the Galaxy has no paired controller:

1. Generate:
   - Random pairing ID
   - Cryptographically random 256-bit one-time secret
   - Expiration timestamp
2. Display a QR code containing a compact, versioned pairing payload:

```text
protocolVersion
deviceId
pairingId
pairingSecret
expiresAt
```

3. Advertise the Flip Trainer BLE service plus a short discriminator derived from `pairingId`.
4. The iPhone scans the QR code.
5. The iPhone scans BLE advertisements only for the matching service and discriminator.
6. After connecting, the devices exchange fresh nonces.
7. The iPhone proves possession of the QR secret using HMAC-SHA-256. The secret itself is never transmitted over BLE.
8. The Galaxy returns its own proof.
9. Both devices derive a persistent paired-device key using HKDF and the exchanged nonces.
10. Store the key in Android Keystore and iOS Keychain.
11. Invalidate the QR secret immediately after successful pairing.

Security and lifecycle rules:

- The QR payload is a temporary bearer credential. Expire it after a short interval, initially five minutes.
- Generate a new secret when an expired QR is refreshed.
- Pairing succeeds only once per QR.
- Normal reconnect uses the stored device identity and paired-device key.
- Authenticate every post-pairing protocol envelope and reject stale counters or replays.
- Prefer encrypted BLE characteristics where supported.
- Do not place cloud credentials, IP addresses, or permanent bearer tokens in the QR.
- Do not encode workout history in the QR.

Touch-free behavior:

- If no paired controller exists, show the pairing QR automatically when the app opens.
- Provide an ADB command or explicit debug intent that opens pairing mode.
- Provide an authenticated “unpair this Galaxy” action from the iPhone.
- If the paired iPhone is lost, allow pairing reset through ADB initially. A deliberate hardware-button recovery gesture may be added during hardening.
- Do not depend on Samsung cover-screen APIs. Showing the QR on the functioning main display is sufficient.

---

## 7. Exercise-engine strategy

### 7.1 Avoid both extremes

Do not create:

- One giant model expected to understand every exercise.
- A fully generic exercise-definition language before any real exercise works.

For the first four exercises:

- Share geometric feature primitives.
- Use typed Kotlin evaluators for phase transitions and cues.
- Keep thresholds and metadata in JSON.
- Add deterministic trace-replay tests.

After the first four evaluators are stable, inspect the repeated patterns. Only then extract a constrained declarative state-machine format.

### 7.2 Shared feature primitives

Implement reusable, unit-tested features:

- Joint angle
- Signed angle
- Normalized distance
- Landmark velocity
- Landmark vertical and horizontal displacement
- Torso inclination
- Shoulder and hip line inclination
- Left-right symmetry
- Landmark visibility aggregate
- Body scale based on shoulder or hip width
- Camera-relative direction

### 7.3 Temporal behavior

Rep logic must include:

- Filtering
- Hysteresis
- Minimum phase duration
- Minimum excursion
- Direction reversal confirmation
- Confidence dropout tolerance
- Cooldown after rep completion

A single threshold crossing must not count a rep.

### 7.4 Form-cue policy

- Begin with one or two cues per exercise.
- Prefer high precision over high recall.
- Require the relevant landmarks to be visible throughout the evaluation window.
- Debounce cues so they do not flicker.
- Separate observable facts from recommendations.
- Phrase feedback as movement observations, not injury prevention claims.
- Store the evidence values that triggered a cue for later debugging.

---

## 8. Repository structure

Create:

```text
flip-trainer/
  AGENTS.md
  README.md
  android-sensor/
  ios-app/
  protocol/
  exercise-definitions/
  test-fixtures/
    pose-traces/
    expected-events/
  tools/
    trace-inspector/
  docs/
    IMPLEMENTATION_PLAN.md
    STATUS.md
    DECISIONS.md
    DEVICE_NOTES.md
    TEST_MATRIX.md
    EXERCISE_VALIDATION.md
```

Do not create a backend directory until cloud work is explicitly approved.

### 8.1 `STATUS.md`

Maintain:

```text
Current session:
Current objective:
Last known green revision:
Completed:
Verification performed:
Physical-device evidence:
Known failures:
Blockers:
Next exact action:
```

### 8.2 `DECISIONS.md`

For each architecture decision record:

```text
Date
Decision
Evidence
Alternatives considered
Consequences
Revisit trigger
```

Do not repeatedly reopen settled decisions without hitting a documented revisit trigger.

### 8.3 `TEST_MATRIX.md`

Track results by:

- Exercise
- Exercise-definition version
- Person
- Camera view
- Distance
- Lighting
- Clothing
- Tempo
- Full or partial repetitions
- Left or right side
- Expected count
- Observed count
- False cues
- Missed cues
- Readiness failures
- Notes

---

## 9. Coding-agent operating protocol

### 9.1 At the start of every session

1. Read this implementation plan in full.
2. Read `STATUS.md`, `DECISIONS.md`, `DEVICE_NOTES.md`, and `TEST_MATRIX.md`.
3. Inspect repository status and preserve unrelated user changes.
4. Run the smallest relevant existing test suite.
5. Select the earliest incomplete session whose prerequisites are satisfied.
6. State the exact session objective before editing.

### 9.2 During a session

- Work only on the current session’s deliverables.
- Do not implement later-phase infrastructure opportunistically.
- Keep business logic outside activities, views, camera callbacks, and BLE callbacks.
- Add tests with the production change.
- Record measured performance rather than saying it “looks fast.”
- Do not claim physical-device validation unless it was performed on the Flip5.
- When human movement or device interaction is required, produce a concise test script and pause for the result.

### 9.3 At the end of every session

1. Run the documented verification commands.
2. Update `STATUS.md`.
3. Update `DECISIONS.md` if a decision changed.
4. Update `DEVICE_NOTES.md` with actual hardware observations.
5. Update `TEST_MATRIX.md` for physical exercise tests.
6. Summarize:
   - What changed
   - What was verified
   - What remains uncertain
   - The next exact step
7. Make one logical commit if repository policy allows it. Do not push unless requested.

### 9.4 Session gate

Do not begin the next session if the current acceptance criteria fail. Diagnose, document, and repair first.

---

## 10. Multi-session execution plan

## Session 0. Prepare the hardware

Owner: user, assisted by coding agent  
Code changes: none required

### Tasks

1. Remove the previous owner’s Google and Samsung accounts before resetting, to avoid Factory Reset Protection.
2. Factory-reset the Galaxy.
3. Use a USB-C mouse or hub to complete minimal setup.
4. Enable Developer Options and USB debugging.
5. Authorize the development computer.
6. Confirm:

```bash
adb devices
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
```

7. Install and verify `scrcpy`.
8. Configure the phone so the app is not battery-restricted during development.
9. Prepare:
   - Stable tripod or clamp
   - Charger and sufficiently long cable
   - Clear full-body test area
10. Confirm an iPhone can run locally signed development builds from Xcode.

### Acceptance criteria

- Galaxy is visible to ADB.
- `scrcpy` provides mouse and keyboard control.
- A test APK can be installed.
- An iPhone test app can be installed.
- Actual Android and iOS versions are recorded in `DEVICE_NOTES.md`.

### Stop condition

If the Galaxy display is not visible at all and USB debugging cannot be authorized, stop and reassess the recovery route.

---

## Session 1. Create the repository foundation

### Tasks

1. Create the repository structure from Section 8.
2. Add `AGENTS.md` containing the operating protocol and fixed scope decisions.
3. Scaffold the Android project.
4. Scaffold the iOS project.
5. Add minimal unit-test targets.
6. Add formatting and linting with pinned versions.
7. Create the initial documentation files.
8. Add a root README containing build, install, and test commands.
9. Do not add BLE, MediaPipe, Room, SwiftData, or cloud dependencies yet.

### Acceptance criteria

- Android debug APK builds and installs on the Flip5.
- Android app launches through ADB.
- iOS app builds and installs on the iPhone.
- Unit-test commands pass on both projects.
- The repository contains no backend or cloud code.

---

## Session 2. Implement single-camera capture

### Tasks

1. Add CameraX.
2. Request camera permission from a visible activity.
3. Bind the default rear camera.
4. Add:
   - Preview
   - `ImageAnalysis`
   - `STRATEGY_KEEP_ONLY_LATEST`
5. Start at 1280 by 720.
6. Record:
   - Delivered frame rate
   - Frame dimensions
   - Rotation
   - Frame drops
   - Camera start latency
7. Ensure every `ImageProxy` is closed.
8. Add a debug overlay with camera statistics.
9. Add a ten-minute camera soak test.
10. Record the selected logical camera ID and available resolutions in `DEVICE_NOTES.md`.

### Explicit exclusions

- Do not enumerate or bind two cameras.
- Do not implement ultrawide selection.
- Do not add pose inference.

### Acceptance criteria

- The rear wide-camera preview is correctly oriented.
- Analysis receives frames continuously for ten minutes.
- There is no increasing memory trend caused by unclosed frames.
- Stopping and restarting the camera works five consecutive times.
- Measured frame behavior is documented.

---

## Session 3. Add MediaPipe pose inference

### Tasks

1. Add the `PoseEstimator` interface.
2. Implement `MediaPipePoseEstimator`.
3. Run MediaPipe in live-stream mode.
4. Convert CameraX frames without unnecessary bitmap copies where practical.
5. Normalize rotation and coordinate transforms.
6. Emit `PoseFrame` objects.
7. Display a debug skeleton overlay.
8. Measure:
   - Inference p50 and p95
   - Analyzed frames per second
   - Dropped analysis frames
   - CPU and memory behavior
9. Add an opt-in landmark-trace recorder that stores coordinates and confidence, not raw video.
10. Create a trace-file schema with a version header.

### Acceptance criteria

- A full-body skeleton tracks correctly in portrait and landscape device orientation.
- Left and right landmarks are not swapped by display transformations.
- Pose inference remains stable for ten minutes.
- At least 15 analyzed pose frames per second are sustained unless measurement shows that a lower rate is sufficient and the reason is documented.
- Landmark traces can be recorded and replayed.

### Decision gate

Do not add ML Kit because of a vague perception that it might be better. Add a second estimator only if MediaPipe misses an explicit acceptance requirement.

---

## Session 4. Build the readiness and framing gate

### Tasks

1. Implement `ReadinessState`.
2. Detect:
   - No person
   - Person too small
   - Person too large
   - Required body region clipped
   - Required landmark visibility too low
   - Camera movement
   - Unsupported orientation
3. Add exercise-specific required-landmark sets.
4. Add state stabilization so readiness does not flicker.
5. Emit readiness events.
6. Show a simple debug instruction such as:
   - Move closer
   - Move farther away
   - Show your feet
   - Turn slightly sideways
   - Hold the camera still
   - Ready
7. Add trace-based tests for clipped and low-confidence conditions.

### Acceptance criteria

- Counting cannot begin unless readiness is stable.
- Deliberately clipping wrists, knees, feet, or head produces the expected state for exercises that require them.
- Short landmark dropouts do not cause rapid state oscillation.
- Readiness transitions are deterministic during trace replay.

---

## Session 5. Build feature extraction and the deterministic exercise framework

### Tasks

1. Implement unit-tested geometric feature primitives.
2. Add scale and translation normalization.
3. Add a configurable temporal filter.
4. Preserve both raw and filtered feature values for debugging.
5. Define:

```kotlin
interface ExerciseEvaluator {
    fun reset(manifest: SessionManifest)
    fun accept(frame: PoseFrame, readiness: ReadinessState): List<WorkoutEvent>
    fun snapshot(): ExerciseSnapshot
}
```

6. Implement generic phase-state utilities:
   - Hysteresis
   - Minimum duration
   - Direction confirmation
   - Confidence dropout tolerance
   - Rep cooldown
7. Build an offline trace-replay runner.
8. Build synthetic traces for an oscillating joint angle, partial excursion, noisy reversal, and confidence dropout.
9. Verify deterministic output.

### Acceptance criteria

- Replaying the same trace produces byte-equivalent ordered events.
- Noisy threshold crossings do not create duplicate reps.
- Partial excursions can be rejected.
- A short confidence dropout does not corrupt evaluator state.
- Feature primitives have unit tests covering coordinate and left-right edge cases.

---

## Session 6. Implement the standing dumbbell curl vertical slice

### Tasks

1. Define curl metadata and thresholds in versioned JSON.
2. Implement `StandingCurlEvaluator`.
3. Detect:
   - Bottom
   - Concentric
   - Top
   - Eccentric
4. Count a rep only after a valid complete cycle.
5. Measure:
   - Elbow excursion
   - Rep duration
   - Concentric and eccentric durations
6. Add no more than two experimental cues:
   - Incomplete range
   - Excessive upper-arm drift
7. Add a local Android debug-session screen.
8. Record controlled landmark traces.
9. Add golden expected-event tests.
10. Create a human test protocol covering:
    - Normal reps
    - Slow reps
    - Fast reps
    - Half reps
    - Long pause at the top
    - Arm movement while idle

### Acceptance criteria

- No phantom reps during 60 seconds of standing idle.
- Partial curls are not counted as complete reps.
- At least 9 of 10 controlled clean sets have the exact expected count in the supported camera setup.
- Every failure is added to `TEST_MATRIX.md`.
- Cues abstain when the relevant shoulder, elbow, or wrist confidence is inadequate.

### Human validation gate

The coding agent cannot mark this session complete using only synthetic traces. A person must perform the physical test protocol on the Flip5.

---

## Session 7. Add three representative exercises

Implement in this order:

1. Goblet squat
2. Dumbbell Romanian deadlift
3. Standing dumbbell shoulder press

### For each exercise

1. Define preferred camera view.
2. Define required landmarks.
3. Define normalized features.
4. Implement phases with hysteresis and timing.
5. Define rep completion.
6. Add range-of-motion and tempo metrics.
7. Add at most two high-confidence observable cues.
8. Record landmark traces.
9. Add golden replay tests.
10. Execute the physical test protocol.
11. Document unsupported views and known occlusions.

### Initial cue candidates

Goblet squat:

- Incomplete depth
- Strong left-right asymmetry when the front-diagonal view supports it

Dumbbell Romanian deadlift:

- Insufficient hip excursion
- Excessive knee travel relative to the calibrated starting position

Shoulder press:

- Incomplete overhead range
- Strong left-right timing asymmetry

These are hypotheses. Remove a cue if validation cannot make it precise.

### Acceptance criteria

- Each exercise passes deterministic trace replay.
- Each exercise has no phantom reps during 60 seconds of idle movement.
- Each exercise achieves exact expected count in at least 9 of 10 controlled clean sets in its supported setup.
- Bad framing produces abstention instead of fabricated metrics.
- The four-exercise catalog can be selected in the Android debug UI.

### Human validation gate

Do not proceed to BLE because skeletons and state machines appear plausible. Proceed only after physical count testing is documented.

---

## Session 8. Make the Galaxy a durable local workout appliance

### Tasks

1. Add Room.
2. Implement tables for:
   - Session
   - Set
   - Workout event
   - Device diagnostic
3. Implement the session lifecycle:
   - Idle
   - Armed
   - Not ready
   - Ready
   - Running
   - Paused
   - Completed
   - Aborted
   - Error
4. Persist every event before exposing it externally.
5. Add a camera foreground service that is started while the app is visible.
6. Add a persistent notification.
7. Add manual appliance controls that do not require main-screen touch:
   - ADB or `scrcpy`
   - Volume-button fallback where practical
8. Recover an incomplete session after process restart.
9. Add local audio cues if useful, behind a setting.
10. Test the app without internet.

### Acceptance criteria

- A complete four-exercise workout can be recorded entirely on the Galaxy.
- Killing and reopening the process does not duplicate completed events.
- An interrupted active set is recovered or explicitly marked interrupted.
- The camera service follows Android lifecycle and permission rules.
- The system works with Wi-Fi and mobile data disabled.

### Decision gate

Do not add Device Owner mode unless ordinary foreground operation is proven insufficient for the intended workout flow.

---

## Session 9. Define the protocol and implement Galaxy BLE

### Tasks

1. Define protocol version 1 in `.proto`.
2. Include:
   - Pairing challenge
   - Pairing proof
   - Pairing result
   - Authenticated envelope
   - Device capability
   - Session manifest
   - Session command
   - Readiness update
   - Workout event
   - Device status
   - Acknowledgement
   - Replay request
   - Error
3. Generate Kotlin protocol classes.
4. Implement the QR pairing bootstrap from Section 6.6.
5. When no paired controller exists:
   - Generate the pairing ID and secret
   - Display the QR automatically
   - Refresh it after expiration
   - Advertise a discriminator derived from the pairing ID
6. Implement Android as BLE peripheral.
7. Create characteristics for:
   - Control writes
   - Event notifications
   - Status read and notifications
   - Replay transfer
8. Implement nonce exchange, HMAC pairing proof, paired-key derivation, and Keystore storage.
9. Invalidate the QR secret after successful pairing.
10. Add authenticated protocol envelopes and replay-counter validation.
11. Add message framing and size checks.
12. Add sequence acknowledgement and workout-event replay.
13. Add protocol compatibility rejection.
14. Add an authenticated unpair command.
15. Add an ADB-accessible recovery action that deletes the paired controller and returns to QR mode.
16. Use a generic BLE inspection client to verify advertisement and characteristic visibility.
17. Use unit tests or a small development client to test the authenticated handshake.
18. Add integration tests for pairing, disconnects, duplicate delivery, and stale counters.

### Acceptance criteria

- The Galaxy automatically displays a valid QR code when unpaired.
- The QR expires and refreshes after five minutes.
- A client with the correct QR payload can authenticate.
- A wrong, expired, or previously consumed QR payload is rejected.
- The one-time QR secret is never transmitted over BLE.
- Successful pairing stores a persistent paired-controller key.
- After pairing, the Galaxy stops displaying the active pairing credential.
- The paired client reconnects without another QR scan.
- An unknown client cannot issue workout-control commands.
- A client can arm, start, pause, resume, and stop a set.
- Rep events are received as notifications.
- Disconnecting for several reps and reconnecting replays every missing event.
- Duplicate delivery does not create duplicate logical events.
- The Galaxy completes the set when no BLE client is present.
- No pose frames or video data cross BLE.

---

## Session 10. Build the iPhone BLE companion

### Tasks

1. Generate Swift protocol classes.
2. Add a QR scanner using AVFoundation.
3. Parse and validate the versioned Flip Trainer pairing payload.
4. Reject malformed or expired QR payloads before starting BLE discovery.
5. Implement the CoreBluetooth central.
6. Discover the BLE device matching the QR service and pairing discriminator.
7. Complete the nonce and HMAC pairing handshake.
8. Derive and store the paired-device key in Keychain.
9. Add connection states:
   - No device
   - Awaiting QR scan
   - QR expired
   - Scanning
   - Connecting
   - Authenticating
   - Connected
   - Armed
   - Reconnecting
   - Incompatible
10. Build SwiftUI screens:
   - Device setup
   - QR scanner
   - Pairing result
   - Exercise selection
   - Set configuration
   - Placement and readiness
   - Live set
   - Set result
11. Send authenticated control messages.
12. Send a complete manifest before arming.
13. Persist the highest contiguous event sequence before acknowledging it.
14. Display live reps, phase, tempo, confidence, and cues.
15. Handle app foreground and background transitions.
16. Make reconnection safe.
17. Add an authenticated unpair action.
18. Add audio and haptic feedback behind settings.

### Acceptance criteria

- Scanning the Galaxy QR identifies the correct BLE device without asking the user to select from a device list.
- A malformed, expired, or wrong-device QR produces a clear error.
- The paired-device key survives iPhone app restart.
- Subsequent connections do not require another QR scan.
- Unpairing removes credentials from both devices and returns the Galaxy to QR mode.
- The iPhone starts and completes all four exercise types.
- The live UI reflects Galaxy events.
- Locking or moving the iPhone out of range does not stop Galaxy counting.
- Reconnection fills the missing event history.
- Protocol mismatch produces a clear recoverable error.
- The complete flow works without internet.

---

## Session 11. Add local iPhone history and progress

### Tasks

1. Add SwiftData models for:
   - Exercise
   - Workout
   - Set
   - Rep
   - Cue
   - Paired device
2. Import Galaxy events idempotently.
3. Add:
   - Workout history
   - Exercise history
   - Weight, reps, and volume progression
   - Tempo and range-of-motion summaries
4. Allow manual correction of:
   - Dumbbell weight
   - Completed rep count
   - Exercise selection if configured incorrectly
5. Preserve both measured and corrected values.
6. Add JSON and CSV export.
7. Add local restore or import for development backups if practical.
8. Add migration tests for the first schema version.

### Acceptance criteria

- History survives app restart and phone restart.
- Replayed Galaxy events do not duplicate reps.
- Corrections do not overwrite the original measured values.
- Exported data contains model and exercise-definition versions.
- No cloud SDK or login exists.

---

## Session 12. Make device placement usable

### Step 1. Try the minimal path

Use:

- BLE readiness state
- Spoken placement instructions
- Clear status on the iPhone
- Galaxy debug screen through `scrcpy` during development
- Cover-screen status only if it is straightforward

Do not make Samsung-specific cover-screen integration a dependency.

### Step 2. Measure placement success

Have a user set up the Galaxy for each of the four exercises without touching the broken main display.

Record:

- Time to ready
- Number of placement corrections
- Most common failure
- Whether the athlete understands the preferred view

### Step 3. Optional local preview

Only if readiness instructions are insufficient, implement a low-rate local-network preview from Galaxy to iPhone.

Requirements:

- Local network only
- Explicitly active only during placement
- Low resolution and low frame rate
- Stops before or shortly after the set begins
- No cloud relay
- No BLE video

### Step 4. Optional ultrawide selection

Only if a supported exercise cannot fit in frame with the wide camera at a practical distance:

- Add manual wide or ultrawide selection.
- Recalibrate framing rules for ultrawide.
- Validate each supported exercise separately.
- Do not run both cameras concurrently.

### Acceptance criteria

- A user can place the Galaxy and reach a stable ready state without main-screen touch.
- Median setup time is under 60 seconds after the user has learned the flow.
- Any preview turns off reliably.
- The standard wide camera remains the default.

---

## Session 13. Expand from four exercises to dozens

Do not treat “dozens” as one coding task. Add exercise families in controlled batches.

### 13.1 First analyze the four evaluators

Identify repeated:

- Features
- Phase patterns
- Threshold patterns
- Laterality rules
- Readiness requirements
- Cue debounce behavior

Extract a constrained declarative representation only for patterns that actually repeat.

Do not create a general expression language capable of arbitrary code.

### 13.2 Suggested expansion order

Batch A. Close variants:

- Hammer curl
- Alternating curl
- Front raise
- Lateral raise
- Reverse lunge

Batch B. Moderate complexity:

- Split squat
- Overhead triceps extension
- Dumbbell sumo squat
- Arnold press
- Bent-over reverse fly

Batch C. Higher occlusion:

- One-arm dumbbell row
- Bent-over two-arm row
- Floor press
- Bench press
- Dumbbell fly

Add seated and unilateral variants only after the base family is reliable.

### 13.3 Per-exercise completion checklist

An exercise is not “supported” until it has:

1. Stable ID and version.
2. User-facing name and instructions.
3. Preferred camera view.
4. Required landmarks.
5. Readiness rules.
6. Phase state machine.
7. Rep-completion rule.
8. Tempo and range metric.
9. Abstention rules.
10. Trace fixtures.
11. Golden expected events.
12. Physical test results.
13. Known limitations.
14. Versioned thresholds.

### 13.4 Object-detection decision gate

Do not train a dumbbell detector merely because the product uses dumbbells.

Add equipment detection only if a documented exercise failure cannot be solved from body landmarks and temporal motion. Likely candidates are rows, fly movements, and cases where the wrist is occluded.

If required, begin with:

- Dumbbell presence
- Dumbbell center tracking
- Bench presence and orientation

Do not attempt weight recognition.

### Validation gate

Before calling the expanded catalog a beta:

- Test every exercise across at least three people.
- Include different body proportions, clothing, tempo, distance, and lighting.
- Include partial, paused, and intentionally incorrect repetitions.
- Optimize correction precision, even if that increases abstention.
- Keep per-exercise results. Do not hide weak exercises inside one aggregate accuracy number.

---

## Session 14. Hardening and appliance behavior

### Tasks

1. Run 30-minute and 60-minute thermal tests.
2. Measure:
   - Analyzed frames per second over time
   - Inference p95 over time
   - Battery drain
   - Thermal warnings
   - Memory growth
3. Test:
   - Screen on and off
   - Folded and unfolded placement
   - Charger connected
   - BLE disconnect and reconnect
   - iPhone app termination
   - Galaxy process termination
   - Airplane mode with Bluetooth re-enabled
4. Harden pairing:
   - Verify pairing-secret expiration and one-time consumption
   - Verify Keychain and Keystore persistence behavior
   - Reject unknown controllers
   - Reject replayed authenticated envelopes
   - Test authenticated unpairing
   - Test lost-iPhone recovery through ADB
   - Decide whether application-layer encryption is required in addition to authenticated messages and encrypted BLE characteristics
5. Add local diagnostic export.
6. Add database migrations.
7. Add end-to-end automated protocol tests.
8. Add a release checklist.

### Device Owner decision

Only now evaluate whether the user needs:

- Automatic recovery after Galaxy reboot
- Camera startup initiated entirely from the iPhone
- Kiosk behavior
- Prevention of accidental navigation away from the sensor app

If manual arming and the normal foreground service are adequate, do not add Device Owner mode.

If Device Owner is necessary:

1. Write a dedicated architecture decision.
2. Explain the reset and privilege implications.
3. Obtain explicit user approval.
4. Implement it in an isolated branch.
5. Preserve a normal non-Device-Owner development mode.

### Acceptance criteria

- A 60-minute test completes without event loss or unrecoverable failure.
- All lifecycle cases have deterministic recovery behavior.
- BLE replay is idempotent.
- Diagnostic export is sufficient to explain failures without retaining raw video.
- Device Owner and root remain absent unless separately approved.

---

## Session 15. Optional cloud phase

Status: deferred and not authorized for current implementation

Do not begin this session automatically.

### Entry criteria

- Local Galaxy processing is reliable.
- BLE replay is reliable.
- iPhone local history is reliable.
- The exercise catalog has useful real-world validation.
- The user has explicitly requested cloud work.

### First task

Write an architecture decision comparing:

- Firebase
- Supabase
- Apple-only storage plus a separate Android mechanism
- A custom API
- No cloud

Evaluate:

- Source of truth
- Authentication
- Conflict resolution
- Offline behavior
- Privacy
- Operating cost
- Export and deletion
- Model and exercise-definition distribution

### Cloud design constraint

Cloud must remain outside the live rep-counting control loop.

The first cloud function should be backup and synchronization of iPhone-owned workout history. It should not be required to start, conduct, or finish a workout.

Raw video remains local and opt-in even after cloud support exists.

---

## 11. Validation program

### 11.1 Primary metrics

Track per exercise and supported view:

- Exact set-count rate
- Rep-count mean absolute error
- Phantom-rep rate
- Partial-rep rejection rate
- Phase-transition latency
- Tempo error
- Readiness false-positive rate
- Readiness false-negative rate
- Cue precision
- Cue recall
- Abstention rate
- Inference p50 and p95
- Sustained analyzed frames per second
- BLE event-loss rate
- Replay correctness

### 11.2 Initial engineering targets

These are development gates, not public accuracy claims:

- At least 90 percent of controlled clean sets have exact rep counts for each supported exercise and view.
- No phantom reps during one minute of exercise-specific idle movement.
- All persisted events survive disconnect and replay exactly once logically.
- Live rep feedback appears within 300 milliseconds of confirmed rep completion.
- Form-cue precision is at least 90 percent on the available held-out validation examples, or the cue is removed.
- The system abstains when required landmarks are not reliable.
- A 60-minute workout remains operational.

Raise these targets before any public product claim.

### 11.3 Human-labeled evidence

The coding agent may build tools, collect logs, and calculate metrics. It may not invent ground truth.

For every physical validation set, a human records:

- Actual exercise
- Actual rep count
- Whether repetitions were full or partial
- Intended movement errors
- Camera view
- Distance
- Any observed tracking failure

---

## 12. Explicit non-goals for the current roadmap

- Simultaneous use of both Galaxy rear cameras
- Stereo depth reconstruction
- Automatic exercise recognition
- Visual weight recognition
- Multi-person workouts
- Apple Watch support
- Cloud login or synchronization
- Social features
- Trainer portal
- Remote video coaching
- Injury prediction
- Medical or rehabilitation claims
- Root access
- Custom ROM
- Custom kernel
- Samsung Knox modification
- Docker or Linux-container infrastructure on the Galaxy

---

## 13. Revisit triggers

Reconsider an excluded capability only under these conditions.

### Ultrawide camera

Trigger: a required exercise cannot fit reliably in the wide frame at a usable distance.

### Simultaneous dual camera

Trigger: a documented accuracy failure persists after placement guidance, manual lens selection, filtering, evaluator changes, and validation-data expansion. A proposed dual-camera experiment must define the expected measurable gain and thermal budget.

### Second pose engine

Trigger: MediaPipe misses a specific latency, accuracy, lifecycle, platform, or licensing requirement.

### Dumbbell object detector

Trigger: wrist and body landmarks cannot resolve equipment trajectory or occlusion for a target exercise.

### Generic exercise DSL

Trigger: at least four stable evaluators demonstrate repeated structures that can be represented safely and more efficiently as data.

### Device Owner mode

Trigger: normal foreground-service operation cannot satisfy the required headless startup or recovery flow.

### Cloud

Trigger: the local product is useful and the user explicitly prioritizes backup, multi-device access, remote configuration, or sharing.

---

## 14. Coding-agent final instruction

The implementation should advance by proving one narrow layer at a time:

```text
Camera
  -> Pose
    -> Readiness
      -> Deterministic features
        -> One reliable exercise
          -> Four reliable exercises
            -> Durable Galaxy sessions
              -> BLE reliability
                -> iPhone experience
                  -> Local history
                    -> Placement usability
                      -> Exercise expansion
                        -> Hardening
                          -> Optional cloud
```

Do not skip validation gates. Do not add an excluded subsystem because it appears architecturally elegant. The preferred implementation is the smallest system that produces reliable, inspectable workout events on the actual Flip5.

---

## 15. Primary technical references

- CameraX configuration and image analysis: <https://developer.android.com/media/camera/camerax/configuration>
- MediaPipe Pose Landmarker for Android: <https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android>
- Android camera foreground-service restrictions: <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- Android dedicated-device provisioning, only if later approved: <https://developer.android.com/work/dpc/dedicated-devices/cookbook>
- Apple Core Bluetooth background behavior: <https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html>
- Firestore offline behavior, only for the deferred cloud phase: <https://firebase.google.com/docs/firestore/manage-data/enable-offline>
