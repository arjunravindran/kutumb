package com.vibethroughcode.ftree.kutumb.treesync

/**
 * Whether to offer to bring another tree on this phone into the one that is open.
 *
 * It arises when a phone ends up with two trees, usually its own and the one it was invited into. The offer is
 * made only inside a shared family tree (the one worth growing), for the biggest other family tree that has
 * people in it and has not been waved away. A circle is never offered: it has no family to line up.
 */
object MergeSuggestion {
    data class Candidate(val id: String, val name: String, val people: Int, val family: Boolean)

    fun key(openId: String, otherId: String) = "$openId>$otherId"

    fun pick(
        openId: String,
        openIsFamily: Boolean,
        openIsShared: Boolean,
        others: List<Candidate>,
        waved: Set<String>,
    ): Candidate? {
        if (!openIsFamily || !openIsShared) return null
        return others
            .filter { it.id != openId && it.family && it.people > 0 && key(openId, it.id) !in waved }
            .maxByOrNull { it.people }
    }
}
