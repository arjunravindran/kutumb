package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.CatalogResult
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.data.TreeInfo
import com.vibethroughcode.ftree.data.TreeKind
import com.vibethroughcode.ftree.ui.tree.TreeSwitcherItemTagPrefix
import com.vibethroughcode.ftree.ui.tree.TreeSwitcherTag
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Moving between trees from the main screen, without going to Settings. */
@RunWith(AndroidJUnit4::class)
class TreeSwitcherTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private lateinit var circle: TreeInfo
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun twoTrees() {
        app.switchTo(TreeCatalog.ORIGINAL.id)
        circle = (app.catalog.create("Switcher circle ${System.nanoTime()}", TreeKind.CIRCLE) as CatalogResult.Ok).tree
    }

    @After fun cleanUp() {
        scenario?.close()
        app.switchTo(TreeCatalog.ORIGINAL.id)
        Thread.sleep(3_500)
        (app.catalog.remove(circle.id) as? CatalogResult.Ok)?.let { app.deleteFilesOf(it.tree) }
    }

    @Test fun theTitleOpensAMenuOfTreesAndChoosingOneOpensIt() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(TreeSwitcherTag).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(TreeSwitcherTag).performClick()
        compose.onNodeWithTag(TreeSwitcherItemTagPrefix + circle.id).assertIsDisplayed()
        compose.onNodeWithTag(TreeSwitcherItemTagPrefix + circle.id).performClick()

        // A new circle has nobody in it; that its empty screen shows is what proves the switch.
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Nobody here yet").fetchSemanticsNodes().isNotEmpty() }
        assert(app.container.tree.id == circle.id)
    }
}
