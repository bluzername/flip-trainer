# EXERCISE_VALIDATION.md

Per-exercise support status. An exercise is **not** "supported" until every item in the plan's
§13.3 checklist is satisfied — including physical test results, which no exercise has yet.

## Checklist (plan §13.3)

1. Stable id and version · 2. User-facing name and instructions · 3. Preferred camera view ·
4. Required landmarks · 5. Readiness rules · 6. Phase state machine · 7. Rep-completion rule ·
8. Tempo and range metric · 9. Abstention rules · 10. Trace fixtures · 11. Golden expected events ·
12. **Physical test results** · 13. Known limitations · 14. Versioned thresholds

## Status

Legend: ✅ done · ⬜ not done · 🚫 blocked on hardware

| Exercise | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | Supported? |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `standing_dumbbell_curl` | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 🚫 | ⬜ | ⬜ | **No** |
| `goblet_squat` | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 🚫 | ⬜ | ⬜ | **No** |
| `dumbbell_rdl` | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 🚫 | ⬜ | ⬜ | **No** |
| `standing_dumbbell_shoulder_press` | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 🚫 | ⬜ | ⬜ | **No** |

Item 10 (trace fixtures) is marked done only for **recorded** traces from a real athlete.
Synthetic traces prove determinism, not real-world behavior, and never satisfy item 10 or 12.
