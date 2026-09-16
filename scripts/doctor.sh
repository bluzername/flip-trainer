#!/usr/bin/env bash
# Flip Trainer environment check.
#
# Reports what is present, what is missing, and the exact command to fix each gap. Run it as the
# first thing in a new local session — it turns Session 0 from a checklist into a diff.
#
#   bash scripts/doctor.sh
#
# Exit code is 0 if the JVM suite can run, 1 if even that is blocked. Missing Android/iOS
# toolchains are reported but do not fail the run: the JVM modules are useful without them.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

if [ -t 1 ]; then
  GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; DIM='\033[2m'; OFF='\033[0m'
else
  GREEN=''; RED=''; YELLOW=''; DIM=''; OFF=''
fi

BLOCKERS=0
ANDROID_READY=1
IOS_READY=1

ok()   { printf "  ${GREEN}✓${OFF} %s\n" "$1"; }
warn() { printf "  ${YELLOW}!${OFF} %s\n" "$1"; [ -n "${2:-}" ] && printf "      ${DIM}%s${OFF}\n" "$2"; }
bad()  { printf "  ${RED}✗${OFF} %s\n" "$1"; [ -n "${2:-}" ] && printf "      ${DIM}%s${OFF}\n" "$2"; }
head_() { printf "\n${DIM}%s${OFF}\n" "$1"; }

printf "Flip Trainer environment check\n"

# ---------------------------------------------------------------- host
head_ "Host"
UNAME=$(uname -s)
if [ "$UNAME" = "Darwin" ]; then
  ok "macOS $(sw_vers -productVersion 2>/dev/null) — both Android and iOS work here"
else
  warn "$UNAME — Android work is fine, iOS is impossible" \
       "Xcode is macOS-only. The iPhone half of this project cannot be built or run on $UNAME."
  IOS_READY=0
fi

# ---------------------------------------------------------------- jvm
head_ "JVM toolchain (required — this is what runs the 274 tests)"
if command -v java >/dev/null 2>&1; then
  # Filter "Picked up JAVA_TOOL_OPTIONS" / "_JAVA_OPTIONS" banners before parsing. They are
  # printed to stderr ahead of the version line on any machine with those variables set, and
  # naive `head -1` parsing reads the banner as the version.
  JAVA_RAW=$(java -version 2>&1 | grep -v '^Picked up' | grep -m1 'version')
  # Handles both `"17.0.9"` and legacy `"1.8.0_392"`.
  JAVA_VER=$(printf '%s' "$JAVA_RAW" | sed -n 's/.*version "\([0-9][0-9]*\)\.\([0-9][0-9]*\).*/\1 \2/p' | awk '{print ($1=="1") ? $2 : $1}')
  if [ "${JAVA_VER:-0}" -ge 17 ] 2>/dev/null; then
    ok "Java $JAVA_VER"
  else
    bad "Java ${JAVA_VER:-unknown} is too old or unparseable (need 17+)" \
        "brew install --cask temurin   [reported: ${JAVA_RAW:-no version line}]"
    BLOCKERS=$((BLOCKERS + 1))
  fi
else
  bad "java not found" "brew install --cask temurin"
  BLOCKERS=$((BLOCKERS + 1))
fi
if [ -x ./gradlew ]; then
  ok "Gradle wrapper present"
else
  bad "./gradlew missing"
  BLOCKERS=$((BLOCKERS + 1))
fi

# ---------------------------------------------------------------- android
head_ "Android (Galaxy Z Flip5)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
  SDK=$(grep -E '^sdk\.dir=' local.properties 2>/dev/null | cut -d= -f2-)
fi
if [ -n "$SDK" ] && [ -d "$SDK" ]; then
  ok "Android SDK at $SDK"
else
  bad "No Android SDK — :android-sensor:app is excluded from the build" \
      "brew install --cask android-commandlinetools && export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools"
  ANDROID_READY=0
fi

