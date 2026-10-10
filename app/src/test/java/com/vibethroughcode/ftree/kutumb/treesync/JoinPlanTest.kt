package com.vibethroughcode.ftree.kutumb.treesync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinPlanTest {
    @Test fun anEmptyTreeOrOneWithJustTheTwoOfThemIsFilledInPlace() {
        assertTrue(JoinPlan.fillsInPlace(shared = false, people = 0, relationships = 0))
        assertTrue(JoinPlan.fillsInPlace(shared = false, people = 1, relationships = 0))
        assertTrue(JoinPlan.fillsInPlace(shared = false, people = 2, relationships = 1))
    }

    @Test fun aTreeSomebodyBuiltIsLeftAlone() {
        assertFalse(JoinPlan.fillsInPlace(shared = false, people = 3, relationships = 0))
        assertFalse(JoinPlan.fillsInPlace(shared = false, people = 2, relationships = 2))
    }

    @Test fun aTreeAlreadySharedIsNeverFilledOver() {
        assertFalse(JoinPlan.fillsInPlace(shared = true, people = 0, relationships = 0))
    }
}
