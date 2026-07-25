# Flip Trainer

A two-device, local-only workout system.

- **Galaxy Z Flip5** — a dedicated edge-vision appliance. Captures video, runs pose inference,
  decides exercise phase and repetitions, evaluates a small number of high-confidence movement
  rules, and writes a durable append-only local event log. It owns the real-time loop.
- **iPhone** — the user-facing app. Chooses the exercise, configures and controls the set, shows
  live progress, accepts weight/rep corrections, and stores workout history locally.
- **BLE** carries commands, status, and compact workout events between them. Nothing else does.

No internet, no accounts, no cloud. Raw video never leaves the Galaxy.

Full plan: [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md).
Agent rules: [`AGENTS.md`](AGENTS.md). Current state: [`docs/STATUS.md`](docs/STATUS.md).

## Layout

```
android-sensor/core/     pure JVM — geometry, readiness, evaluators, session lifecycle
android-sensor/app/      Android shell — CameraX, MediaPipe, Room, BLE GATT, Compose
protocol/                .proto wire format, pairing crypto, envelopes, ack/replay engine
ios-app/                 Swift / SwiftUI companion
exercise-definitions/    versioned JSON: thresholds, required landmarks, metadata
test-fixtures/           recorded pose traces + golden expected events
tools/trace-inspector/   offline CLI: replay a trace through an evaluator
docs/                    plan, status, decisions, device notes, test matrix
```

The important structural rule: **all decision logic is pure JVM and unit-tested without a
device.** `android-sensor/app` is a thin shell. See ADR-0001 in `docs/DECISIONS.md`.

## Build and test

The JVM modules need only a JDK 17+ — no Android SDK, no emulator, no device:

```bash
cd flip-trainer
./gradlew verify              # compile + ktlint + all unit tests
./gradlew :android-sensor:core:test
./gradlew :protocol:test
```

`settings.gradle.kts` includes `:android-sensor:app` **only** when an Android SDK is configured
via `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`. Without one, the
build cleanly excludes it and everything else still builds and tests.

Replay a recorded trace through an evaluator without a phone:

```bash
./gradlew :tools:trace-inspector:run --args="--trace ../test-fixtures/pose-traces/curl_clean_10reps.jsonl"
./gradlew :tools:trace-inspector:run --args="--trace <file> --format events"   # golden-file form
```

## Android

Requires the Android SDK (API 35) and a Flip5 in developer mode.

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :android-sensor:app:assembleDebug
./gradlew :android-sensor:app:installDebug
adb shell am start -n com.fliptrainer.sensor/.MainActivity
adb logcat -s FlipTrainer:V
```

The main-screen digitizer on the target Flip5 is dead, so the appliance is driven without touch:

```bash
scrcpy                                                    # mouse/keyboard over USB
adb shell am start -a com.fliptrainer.action.SHOW_PAIRING # force pairing QR
adb shell am broadcast -a com.fliptrainer.action.UNPAIR   # recovery: forget the controller
```

## iOS

Requires Xcode 16+ and a device that can run locally signed builds.

```bash
cd ios-app
open FlipTrainer.xcodeproj
xcodebuild -project FlipTrainer.xcodeproj -scheme FlipTrainer -destination 'platform=iOS Simulator,name=iPhone 16' test
```

## Not in scope

Dual-camera capture, stereo depth, automatic exercise recognition, visual weight recognition,
multi-person, Apple Watch, cloud/login/sync, social features, injury prediction, medical claims,
root, custom ROM, Knox modification. See §12 of the implementation plan and the revisit triggers
in §13 before proposing any of them.
