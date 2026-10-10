package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.ui.welcome.WelcomePreferences
import com.vibethroughcode.ftree.ui.welcome.WelcomeStartTag
import com.vibethroughcode.ftree.ui.welcome.WelcomeTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first-run welcome, as a newcomer meets it: once, in plain words, before the empty tree. Everywhere
 * else in the suite it is held back (see `WelcomePolicy`), so these ask for it by name.
 */
@RunWith(AndroidJUnit4::class)
class WelcomeFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private val welcome = WelcomePreferences(app)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun forgetEverWelcoming() {
        app.switchTo(TreeCatalog.ORIGINAL.id)
        welcome.forget()
    }

    @After fun leaveItHeldBackForOtherTests() {
        scenario?.close()
        welcome.forget()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test fun anInstrumentedRunIsNotWelcomedUnlessItAsks() {
        launch()
        compose.waitUntil(8_000) { count(NavMoreTag) == 1 }
        assertEquals(0, count(WelcomeTag))
        assertNull("nothing was decided or stored", welcome.stored())
    }

    @Test fun askedFor_itIsShownInPlainWordsAndGetStartedEndsItForGood() {
        welcome.showAgain()
        launch()
        compose.waitUntil(8_000) { count(WelcomeTag) == 1 }
        compose.onNodeWithText("Welcome to Kutumb").assertIsDisplayed()
        compose.onNodeWithText("Private by default").assertIsDisplayed()
        compose.onNodeWithTag(WelcomeStartTag).assertIsDisplayed()

        compose.onNodeWithTag(WelcomeStartTag).performClick()
        compose.waitUntil(5_000) { count(WelcomeTag) == 0 }
        assertEquals(true, welcome.stored())
        compose.waitUntil(8_000) { count(NavMoreTag) == 1 }
    }

    @Test fun onceWelcomedItDoesNotComeBackOnTheNextLaunch() {
        welcome.markSeen()
        launch()
        compose.waitUntil(8_000) { count(NavMoreTag) == 1 }
        assertTrue(count(WelcomeTag) == 0)
    }
}
