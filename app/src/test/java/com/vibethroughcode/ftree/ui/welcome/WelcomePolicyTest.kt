package com.vibethroughcode.ftree.ui.welcome

import org.junit.Assert.assertEquals
import org.junit.Test

class WelcomePolicyTest {
    private fun decide(stored: Boolean?, test: Boolean = false, people: Int = 0) =
        WelcomePolicy.decide(stored, runningUnderTest = test, people = people)

    @Test fun aNewInstallWithAnEmptyTreeIsWelcomed() {
        assertEquals(WelcomeAction.SHOW, decide(stored = null, people = 0))
    }

    @Test fun somebodyWhoUpdatedAndAlreadyHasPeopleIsNotWelcomedButIsMarked() {
        assertEquals(WelcomeAction.MARK_SEEN, decide(stored = null, people = 3))
    }

    @Test fun onceWelcomedNeverAgain_whateverTheTreeHolds() {
        assertEquals(WelcomeAction.NOTHING, decide(stored = true, people = 0))
        assertEquals(WelcomeAction.NOTHING, decide(stored = true, people = 9))
    }

    @Test fun anInstrumentedTestIsNotWelcomedUnlessItAsksToBe() {
        assertEquals(WelcomeAction.NOTHING, decide(stored = null, test = true, people = 0))
        assertEquals(WelcomeAction.NOTHING, decide(stored = null, test = true, people = 4))
        assertEquals("asking is storing false", WelcomeAction.SHOW, decide(stored = false, test = true, people = 0))
    }

    @Test fun askingToSeeItAgainShowsItEvenOverAFullTree() {
        assertEquals(WelcomeAction.SHOW, decide(stored = false, people = 5))
    }
}
