package com.mahmoud.gymledger

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncEngineTest {
    private fun remote() = SheetCodec.parse(JSONObject(javaClass.getResource("/workbook.json")!!.readText())).first()

    private class MemoryStore(var workout: Workout, val entries: List<SetEntry>) : SyncStore {
        override suspend fun queued() = if (workout.status == "PENDING") listOf(workout) else emptyList()
        override suspend fun entries(id: String) = entries
        override suspend fun reservations(sheetId: Long) = listOfNotNull(workout.destination).toSet()
        override suspend fun save(workout: Workout) { this.workout = workout }
    }
    private class FakeSheets(var sheet: RemoteSheet) : SheetAccess {
        var writes = 0
        var loseResponse = false
        var failBeforeWrite = false
        override suspend fun read() = listOf(sheet)
        override suspend fun write(title: String, cells: List<WriteCell>) {
            writes++
            if (failBeforeWrite) throw java.io.IOException("Offline")
            sheet = sheet.copy(cells = sheet.cells + cells.associate { (it.row to it.column) to GridCell(it.value, true) })
            if (loseResponse) throw java.io.IOException("Response lost")
        }
    }
    private fun store(r: RemoteSheet): MemoryStore {
        val w = Workout(id = "session", sheetId = r.routine.sheetId, date = "2026-10-20", routineJson = Json.encode(r.routine), status = "PENDING")
        return MemoryStore(w, listOf(SetEntry("session", 5, "8", "40")))
    }

    @Test fun successfulUploadPersistsDestinationAndAcknowledges() = runTest {
        val r = remote(); val store = store(r); val api = FakeSheets(r)
        SyncEngine(store, api) { true }.upload()
        assertEquals("SYNCED", store.workout.status)
        assertEquals(3, store.workout.destination)
        assertEquals(1, api.writes)
    }
    @Test fun lostResponseIsRecoveredWithoutSecondWrite() = runTest {
        val r = remote(); val store = store(r); val api = FakeSheets(r).apply { loseResponse = true }
        val engine = SyncEngine(store, api) { true }
        engine.upload()
        assertEquals("PENDING", store.workout.status)
        assertNotNull(store.workout.destination)
        engine.upload()
        assertEquals("SYNCED", store.workout.status)
        assertEquals(1, api.writes)
    }
    @Test fun cellConflictAndChangedMappingKeepLocalWorkout() = runTest {
        val r = remote(); val store = store(r)
        store.workout = store.workout.copy(destination = 3)
        val api = FakeSheets(r.copy(cells = r.cells + ((5 to 3) to GridCell(99, true))))
        SyncEngine(store, api) { true }.upload()
        assertEquals("PENDING", store.workout.status)
        assertTrue(store.workout.error.contains("changed"))
        assertEquals(0, api.writes)
        api.sheet = r.copy(routine = r.routine.copy(exercises = emptyList()))
        SyncEngine(store, api) { true }.upload()
        assertTrue(store.workout.error.contains("routine"))
    }
    @Test fun noWifiOrForegroundMeansNoNetworkRequests() = runTest {
        assertFalse(UploadPolicy.allowed(true, false, true))
        assertFalse(UploadPolicy.allowed(false, true, true))
        assertFalse(UploadPolicy.allowed(true, true, false))
        assertTrue(UploadPolicy.allowed(true, true, true))
        val r = remote(); val store = store(r); val api = FakeSheets(r)
        SyncEngine(store, api) { false }.upload()
        assertEquals(0, api.writes)
        assertNull(store.workout.destination)
    }
    @Test fun fullSheetAndAuthorizationFailuresPreservePendingData() = runTest {
        val r = remote(); val store = store(r)
        val full = r.copy(cells = r.cells + r.routine.blocks.associate { (1 to it) to GridCell(46315, true) })
        val api = FakeSheets(full)
        SyncEngine(store, api) { true }.upload()
        assertTrue(store.workout.error.contains("full"))
        assertEquals("PENDING", store.workout.status)
        val denied = object : SheetAccess {
            override suspend fun read(): List<RemoteSheet> = throw AuthRequired()
            override suspend fun write(title: String, cells: List<WriteCell>) { error("Must not write") }
        }
        try { SyncEngine(store, denied) { true }.upload(); fail("Expected authorization failure") }
        catch (_: AuthRequired) { assertEquals("PENDING", store.workout.status) }
    }

    @Test fun failureBeforeWriteReusesPersistedBlockAfterRestart() = runTest {
        val r = remote(); val store = store(r); val api = FakeSheets(r).apply { failBeforeWrite = true }
        SyncEngine(store, api) { true }.upload()
        assertEquals(3, store.workout.destination)
        assertEquals("PENDING", store.workout.status)
        api.failBeforeWrite = false
        SyncEngine(store, api) { true }.upload()
        assertEquals("SYNCED", store.workout.status)
        assertEquals(2, api.writes)
    }

    @Test fun missingTabAndUnverifiedResponseDoNotAcknowledgeUpload() = runTest {
        val r = remote(); val store = store(r)
        val missing = object : SheetAccess {
            override suspend fun read() = emptyList<RemoteSheet>()
            override suspend fun write(title: String, cells: List<WriteCell>) { error("Must not write") }
        }
        SyncEngine(store, missing) { true }.upload()
        assertTrue(store.workout.error.contains("missing"))
        val noPersistence = object : SheetAccess {
            override suspend fun read() = listOf(r)
            override suspend fun write(title: String, cells: List<WriteCell>) { }
        }
        SyncEngine(store, noPersistence) { true }.upload()
        assertTrue(store.workout.error.contains("verified"))
        assertEquals("PENDING", store.workout.status)
    }

    @Test fun leavingWifiDuringReadStopsBeforeWrite() = runTest {
        val r = remote(); val store = store(r)
        var allowed = true
        val api = object : SheetAccess {
            override suspend fun read(): List<RemoteSheet> { allowed = false; return listOf(r) }
            override suspend fun write(title: String, cells: List<WriteCell>) { error("Must not write after Wi-Fi is lost") }
        }
        SyncEngine(store, api) { allowed }.upload()
        assertEquals("PENDING", store.workout.status)
    }
}
