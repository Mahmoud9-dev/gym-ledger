package com.mahmoud.gymledger

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GymDao {
    @Query("SELECT * FROM cardio ORDER BY createdAt DESC") fun observeCardio(): Flow<List<CardioSession>>
    @Query("SELECT * FROM cardio WHERE id = :id") suspend fun cardio(id: String): CardioSession?
    @Query("SELECT * FROM cardio WHERE status = 'PENDING' ORDER BY createdAt") suspend fun queuedCardio(): List<CardioSession>
    @Query("SELECT * FROM cardio WHERE destination IS NOT NULL") suspend fun assignedCardio(): List<CardioSession>
    @Query("SELECT * FROM cardio WHERE status = 'IMPORTED'") suspend fun importedCardio(): List<CardioSession>
    @Query("DELETE FROM cardio WHERE id = :id AND status = 'IMPORTED'") suspend fun deleteCardioImport(id: String)
    @Upsert suspend fun putCardio(session: CardioSession)
    @Query("""UPDATE cardio SET
        activity = CASE WHEN :field = 'activity' THEN :value ELSE activity END,
        duration = CASE WHEN :field = 'duration' THEN :value ELSE duration END,
        distance = CASE WHEN :field = 'distance' THEN :value ELSE distance END,
        effort = CASE WHEN :field = 'effort' THEN :value ELSE effort END,
        notes = CASE WHEN :field = 'notes' THEN :value ELSE notes END
        WHERE id = :id AND status = 'DRAFT'""")
    suspend fun editCardio(id: String, field: String, value: String)

    @Query("SELECT * FROM routines ORDER BY orderIndex") fun observeRoutines(): Flow<List<CachedRoutine>>
    @Query("SELECT * FROM workouts ORDER BY createdAt DESC") fun observeWorkouts(): Flow<List<Workout>>
    @Query("SELECT * FROM entries ORDER BY row") fun observeEntries(): Flow<List<SetEntry>>
    @Query("SELECT * FROM workouts WHERE id = :id") suspend fun workout(id: String): Workout?
    @Query("SELECT * FROM routines") suspend fun routines(): List<CachedRoutine>
    @Query("SELECT * FROM workouts WHERE status = 'PENDING' ORDER BY createdAt") suspend fun queued(): List<Workout>
    @Query("SELECT * FROM entries WHERE sessionId = :id ORDER BY row") suspend fun entries(id: String): List<SetEntry>
    @Query("SELECT * FROM workouts WHERE sheetId = :id AND destination IS NOT NULL") suspend fun assigned(id: Long): List<Workout>
    @Upsert suspend fun putRoutine(routine: CachedRoutine)
    @Upsert suspend fun putWorkout(workout: Workout)
    @Upsert suspend fun putEntries(entries: List<SetEntry>)
    @Query("DELETE FROM workouts WHERE id = :id AND status = 'IMPORTED'") suspend fun deleteImport(id: String)
    @Query("SELECT * FROM workouts WHERE status = 'IMPORTED'") suspend fun imports(): List<Workout>

    @Query("""UPDATE entries SET
        reps = CASE WHEN :field = 'reps' THEN :value ELSE reps END,
        weight = CASE WHEN :field = 'weight' THEN :value ELSE weight END,
        effort = CASE WHEN :field = 'effort' THEN :value ELSE effort END,
        rest = CASE WHEN :field = 'rest' THEN :value ELSE rest END,
        notes = CASE WHEN :field = 'notes' THEN :value ELSE notes END
        WHERE sessionId = :id AND row = :row AND EXISTS (SELECT 1 FROM workouts WHERE id = :id AND status = 'DRAFT')""")
    suspend fun edit(id: String, row: Int, field: String, value: String)

    @Query("UPDATE entries SET done = :done WHERE sessionId = :id AND row = :row AND EXISTS (SELECT 1 FROM workouts WHERE id = :id AND status = 'DRAFT')")
    suspend fun markDone(id: String, row: Int, done: Boolean)
}

