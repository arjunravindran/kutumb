package com.vibethroughcode.ftree.kutumb.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    private fun place(name: String, cc: String, pop: Int, ascii: String = "") = Place(name, cc, 10.0, 20.0, pop, ascii)

    private val index = PlaceIndex(
        listOf(
            place("Chennai", "IN", 7_000_000),
            place("Chengdu", "CN", 9_000_000),
            place("Chelmsford", "GB", 110_000),
            place("New York City", "US", 8_000_000),
            place("York", "GB", 150_000),
            place("Yorkville", "US", 3_000),
            place("Zürich", "CH", 400_000, ascii = "Zurich"),
            place("Springfield", "US", 150_000),
            place("Springfield", "GB", 5_000),
            place("Kolkata", "IN", 4_500_000),
        ),
    )

    @Test fun aPrefixMatchesAndTheBiggerPlaceComesFirst() {
        assertEquals(listOf("Chengdu", "Chennai", "Chelmsford"), index.search("che").map { it.name })
    }

    @Test fun aNameStartingWithTheQueryBeatsOneWithAWordThatDoes() {
        // "York" the city before "New York City" and "Yorkville", though New York City is far bigger.
        assertEquals(listOf("York", "Yorkville", "New York City"), index.search("york").map { it.name })
    }

    @Test fun caseAndAccentsDoNotMatter() {
        assertEquals("Zürich", index.search("ZURICH").single().name)
        assertEquals("Zürich", index.search("zür").single().name)
    }

    @Test fun aPlainLetterSpellingIsSearchedToo() {
        val withAscii = PlaceIndex(listOf(place("München", "DE", 1_500_000, ascii = "Munich")))
        assertEquals("München", withAscii.search("munich").single().name)
    }

    @Test fun aCountryCodeAfterACommaNarrowsTheSearch() {
        assertEquals(listOf("GB"), index.search("springfield, gb").map { it.country })
        // Unfinished or not a code: no filter, both places.
        assertEquals(2, index.search("springfield, ").size)
        assertEquals(2, index.search("springfield, uk!").size)
    }

    @Test fun theLimitAndABlankQueryAreRespected() {
        assertEquals(2, index.search("c", limit = 2).size)
        assertTrue(index.search("   ").isEmpty())
        assertTrue(index.search("che", limit = 0).isEmpty())
        assertTrue(index.search("nowhere").isEmpty())
    }

    @Test fun theBundledFormatParsesAndBadLinesAreSkipped() {
        val text = listOf(
            "Chennai\t\tin\t13.08\t80.27\t7088000",
            "Zürich\tZurich\tCH\t47.37\t8.54\t341730",
            "no fields",
            "Nowhere\t\tXX\t95.0\t10.0\t1",       // latitude out of range
            "Nowhere\t\tXXX\t10.0\t10.0\t1",      // not a country code
            "\t\tIN\t10.0\t10.0\t1",              // no name
            "Badnumber\t\tIN\tx\t10.0\t1",
        ).joinToString("\n")
        val parsed = PlaceIndex.parse(text)
        assertEquals(2, parsed.size)
        val chennai = parsed.search("chennai").single()
        assertEquals("IN", chennai.country)
        assertEquals(13.08, chennai.lat, 1e-9)
        assertEquals("Zürich", parsed.search("zurich").single().name)
    }

    @Test fun nearestNamesAPositionAndGivesUpWhenNothingIsClose() {
        val cities = PlaceIndex(
            listOf(
                Place("Chennai", "IN", 13.09, 80.28, 4_000_000),
                Place("Kolkata", "IN", 22.57, 88.36, 4_000_000),
                Place("Tambaram", "IN", 12.92, 80.12, 1_000_000),
            ),
        )
        assertEquals("Chennai", cities.nearest(13.08, 80.27)?.name)
        // Nearer to Tambaram than Chennai.
        assertEquals("Tambaram", cities.nearest(12.93, 80.13)?.name)
        assertEquals(null, cities.nearest(0.0, 0.0))
        assertEquals("Chennai", cities.nearest(13.5, 80.28, withinKm = 100.0)?.name)
    }
}
