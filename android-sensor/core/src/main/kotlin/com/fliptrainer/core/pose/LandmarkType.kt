package com.fliptrainer.core.pose

/**
 * The 33 landmarks produced by MediaPipe Pose Landmarker, in model index order.
 *
 * Index order is part of the wire and trace format — do not reorder. Adding a landmark means a
 * new pose model, which means a new trace schema version.
 */
enum class LandmarkType(
    val index: Int,
) {
    NOSE(0),
    LEFT_EYE_INNER(1),
    LEFT_EYE(2),
    LEFT_EYE_OUTER(3),
    RIGHT_EYE_INNER(4),
    RIGHT_EYE(5),
    RIGHT_EYE_OUTER(6),
    LEFT_EAR(7),
    RIGHT_EAR(8),
    MOUTH_LEFT(9),
    MOUTH_RIGHT(10),
    LEFT_SHOULDER(11),
    RIGHT_SHOULDER(12),
    LEFT_ELBOW(13),
    RIGHT_ELBOW(14),
    LEFT_WRIST(15),
    RIGHT_WRIST(16),
    LEFT_PINKY(17),
    RIGHT_PINKY(18),
    LEFT_INDEX(19),
    RIGHT_INDEX(20),
    LEFT_THUMB(21),
    RIGHT_THUMB(22),
    LEFT_HIP(23),
    RIGHT_HIP(24),
    LEFT_KNEE(25),
    RIGHT_KNEE(26),
    LEFT_ANKLE(27),
    RIGHT_ANKLE(28),
    LEFT_HEEL(29),
    RIGHT_HEEL(30),
    LEFT_FOOT_INDEX(31),
    RIGHT_FOOT_INDEX(32),
    ;

    /** Which side of the body this landmark belongs to. */
    val side: Side
        get() =
            when {
                name.startsWith("LEFT_") -> Side.LEFT
                name.startsWith("RIGHT_") -> Side.RIGHT
                else -> Side.CENTER
            }

    /**
     * The mirror-image landmark: `LEFT_WRIST` ↔ `RIGHT_WRIST`, centre landmarks map to themselves.
     *
     * Needed when inference runs on a horizontally flipped image: the model labels sides from what
     * the image looks like, so the labels have to be swapped back to recover anatomical sides.
     */
    val opposite: LandmarkType
        get() = OPPOSITES.getValue(this)

    companion object {
        private val BY_INDEX: Map<Int, LandmarkType> = entries.associateBy { it.index }

        private val OPPOSITES: Map<LandmarkType, LandmarkType> =
            entries.associateWith { type ->
                when (type.side) {
                    Side.LEFT -> valueOf("RIGHT_" + type.name.removePrefix("LEFT_"))
                    Side.RIGHT -> valueOf("LEFT_" + type.name.removePrefix("RIGHT_"))
                    Side.CENTER -> type
                }
            }

        fun fromIndex(index: Int): LandmarkType? = BY_INDEX[index]

        /** Shoulders, elbows, wrists, hips, knees, ankles — everything an evaluator reasons about. */
        val MAJOR_JOINTS: Set<LandmarkType> =
            setOf(
                LEFT_SHOULDER,
                RIGHT_SHOULDER,
                LEFT_ELBOW,
                RIGHT_ELBOW,
                LEFT_WRIST,
                RIGHT_WRIST,
                LEFT_HIP,
                RIGHT_HIP,
                LEFT_KNEE,
                RIGHT_KNEE,
                LEFT_ANKLE,
                RIGHT_ANKLE,
            )
    }
}

enum class Side {
    LEFT,
    RIGHT,
    CENTER,
    ;

    val opposite: Side
        get() =
            when (this) {
                LEFT -> RIGHT
                RIGHT -> LEFT
                CENTER -> CENTER
            }
}

/**
 * Which limb(s) an exercise is evaluated on.
 *
 * `BILATERAL` means both sides move together and both must be visible. `LEFT`/`RIGHT` mean a
 * unilateral set. `EITHER` means the evaluator picks whichever side is better tracked.
 */
enum class Laterality {
    BILATERAL,
    LEFT,
    RIGHT,
    EITHER,
    ;

    /** Sides that must be tracked for this laterality, given a resolved working side. */
    fun requiredSides(resolved: Side?): Set<Side> =
        when (this) {
            BILATERAL -> setOf(Side.LEFT, Side.RIGHT)
            LEFT -> setOf(Side.LEFT)
            RIGHT -> setOf(Side.RIGHT)
            EITHER -> resolved?.let { setOf(it) } ?: emptySet()
        }
}
