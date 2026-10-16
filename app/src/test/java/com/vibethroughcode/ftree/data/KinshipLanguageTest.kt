package com.vibethroughcode.ftree.data

import com.vibethroughcode.ftree.graph.HindiKinTerm
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KinshipLanguageTest {
    @Test fun theTenMostSpokenLanguagesAndIndiasFiveAreOfferedAndSixHaveWords() {
        // World top ten (English, Mandarin, Hindi, Spanish, Arabic, French, Bengali, Portuguese, Russian, Urdu)
        // plus India's top five (Hindi, Bengali, Marathi, Telugu, Tamil): thirteen once the overlap is counted.
        assertEquals(13, KinshipLanguage.entries.size)
        assertEquals(
            setOf(
                KinshipLanguage.ENGLISH, KinshipLanguage.HINDI,
                KinshipLanguage.BENGALI, KinshipLanguage.MARATHI, KinshipLanguage.TELUGU, KinshipLanguage.TAMIL,
            ),
            KinshipLanguage.entries.filter { it.hasWords }.toSet(),
        )
    }

    @Test fun onlyTheFourUnreviewedLanguagesAreDrafts() {
        assertEquals(
            setOf(KinshipLanguage.BENGALI, KinshipLanguage.MARATHI, KinshipLanguage.TELUGU, KinshipLanguage.TAMIL),
            KinshipLanguage.entries.filter { it.isDraft }.toSet(),
        )
    }

    /**
     * Every term the rules can return needs a spelling in every language that claims to have words, or the
     * screen would meet a missing resource. The generated Kotlin already fails to compile without one; this
     * also catches an empty entry, and a draft file that has drifted from the Hindi enum.
     */
    @Test fun everyDraftLanguageSpellsEveryTerm() {
        val wanted = HindiKinTerm.entries.map { it.name.lowercase() }
        for (code in listOf("mr", "bn", "te", "ta")) {
            val file = File("src/main/res/values/kinship_$code.xml")
            assertTrue("missing ${file.path}", file.exists())
            val entries = Regex("""<string name="kin_${code}_([a-z_]+)"[^>]*>([^<]*)</string>""")
                .findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
            assertEquals("$code has the wrong set of terms", wanted.toSet(), entries.keys)
            entries.forEach { (term, word) -> assertTrue("$code/$term is blank", word.isNotBlank()) }
        }
    }
}
