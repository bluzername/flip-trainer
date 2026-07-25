# Model assets

The MediaPipe Pose Landmarker bundle is **not committed** — it is a large binary with its own
license, and pinning it in git would make every model experiment a repository-sized change.

Download it once into this directory before building:

```bash
cd android-sensor/app/src/main/assets
curl -LO https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task
```

Variants, fastest to most accurate: `pose_landmarker_lite`, `pose_landmarker_full`,
`pose_landmarker_heavy`. Start with `lite` and only move up if Session 3's measured accuracy is
insufficient — then record the measurement and the change in `docs/DECISIONS.md`, and re-record
every trace fixture, because goldens are bound to the model version that produced them (ADR-0004).

The filename is referenced by `MediaPipePoseEstimator.DEFAULT_MODEL`. Whichever variant you
install, record its exact version in `docs/DEVICE_NOTES.md` and in the trace header
(`poseModelVersion`) — a trace without a model version is not a usable fixture.
