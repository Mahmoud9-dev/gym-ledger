package com.mahmoud.gymledger

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabasePersistenceTest {
    @Test fun draftsPendingQueueAndDestinationsSurviveDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}.db"
        var db = GymDatabase.open(context, name)
        try {
            val fixture = context.assets.open("workbook.json").bufferedReader().use { SheetCodec.parse(JSONObject(it.readText())) }
            var repo = GymRepository(db)
            repo.seed(fixture)
            val id = repo.start(fixture.last().routine, "2026-10-20")
            repo.dao.edit(id, 5, "reps", "8")
            repo.dao.edit(id, 5, "weight", "65.5")
            repo.dao.edit(id, 5, "notes", "=literal note")
            db.close()
            db = GymDatabase.open(context, name)
            repo = GymRepository(db)
            assertEquals("DRAFT", repo.dao.workout(id)!!.status)
            assertEquals("65.5", repo.entries(id).first().weight)
            assertEquals("=literal note", repo.entries(id).first().notes)
            repo.finish(id)
            repo.save(repo.dao.workout(id)!!.copy(destination = 3))
            db.close()
            db = GymDatabase.open(context, name)
            repo = GymRepository(db)
            assertEquals(3, repo.queued().single().destination)
            repo.dao.edit(id, 5, "weight", "999")
            assertEquals("65.5", repo.entries(id).first().weight)
            repo.refresh(fixture)
            assertEquals("PENDING", repo.dao.workout(id)!!.status)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun importsDoNotDuplicateLocallySyncedSessions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}.db"
        val db = GymDatabase.open(context, name)
        try {
            val fixture = context.assets.open("workbook.json").bufferedReader().use { SheetCodec.parse(JSONObject(it.readText())) }
            val repo = GymRepository(db)
            val r = fixture.first()
            val id = repo.start(r.routine, "2026-10-20")
            repo.finish(id)
            repo.save(repo.dao.workout(id)!!.copy(status = "SYNCED", destination = 3))
            val cells = SheetCodec.uploadCells(r.routine, "2026-10-20", emptyList(), 3)
            val written = r.copy(cells = r.cells + cells.associate { (it.row to it.column) to GridCell(it.value, true) })
            repo.refresh(listOf(written))
            assertTrue(repo.dao.imports().isEmpty())
            assertEquals("SYNCED", repo.dao.workout(id)!!.status)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun versionOneUpgradePreservesStrengthDraftAndCardioSurvivesReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val schema = JSONObject(instrumentation.context.assets.open("schema-v1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO workouts (id,sheetId,date,routineJson,createdAt,status,destination,error) VALUES ('old-draft',1,'2026-10-20','{}',1,'DRAFT',NULL,'')")
            old.execSQL("INSERT INTO entries (sessionId,row,reps,weight,effort,rest,notes,done) VALUES ('old-draft',5,'10','22.5','','90','=saved',1)")
            old.version = 1
        }
        var db = GymDatabase.open(context, name)
        try {
            var repo = GymRepository(db)
            assertEquals("DRAFT", repo.dao.workout("old-draft")!!.status)
            assertEquals("22.5", repo.entries("old-draft").single().weight)
            val id = repo.startCardio("2026-10-21", "old-draft")
            repo.dao.editCardio(id, "activity", "Cycling")
            repo.dao.editCardio(id, "duration", "25.5")
            repo.dao.editCardio(id, "notes", "=literal cardio")
            db.close()
            db = GymDatabase.open(context, name)
            repo = GymRepository(db)
            assertEquals("DRAFT", repo.dao.cardio(id)!!.status)
            assertEquals("25.5", repo.dao.cardio(id)!!.duration)
            assertEquals("old-draft", repo.dao.cardio(id)!!.workoutId)
            repo.finishCardio(id)
            repo.saveCardio(repo.dao.cardio(id)!!.copy(destination = 2))
            db.close()
            db = GymDatabase.open(context, name)
            repo = GymRepository(db)
            assertEquals(2, repo.queuedCardio().single().destination)
            repo.dao.editCardio(id, "duration", "999")
            assertEquals("25.5", repo.dao.cardio(id)!!.duration)
            val headers = CardioCodec.headers.mapIndexed { col, value -> (1 to col) to GridCell(value, true) }.toMap()
            val expected = CardioCodec.uploadCells(repo.dao.cardio(id)!!, 2)
            repo.refreshCardio(RemoteCardio("Cardio", 1001, headers + expected.associate { (it.row to it.column) to GridCell(it.value, true) }))
            assertTrue(repo.dao.importedCardio().isEmpty())
            assertEquals("PENDING", repo.dao.cardio(id)!!.status)
            assertEquals("=saved", repo.entries("old-draft").single().notes)
        } finally { db.close(); context.deleteDatabase(name) }
    }

}
