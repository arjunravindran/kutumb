package com.vibethroughcode.ftree.kutumb.geo

import java.text.Normalizer

/** A named place with a position, from the bundled list of towns and cities. */
data class Place(
    val name: String,
    /** ISO 3166-1 alpha-2 country code, upper case. */
    val country: String,
    val lat: Double,
    val lon: Double,
    val population: Int,
    /** The name in plain Latin letters when it differs from [name] (Zürich, Zurich); empty otherwise. */
    val ascii: String = "",
)

/**
 * Finds a place by typing part of its name, with no network and no permission: the list is bundled,
 * so choosing a birthplace sends nothing anywhere.
 *
 * Matching ignores case and accents, in either the local or the plain-letter spelling. A name that
 * starts with what was typed comes first, then one with a word that does ("York" for "New York"),
 * then one that merely contains it; within each, the bigger place first, since that is usually the
 * one meant.
 */
class PlaceIndex(private val places: List<Place>) {
    private class Entry(val place: Place, val keys: List<String>)

    private val entries = places.map { p -> Entry(p, listOf(fold(p.name), fold(p.ascii)).filter { it.isNotEmpty() }.distinct()) }

    val size: Int get() = places.size

    /** At most [limit] places matching [query]; empty for a blank query. A trailing ", IN" limits it to that country. */
    fun search(query: String, limit: Int = 8): List<Place> {
        val (text, country) = split(query)
        val q = fold(text)
        if (q.isEmpty() || limit <= 0) return emptyList()

        return entries.asSequence()
            .filter { country == null || it.place.country == country }
            .mapNotNull { e -> tier(e.keys, q)?.let { it to e.place } }
            .sortedWith(compareBy<Pair<Int, Place>> { it.first }.thenByDescending { it.second.population }.thenBy { it.second.name })
            .take(limit)
            .map { it.second }
            .toList()
    }

    /**
     * The listed place closest to a position, or null if none is within [withinKm]. A saved place is
     * only a latitude and longitude, so this is how it gets its name back.
     */
    fun nearest(lat: Double, lon: Double, withinKm: Double = 25.0): Place? {
        var best: Place? = null
        var bestKm = withinKm
        for (p in places) {
            // A degree of latitude is about 111 km; skip what cannot be near before doing the trigonometry.
            if (kotlin.math.abs(p.lat - lat) * 111.0 > bestKm) continue
            val km = Geo.haversineKm(lat, lon, p.lat, p.lon)
            if (km <= bestKm) { best = p; bestKm = km }
        }
        return best
    }

    /** 0 = starts with it, 1 = a word starts with it, 2 = contains it, null = no match. */
    private fun tier(keys: List<String>, q: String): Int? {
        var best: Int? = null
        for (key in keys) {
            val t = when {
                key.startsWith(q) -> 0
                key.contains(" $q") || key.contains("-$q") -> 1
                key.contains(q) -> 2
                else -> continue
            }
            if (best == null || t < best) best = t
        }
        return best
    }

    private fun split(query: String): Pair<String, String?> {
        val comma = query.lastIndexOf(',')
        if (comma < 0) return query to null
        val code = query.substring(comma + 1).trim()
        // "Springfield, " is still being typed: no filter yet. Anything but two letters is not a code.
        return if (code.length == 2 && code.all { it.isLetter() }) query.substring(0, comma) to code.uppercase() else query.substring(0, comma) to null
    }

    companion object {
        /**
         * Reads the bundled list: one place per line, tab separated, as
         * `name, ascii (empty if the same), country, latitude, longitude, population`.
         * Lines that do not parse are skipped rather than failing the whole list.
         */
        fun parse(text: String): PlaceIndex = PlaceIndex(
            text.lineSequence().mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 6) return@mapNotNull null
                val lat = f[3].toDoubleOrNull() ?: return@mapNotNull null
                val lon = f[4].toDoubleOrNull() ?: return@mapNotNull null
                if (f[0].isBlank() || f[2].length != 2 || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                Place(f[0], f[2].uppercase(), lat, lon, f[5].toIntOrNull() ?: 0, f[1])
            }.toList(),
        )

        /** Lower case, accents removed, runs of spaces collapsed: what two spellings of a name have in common. */
        fun fold(text: String): String =
            Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                .replace(Regex("""\s+"""), " ")
    }
}
