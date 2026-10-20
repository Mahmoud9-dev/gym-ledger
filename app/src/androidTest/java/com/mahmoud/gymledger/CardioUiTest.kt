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
class CardioUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun cardioDraftRecreationFinishAndOfflineHistory() {
        val db = (compose.activity.application as GymApp).database
        compose.waitUntil(15_000) { runBlocking { db.dao().routines().size == 5 } }
        compose.onNodeWithText("Cardio", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("start-cardio").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("cardio-duration").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("finish-cardio").assertIsNotEnabled()
        compose.onNodeWithTag("cardio-activity").performClick()
        compose.onNodeWithText("Cycling").performClick()
        compose.onNodeWithTag("cardio-duration").performTextInput("20.5")
        compose.onNodeWithTag("cardio-distance").performTextInput("5.25")
        compose.onNodeWithTag("cardio-effort").performScrollTo().performClick()
        compose.onNodeWithText("Hard", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("cardio-notes").performScrollTo().performTextInput("=literal cardio")
        compose.waitUntil(10_000) { runBlocking { db.dao().observeCardio().first().any { it.duration == "20.5" && it.distance == "5.25" && it.notes == "=literal cardio" && it.effort == "Hard" } } }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("cardio-duration").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cardio-duration").assertTextContains("20.5")
        compose.onNodeWithTag("cardio-notes").performScrollTo().assertTextContains("=literal cardio")
        compose.onNodeWithTag("finish-cardio").performClick()
        compose.waitUntil(10_000) { runBlocking { db.dao().queuedCardio().any { it.notes == "=literal cardio" } } }
        compose.onNodeWithTag("cardio-list").performScrollToIndex(0)
        compose.onNodeWithText("← Back").performClick()
        compose.onNodeWithText("History").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Cycling"))
        compose.onAllNodesWithText("Cycling").onFirst().performClick()
        compose.onNodeWithText("Finished cardio · Read only").assertIsDisplayed()
        compose.onNodeWithText("20.5 min · 5.25 km").assertIsDisplayed()
        compose.activity.openFileOutput("qa-cardio.png", 0).use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(runBlocking { db.dao().queuedCardio() }.all { it.destination == null })
    }
}
