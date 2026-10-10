package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.kutumb.treesync.MergeSuggestion.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MergeSuggestionTest {
    private val mine = Candidate("mine", "Mine", 5, true)
    private val big = Candidate("big", "Big", 40, true)
    private fun pick(
        others: List<Candidate>, shared: Boolean = true, family: Boolean = true, waved: Set<String> = emptySet(),
    ) = MergeSuggestion.pick("open", family, shared, others, waved)

    @Test fun theBiggestOtherFamilyTreeIsOffered() {
        assertEquals(big, pick(listOf(mine, big)))
    }

    @Test fun nothingIsOfferedInsideATreeThatIsNotShared() {
        assertNull(pick(listOf(big), shared = false))
    }

    @Test fun nothingIsOfferedInsideACircle() {
        assertNull(pick(listOf(big), family = false))
    }

    @Test fun aCircleOrAnEmptyTreeOrTheOpenTreeItselfIsNotOffered() {
        val circle = Candidate("c", "Circle", 9, false)
        val empty = Candidate("e", "Empty", 0, true)
        val self = Candidate("open", "Open", 30, true)
        assertNull(pick(listOf(circle, empty, self)))
    }

    @Test fun aTreeWavedAwayIsNotOfferedAgainButAnotherIs() {
        val waved = setOf(MergeSuggestion.key("open", "big"))
        assertEquals(mine, pick(listOf(mine, big), waved = waved))
        assertNull(pick(listOf(big), waved = waved))
    }
}