@Database(entities = [CachedRoutine::class, Workout::class, SetEntry::class, CardioSession::class], version = 2, exportSchema = true)
abstract class GymDatabase : RoomDatabase() {
    abstract fun dao(): GymDao
    companion object {
        fun open(context: Context, name: String = "gym-ledger.db") = Room.databaseBuilder(context, GymDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `cardio` (`id` TEXT NOT NULL, `date` TEXT NOT NULL, `activity` TEXT NOT NULL, `duration` TEXT NOT NULL, `distance` TEXT NOT NULL, `effort` TEXT NOT NULL, `notes` TEXT NOT NULL, `workoutId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `destination` INTEGER, `error` TEXT NOT NULL, PRIMARY KEY(`id`))""")
            }
        }
    }
}

class GymRepository(private val db: GymDatabase) : SyncStore, CardioStore {
    val dao = db.dao()
    override suspend fun queuedCardio() = dao.queuedCardio()
    override suspend fun cardioReservations() = dao.assignedCardio().mapNotNull { it.destination }.toSet()
    override suspend fun saveCardio(session: CardioSession) = dao.putCardio(session)
    suspend fun startCardio(date: String, workoutId: String = ""): String = db.withTransaction {
        if (workoutId.isNotEmpty()) requireNotNull(dao.workout(workoutId))
        val session = CardioSession(date = date, workoutId = workoutId)
        dao.putCardio(session)
        session.id
    }
    suspend fun finishCardio(id: String) = db.withTransaction {
        val session = requireNotNull(dao.cardio(id))
        check(session.status == "DRAFT")
        require(CardioRules.ready(session)) { "Choose an activity and enter a duration greater than zero." }
        dao.putCardio(session.copy(status = "PENDING", error = ""))
    }
    suspend fun refreshCardio(remote: RemoteCardio) = db.withTransaction {
        val localRows = dao.assignedCardio().filter { it.status != "IMPORTED" }.mapNotNull { it.destination }.toSet()
        val history = CardioCodec.history(remote).filter { it.destination !in localRows }
        val liveIds = history.map { it.id }.toSet()
        history.forEach { dao.putCardio(it) }
        dao.importedCardio().filter { it.id !in liveIds }.forEach { dao.deleteCardioImport(it.id) }
    }
    override suspend fun queued() = dao.queued()
    override suspend fun entries(id: String) = dao.entries(id)
    override suspend fun reservations(sheetId: Long) = dao.assigned(sheetId).mapNotNull { it.destination }.toSet()
    override suspend fun save(workout: Workout) = dao.putWorkout(workout)

    suspend fun seed(sheets: List<RemoteSheet>) = db.withTransaction {
        if (dao.routines().isEmpty()) sheets.forEachIndexed { i, sheet -> dao.putRoutine(CachedRoutine(sheet.routine.sheetId, Json.encode(sheet.routine), i)) }
    }

    suspend fun start(routine: Routine, date: String): String = db.withTransaction {
        val workout = Workout(sheetId = routine.sheetId, date = date, routineJson = Json.encode(routine))
        dao.putWorkout(workout)
        dao.putEntries(routine.exercises.flatMap { it.sets }.map { SetEntry(workout.id, it.row) })
        workout.id
    }

    suspend fun finish(id: String) = db.withTransaction {
        val workout = requireNotNull(dao.workout(id))
        check(workout.status == "DRAFT")
        val routine = Json.routine(workout.routineJson)
        require(dao.entries(id).all { InputRules.valid(it, routine) }) { "Review invalid set values before finishing." }
        dao.putWorkout(workout.copy(status = "PENDING", error = ""))
    }

    suspend fun refresh(sheets: List<RemoteSheet>) = db.withTransaction {
        val liveIds = mutableSetOf<String>()
        sheets.forEachIndexed { index, sheet ->
            dao.putRoutine(CachedRoutine(sheet.routine.sheetId, Json.encode(sheet.routine), index))
            val localBlocks = dao.assigned(sheet.routine.sheetId).filter { it.status != "IMPORTED" }.mapNotNull { it.destination }.toSet()
            for (history in SheetCodec.history(sheet)) {
                val id = "sheet:${sheet.routine.sheetId}:${history.block}"
                if (history.block in localBlocks) continue
                liveIds += id
                val created = SheetCodec.date(history.date)?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0
                dao.putWorkout(Workout(id, sheet.routine.sheetId, history.date, Json.encode(sheet.routine), created, "IMPORTED", history.block))
                dao.putEntries(history.entries.map { it.copy(sessionId = id) })
            }
        }
        dao.imports().filter { it.id !in liveIds }.forEach { dao.deleteImport(it.id) }
    }
}
