package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.geo.LocationLabel
import com.vibethroughcode.ftree.ui.person.PlaceSearchTag
import com.vibethroughcode.ftree.ui.person.PlacesSharingTag
import com.vibethroughcode.ftree.ui.person.placeChangeTag
import com.vibethroughcode.ftree.ui.person.placeClearTag
import com.vibethroughcode.ftree.ui.person.placeNameTag
import com.vibethroughcode.ftree.ui.person.placeResultTag
import com.vibethroughcode.ftree.ui.tree.FamilyChartTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Recording where somebody was born and lives, from the bundled list of towns and cities. */
@RunWith(AndroidJUnit4::class)
class PersonPlacesTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FTreeApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication
    private val kutumb get() = app.container.kutumbRepository

    private lateinit var asha: Person

    @Before
    fun seed() {
        app.container.updatePreferences.setEnabled(false)
        app.container.database.clearAllTables()
        runBlocking {
            kutumb.clearAll()
            asha = app.container.familyRepository.addPerson(Person(name = "Asha Menon"))
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(FamilyChartTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(NavPeopleTag).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Asha Menon").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun choose(label: LocationLabel, typed: String) {
        rule.onNodeWithTag(placeChangeTag(label)).performScrollTo().performClick()
        rule.onNodeWithTag(PlaceSearchTag).performTextInput(typed)
        // The first search reads the whole bundled list, so allow for that.
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(placeResultTag(0)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(placeResultTag(0)).performClick()
    }

    @Test
    fun aBirthplaceIsFoundByTypingSavedAndNamedAgain() {
        rule.onNodeWithTag(placeNameTag(LocationLabel.BIRTHPLACE)).performScrollTo().assertTextContains("Not recorded")

        choose(LocationLabel.BIRTHPLACE, "chennai")

        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag(placeClearTag(LocationLabel.BIRTHPLACE)).fetchSemanticsNodes().isNotEmpty()
        }
        // Only a position is stored; the name on screen is the nearest listed town to it.
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithTag(placeNameTag(LocationLabel.BIRTHPLACE)).assertTextContains("Chennai, IN") }.isSuccess
        }
        val saved = runBlocking { kutumb.observeLocations().first().single() }
        assertEquals(LocationLabel.BIRTHPLACE, saved.label)
        assertEquals(asha.id, saved.personId)
        assertTrue("Chennai is near 13N 80E, got ${saved.lat},${saved.lon}", saved.lat in 12.5..13.5 && saved.lon in 79.8..80.6)
    }

    @Test
    fun aPlaceCanBeRemovedAndTheSharingSwitchAppliesToAllOfThem() {
        choose(LocationLabel.BIRTHPLACE, "chennai")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(placeClearTag(LocationLabel.BIRTHPLACE)).fetchSemanticsNodes().isNotEmpty() }
        choose(LocationLabel.CURRENT, "london")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(placeClearTag(LocationLabel.CURRENT)).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(PlacesSharingTag).performScrollTo().performClick()
        rule.waitUntil(5_000) { runBlocking { kutumb.observeLocations().first() }.all { !it.sharingEnabled } }

        rule.onNodeWithTag(placeClearTag(LocationLabel.BIRTHPLACE)).performScrollTo().performClick()
        rule.waitUntil(5_000) { runBlocking { kutumb.observeLocations().first() }.size == 1 }
        val left = runBlocking { kutumb.observeLocations().first().single() }
        assertEquals(LocationLabel.CURRENT, left.label)
        assertTrue("the person's switch stays off", !left.sharingEnabled)
        rule.onNodeWithTag(placeNameTag(LocationLabel.BIRTHPLACE)).performScrollTo().assertIsDisplayed()
    }
}
