package com.vibethroughcode.ftree.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.RelationshipType
import com.vibethroughcode.ftree.data.RelativeKind
import com.vibethroughcode.ftree.ui.person.EditNameFieldTag
import com.vibethroughcode.ftree.ui.person.EditSaveTag
import com.vibethroughcode.ftree.ui.person.MergeBannerTagPrefix
import com.vibethroughcode.ftree.ui.person.MergeConfirmTag
import com.vibethroughcode.ftree.ui.person.addSectionTag
import com.vibethroughcode.ftree.ui.relative.AddRelativeAddAnywayTag
import com.vibethroughcode.ftree.ui.relative.AddRelativeNameTag
import com.vibethroughcode.ftree.ui.relative.AddRelativeSaveTag
import com.vibethroughcode.ftree.ui.relative.AddRelativeSuggestionTagPrefix
import com.vibethroughcode.ftree.ui.relative.AddRelativeUseExistingTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The first ten minutes: Mom added from my page must not turn up a second time from Dad's. */
@RunWith(AndroidJUnit4::class)
class NoDuplicateRelativesFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication
    private val family get() = app.container.familyRepository

    @Before
    fun startWithMyselfAndBothParents() {
        app.container.database.clearAllTables()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Build your family tree").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Add myself").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(EditNameFieldTag).performTextInput("Ankit")
        rule.onNodeWithTag(EditSaveTag).performScrollTo().performClick()
        rule.waitForIdle()
        addRelative(RelativeKind.PARENT, "Asha")
        addRelative(RelativeKind.PARENT, "Ravi")
    }

    private fun addRelative(kind: RelativeKind, name: String) {
        rule.onNodeWithTag(addSectionTag(kind), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(AddRelativeNameTag).performTextInput(name)
        rule.onNodeWithTag(AddRelativeSaveTag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun openAddSpouseOnRavi() {
        rule.onNodeWithText("Ravi").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(addSectionTag(RelativeKind.SPOUSE), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun people() = runBlocking { family.allPeople() }

    @Test
    fun theOtherParentIsOfferedWhenAddingAFathersSpouseAndOneTapLinksHer() {
        openAddSpouseOnRavi()
        val asha = people().single { it.name == "Asha" }
        rule.onNodeWithTag(AddRelativeSuggestionTagPrefix + asha.id).performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals("still three people", 3, people().size)
        val ravi = people().single { it.name == "Ravi" }
        val married = runBlocking { family.allRelationships() }.any {
            it.type == RelationshipType.SPOUSE && setOf(it.fromPersonId, it.toPersonId) == setOf(asha.id, ravi.id)
        }
        assertTrue("Asha and Ravi are married", married)
    }

    @Test
    fun typingAnExistingNameAsksBeforeMakingASecondPerson() {
        openAddSpouseOnRavi()
        rule.onNodeWithTag(AddRelativeNameTag).performTextInput("Asha")
        rule.onNodeWithTag(AddRelativeSaveTag).performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals("nothing was added yet", 3, people().size)
        rule.onNodeWithTag(AddRelativeUseExistingTag).performClick()
        rule.waitForIdle()
        assertEquals(3, people().size)
    }

    @Test
    fun aSecondPersonOfTheSameNameCanStillBeAddedOnPurpose() {
        openAddSpouseOnRavi()
        rule.onNodeWithTag(AddRelativeNameTag).performTextInput("Asha")
        rule.onNodeWithTag(AddRelativeSaveTag).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(AddRelativeAddAnywayTag).performClick()
        rule.waitForIdle()

        assertEquals(4, people().size)
    }

    @Test
    fun aDuplicateAlreadyInTheTreeIsOfferedForMergingOnTheirPage() {
        val dup = runBlocking {
            val ravi = family.allPeople().single { it.name == "Ravi" }
            family.addNewRelative(ravi.id, com.vibethroughcode.ftree.data.Person(name = "Asha"), RelativeKind.SPOUSE).getOrThrow()
        }
        assertEquals(4, people().size)

        rule.onNodeWithText("Ravi").performClick()
        rule.waitForIdle()
        // Ravi's page lists his wife; open her.
        rule.onAllNodesWithText("Asha")[0].performClick()
        rule.waitForIdle()
        assertEquals(2, people().filter { it.name == "Asha" }.size)
        val banners = rule.onAllNodes(
            SemanticsMatcher("merge banner") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(MergeBannerTagPrefix) == true
            },
        )
        assertEquals("the page offers to merge the other Asha", 1, banners.fetchSemanticsNodes().size)
    }

    @Test
    fun aMergeFromTheBannerCanBeUndoneFromTheMessageThatFollows() {
        runBlocking {
            val ravi = family.allPeople().single { it.name == "Ravi" }
            family.addNewRelative(ravi.id, com.vibethroughcode.ftree.data.Person(name = "Asha"), RelativeKind.SPOUSE).getOrThrow()
        }
        rule.onNodeWithText("Ravi").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Asha")[0].performClick()
        rule.waitForIdle()

        rule.onAllNodesWithText("Merge")[0].performClick()
        rule.onNodeWithTag(MergeConfirmTag).performClick()
        rule.waitUntil(5_000) { people().size == 3 }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { people().size == 4 }
        assertEquals("both Ashas are back", 2, people().filter { it.name == "Asha" }.size)
    }
}
