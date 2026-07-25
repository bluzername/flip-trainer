# Sessions 12–15 — not started, and why

Sessions 1–11 produced code. Sessions 12–15 cannot, from here. This file records what each one
needs, so the next session can start rather than rediscover.

The reason is the same in every case and it is not a scheduling problem: **these sessions are
defined by measurements on hardware that is not attached.** Session 12 measures how long a person
takes to place a phone. Session 13 requires three people doing reps. Session 14 measures a 60-minute
thermal curve. Session 15 needs a decision the user has not made.

Writing plausible results for any of them would be the single most damaging thing this
implementation could do, because every later decision would rest on invented numbers.

---

## Session 12 — Make device placement usable

**Blocked on:** a Flip5 on a tripod and a person setting it up.

**Ready to go.** The minimal path (§12 Step 1) is implemented: the readiness gate produces
per-exercise placement instructions ("Move closer", "Show your feet", "Turn slightly sideways",
"Hold the camera still"), the iPhone renders them on the placement screen, and the Galaxy debug
screen is reachable over `scrcpy`.

**Step 2 is the actual work** and is pure measurement. For each of the four exercises, with the
user setting up without touching the broken main display, record:

| Exercise | Time to ready | Placement corrections | Most common failure | Did the athlete understand the view? |
| --- | --- | --- | --- | --- |
| curl | | | | |
| goblet squat | | | | |
| RDL | | | | |
| shoulder press | | | | |

Target: **median setup under 60 s** once the flow is learned.

**Do not build Step 3 (local-network preview) or Step 4 (ultrawide) before Step 2 is measured.**
Both are explicitly conditional in the plan, and one of them already has a hypothesis worth
testing first:

> The shoulder press is expected to be the hardest to frame. At mid-press the hands are ~2.4
> torso-lengths apart, so on a 9:16 portrait frame the athlete must stand back far enough that the
> torso approaches the readiness floor. **Landscape orientation is the cheap fix and must be ruled
> out before ultrawide is considered.** The framing survey is run 0 of
> `protocols/catalog-physical-test.md`.

---

## Session 13 — Expand from four exercises to dozens

**Blocked on:** Session 7's validation gate, which is open.

Expanding a catalog whose four existing exercises have never been validated would multiply an
unmeasured error rate by twelve. §13.1 is also explicit that the declarative format is extracted
*after* the four evaluators are stable — and "stable" means validated on people, not green on
synthetic traces.

**When it does start**, the four evaluators already show which patterns repeat, and the analysis
§13.1 asks for is largely done in code:

- **Shared and already extracted:** the whole `RepCycleDetector` (hysteresis, direction
  confirmation, minimum excursion, dropout tolerance, cooldown), progress mapping via `RangeSpec`,
  the `observeRangeShortfall` cue, `SideSelector` laterality resolution, and readiness presets.
- **Still per-exercise typed Kotlin:** which geometric feature drives progress, and the second cue.
  Two of the four second cues are symmetry comparisons (squat, press) and one is a
  calibrated-drift comparison (RDL) — three of four fit two shapes.
- **Candidate declarative shape:** `{feature: jointAngle(HIP,KNEE,ANKLE) | torsoInclination |
  verticalOffset(WRIST,SHOULDER), range: {rest, apex}, cues: [rangeShortfall(t) |
  lateralAsymmetry(t) | calibratedDrift(joint, t)]}`. That covers all four. **It is a hypothesis
  from four samples — do not build it until the four are validated**, or it will encode the wrong
  abstraction permanently.

The §13.4 object-detection gate stays shut: no documented failure yet requires equipment detection.

---

## Session 14 — Hardening and appliance behavior

**Blocked on:** a Flip5 that can run for an hour.

Every headline item is a measurement:

| Measurement | Status |
| --- | --- |
| Analyzed FPS over 30 and 60 minutes | NOT MEASURED |
| Inference p95 drift under thermal load | NOT MEASURED |
| Battery drain, charging and not | NOT MEASURED |
| Thermal warnings | NOT MEASURED |
| Memory growth | NOT MEASURED |

The instrumentation exists and is unit-tested (`PipelineStats`), so these are data-collection runs,
not development.

**Already done and testable without hardware** — the pairing hardening from §14 task 4 is
implemented and covered: secret expiry, one-time consumption, rejection of unknown controllers,
rejection of replayed envelopes, and authenticated unpair. What remains there is Keychain/Keystore
persistence behaviour across reboot (device-only) and the application-layer encryption decision,
which ADR-0007 deliberately defers to this session with the reasoning written down.

**The Device Owner decision belongs here and nowhere earlier.** The question to answer with
evidence, not preference: does manual arming plus a normal foreground service actually work for a
whole workout on a phone with a dead digitizer? If it does, Device Owner is not added. If it does
not, it needs its own ADR, an explanation of the factory-reset implications, and **explicit user
approval** before a line is written.

---

## Session 15 — Optional cloud phase

**Status: deferred and not authorized.** Not started, and it must not be started without the user
explicitly asking.

Entry criteria from the plan, honestly assessed:

| Criterion | Met? |
| --- | --- |
| Local Galaxy processing is reliable | **No** — never run on a Galaxy |
| BLE replay is reliable | Logic tested; **never run over a radio** |
| iPhone local history is reliable | **No** — never compiled |
| The exercise catalog has useful real-world validation | **No** |
| The user has explicitly requested cloud work | **No** |

Nothing in this repository contacts a network. There is no backend directory, no cloud SDK, no
account, and no telemetry — the plan forbids creating one before this session, and this session is
not open.
