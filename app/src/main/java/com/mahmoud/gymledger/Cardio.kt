package com.mahmoud.gymledger

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.util.UUID

const val CARDIO_SHEET_ID = 184253101L

@Entity(tableName = "cardio")
data class CardioSession(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: String,
    val activity: String = "Walking",
    val duration: String = "",
    val distance: String = "",
    val effort: String = "",
    val notes: String = "",
    val workoutId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "DRAFT",
    val destination: Int? = null,
    val error: String = ""
)

object CardioRules {
    val activities = listOf("Walking", "Running", "Cycling", "Elliptical", "Other")
    val efforts = listOf("Easy", "Moderate", "Hard", "Very hard")
    fun accepts(field: String, value: String): Boolean = when (field) {
        "activity" -> value in activities
        "duration", "distance" -> InputRules.accepts("weight", value)
        "effort" -> value.isEmpty() || value in efforts
        "notes" -> InputRules.accepts("notes", value)
        else -> false
    }
    fun ready(session: CardioSession): Boolean =
        accepts("activity", session.activity) && accepts("duration", session.duration) &&
            (session.duration.toDoubleOrNull() ?: 0.0) > 0 && accepts("distance", session.distance) &&
            accepts("effort", session.effort) && accepts("notes", session.notes)
}

data class RemoteCardio(val title: String, val rowCount: Int, val cells: Map<Pair<Int, Int>, GridCell>)

object CardioCodec {
    val headers = listOf("Session ID", "Date", "Activity", "Duration (min)", "Distance (km)", "Effort", "Notes", "Strength session ID")
    fun validate(sheet: RemoteCardio) {
        check(headers.indices.all { sheet.cells[1 to it]?.value == headers[it] }) {
            "Cardio headers changed. Restore the eight original headers before retrying. Your cardio is saved locally."
        }
    }
    fun rowEmpty(sheet: RemoteCardio, row: Int) = headers.indices.all { sheet.cells[row to it]?.occupied != true }
    fun firstEmptyRow(sheet: RemoteCardio, reserved: Set<Int>) = (2..sheet.rowCount).firstOrNull { it !in reserved && rowEmpty(sheet, it) }
    fun uploadCells(session: CardioSession, row: Int): List<WriteCell> {
        require(row >= 2)
        require(CardioRules.ready(session)) { "Review cardio activity, duration, distance, and effort before syncing." }
        val values = listOf(session.id, LocalDate.parse(session.date).toString(), session.activity,
            session.duration.toDouble(), session.distance.toDoubleOrNull() ?: "", session.effort, session.notes, session.workoutId)
        return values.mapIndexed { col, value -> WriteCell(row, col, value) }
    }
    fun matches(sheet: RemoteCardio, expected: List<WriteCell>) = expected.all { cell ->
        val actual = sheet.cells[cell.row to cell.column]?.value ?: ""
        if (actual is Number && cell.value is Number) actual.toDouble() == cell.value.toDouble() else actual.toString() == cell.value.toString()
    }
    fun history(sheet: RemoteCardio): List<CardioSession> {
        validate(sheet)
        return (2..sheet.rowCount).mapNotNull { row ->
            fun text(col: Int): String {
                val value = sheet.cells[row to col]?.value
                return if (value is Number && value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value?.toString().orEmpty()
            }
            if (rowEmpty(sheet, row)) return@mapNotNull null
            val date = SheetCodec.date(sheet.cells[row to 1]?.value) ?: return@mapNotNull null
            CardioSession(id = "cardio:$CARDIO_SHEET_ID:$row", date = date.toString(), activity = text(2),
                duration = text(3), distance = text(4), effort = text(5), notes = text(6), workoutId = text(7),
                createdAt = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                status = "IMPORTED", destination = row)
        }
    }
}

interface CardioAccess {
    suspend fun readCardio(): RemoteCardio
    suspend fun writeCardio(title: String, cells: List<WriteCell>)
}
interface CardioStore {
    suspend fun queuedCardio(): List<CardioSession>
    suspend fun cardioReservations(): Set<Int>
    suspend fun saveCardio(session: CardioSession)
}

class CardioSyncEngine(private val store: CardioStore, private val sheets: CardioAccess, private val allowed: () -> Boolean) {
    // Called inside GymViewModel's sync mutex, shared with strength uploads.
    suspend fun upload() {
        for (original in store.queuedCardio()) {
            if (!allowed()) break
            var session = original
            try {
                val remote = sheets.readCardio()
                CardioCodec.validate(remote)
                if (session.destination == null) {
                    val row = CardioCodec.firstEmptyRow(remote, store.cardioReservations())
                        ?: error("The Cardio tab is full. Cardio remains saved locally; add empty rows to that tab and retry.")
                    session = session.copy(destination = row, error = "")
                    store.saveCardio(session)
                }
                val row = requireNotNull(session.destination)
                check(row in 2..remote.rowCount) { "The reserved Cardio row is missing. Restore the row before retrying." }
                val expected = CardioCodec.uploadCells(session, row)
                if (!CardioCodec.matches(remote, expected)) {
                    check(CardioCodec.rowEmpty(remote, row)) { "The reserved Cardio cells changed. Upload paused; your local cardio is safe. Restore the cells before retrying." }
                    if (!allowed()) break
                    sheets.writeCardio(remote.title, expected)
                    if (!allowed()) break
                    check(CardioCodec.matches(sheets.readCardio(), expected)) { "Cardio upload could not be verified. Retry on Wi-Fi to check the same row." }
                }
                store.saveCardio(session.copy(status = "SYNCED", error = ""))
            } catch (e: CancellationException) { throw e }
            catch (e: AuthRequired) { throw e }
            catch (e: Exception) { store.saveCardio(session.copy(error = e.message ?: "Cardio sync failed. Retry on Wi-Fi.")) }
        }
    }
}
