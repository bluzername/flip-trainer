# STATUS.md

```
Current session:            Sessions 1-11 complete (software); 12-15 blocked on hardware
Current objective:          Hand off to a session with a Flip5 and an iPhone attached
Last known green revision:  HEAD (./gradlew verify passes)
Verification performed:     ./gradlew verify — 271 JVM tests, 0 failures, ktlint clean
Physical-device evidence:   NONE. No Flip5, no iPhone, no Android SDK, no Xcode.
Known failures:             none in the JVM suite
Blockers:                   Session 0 hardware prep has not been done (user-owned)
Next exact action:          Session 0, then the two physical protocols in docs/protocols/
```

## What is actually verified

`./gradlew verify` compiles, lints and runs **271 tests** across `:android-sensor:core`,
`:protocol` and `:tools:trace-inspector`. Those results are real. They cover:

| Layer | Verified |
| --- | --- |
| Coordinate transforms | All four rotations, mirroring, left/right relabelling, aspect correction |
| Measurement | Exact percentiles, frame-rate windows, pipeline statistics |
| Trace format | Round trip, versioning, rejection of corrupt/foreign/future traces |
| Readiness gate | All ten statuses, debounce, camera-shake vs athlete-motion discrimination |
| Geometry | Joint angles, normalization, abstention on low confidence |
| Rep cycle | Hysteresis, direction confirmation, partial rejection, dropout tolerance, determinism |
| Four exercises | 10-clean-reps, 60 s idle silence, half-rep rejection, framing abstention, cue precision pairs |
| Golden replay | Byte-identical event streams, one committed golden per exercise |
| Session lifecycle | Transition legality, persist-before-publish, crash recovery, idempotent append |
| Pairing crypto | RFC 5869 vectors, expiry, single use, wrong/expired/replayed proofs, secret never transmitted |
| Transport | Envelope authentication, replay counters, reflection, framing, hostile length |
| Replay engine | At-least-once with idempotence, contiguous acknowledgement, batching |

## What is written but never compiled

| Component | Why |
| --- | --- |
| `android-sensor/app` — CameraX, MediaPipe, Room, BLE GATT, Compose | No Android SDK; `dl.google.com` unreachable |
| `ios-app` — SwiftUI, CoreBluetooth, SwiftData | No Xcode, no `swift`; macOS-only |

Neither has been through a compiler. Expect build errors on first open. This is recorded in
`DEVICE_NOTES.md` and is not a claim that the code is broken — it is a statement that it is
unverified, which is different and worth keeping distinct.

## What is not true yet

Stated plainly, because these are the things a reader would otherwise assume:

- **No exercise is validated.** Every threshold in `exercise-definitions/` is an engineering guess.
  Each definition's `knownLimitations` begins with `EXPERIMENTAL` for that reason.
- **No cue has been measured against human judgement.** Plan §11.2 requires ≥ 90 % precision or the
  cue is deleted; none has been measured once.
- **No performance number exists.** No frame rate, latency, thermal or battery figure has been
  taken from hardware. Every such field in `DEVICE_NOTES.md` reads NOT MEASURED.
- **BLE has never carried a byte.** The protocol logic is tested; the radio path is not.
- **`TEST_MATRIX.md` is empty**, and should stay empty until a human fills it in.

## Session-by-session

| Session | State | Gate |
| --- | --- | --- |
| 0 · Hardware prep | **Not done** — user-owned | — |
| 1 · Repository foundation | Software done | APK/IPA install on device: **BLOCKED** |
| 2 · Single-camera capture | Core done, shell written | 10-min soak on device: **BLOCKED** |
| 3 · Pose inference | Boundary + traces done, MediaPipe written | FPS/latency on device: **BLOCKED** |
| 4 · Readiness gate | **Done and tested** | — |
| 5 · Deterministic framework | **Done and tested** | — |
| 6 · Curl vertical slice | Logic done, golden-pinned | Human validation: **OPEN** |
| 7 · Three more exercises | Logic done, golden-pinned | Human validation: **OPEN** |
| 8 · Durable appliance | Core done, Room written | On-device recovery: **BLOCKED** |
| 9 · Protocol + pairing | **Done and tested**; GATT server written | Over-the-air: **BLOCKED** |
| 10 · iPhone companion | Written, never compiled | **BLOCKED** |
| 11 · Local history | Written, never compiled | **BLOCKED** |
| 12 · Placement usability | Not started | Needs a person and a tripod |
| 13 · Catalog expansion | Not started | Gated on Session 7 |
| 14 · Hardening | Not started | Needs a 60-minute device run |
| 15 · Cloud | **Not authorized** | Needs explicit user request |

See `DEFERRED_SESSIONS.md` for what each of 12–15 needs, and what analysis is already done.

## Next exact action

1. **Session 0.** Factory-reset the Flip5, enable USB debugging, verify `adb` and `scrcpy`, record
   the real model and OS versions in `DEVICE_NOTES.md`.
2. Install the Android SDK, download the MediaPipe model per
   `android-sensor/app/src/main/assets/README.md`, and get `:android-sensor:app:assembleDebug`
   compiling. It has never been through a compiler; budget time for that.
3. `cd ios-app && xcodegen generate`, open in Xcode, and get it building. Run
   `PairingVectorTests` first — if the cross-language vectors fail, fix that before anything else,
   because nothing will pair until they pass.
4. Run `docs/protocols/curl-physical-test.md`. Fill in `TEST_MATRIX.md`.
5. Only then decide whether Session 7's gate is met.
