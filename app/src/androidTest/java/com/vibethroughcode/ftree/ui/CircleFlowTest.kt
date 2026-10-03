package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.CatalogResult
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.data.TreeInfo
import com.vibethroughcode.ftree.data.TreeKind
import com.vibethroughcode.ftree.ui.circle.AddConnectionLabelTag
import com.vibethroughcode.ftree.ui.circle.AddConnectionNameTag
import com.vibethroughcode.ftree.ui.circle.AddConnectionSaveTag
import com.vibethroughcode.ftree.graph.CircleGraphLayout
import com.vibethroughcode.ftree.ui.circle.CircleAddConnectionTag
import com.vibethroughcode.ftree.ui.circle.CircleGraphTag
import com.vibethroughcode.ftree.ui.circle.CircleViewGraphTag
import com.vibethroughcode.ftree.ui.circle.CircleViewListTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A Circle end to end: groups by label, people reached through others, and adding a connection. */
@RunWith(AndroidJUnit4::class)
class CircleFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private lateinit var circle: TreeInfo
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun seedACircle() {
        circle = (app.catalog.create("Test circle ${System.nanoTime()}", TreeKind.CIRCLE) as CatalogResult.Ok).tree
        app.switchTo(circle.id)
        val repo = app.container.familyRepository
        runBlocking {
            listOf("Asha", "Ben", "Chen", "Dara").forEach { repo.addPerson(Person(id = it, name = it)) }
            repo.addConnection("Asha", "Ben", "Friend")
            repo.addConnection("Asha", "Chen", "COLLEAGUE")
            repo.addConnection("Ben", "Dara", "Friend")
        }
    }

    @After fun cleanUp() {
        scenario?.close()
        app.switchTo(TreeCatalog.ORIGINAL.id)
        Thread.sleep(3_500)
        (app.catalog.remove(circle.id) as? CatalogResult.Ok)?.let { app.deleteFilesOf(it.tree) }
    }

    @Test fun aCircleGroupsConnectionsByLabelAndShowsWhoIsReachedThroughOthers() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        // Asha is the best connected, ties broken by name, so the circle opens on her.
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("COLLEAGUE").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError("screen was: " + compose.onRoot().printToString(), e)
        }
        compose.onNodeWithText("COLLEAGUE").assertIsDisplayed()
        compose.onNodeWithText("FRIEND").assertIsDisplayed()
        compose.onNodeWithText("THROUGH PEOPLE THEY KNOW").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("via Ben"))
        compose.onNodeWithText("via Ben").assertIsDisplayed()

        // Moving to Dara: Ben is her only connection, and Asha is reached through him.
        compose.onNodeWithText("via Ben").performClick()
        // Dara knows nobody as a colleague, so that heading going away shows the view moved to her.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("COLLEAGUE").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("FRIEND").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("via Ben"))
        compose.onNodeWithText("via Ben").assertIsDisplayed()
    }

    @Test fun addingAConnectionFromACircleShowsTheNewPerson() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("COLLEAGUE").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(CircleAddConnectionTag).performClick()
        compose.onNodeWithTag(AddConnectionLabelTag).performTextInput("Neighbour")
        compose.onNodeWithTag(AddConnectionNameTag).performTextInput("Esha")
        compose.onNodeWithTag(AddConnectionSaveTag).performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Esha").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("NEIGHBOUR").assertIsDisplayed()
        compose.onNodeWithText("Esha").assertIsDisplayed()
    }

    @Test fun aCircleHasNoFactsTabButKeepsNetwork() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("COLLEAGUE").fetchSemanticsNodes().isNotEmpty() }

        // The facts game asks about family, which a circle does not have; pairing still applies.
        compose.onNodeWithTag(NavNetworkTag).assertIsDisplayed()
        compose.onAllNodesWithTag(NavFactsTag).assertCountEquals(0)
    }

    @Test fun theGraphDrawsTheSameCircleAndTappingAPersonMovesToThem() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("COLLEAGUE").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(CircleViewGraphTag).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(CircleGraphTag).fetchSemanticsNodes().isNotEmpty() }
        // The list's headings are gone; the picture and its legend stand in for them.
        compose.onAllNodesWithText("THROUGH PEOPLE THEY KNOW").assertCountEquals(0)
        compose.onNodeWithText("COLLEAGUE · 1").assertIsDisplayed()
        compose.onNodeWithText("Friend · 1").assertIsDisplayed()

        // Dara is reached through Ben, so she is on the outer ring. Tap where the layout puts her.
        val (people, edges) = runBlocking {
            app.container.familyRepository.observeAllPeople().first() to app.container.familyRepository.observeAllEdges().first()
        }
        val graph = CircleGraphLayout.layout("Asha", people, edges)!!
        val dara = graph.node("Dara")!!
        compose.onNodeWithTag(CircleGraphTag).performTouchInput {
            val scale = minOf(width, height) / (2 * graph.extent)
            click(Offset(width / 2f + dara.x * scale, height / 2f + dara.y * scale))
        }

        // The header is the middle person's name, so seeing Dara's there is the move.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Dara").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Dara").assertIsDisplayed()
        compose.onNodeWithTag(CircleGraphTag).assertIsDisplayed()

        // And back to the list, which is where the graph's own summary points.
        compose.onNodeWithTag(CircleViewListTag).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(CircleGraphTag).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("FRIEND").assertIsDisplayed()
    }
}