if command -v adb >/dev/null 2>&1; then
  DEVICES=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
  if [ -n "$DEVICES" ]; then
    for d in $DEVICES; do
      MODEL=$(adb -s "$d" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
      REL=$(adb -s "$d" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
      SDKV=$(adb -s "$d" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
      ok "Device $d — $MODEL, Android $REL (API $SDKV)"
      printf '      %brecord these three values in docs/DEVICE_NOTES.md%b\n' "$DIM" "$OFF"
    done
  else
    UNAUTH=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="unauthorized" {print $1}')
    if [ -n "$UNAUTH" ]; then
      bad "Galaxy connected but UNAUTHORIZED" "Accept the USB-debugging prompt on the phone. Its screen is broken — use a USB-C mouse."
    else
      warn "No Android device attached" "Connect the Flip5 over USB with USB debugging enabled."
    fi
    ANDROID_READY=0
  fi
else
  bad "adb not found" "Included with android-commandlinetools; add \$ANDROID_HOME/platform-tools to PATH"
  ANDROID_READY=0
fi

if command -v scrcpy >/dev/null 2>&1; then
  ok "scrcpy — mouse/keyboard control for the dead main screen"
else
  warn "scrcpy not found" "brew install scrcpy — you need this, the Flip5 digitizer does not work"
fi

MODEL_FILE=android-sensor/app/src/main/assets/pose_landmarker_lite.task
if [ -f "$MODEL_FILE" ]; then
  ok "MediaPipe model present ($(du -h "$MODEL_FILE" | cut -f1))"
else
  bad "MediaPipe model missing — the app will crash on startup without it" \
      "see android-sensor/app/src/main/assets/README.md for the download command"
  ANDROID_READY=0
fi

# ---------------------------------------------------------------- ios
head_ "iOS (iPhone controller)"
if [ "$IOS_READY" = "0" ]; then
  warn "Skipped — not macOS"
else
  if command -v xcodebuild >/dev/null 2>&1; then
    ok "Xcode $(xcodebuild -version 2>/dev/null | head -1 | awk '{print $2}')"
  else
    bad "xcodebuild not found" "Install Xcode from the App Store, then: sudo xcode-select -s /Applications/Xcode.app"
    IOS_READY=0
  fi

  if command -v xcodegen >/dev/null 2>&1; then
    ok "xcodegen — the .xcodeproj is generated, not committed (ADR-0006)"
  else
    bad "xcodegen not found" "brew install xcodegen"
    IOS_READY=0
  fi

  if [ -d ios-app/FlipTrainer.xcodeproj ]; then
    ok "ios-app/FlipTrainer.xcodeproj generated"
  else
    warn "Xcode project not generated yet" "cd ios-app && xcodegen generate"
  fi

  if command -v xcrun >/dev/null 2>&1; then
    IPHONES=$(xcrun devicectl list devices 2>/dev/null | grep -ci "iphone" || true)
    if [ "${IPHONES:-0}" -gt 0 ]; then
      ok "iPhone visible to devicectl"
    else
      warn "No iPhone attached" "Connect over USB, unlock it, and trust this Mac."
    fi
  fi
fi

# ---------------------------------------------------------------- verdict
head_ "Verdict"
if [ "$BLOCKERS" -gt 0 ]; then
  bad "The JVM suite cannot run. Fix the blockers above first."
  exit 1
fi

ok "JVM suite can run: ./gradlew verify  (expect 274 tests, 0 failures)"
if [ "$ANDROID_READY" = "1" ]; then
  ok "Android build should work: ./gradlew :android-sensor:app:assembleDebug"
else
  warn "Android build blocked - see above"
fi
if [ "$IOS_READY" = "1" ]; then
  ok "iOS build should work: cd ios-app && xcodegen generate && open FlipTrainer.xcodeproj"
else
  warn "iOS build blocked - see above"
fi

cat <<'NEXT'

Next, in this order (docs/STATUS.md has the detail):

  1. ./gradlew verify                     confirm the baseline is green
  2. get :android-sensor:app compiling    it has NEVER been through a compiler; budget time
  3. cd ios-app && xcodegen generate      then run PairingVectorTests FIRST —
                                          if the cross-language vectors fail, nothing will pair
  4. docs/protocols/curl-physical-test.md run it, fill in docs/TEST_MATRIX.md
  5. only then judge Session 6/7's validation gate

NEXT
