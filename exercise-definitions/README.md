# exercise-definitions

Versioned tuning data for each supported exercise. **Behaviour is typed Kotlin; numbers live
here** (plan §7.1) — so a threshold change is a reviewable data diff, and every row in
`docs/TEST_MATRIX.md` can name the definition version it was measured against.

`index.json` is the shipped catalog. A definition file that is not listed there is not loaded.
That makes "is this exercise live?" a one-line diff rather than a question about classpath
scanning.

These files are copied onto the classpath by `android-sensor/core/build.gradle.kts` and read
through `ExerciseCatalog`. There is exactly one copy — do not duplicate them into `resources/`.

## Changing a threshold

1. Bump `version` (semver). Golden event files are keyed to it.
2. Re-run `./gradlew :android-sensor:core:test`. Golden mismatches are the point — inspect the
   diff with `tools/trace-inspector` before regenerating.
3. Record the change and the reason in `docs/DECISIONS.md` if it was motivated by a measurement.
4. Add the new version's physical results as new rows in `docs/TEST_MATRIX.md`. Do not edit old
   rows: they were measured against the old numbers.

## Cue budget

At most two cues per exercise, enforced in `ExerciseDefinition`. Precision over recall: a cue that
cannot be made precise gets deleted, not loosened (plan §7.4).
