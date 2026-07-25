# DEVICE_NOTES.md

Real hardware observations only. Every value here must come from a device that was actually run.
Anything not yet measured says **NOT MEASURED** — never a plausible-looking guess.

---

## Galaxy Z Flip5 (sensor appliance)

| Property | Value |
| --- | --- |
| `ro.product.model` | NOT MEASURED — Session 0 |
| Android release | NOT MEASURED — Session 0 |
| SDK level | NOT MEASURED — Session 0 |
| Main-screen touch | **Nonfunctional.** Drives the whole touch-free requirement. |
| Cover screen | Present. Deliberately not a dependency (plan §6.6). |
| Rear cameras | Wide + ultrawide. Only the wide is used. |
| Selected logical camera ID | NOT MEASURED — Session 2 |
| Available analysis resolutions | NOT MEASURED — Session 2 |
| ADB authorized | NOT MEASURED — Session 0 |
| `scrcpy` verified | NOT MEASURED — Session 0 |
| Battery-optimization exemption | NOT MEASURED — Session 0 |

### Camera measurements (Session 2)

| Metric | Value |
| --- | --- |
| Camera start latency | NOT MEASURED |
| Delivered analysis FPS (p50) | NOT MEASURED |
| Frame dimensions / rotation | NOT MEASURED |
| Dropped frames over 10 min | NOT MEASURED |
| Memory trend over 10 min | NOT MEASURED |
| Stop/restart × 5 | NOT MEASURED |

### Pose measurements (Session 3)

| Metric | Value |
| --- | --- |
| Inference p50 / p95 | NOT MEASURED |
| Sustained analyzed FPS | NOT MEASURED (target ≥ 15) |
| Left/right landmark correctness after display transform | NOT MEASURED |
| 10-minute stability | NOT MEASURED |

### Thermal and endurance (Session 14)

| Metric | 30 min | 60 min |
| --- | --- | --- |
| Analyzed FPS trend | NOT MEASURED | NOT MEASURED |
| Inference p95 trend | NOT MEASURED | NOT MEASURED |
| Battery drain | NOT MEASURED | NOT MEASURED |
| Thermal warnings | NOT MEASURED | NOT MEASURED |
| Memory growth | NOT MEASURED | NOT MEASURED |

---

## iPhone (controller)

| Property | Value |
| --- | --- |
| Model | NOT MEASURED — Session 0 |
| iOS version | NOT MEASURED — Session 0 |
| Xcode version | NOT MEASURED — Session 0 |
| Local signing works | NOT MEASURED — Session 0 |
| Minimum deployment target | Provisionally iOS 17 (SwiftData). Confirm against the real device. |

---

## Development environment

The environment used for Sessions 1–11 of this implementation:

| Property | Value |
| --- | --- |
| OS | Linux (container), 4 cores, 16 GB RAM |
| JDK | OpenJDK 21 |
| Gradle | 8.14.3 (wrapper pinned) |
| Android SDK | **Absent.** `dl.google.com` unreachable, so AGP and platform jars cannot resolve. |
| `adb` / `scrcpy` | Absent |
| Xcode / `swift` / `xcodebuild` | Absent (macOS-only) |
| Galaxy Z Flip5 | Not attached |
| iPhone | Not attached |

Consequences, stated plainly:

- Pure JVM modules (`:android-sensor:core`, `:protocol`, `:tools:trace-inspector`) are **compiled,
  linted and unit-tested** here. Those results are real.
- `android-sensor/app` (CameraX, MediaPipe, Room, BLE GATT, Compose) is **written but never
  compiled**. It has not been type-checked by a compiler.
- `ios-app` (SwiftUI, CoreBluetooth, SwiftData) is **written but never compiled**.
- No frame rate, latency, thermal, battery, BLE-range or rep-accuracy number in this repository
  was measured on hardware. Every such field above reads NOT MEASURED and must stay that way
  until a human runs the device protocol.

### One prediction worth checking early

Not a measurement — an implication of the geometry, recorded so it can be confirmed or killed
quickly on the real device.

At the mid-point of a dumbbell shoulder press the hands are roughly **2.4 torso-lengths apart**
(shoulder half-width 0.42 + upper arm 0.62 + forearm 0.54, doubled). A 9:16 portrait frame is only
0.5625 frame-heights wide, so the athlete must stand back until the torso spans under about 0.22
of frame height — which is close to that exercise's readiness floor of 0.18.

If that holds on the Flip5, the shoulder press has the tightest framing budget in the catalog.
**Check landscape orientation before concluding anything about ultrawide** — a wider frame is the
cheap fix, and plan §13's ultrawide trigger requires a documented failure that placement and
orientation cannot solve. Run 0 and run 11 of `protocols/catalog-physical-test.md` cover this.
