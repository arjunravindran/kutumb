package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.ui.person.EditNameFieldTag
import com.vibethroughcode.ftree.ui.person.EditNotesFieldTag
import com.vibethroughcode.ftree.ui.person.EditSaveTag
import com.vibethroughcode.ftree.ui.person.PersonEditTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Editing someone already in the tree has no Save button: a field is taken in when focus leaves it. */
@RunWith(AndroidJUnit4::class)
class AutosaveEditFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val family get() =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication)
            .container.familyRepository

    private fun saved() = runBlocking { family.allPeople().single() }

    @Before
    fun oneSavedPersonWithTheEditFormOpen() {
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication)
            .container.database.clearAllTables()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Build your family tree").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Add myself").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(EditNameFieldTag).performTextInput("Ankit")
        rule.onNodeWithTag(EditSaveTag).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(PersonEditTag).performClick()
        rule.waitForIdle()
    }

    @Test
    fun thereIsNoSaveButtonForSomeoneAlreadyInTheTree() {
        rule.onNodeWithTag(EditSaveTag).assertDoesNotExist()
    }

    @Test
    fun aFieldIsKeptAsSoonAsFocusMovesOnWithoutLeavingTheForm() {
        rule.onNodeWithTag(EditNameFieldTag).performTextClearance()
        rule.onNodeWithTag(EditNameFieldTag).performTextInput("Ankit Kumar")
        // Moving to the next field is all it takes.
        rule.onNodeWithTag(EditNotesFieldTag).performScrollTo().performClick()
        rule.waitForIdle()

        rule.waitUntil(5_000) { saved().name == "Ankit Kumar" }
        assertEquals("Ankit Kumar", saved().name)
    }

    @Test
    fun leavingWithTheLastFieldStillFocusedKeepsIt() {
        rule.onNodeWithTag(EditNotesFieldTag).performScrollTo().performTextInput("Born in a hurry")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()

        rule.waitUntil(5_000) { saved().notes == "Born in a hurry" }
        assertEquals("Born in a hurry", saved().notes)
    }
}
