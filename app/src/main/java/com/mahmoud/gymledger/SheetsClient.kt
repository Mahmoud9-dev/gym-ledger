package com.mahmoud.gymledger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder

const val WORKBOOK_ID = "1uTbsFmIRe_XM8ChOrIPOFjbuyuX8Ij06NvbNmM07oKk"
const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"

class SheetsClient(private val token: () -> String?, private val network: WifiGate) : SheetAccess, CardioAccess {
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private suspend fun request(query: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val accessToken = token() ?: throw AuthRequired()
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$WORKBOOK_ID$query"
        val connection = network.open(url) as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.instanceFollowRedirects = false
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code == 401) throw AuthRequired()
            if (code == 403) throw IOException("Google denied sheet access. Check the signed-in account, Sheets API setup, and edit permission.")
            if (code == 429) throw IOException("Google is limiting requests. Your workout is safe; retry shortly.")
            if (code !in 200..299) throw IOException("Google Sheets returned HTTP $code. Your workout is saved locally; retry on Wi-Fi.")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    override suspend fun read(): List<RemoteSheet> {
        val meta = request("?fields=" + encode("sheets.properties(sheetId,title,gridProperties(rowCount,columnCount))"))
        val required = setOf(1825007893L, 300187019L, 279747673L, 2018210557L, 252919119L)
        val sheets = meta.getJSONArray("sheets")
        val titles = (0 until sheets.length()).map { sheets.getJSONObject(it).getJSONObject("properties") }
            .filter { it.getLong("sheetId") in required }
        require(titles.size == required.size) { "One of the five workout tabs is missing. Restore it before syncing." }
        val ranges = titles.joinToString("") { prop ->
            val grid = prop.getJSONObject("gridProperties")
            require(grid.getInt("rowCount") >= 86 && grid.getInt("columnCount") >= 253) { "The workout sheet layout changed." }
            "&ranges=" + encode("'${prop.getString("title").replace("'", "''")}'!A1:IS90")
        }
        val fields = "sheets(properties(sheetId,title),data(startRow,startColumn,rowData(values(formattedValue,userEnteredValue,effectiveValue,hyperlink,dataValidation))))"
        return SheetCodec.parse(request("?includeGridData=true&fields=" + encode(fields) + ranges))
    }

    override suspend fun readCardio(): RemoteCardio {
        val meta = request("?fields=" + encode("sheets.properties(sheetId,title,gridProperties(rowCount,columnCount))"))
        val sheets = meta.getJSONArray("sheets")
        val props = (0 until sheets.length()).map { sheets.getJSONObject(it).getJSONObject("properties") }
            .singleOrNull { it.getLong("sheetId") == CARDIO_SHEET_ID }
            ?: error("The Cardio tab is missing. Restore it or follow the cardio setup instructions. Your cardio is saved locally.")
        val grid = props.getJSONObject("gridProperties")
        check(grid.getInt("columnCount") >= CardioCodec.headers.size) { "The Cardio columns changed. Restore all eight columns before retrying." }
        val title = props.getString("title")
        val rowCount = grid.getInt("rowCount")
        val range = "'${title.replace("'", "''")}'!A1:H$rowCount"
        val fields = "sheets(data(startRow,startColumn,rowData(values(userEnteredValue,effectiveValue,formattedValue))))"
        val book = request("?includeGridData=true&fields=" + encode(fields) + "&ranges=" + encode(range))
        val cells = mutableMapOf<Pair<Int, Int>, GridCell>()
        val data = book.getJSONArray("sheets").getJSONObject(0).optJSONArray("data") ?: JSONArray()
        for (d in 0 until data.length()) {
            val block = data.getJSONObject(d)
            val rows = block.optJSONArray("rowData") ?: continue
            for (r in 0 until rows.length()) {
                val values = rows.getJSONObject(r).optJSONArray("values") ?: continue
                for (c in 0 until values.length()) {
                    val cell = values.optJSONObject(c) ?: continue
                    val entered = cell.optJSONObject("userEnteredValue")
                    val effective = cell.optJSONObject("effectiveValue") ?: entered
                    val value: Any = when {
                        effective?.has("numberValue") == true -> effective.getDouble("numberValue")
                        effective?.has("stringValue") == true -> effective.getString("stringValue")
                        effective?.has("boolValue") == true -> effective.getBoolean("boolValue")
                        else -> cell.optString("formattedValue")
                    }
                    cells[(block.optInt("startRow") + r + 1) to (block.optInt("startColumn") + c)] =
                        GridCell(value, entered?.has("formulaValue") == true || value.toString().isNotEmpty())
                }
            }
        }
        return RemoteCardio(title, rowCount, cells).also(CardioCodec::validate)
    }

    override suspend fun writeCardio(title: String, cells: List<WriteCell>) = write(title, cells)

    override suspend fun write(title: String, cells: List<WriteCell>) {
        val data = JSONArray()
        cells.forEach { cell ->
            data.put(JSONObject().put("range", SheetCodec.range(title, cell.column, cell.row))
                .put("values", JSONArray().put(JSONArray().put(cell.value))))
        }
        request("/values:batchUpdate", JSONObject().put("valueInputOption", "RAW").put("data", data))
    }
}
