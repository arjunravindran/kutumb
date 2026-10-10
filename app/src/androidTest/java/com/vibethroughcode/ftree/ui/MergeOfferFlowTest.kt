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
import com.vibethroughcode.ftree.data.CatalogResult
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.data.TreeInfo
import com.vibethroughcode.ftree.data.TreeKind
import com.vibethroughcode.ftree.ui.more.MergeNoThanksTag
import com.vibethroughcode.ftree.ui.more.MergeReviewTag
import com.vibethroughcode.ftree.ui.more.MoreMergeTag
import com.vibethroughcode.ftree.ui.transfer.ImportCancelTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A phone with a shared tree open and a second tree of its own: More offers to bring the second into the first,
 * Review opens the ordinary import review, and No thanks stops the offer. On throwaway trees; nothing is
 * confirmed, so no tree is changed.
 */
@RunWith(AndroidJUnit4::class)
class MergeOfferFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var shared: TreeInfo
    private lateinit var own: TreeInfo

    @Before fun aSharedTreeAndAnotherWithPeople() {
        app.switchTo(TreeCatalog.ORIGINAL.id)
        val stamp = System.nanoTime()
        own = (app.catalog.create("Own $stamp", TreeKind.FAMILY) as CatalogResult.Ok).tree
        check(app.switchTo(own.id))
        runBlocking { app.container.familyRepository.addPerson(Person(name = "Priya")) }
        shared = (app.catalog.create("Shared $stamp", TreeKind.FAMILY) as CatalogResult.Ok).tree
        check(app.switchTo(shared.id))
        runBlocking { check(app.container.treeSync.share("merge-offer-test-$stamp")) }
    }

    @After fun cleanUp() {
        scenario?.close()
        app.switchTo(TreeCatalog.ORIGINAL.id)
        Thread.sleep(3_500)
        listOf(shared, own).forEach { t -> (app.catalog.remove(t.id) as? CatalogResult.Ok)?.let { app.deleteFilesOf(it.tree) } }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun openMore() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(8_000) { count(NavMoreTag) == 1 }
        compose.onNodeWithTag(NavMoreTag).performClick()
    }

    @Test fun moreOffersTheOtherTreeAndReviewOpensTheImportReview() {
        openMore()
        compose.waitUntil(8_000) { count(MoreMergeTag) == 1 }
        compose.onNodeWithText("Bring “${own.name}” into this tree").assertIsDisplayed()

        compose.onNodeWithTag(MoreMergeTag).performClick()
        compose.onNodeWithTag(MergeReviewTag).performClick()
        compose.waitUntil(15_000) { count(ImportCancelTag) == 1 }
        compose.onNodeWithTag(ImportCancelTag).performClick()
        assertEquals("backing out changed nothing", 0, runBlocking { app.container.familyRepository.allPeople().size })
        assertNull("and it is not offered again", runBlocking { app.mergeOffer() })
    }

    @Test fun noThanksStopsTheOffer() {
        openMore()
        compose.waitUntil(8_000) { count(MoreMergeTag) == 1 }
        compose.onNodeWithTag(MoreMergeTag).performClick()
        compose.onNodeWithTag(MergeNoThanksTag).performClick()
        compose.waitUntil(5_000) { count(MoreMergeTag) == 0 }
        assertNull(runBlocking { app.mergeOffer() })
    }
}
