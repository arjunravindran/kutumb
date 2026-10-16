package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.ui.person.EditNameFieldTag
import com.vibethroughcode.ftree.ui.person.EditNotesFieldTag
import com.vibethroughcode.ftree.ui.person.EditSaveTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A new person is added to the tree by the first field finished, with no need to press Save. */
@RunWith(AndroidJUnit4::class)
class NewPersonAutosaveFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val family get() =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication)
            .container.familyRepository

    private fun everyone() = runBlocking { family.allPeople() }

    @Before
    fun theNewPersonFormIsOpenOnAnEmptyTree() {
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication)
            .container.database.clearAllTables()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Build your family tree").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Add myself").performClick()
        rule.waitForIdle()
    }

    @Test
    fun movingOnFromTheNameAddsThePerson() {
        rule.onNodeWithTag(EditNameFieldTag).performTextInput("Ankit")
        rule.onNodeWithTag(EditNotesFieldTag).performScrollTo().performClick()
        rule.waitForIdle()

        rule.waitUntil(5_000) { everyone().size == 1 }
        assertEquals("Ankit", everyone().single().name)
    }

    @Test
    fun leavingWithTheNameStillBeingTypedKeepsTheNameAndMakesOnePerson() {
        rule.onNodeWithTag(EditNameFieldTag).performTextInput("Ankit")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()

        rule.waitUntil(5_000) { everyone().size == 1 }
        assertEquals("Ankit", everyone().single().name)
    }

    @Test
    fun savingAfterAFieldWasTakenInDoesNotMakeASecondPerson() {
        rule.onNodeWithTag(EditNameFieldTag).performTextInput("Ankit")
        rule.onNodeWithTag(EditNotesFieldTag).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(EditSaveTag).performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(1, everyone().size)
    }

    @Test
    fun leavingAnEmptyFormAddsNobody() {
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()

        assertEquals(0, everyone().size)
    }
}
