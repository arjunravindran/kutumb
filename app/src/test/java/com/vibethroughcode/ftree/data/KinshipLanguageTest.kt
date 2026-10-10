package com.vibethroughcode.ftree.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KinshipLanguageTest {
    @Test fun theTenMostSpokenLanguagesAndIndiasFiveAreOfferedAndOnlyTwoHaveWordsYet() {
        // World top ten (English, Mandarin, Hindi, Spanish, Arabic, French, Bengali, Portuguese, Russian, Urdu)
        // plus India's top five (Hindi, Bengali, Marathi, Telugu, Tamil): thirteen once the overlap is counted.
        assertEquals(13, KinshipLanguage.entries.size)
        assertEquals(setOf(KinshipLanguage.ENGLISH, KinshipLanguage.HINDI), KinshipLanguage.entries.filter { it.hasWords }.toSet())
    }
}
