package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.ui.more.MoreSettingsTag
import com.vibethroughcode.ftree.ui.settings.SettingsBackTag
import com.vibethroughcode.ftree.ui.settings.SettingsPage
import com.vibethroughcode.ftree.ui.settings.SettingsPhotosToggleTag
import com.vibethroughcode.ftree.ui.settings.SettingsSyncToggleTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings opens on a short list of places, not one long page: each page holds its own controls, and
 * Back (the arrow, or the system's) returns to the list rather than leaving Settings.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPagesTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun theOriginalTree() {
        app.switchTo(TreeCatalog.ORIGINAL.id)
    }

    @After fun cleanUp() {
        scenario?.close()
    }

    private fun openSettings() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(8_000) { compose.onAllNodesWithTag(NavMoreTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(NavMoreTag).performClick()
        compose.onNodeWithTag(MoreSettingsTag).performClick()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test fun settingsOpensOnAListOfPlacesAndNotTheirControls() {
        openSettings()
        SettingsPage.entries.filter { it != SettingsPage.UPDATES || com.vibethroughcode.ftree.BuildConfig.SELF_UPDATE }.forEach {
            compose.onNodeWithTag(it.tag).assertIsDisplayed()
        }
        assertEquals("a control of one page is not on the list", 0, count(SettingsPhotosToggleTag))
        assertEquals("nor another's", 0, count(SettingsSyncToggleTag))
        assertEquals("and there is no way back on the list itself", 0, count(SettingsBackTag))
    }

    @Test fun aPageHoldsItsOwnControlsAndNoOthers() {
        openSettings()
        compose.onNodeWithTag(SettingsPage.NAMES.tag).performClick()
        compose.onNodeWithTag(SettingsPhotosToggleTag).assertIsDisplayed()
        assertEquals(0, count(SettingsSyncToggleTag))
        assertEquals("the list is gone while a page is open", 0, count(SettingsPage.SYNC.tag))
    }

    @Test fun theArrowReturnsToTheList() {
        openSettings()
        compose.onNodeWithTag(SettingsPage.SYNC.tag).performClick()
        compose.onNodeWithTag(SettingsSyncToggleTag).assertIsDisplayed()
        compose.onNodeWithTag(SettingsBackTag).performClick()
        compose.onNodeWithTag(SettingsPage.SYNC.tag).assertIsDisplayed()
        assertEquals(0, count(SettingsSyncToggleTag))
    }

    @Test fun theSystemBackReturnsToTheListAndDoesNotLeaveSettings() {
        openSettings()
        compose.onNodeWithTag(SettingsPage.NAMES.tag).performClick()
        compose.onNodeWithTag(SettingsPhotosToggleTag).assertIsDisplayed()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { count(SettingsPage.NAMES.tag) == 1 }
        compose.onNodeWithTag(SettingsPage.NAMES.tag).assertIsDisplayed()
    }
}
