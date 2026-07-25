# Continuing locally, with both devices attached

Sessions 1–11 were built in a cloud container with no USB bus, no Android SDK, and no Xcode.
Sessions 0 and 12–14 need hardware. This is the handoff.

## Why it has to be a Mac

Xcode is macOS-only, and the project needs both halves. Android alone would work on Linux or
Windows, but you cannot build, sign, or install the iPhone controller anywhere but macOS. Apple
Silicon or Intel both work.

## What USB does and does not give you

Worth being precise, because it changes what you can debug:

| Over USB | Not over USB |
| --- | --- |
| Install the APK, `adb logcat`, `adb shell`, pull traces | **The Galaxy↔iPhone BLE link** |
| `scrcpy` mouse/keyboard control of the dead Flip5 screen | Anything about radio range or interference |
| Install the iOS app, Xcode console, `devicectl` | Pairing behaviour at distance |

USB is for **deploying and observing**. The device-to-device transport is BLE and stays wireless,
so protocol behaviour still has to be proven over the air — which is exactly what Session 9's
acceptance criteria are about, and none of them have been exercised on a radio yet.

Practical consequence: you will want both devices on USB **simultaneously** (Galaxy for `logcat`,
iPhone for the Xcode console) while they talk to each other over BLE. A powered hub helps, and the
Galaxy should stay on the charger — a camera pipeline plus BLE advertising drains it fast.

## Setup

```bash
# 1. Claude Code, locally
npm install -g @anthropic-ai/claude-code

# 2. The repo, on the branch this work lives on
git clone https://github.com/bluzername/bee-api-experiments.git
cd bee-api-experiments
git checkout claude/plan-execution-p62uzr

# 3. Toolchains
brew install --cask temurin                      # JDK 21
brew install --cask android-commandlinetools     # SDK + adb
brew install scrcpy xcodegen                     # phone control + iOS project generation
# Xcode from the App Store, then:
sudo xcode-select -s /Applications/Xcode.app

# 4. Point the build at the SDK (either works; local.properties is gitignored)
echo "sdk.dir=$(brew --prefix)/share/android-commandlinetools" > flip-trainer/local.properties
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 5. The MediaPipe model — not committed, and the app crashes without it
cd flip-trainer/android-sensor/app/src/main/assets
curl -LO https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task
cd -

# 6. Check everything at once
cd flip-trainer && bash scripts/doctor.sh
```

`doctor.sh` prints a ✓/✗ for each dependency with the exact fix command, reads the model and
Android release off an attached Galaxy (the three values `DEVICE_NOTES.md` wants), and ends with
the ordered next steps. Run it first in every local session.

## Starting the session

```bash
cd bee-api-experiments/flip-trainer
claude
```

Start it **inside `flip-trainer/`**, not at the repository root. The root `CLAUDE.md` describes
beelake — a completely different project that shares the repository — and a session started there
loads the wrong context. `flip-trainer/CLAUDE.md` exists to override it, but starting in the
subdirectory is cleaner.

Opening prompt that matches the operating protocol in `AGENTS.md`:

> Read AGENTS.md, docs/STATUS.md, docs/DECISIONS.md and docs/DEVICE_NOTES.md. Run
> `bash scripts/doctor.sh` and `./gradlew verify`. The Flip5 and the iPhone are attached over USB.
> Start with Session 0: record the real device values in DEVICE_NOTES.md. Then get
> `:android-sensor:app:assembleDebug` compiling — it has never been through a compiler.

## Expect these three things to go wrong first

Not pessimism — these are the known-unverified parts, and knowing the order saves a day:

1. **`:android-sensor:app` has never been compiled.** CameraX, MediaPipe, Room and the BLE server
   were written without a compiler ever seeing them. Expect import and API-signature errors.
   Nothing in `:android-sensor:core` or `:protocol` should need to change to fix them — if it
   does, that is a signal the abstraction boundary is wrong, not that core needs an Android type.
2. **`ios-app` has never been compiled either.** Same situation, plus SwiftData and strict
   concurrency, which are both fussy.
3. **Run `PairingVectorTests` before anything else on iOS.** The Swift crypto reimplements the
   Kotlin pairing transcript, HKDF and envelope MAC. If those vectors do not match, **nothing will
   ever pair**, and the on-device symptom gives you no hint where the mismatch is. The test turns
   that into a one-line failure. Fix the Swift side to match the vectors — the Kotlin side is the
   tested one.

## Then the part that actually matters

Everything above is plumbing. The real work is that **no exercise has been validated**, and the
system currently cannot honestly claim to count anything.

```bash
./gradlew :android-sensor:app:installDebug
adb shell am start -n com.fliptrainer.sensor/.MainActivity
adb logcat -s FlipTrainer:V
scrcpy    # the main-screen digitizer is dead; this is how you drive it
```

Then run `docs/protocols/curl-physical-test.md`. Have someone count out loud and **write the
number down before looking at the device** — reading the device first and confirming it is
anchoring, not ground truth. Fill in `docs/TEST_MATRIX.md`. Only then is Session 6's gate
answerable.

When a run disagrees with the device, pull the trace and replay it offline rather than guessing:

```bash
adb pull /sdcard/Android/data/com.fliptrainer.sensor/files/traces/<file> test-fixtures/pose-traces/
./gradlew :tools:trace-inspector:installDist
./tools/trace-inspector/build/install/trace-inspector/bin/trace-inspector --trace test-fixtures/pose-traces/<file>
```

Resist tuning a threshold to make one run pass. That is how a system becomes fitted to one person
in one room. The procedure is in the protocol document.

## Two ADB commands you will need because the screen is broken

```bash
adb shell am start -a com.fliptrainer.action.SHOW_PAIRING   # force the pairing QR back up
adb shell am broadcast -a com.fliptrainer.action.UNPAIR     # forget the controller
```

The unpair receiver is exported and unauthenticated by design, because a phone with a dead
digitizer has no other recovery path. That trade-off is written up in `RecoveryReceiver`'s class
documentation and needs revisiting before this is anything but a personal project.

## Optional: give Flip Trainer its own repository

It currently lives inside `bee-api-experiments`, which is otherwise a Python Bee-wearable data
lake. They share nothing. Splitting it out with `git subtree split --prefix=flip-trainer` would
give it a clean root, its own CI, and remove the wrong-`CLAUDE.md` trap entirely. Worth doing
before the catalog expands — say so and it can be done in one step.
