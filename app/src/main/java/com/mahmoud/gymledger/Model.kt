package com.mahmoud.gymledger

import com.google.gson.Gson
import java.util.UUID

object Json {
    private val gson = Gson()
    fun encode(value: Any): String = gson.toJson(value)
    fun routine(value: String): Routine = gson.fromJson(value, Routine::class.java)
}

data class SetSpec(val row: Int, val label: String)
data class Exercise(val name: String, val group: String, val tutorial: String, val sets: List<SetSpec>)
data class Routine(val sheetId: Long, val title: String, val exercises: List<Exercise>, val efforts: List<String>, val blocks: List<Int>)
data class GridCell(val value: Any? = "", val occupied: Boolean = false)
data class RemoteSheet(val routine: Routine, val cells: Map<Pair<Int, Int>, GridCell>)
data class WriteCell(val row: Int, val column: Int, val value: Any)
data class HistoricalWorkout(val block: Int, val date: String, val entries: List<SetEntry>)

@androidx.room.Entity(tableName = "routines")
data class CachedRoutine(@androidx.room.PrimaryKey val sheetId: Long, val json: String, val orderIndex: Int)

@androidx.room.Entity(tableName = "workouts")
data class Workout(
    @androidx.room.PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sheetId: Long,
    val date: String,
    val routineJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "DRAFT",
    val destination: Int? = null,
    val error: String = ""
)

@androidx.room.Entity(tableName = "entries", primaryKeys = ["sessionId", "row"],
    foreignKeys = [androidx.room.ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = androidx.room.ForeignKey.CASCADE)])
data class SetEntry(
    val sessionId: String,
    val row: Int,
    val reps: String = "",
    val weight: String = "",
    val effort: String = "",
    val rest: String = "",
    val notes: String = "",
    val done: Boolean = false
) {
    fun hasData() = listOf(reps, weight, effort, rest, notes).any { it.isNotBlank() }
}

object InputRules {
    fun accepts(field: String, value: String): Boolean = when (field) {
        "reps", "rest" -> value.isEmpty() || (value.length <= 6 && value.all(Char::isDigit) && value.toIntOrNull() != null)
        "weight" -> value.isEmpty() || (value.length <= 10 && value.matches(Regex("[0-9]+(\\.[0-9]*)?")) && value.toDoubleOrNull()?.isFinite() == true)
        "notes" -> value.length <= 5000
        "effort" -> value.length <= 100
        else -> false
    }
    fun valid(entry: SetEntry, routine: Routine): Boolean =
        accepts("reps", entry.reps) && accepts("weight", entry.weight) &&
        accepts("rest", entry.rest) && accepts("notes", entry.notes) &&
        (entry.effort.isBlank() || entry.effort in routine.efforts)
}

object UploadPolicy {
    fun allowed(foreground: Boolean, wifi: Boolean, validated: Boolean) = foreground && wifi && validated
}

class AuthRequired : java.io.IOException("Connect Google again to continue syncing. Your workouts are saved locally.")
