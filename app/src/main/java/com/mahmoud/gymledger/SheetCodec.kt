package com.mahmoud.gymledger

import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object SheetCodec {
    private val epoch = LocalDate.of(1899, 12, 30)
    private val header = listOf("R", "W", "E", "Rest", "Notes")

    fun parse(book: JSONObject): List<RemoteSheet> {
        val sheets = book.getJSONArray("sheets")
        return (0 until sheets.length()).map { index ->
            val sheet = sheets.getJSONObject(index)
            val props = sheet.getJSONObject("properties")
            val cells = mutableMapOf<Pair<Int, Int>, GridCell>()
            val raw = mutableMapOf<Pair<Int, Int>, JSONObject>()
            val ranges = sheet.optJSONArray("data") ?: org.json.JSONArray()
            for (d in 0 until ranges.length()) {
                val data = ranges.getJSONObject(d)
                val rows = data.optJSONArray("rowData") ?: continue
                for (r in 0 until rows.length()) {
                    val values = rows.getJSONObject(r).optJSONArray("values") ?: continue
                    for (c in 0 until values.length()) {
                        val cell = values.optJSONObject(c) ?: continue
                        val position = (data.optInt("startRow") + r + 1) to (data.optInt("startColumn") + c)
                        raw[position] = cell
                        val value = cell.optJSONObject("effectiveValue") ?: cell.optJSONObject("userEnteredValue")
                        val effective: Any = when {
                            value?.has("numberValue") == true -> value.getDouble("numberValue")
                            value?.has("stringValue") == true -> value.getString("stringValue")
                            value?.has("boolValue") == true -> value.getBoolean("boolValue")
                            else -> cell.optString("formattedValue")
                        }
                        val entered = cell.optJSONObject("userEnteredValue")
                        val occupied = entered?.has("formulaValue") == true || effective.toString().isNotEmpty()
                        cells[position] = GridCell(effective, occupied)
                    }
                }
            }
            fun text(row: Int, col: Int) = cells[row to col]?.value?.toString().orEmpty()
            val blocks = (3..248 step 5).filter { start -> header.indices.all { text(2, start + it) == header[it] } }
            require(blocks.isNotEmpty()) { "Unsupported logging columns in ${props.getString("title")}. Sheet was not changed." }
            val exercises = mutableListOf<Exercise>()
            for (row in 3..39) {
                val labels = (0..2).map { text(row, it) }
                if (labels.none { it.startsWith("See Tutorial") }) continue
                val previous = (0..2).map { text(row - 1, it) }.filter(String::isNotBlank)
                val name = previous.lastOrNull { it != "Dropset" && !it.startsWith("SUPERSET") && !it.matches(Regex("[A-Z][0-9]+")) } ?: continue
                val group = previous.filter { it != name }.joinToString(" · ").ifBlank {
                    (row - 2 downTo 3).map { text(it, 0) }.firstOrNull { it.startsWith("SUPERSET") }.orEmpty()
                }
                val tutorialCell = (0..2).mapNotNull { raw[row to it] }.firstOrNull { it.optString("formattedValue").startsWith("See Tutorial") }
                val tutorial = tutorialCell?.optString("hyperlink").orEmpty()
                val sets = (row + 1..minOf(row + 4, 40)).mapNotNull { setRow ->
                    (0..2).map { text(setRow, it) }.firstOrNull { it.startsWith("Set ") }?.let { SetSpec(setRow, it) }
                }
                require(sets.isNotEmpty()) { "No sets found for $name" }
                exercises += Exercise(name, group, tutorial, sets)
            }
            require(exercises.isNotEmpty()) { "No exercises found in ${props.getString("title")}" }
            val effortRule = raw[exercises.first().sets.first().row to blocks.first() + 2]
                ?.optJSONObject("dataValidation")?.optJSONObject("condition")
            val effortRange = effortRule?.optJSONArray("values")?.optJSONObject(0)?.optString("userEnteredValue").orEmpty()
            val bounds = Regex("\\$?C\\$?(\\d+):\\$?C\\$?(\\d+)").find(effortRange)
                ?: error("Unsupported effort dropdown in ${props.getString("title")}")
            val firstEffort = bounds.groupValues[1].toInt()
            val lastEffort = bounds.groupValues[2].toInt()
            require(firstEffort in 1..90 && lastEffort in firstEffort..90) { "Effort choices moved outside the cached range." }
            val efforts = (firstEffort..lastEffort).map { text(it, 2) }.filter(String::isNotBlank).distinct()
            require(efforts.isNotEmpty()) { "Effort choices are missing in ${props.getString("title")}" }
            RemoteSheet(Routine(props.getLong("sheetId"), props.getString("title"), exercises, efforts, blocks), cells)
        }
    }

    fun column(index: Int): String {
        require(index >= 0)
        var n = index + 1
        var result = ""
        while (n > 0) { n--; result = ('A'.code + n % 26).toChar() + result; n /= 26 }
        return result
    }
    fun range(title: String, col: Int, row: Int) = "'${title.replace("'", "''")}'!${column(col)}$row"

    fun sameMapping(a: Routine, b: Routine): Boolean =
        a.sheetId == b.sheetId && a.blocks == b.blocks &&
            a.exercises.map { it.name to it.sets } == b.exercises.map { it.name to it.sets }

    fun blockEmpty(sheet: RemoteSheet, start: Int): Boolean =
        (1..40).all { row -> row == 2 || (start until start + 5).all { col -> sheet.cells[row to col]?.occupied != true } }

    fun firstEmptyBlock(sheet: RemoteSheet, reserved: Set<Int>): Int? =
        sheet.routine.blocks.firstOrNull { it !in reserved && blockEmpty(sheet, it) }

    fun uploadCells(routine: Routine, date: String, entries: List<SetEntry>, start: Int): List<WriteCell> {
        require(start in routine.blocks) { "Invalid logging block" }
        val isoDate = LocalDate.parse(date).toString()
        val byRow = entries.associateBy { it.row }
        return listOf(WriteCell(1, start, isoDate)) + routine.exercises.flatMap { exercise ->
            exercise.sets.flatMap { spec ->
                val e = byRow[spec.row] ?: SetEntry("", spec.row)
                require(InputRules.valid(e, routine)) { "Invalid set values. Review your workout before syncing." }
                listOf(
                    e.reps.toIntOrNull() ?: "", e.weight.toDoubleOrNull() ?: "", e.effort,
                    e.rest.toIntOrNull() ?: "", e.notes
                ).mapIndexed { offset, value -> WriteCell(spec.row, start + offset, value) }
            }
        }
    }

    private fun equal(a: Any?, b: Any): Boolean = if (a is Number && b is Number) a.toDouble() == b.toDouble() else (a ?: "").toString() == b.toString()
    fun matches(sheet: RemoteSheet, expected: List<WriteCell>) = expected.all { equal(sheet.cells[it.row to it.column]?.value, it.value) }

    fun date(value: Any?): LocalDate? {
        if (value is Number && value.toDouble().isFinite() && value.toDouble() in 1.0..2958465.0) return epoch.plusDays(value.toLong())
        val text = value?.toString().orEmpty()
        for (pattern in listOf("uuuu-MM-dd", "M/d/uuuu", "M/d/uu", "d MMM uuuu")) {
            try { return LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH)) }
            catch (_: java.time.DateTimeException) { }
        }
        return null
    }
    private fun display(value: Any?): String = if (value is Number && value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value?.toString().orEmpty()

    fun history(sheet: RemoteSheet): List<HistoricalWorkout> = sheet.routine.blocks.mapNotNull { block ->
        if (blockEmpty(sheet, block)) return@mapNotNull null
        val entries = sheet.routine.exercises.flatMap { it.sets }.map { set ->
            val fields = (0..4).map { display(sheet.cells[set.row to block + it]?.value) }
            SetEntry("", set.row, fields[0], fields[1], fields[2], fields[3], fields[4], fields.any(String::isNotBlank))
        }
        HistoricalWorkout(block, date(sheet.cells[1 to block]?.value)?.toString() ?: "Undated", entries)
    }
}
