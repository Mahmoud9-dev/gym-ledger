package com.mahmoud.gymledger

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SheetCodecTest {
    private fun sheets() = SheetCodec.parse(JSONObject(javaClass.getResource("/workbook.json")!!.readText()))

    @Test fun parsesAllFiveRealLayoutsAndEffortChoices() {
        val all = sheets()
        assertEquals(listOf(5, 6, 5, 5, 5), all.map { it.routine.exercises.size })
        assertEquals("Barbell Deadlift", all.last().routine.exercises.first().name)
        assertEquals(5, all.last().routine.exercises.first().sets.first().row)
        all.forEach {
            assertEquals(50, it.routine.blocks.size)
            assertTrue(it.routine.efforts.contains("9 (Hard)"))
            assertTrue(it.routine.exercises.all { e -> e.sets.size == 3 && e.tutorial.startsWith("https://") })
            assertEquals(3, SheetCodec.firstEmptyBlock(it, emptySet()))
        }
    }

    @Test fun handlesColumnNamesAndQuotedSheetNames() {
        assertEquals("D", SheetCodec.column(3))
        assertEquals("IS", SheetCodec.column(252))
        assertEquals("'O''Reilly'!D5", SheetCodec.range("O'Reilly", 3, 5))
    }

    @Test fun writesOnlyDateAndSetCellsAndRoundTripsHistory() {
        val remote = sheets()[2]
        val routine = remote.routine
        val entry = SetEntry("local", 5, "10", "22.5", "9 (Hard)", "90", "=literal note", true)
        val cells = SheetCodec.uploadCells(routine, "2026-10-20", listOf(entry), 3)
        assertEquals(76, cells.size)
        assertEquals("2026-10-20", cells.first().value)
        assertEquals("=literal note", cells.single { it.row == 5 && it.column == 7 }.value)
        assertTrue(cells.all { it.row == 1 || it.row in routine.exercises.flatMap { e -> e.sets.map { s -> s.row } } })
        val written = remote.copy(cells = remote.cells + cells.associate { (it.row to it.column) to GridCell(it.value, true) })
        val history = SheetCodec.history(written)
        assertEquals(1, history.size)
        assertEquals("2026-10-20", history.single().date)
        assertEquals("22.5", history.single().entries.first().weight)
        assertTrue(SheetCodec.matches(written, cells))
        assertFalse(SheetCodec.matches(remote, cells))
    }

    @Test fun allocationsRespectReservationsAndAnyOccupiedCell() {
        val r = sheets().first()
        val occupied = r.copy(cells = r.cells + ((40 to 3) to GridCell("", true)))
        assertEquals(8, SheetCodec.firstEmptyBlock(occupied, emptySet()))
        assertEquals(13, SheetCodec.firstEmptyBlock(occupied, setOf(8)))
        assertNull(SheetCodec.firstEmptyBlock(r, r.routine.blocks.toSet()))
    }

    @Test fun mappingDetectsChangedExerciseButAllowsTitleAndVideoChanges() {
        val r = sheets().first().routine
        assertTrue(SheetCodec.sameMapping(r, r.copy(title = "Renamed")))
        assertTrue(SheetCodec.sameMapping(r, r.copy(exercises = r.exercises.map { it.copy(tutorial = "https://example.com") })))
        assertFalse(SheetCodec.sameMapping(r, r.copy(exercises = r.exercises.map { it.copy(name = "Changed") })))
    }

    @Test fun validatesInputsAndDatesWithoutTreatingBlankAsZero() {
        assertTrue(InputRules.accepts("weight", "22.5"))
        assertTrue(InputRules.accepts("weight", ""))
        assertFalse(InputRules.accepts("weight", "-1"))
        assertFalse(InputRules.accepts("weight", "NaN"))
        assertFalse(InputRules.accepts("reps", "3.5"))
        assertFalse(InputRules.accepts("rest", "-1"))
        assertEquals(LocalDate.of(2026, 10, 20), SheetCodec.date(46315.0))
        assertNull(SheetCodec.date("invalid"))
    }

    @Test fun blankAndZeroValuesAreDifferentAndDatesRemainReadableWithoutFormattingChanges() {
        val r = sheets().last().routine
        val cells = SheetCodec.uploadCells(r, "2026-10-20", listOf(SetEntry("x", 5, "0", "0", rest = "0")), 8)
        assertEquals("2026-10-20", cells.first().value)
        assertEquals(0, cells.single { it.row == 5 && it.column == 8 }.value)
        assertEquals(0.0, cells.single { it.row == 5 && it.column == 9 }.value)
        assertEquals("", cells.single { it.row == 6 && it.column == 8 }.value)
        assertEquals(LocalDate.of(2026, 10, 20), SheetCodec.date("10/20/2026"))
        assertNull(SheetCodec.date(null))
        assertNull(SheetCodec.date(-1))
    }

    @Test fun invalidEffortOrNegativeWeightCannotReachTheSheet() {
        val r = sheets().first().routine
        for (entry in listOf(SetEntry("x", 5, effort = "Unknown effort"), SetEntry("x", 5, weight = "-5"))) {
            try { SheetCodec.uploadCells(r, "2026-10-20", listOf(entry), 3); fail("Expected invalid input rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertFalse(InputRules.accepts("other", "anything"))
        assertFalse(InputRules.accepts("notes", "a".repeat(5001)))
        assertFalse(InputRules.accepts("reps", "1000000"))
        assertFalse(InputRules.accepts("weight", "."))
        assertTrue(InputRules.accepts("notes", "=literal note"))
        assertTrue(InputRules.accepts("effort", "9 (Hard)"))
    }

    @Test fun importedUndatedWorkoutsAndDecimalHistoryStayAvailable() {
        val r = sheets().first()
        val populated = r.copy(cells = r.cells + mapOf((5 to 3) to GridCell(8.0, true), (5 to 4) to GridCell(22.5, true)))
        val session = SheetCodec.history(populated).single()
        assertEquals("Undated", session.date)
        assertEquals("8", session.entries.first().reps)
        assertEquals("22.5", session.entries.first().weight)
    }

    @Test fun malformedHeadersAndEffortRangesFailBeforeAnyWrite() {
        val json = javaClass.getResource("/workbook.json")!!.readText()
        for (broken in listOf(json.replace("\"stringValue\":\"R\"", "\"stringValue\":\"Bad\"").replace("\"formattedValue\":\"R\"", "\"formattedValue\":\"Bad\""),
            json.replace("$" + "C$" + "76:$" + "C$" + "82", "$" + "C$" + "100:$" + "C$" + "106"))) {
            try { SheetCodec.parse(JSONObject(broken)); fail("Expected unsupported layout") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
