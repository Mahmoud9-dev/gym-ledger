package com.mahmoud.gymledger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SheetAccess {
    suspend fun read(): List<RemoteSheet>
    suspend fun write(title: String, cells: List<WriteCell>)
}
interface SyncStore {
    suspend fun queued(): List<Workout>
    suspend fun entries(id: String): List<SetEntry>
    suspend fun reservations(sheetId: Long): Set<Int>
    suspend fun save(workout: Workout)
}

class SyncEngine(private val store: SyncStore, private val sheets: SheetAccess, private val allowed: () -> Boolean) {
    private val lock = Mutex()

    suspend fun upload() = lock.withLock {
        for (original in store.queued()) {
            if (!allowed()) break
            var workout = original
            try {
                val remote = sheets.read().singleOrNull { it.routine.sheetId == workout.sheetId }
                    ?: error("The workout tab is missing. Restore it in the sheet to sync this workout.")
                val routine = Json.routine(workout.routineJson)
                check(SheetCodec.sameMapping(routine, remote.routine)) { "The sheet routine changed. Restore its exercise/set layout before retrying this workout." }
                if (workout.destination == null) {
                    val start = SheetCodec.firstEmptyBlock(remote, store.reservations(workout.sheetId))
                        ?: error("The sheet's existing logging blocks are full. Your workout is saved locally.")
                    workout = workout.copy(destination = start, error = "")
                    store.save(workout)
                }
                val start = requireNotNull(workout.destination)
                val expected = SheetCodec.uploadCells(routine, workout.date, store.entries(workout.id), start)
                if (!SheetCodec.matches(remote, expected)) {
                    check(SheetCodec.blockEmpty(remote, start)) { "The reserved sheet cells changed. Upload paused; your local workout is safe. Restore the cells before retrying." }
                    if (!allowed()) break
                    sheets.write(remote.routine.title, expected)
                    if (!allowed()) break
                    val verified = sheets.read().single { it.routine.sheetId == workout.sheetId }
                    check(SheetCodec.matches(verified, expected)) { "Upload could not be verified. Retry on Wi-Fi; the same cells will be checked first." }
                }
                store.save(workout.copy(status = "SYNCED", error = ""))
            } catch (e: CancellationException) { throw e }
            catch (e: AuthRequired) { throw e }
            catch (e: Exception) {
                store.save(workout.copy(error = e.message ?: "Sync failed. Retry on Wi-Fi."))
            }
        }
    }
}
