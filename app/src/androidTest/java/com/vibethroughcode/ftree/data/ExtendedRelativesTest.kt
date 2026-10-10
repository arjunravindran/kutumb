package com.vibethroughcode.ftree.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uncles, aunts and cousins added in one step: the same ordinary links, with a stand-in where nobody is named yet. */
@RunWith(AndroidJUnit4::class)
class ExtendedRelativesTest {
    private lateinit var db: FTreeDatabase
    private lateinit var repo: FamilyRepository

    @Before fun anEmptyTree() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FTreeDatabase::class.java)
            .addCallback(FTreeDatabase.enforceForeignKeys)
            .allowMainThreadQueries()
            .build()
        repo = FamilyRepository(db, PhotoStore(context))
    }

    @After fun close() = db.close()

    private suspend fun byName(name: String) = repo.allPeople().single { it.name == name }
    private suspend fun parentEdges() =
        repo.allRelationships().filter { it.type == RelationshipType.PARENT }.map { it.fromPersonId to it.toPersonId }.toSet()

    @Test fun anUncleIsAddedAsABrotherOfAParentAndSharesTheGrandparents() = runBlocking {
        val me = repo.addPerson(Person(name = "Me"))
        val mother = repo.addNewRelative(me.id, Person(name = "Mother"), RelativeKind.PARENT).getOrThrow()
        val grandma = repo.addNewRelative(mother.id, Person(name = "Grandma"), RelativeKind.PARENT).getOrThrow()

        val uncle = repo.addUncleOrAunt(me.id, mother.id, Person(name = "Uncle Raj")).getOrThrow()

        assertTrue("the uncle is a child of my grandmother, as my mother is", grandma.id to uncle.id in parentEdges())
        assertEquals("nobody else was made", 4, repo.allPeople().size)
    }

    @Test fun anUncleWhenNoParentIsRecordedMakesAnUnknownParentToHangFrom() = runBlocking {
        val me = repo.addPerson(Person(name = "Me"))
        val uncle = repo.addUncleOrAunt(me.id, null, Person(name = "Uncle Raj")).getOrThrow()

        val people = repo.allPeople()
        assertEquals(3, people.size)
        val unknown = people.single { it.name == null }
        assertTrue("the stand-in is my parent", unknown.id to me.id in parentEdges())
        assertTrue(
            "and joined to the uncle as brother or sister",
            repo.allRelationships().any {
                it.type == RelationshipType.SIBLING && setOf(it.fromPersonId, it.toPersonId) == setOf(unknown.id, uncle.id)
            },
        )
    }

    @Test fun aCousinIsAChildOfAnAuntAlreadyInTheTree() = runBlocking {
        val me = repo.addPerson(Person(name = "Me"))
        val mother = repo.addNewRelative(me.id, Person(name = "Mother"), RelativeKind.PARENT).getOrThrow()
        val aunt = repo.addUncleOrAunt(me.id, mother.id, Person(name = "Aunt Sita")).getOrThrow()

        val cousin = repo.addCousin(me.id, aunt.id, null, Person(name = "Cousin Dev")).getOrThrow()

        assertTrue(aunt.id to cousin.id in parentEdges())
        assertEquals(4, repo.allPeople().size)
    }

    @Test fun aCousinWithNoAuntYetMakesAnUnknownOneBesideTheParent() = runBlocking {
        val me = repo.addPerson(Person(name = "Me"))
        val mother = repo.addNewRelative(me.id, Person(name = "Mother"), RelativeKind.PARENT).getOrThrow()

        val cousin = repo.addCousin(me.id, null, mother.id, Person(name = "Cousin Dev")).getOrThrow()

        val stand = repo.allPeople().single { it.name == null }
        assertTrue("the cousin's parent is the stand-in", stand.id to cousin.id in parentEdges())
        assertTrue(
            "who is a brother or sister of my mother",
            repo.allRelationships().any {
                it.type == RelationshipType.SIBLING && setOf(it.fromPersonId, it.toPersonId) == setOf(mother.id, stand.id)
            },
        )
        assertEquals(4, repo.allPeople().size)
    }

    @Test fun aRefusedLinkLeavesNothingBehind() = runBlocking {
        val me = repo.addPerson(Person(name = "Me"))
        val result = repo.addUncleOrAunt(me.id, "no-such-parent", Person(name = "Ghost"))
        assertTrue(result.isFailure)
        assertEquals("no half-added people", 1, repo.allPeople().size)
    }

    @Test fun somebodyAddedEarlierWithNoConnectionIsJoinedUpAsMyFathersBrother() = runBlocking {
        val me = repo.addPerson(Person(name = "Me"))
        val father = repo.addNewRelative(me.id, Person(name = "Father"), RelativeKind.PARENT).getOrThrow()
        val raj = repo.addPerson(Person(name = "Raj"))
        assertTrue("nothing joins Raj to anybody yet", repo.allRelationships().none { raj.id in listOf(it.fromPersonId, it.toPersonId) })

        val joined = repo.addUncleOrAunt(me.id, father.id, relative = null, existingId = raj.id).getOrThrow()

        assertEquals(raj.id, joined.id)
        assertEquals("no extra person was made", 3, repo.allPeople().size)
        assertTrue(
            "Raj is a brother of my father",
            repo.allRelationships().any { it.type == RelationshipType.SIBLING && setOf(it.fromPersonId, it.toPersonId) == setOf(father.id, raj.id) },
        )
    }

    @Test fun aNephewIsAChildOfMyBrotherAndOneWithNoBrotherGetsAStandIn() = runBlocking {
        val me = repo.addPerson(Person(name = "Me"))
        val brother = repo.addNewRelative(me.id, Person(name = "Brother"), RelativeKind.SIBLING).getOrThrow()
        val nephew = repo.addNephewOrNiece(me.id, brother.id, Person(name = "Nephew")).getOrThrow()
        assertTrue(brother.id to nephew.id in parentEdges())

        val loner = repo.addPerson(Person(name = "Loner"))
        val niece = repo.addNephewOrNiece(loner.id, null, Person(name = "Niece")).getOrThrow()
        val stand = repo.allPeople().filter { it.name == null }.single()
        assertTrue(stand.id to niece.id in parentEdges())
    }

    @Test fun aGrandparentAndAGrandchildGoInThroughTheMiddleGeneration() = runBlocking {
        val me = repo.addPerson(Person(name = "Me"))
        val mother = repo.addNewRelative(me.id, Person(name = "Mother"), RelativeKind.PARENT).getOrThrow()
        val nani = repo.addGrandparent(me.id, mother.id, Person(name = "Nani")).getOrThrow()
        assertTrue(nani.id to mother.id in parentEdges())

        val grandchild = repo.addGrandchild(me.id, null, Person(name = "Grandchild")).getOrThrow()
        val child = repo.allPeople().single { it.name == null }
        assertTrue(me.id to child.id in parentEdges() && child.id to grandchild.id in parentEdges())
    }

    @Test fun aConnectionTheRulesRefuseUndoesEverything() = runBlocking {
        val me = repo.addPerson(Person(name = "Me"))
        val child = repo.addNewRelative(me.id, Person(name = "Child"), RelativeKind.CHILD).getOrThrow()
        // My own child cannot also be my grandparent.
        val result = repo.addGrandparent(me.id, null, relative = null, existingId = child.id)
        assertTrue(result.isFailure)
        assertEquals("the stand-in parent was not left behind", 2, repo.allPeople().size)
    }
}
