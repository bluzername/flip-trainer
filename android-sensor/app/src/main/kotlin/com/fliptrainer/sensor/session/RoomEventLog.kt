package com.fliptrainer.sensor.session

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.fliptrainer.core.session.EventPayload
import com.fliptrainer.core.session.WorkoutEvent
import com.fliptrainer.core.session.WorkoutEventLog

/**
 * Durable storage for the append-only event log.
 *
 * The payload is stored as the same JSON the BLE protocol and the golden files use, rather than
 * exploded into columns. That is deliberate: the payload schema is versioned and will grow, and a
 * column-per-field table turns every new event field into a database migration. Querying happens
 * on `sessionId` and `sequenceNumber`, which are columns.
 */
@Entity(
    tableName = "workout_event",
    primaryKeys = ["sessionId", "sequenceNumber"],
    indices = [Index(value = ["sessionId", "type"])],
)
data class WorkoutEventRow(
    val sessionId: String,
    val sequenceNumber: Long,
    @ColumnInfo(name = "monotonicTimestampMs") val monotonicTimestampMs: Long,
    /** Discriminator, duplicated out of the payload so terminal events can be found by query. */
    val type: String,
    val payloadJson: String,
) {
    fun toEvent(): WorkoutEvent =
        WorkoutEvent(
            sessionId = sessionId,
            sequenceNumber = sequenceNumber,
            monotonicTimestampMs = monotonicTimestampMs,
            payload = WorkoutEvent.json.decodeFromString(EventPayload.serializer(), payloadJson),
        )

    companion object {
        fun of(event: WorkoutEvent): WorkoutEventRow =
            WorkoutEventRow(
                sessionId = event.sessionId,
                sequenceNumber = event.sequenceNumber,
                monotonicTimestampMs = event.monotonicTimestampMs,
                type = event.payload.discriminator(),
                payloadJson = WorkoutEvent.json.encodeToString(EventPayload.serializer(), event.payload),
            )

        private fun EventPayload.discriminator(): String =
            this::class.simpleName?.replaceFirstChar { it.lowercase() } ?: "unknown"
    }
}

@Entity(tableName = "session_row")
data class SessionRow(
    @PrimaryKey val sessionId: String,
    val exerciseId: String,
    val exerciseDefinitionVersion: String,
    val setNumber: Int,
    val targetReps: Int,
    val dumbbellWeight: Double,
    val weightUnit: String,
    val createdAtEpochMs: Long,
    val appVersion: String,
    val protocolVersion: Int,
    val poseModelVersion: String,
    val deviceId: String,
    /** Highest sequence the paired controller has confirmed persisting. -1 when none. */
    val acknowledgedThrough: Long = -1,
)

@Entity(tableName = "device_diagnostic")
data class DeviceDiagnosticRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String?,
    val monotonicTimestampMs: Long,
    val code: String,
    val detail: String,
)

@Dao
interface WorkoutEventDao {
    /**
     * IGNORE, not REPLACE. `sessionId + sequenceNumber` is the event identity, so a second write
     * of the same key is a duplicate delivery and must be dropped — replacing it would let a
     * replayed event overwrite the original with a different timestamp.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(row: WorkoutEventRow): Long

    @Query("SELECT * FROM workout_event WHERE sessionId = :sessionId ORDER BY sequenceNumber ASC")
    fun all(sessionId: String): List<WorkoutEventRow>

    @Query(
        "SELECT * FROM workout_event WHERE sessionId = :sessionId AND sequenceNumber > :after " +
            "ORDER BY sequenceNumber ASC LIMIT :limit",
    )
    fun after(
        sessionId: String,
        after: Long,
        limit: Int,
    ): List<WorkoutEventRow>

    @Query("SELECT MAX(sequenceNumber) FROM workout_event WHERE sessionId = :sessionId")
    fun highestSequence(sessionId: String): Long?

    @Query("SELECT DISTINCT sessionId FROM workout_event")
    fun sessionIds(): List<String>

    @Query(
        "SELECT DISTINCT sessionId FROM workout_event WHERE sessionId NOT IN " +
            "(SELECT sessionId FROM workout_event WHERE type IN ('setCompleted', 'setAborted'))",
    )
    fun openSessions(): List<String>
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: SessionRow)

    @Query("SELECT * FROM session_row WHERE sessionId = :sessionId")
    fun find(sessionId: String): SessionRow?

    @Query("UPDATE session_row SET acknowledgedThrough = :upTo WHERE sessionId = :sessionId AND acknowledgedThrough < :upTo")
    fun acknowledge(
        sessionId: String,
        upTo: Long,
    )

    @Query("SELECT acknowledgedThrough FROM session_row WHERE sessionId = :sessionId")
    fun acknowledgedThrough(sessionId: String): Long?
}

@Dao
interface DiagnosticDao {
    @Insert
    fun insert(row: DeviceDiagnosticRow)

    @Query("SELECT * FROM device_diagnostic ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): List<DeviceDiagnosticRow>
}

@Database(
    entities = [WorkoutEventRow::class, SessionRow::class, DeviceDiagnosticRow::class],
    version = 1,
    exportSchema = true,
)
abstract class FlipTrainerDatabase : RoomDatabase() {
    abstract fun events(): WorkoutEventDao

    abstract fun sessions(): SessionDao

    abstract fun diagnostics(): DiagnosticDao
}

/**
 * [WorkoutEventLog] over Room.
 *
 * The semantics are defined and tested by `InMemoryWorkoutEventLog` in core; this adapter exists
 * only to put them on disk. If the two ever disagree, core is right.
 */
class RoomWorkoutEventLog(
    private val database: FlipTrainerDatabase,
) : WorkoutEventLog {
    override fun append(event: WorkoutEvent): Boolean = database.events().insert(WorkoutEventRow.of(event)) != -1L

    override fun eventsAfter(
        sessionId: String,
        after: Long,
        limit: Int,
    ): List<WorkoutEvent> = database.events().after(sessionId, after, limit).map { it.toEvent() }

    override fun allEvents(sessionId: String): List<WorkoutEvent> = database.events().all(sessionId).map { it.toEvent() }

    override fun highestSequence(sessionId: String): Long? = database.events().highestSequence(sessionId)

    override fun acknowledge(
        sessionId: String,
        upTo: Long,
    ) {
        // The SQL itself refuses to go backwards, so a stale ack from a reconnecting client is a
        // no-op rather than a rewind.
        database.sessions().acknowledge(sessionId, upTo)
    }

    override fun highestAcknowledged(sessionId: String): Long = database.sessions().acknowledgedThrough(sessionId) ?: -1L

    override fun openSessions(): List<String> = database.events().openSessions()

    override fun sessionIds(): List<String> = database.events().sessionIds()
}
