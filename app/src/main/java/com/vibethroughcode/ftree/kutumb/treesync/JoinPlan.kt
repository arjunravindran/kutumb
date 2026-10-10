package com.vibethroughcode.ftree.kutumb.treesync

/**
 * Whether a tree is little enough to be handed over to the one somebody is inviting this phone into.
 *
 * Somebody who has just installed the app has at most themselves and the person they met in the room (pairing
 * needs that person in the tree), perhaps joined by a line. That is nothing worth keeping beside a whole family,
 * so joining fills it in place. Anything more, or a tree already shared, was built on purpose and is left
 * alone: the invitation then opens a tree of its own.
 */
object JoinPlan {
    const val MOST_PEOPLE = 2
    const val MOST_RELATIONSHIPS = 1

    fun fillsInPlace(shared: Boolean, people: Int, relationships: Int): Boolean =
        !shared && people <= MOST_PEOPLE && relationships <= MOST_RELATIONSHIPS
}
