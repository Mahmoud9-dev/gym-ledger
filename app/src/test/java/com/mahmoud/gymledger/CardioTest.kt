package com.mahmoud.gymledger

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CardioTest {
    private val session = CardioSession(id = "cardio-1", date = "2026-10-21", activity = "Cycling", duration = "20.5", distance = "5.25", effort = "Hard", notes = "=literal", workoutId = "strength-1", status = "PENDING")
    private fun remote() = RemoteCardio("Cardio", 1001, CardioCodec.headers.mapIndexed { col, name -> (1 to col) to GridCell(name, true) }.toMap())
    private class Store(var session: CardioSession) : CardioStore {
        override suspend fun queuedCardio() = if (session.status == "PENDING") listOf(session) else emptyList()
        override suspend fun cardioReservations() = setOfNotNull(session.destination)
        override suspend fun saveCardio(session: CardioSession) { this.session = session }
    }
    private class Api(var sheet: RemoteCardio) : CardioAccess {
        var writes = 0
        var loseResponse = false
        override suspend fun readCardio() = sheet
        override suspend fun writeCardio(title: String, cells: List<WriteCell>) {
            writes++
            sheet = sheet.copy(cells = sheet.cells + cells.associate { (it.row to it.column) to GridCell(it.value, true) })
            if (loseResponse) throw java.io.IOException("Response lost")
        }
    }
    @Test fun durationRequiredDecimalsOptionalFieldsAndLiteralNotes() {
        assertTrue(CardioRules.ready(session))
        assertFalse(CardioRules.ready(session.copy(duration = "")))
        assertFalse(CardioRules.ready(session.copy(duration = "0")))
        assertFalse(CardioRules.ready(session.copy(distance = "-1")))
        assertFalse(CardioRules.ready(session.copy(activity = "Unknown")))
        assertTrue(CardioRules.ready(session.copy(distance = "", effort = "", notes = "")))
        val cells = CardioCodec.uploadCells(session, 2)
        assertEquals(20.5, cells[3].value)
        assertEquals(5.25, cells[4].value)
        assertEquals("=literal", cells[6].value)
        assertEquals("strength-1", cells[7].value)
        val written = remote().copy(cells = remote().cells + cells.associate { (it.row to it.column) to GridCell(it.value, true) })
        val imported = CardioCodec.history(written).single()
        assertEquals("20.5", imported.duration)
        assertEquals("=literal", imported.notes)
    }
    @Test fun responseLossRecoversSameRowWithoutDuplicate() = runTest {
        val store = Store(session); val api = Api(remote()).apply { loseResponse = true }
        val engine = CardioSyncEngine(store, api) { true }
        engine.upload()
        assertEquals("PENDING", store.session.status)
        assertEquals(2, store.session.destination)
        engine.upload()
        assertEquals("SYNCED", store.session.status)
        assertEquals(1, api.writes)
    }
    @Test fun conflictFullSheetAndChangedHeadersPreservePendingSession() = runTest {
        val store = Store(session.copy(destination = 2))
        val api = Api(remote().copy(cells = remote().cells + ((2 to 6) to GridCell("someone else", true))))
        val engine = CardioSyncEngine(store, api) { true }
        engine.upload()
        assertTrue(store.session.error.contains("changed"))
        assertEquals(0, api.writes)
        store.session = session.copy(destination = null)
        api.sheet = remote().copy(rowCount = 1)
        engine.upload()
        assertTrue(store.session.error.contains("full"))
        api.sheet = remote().copy(cells = emptyMap())
        engine.upload()
        assertTrue(store.session.error.contains("headers"))
        assertEquals("PENDING", store.session.status)
    }
    @Test fun offlineGateAndReservedRowsAreRespected() = runTest {
        val store = Store(session); val api = Api(remote())
        CardioSyncEngine(store, api) { false }.upload()
        assertNull(store.session.destination)
        assertEquals(0, api.writes)
        assertEquals(3, CardioCodec.firstEmptyRow(remote(), setOf(2)))
        assertFalse(CardioCodec.rowEmpty(remote().copy(cells = remote().cells + ((2 to 7) to GridCell("", true))), 2))
    }
    @Test fun sameDateSessionsHaveDistinctIdsAndRows() = runTest {
        val api = Api(remote()); val first = Store(session)
        CardioSyncEngine(first, api) { true }.upload()
        val second = Store(session.copy(id = "cardio-2"))
        CardioSyncEngine(second, api) { true }.upload()
        assertEquals(2, first.session.destination)
        assertEquals(3, second.session.destination)
        assertEquals(2, CardioCodec.history(api.sheet).size)
    }
}
