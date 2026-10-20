package com.mahmoud.gymledger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun logSetRestoreScreenFinishAndBrowseOfflineHistory() {
        val db = (compose.activity.application as GymApp).database
        compose.waitUntil(15_000) { runBlocking { db.dao().routines().size == 5 } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("routine-1825007893").fetchSemanticsNodes().isNotEmpty() }
        compose.activity.openFileOutput("qa-home.png", 0).use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("routine-279747673"))
        compose.onNodeWithTag("routine-279747673").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reps-5").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reps-5").performTextInput("10")
        compose.onNodeWithTag("weight-5").performTextInput("22.5")
        compose.onNodeWithTag("done-5").performClick()
        compose.onNodeWithTag("details-5").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Choose effort (optional)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Choose effort (optional)").performScrollTo().performClick()
        compose.onNodeWithText("9 (Hard)").performClick()
        compose.onNodeWithTag("rest-5").performScrollTo().performTextInput("90")
        compose.onNodeWithTag("notes-5").performScrollTo().performTextInput("=literal note")
        compose.waitUntil(10_000) {
            runBlocking { db.dao().observeEntries().first().any { it.row == 5 && it.reps == "10" && it.weight == "22.5" && it.effort == "9 (Hard)" && it.rest == "90" && it.notes == "=literal note" && it.done } }
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reps-5").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reps-5").assertTextContains("10")
        compose.onNodeWithTag("weight-5").assertTextContains("22.5")
        compose.onNodeWithTag("notes-5").performScrollTo().assertTextContains("=literal note")
        compose.onNodeWithText("Finish workout").performClick()
        compose.onNodeWithText("Finish anyway").performClick()
        compose.waitUntil(10_000) { runBlocking { db.dao().queued().any { it.sheetId == 279747673L } } }
        compose.onNodeWithTag("workout-list").performScrollToIndex(0)
        compose.onNodeWithText("← Back").performClick()
        compose.onNodeWithText("History").performClick()
        compose.onAllNodesWithText("Push").onFirst().performClick()
        compose.onNodeWithText("Finished session · Read only").assertIsDisplayed()
        compose.onNodeWithText("10 reps   ×   22.5 kg").performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking { db.dao().queued() }.all { it.destination == null })
        val context = compose.activity.applicationContext
        context.openFileOutput("qa-workout.png", 0).use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("workout-list").performScrollToIndex(0)
        compose.onNodeWithTag("add-cardio").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("cardio-duration").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("After Push").assertIsDisplayed()
        compose.onNodeWithTag("cardio-duration").assertTextContains("Duration (minutes)")
        assertTrue(runBlocking { db.dao().observeCardio().first() }.any { it.workoutId.isNotBlank() && it.duration.isEmpty() && it.status == "DRAFT" })
    }
}
