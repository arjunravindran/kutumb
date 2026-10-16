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

/** Two entries for one person folded into one: a mother added twice ends as one mother with all her links. */
@RunWith(AndroidJUnit4::class)
class MergePeopleTest {
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

    private suspend fun edges() = repo.allRelationships()
    private suspend fun parentPairs() =
        edges().filter { it.type == RelationshipType.PARENT }.map { it.fromPersonId to it.toPersonId }.toSet()

    @Test fun aMotherAddedTwiceBecomesOneMotherWithAllHerLinks() = runBlocking {
        val me = repo.addPerson(Person(name = "Me"))
        val mother = repo.addNewRelative(me.id, Person(name = "Asha"), RelativeKind.PARENT).getOrThrow()
        val father = repo.addNewRelative(me.id, Person(name = "Ravi"), RelativeKind.PARENT).getOrThrow()
        // The mistake: from his page, "add a wife" made a second Asha.
        val copy = repo.addNewRelative(father.id, Person(name = "Asha", birthDate = "1960-02-11"), RelativeKind.SPOUSE).getOrThrow()

        repo.mergePeople(keepId = mother.id, dropId = copy.id).getOrThrow()

        val people = repo.allPeople()
        assertEquals(3, people.size)
        assertEquals("her birth date came across", "1960-02-11", people.single { it.id == mother.id }.birthDate)
        assertTrue("she is my mother", mother.id to me.id in parentPairs())
        assertTrue(
            "and my father's wife",
            edges().any { it.type == RelationshipType.SPOUSE && setOf(it.fromPersonId, it.toPersonId) == setOf(father.id, mother.id) },
        )
        assertEquals("no link is doubled", edges().size, edges().map { Triple(it.fromPersonId, it.toPersonId, it.type) }.toSet().size)
    }

    @Test fun linksBothEntriesHadAreNotDoubled() = runBlocking {
        val kid = repo.addPerson(Person(name = "Kid"))
        val a = repo.addNewRelative(kid.id, Person(name = "Asha"), RelativeKind.PARENT).getOrThrow()
        val b = repo.addNewRelative(kid.id, Person(name = "Asha"), RelativeKind.PARENT).getOrThrow()

        repo.mergePeople(a.id, b.id).getOrThrow()

        assertEquals(setOf(a.id to kid.id), parentPairs())
        assertEquals(2, repo.allPeople().size)
    }

    @Test fun theKeptEntrysOwnDetailsWinAndNotesAreBothKept() = runBlocking {
        val keep = repo.addPerson(Person(name = "Asha", birthDate = "1960", notes = "from my side"))
        val drop = repo.addPerson(Person(name = "Asha D", birthDate = "1961", notes = "from his side", deceased = true))

        repo.mergePeople(keep.id, drop.id).getOrThrow()

        val merged = repo.allPeople().single()
        assertEquals("Asha", merged.name)
        assertEquals("1960", merged.birthDate)
        assertEquals("from my side\n\nfrom his side", merged.notes)
        assertTrue(merged.deceased)
    }

    @Test fun aParentAndTheirChildCannotBeOnePersonAndNothingChanges() = runBlocking {
        val child = repo.addPerson(Person(name = "Child"))
        val parent = repo.addNewRelative(child.id, Person(name = "Parent"), RelativeKind.PARENT).getOrThrow()

        val result = repo.mergePeople(parent.id, child.id)

        assertTrue(result.isFailure)
        assertEquals(2, repo.allPeople().size)
        assertEquals(1, edges().size)
    }

    @Test fun aMergeThatWouldMakeSomeoneTheirOwnAncestorIsRefusedAndUndone() = runBlocking {
        val grand = repo.addPerson(Person(name = "A"))
        val mid = repo.addNewRelative(grand.id, Person(name = "B"), RelativeKind.CHILD).getOrThrow()
        val last = repo.addNewRelative(mid.id, Person(name = "C"), RelativeKind.CHILD).getOrThrow()

        val result = repo.mergePeople(grand.id, last.id)

        assertTrue(result.isFailure)
        assertEquals(3, repo.allPeople().size)
        assertEquals(setOf(grand.id to mid.id, mid.id to last.id), parentPairs())
    }

    @Test fun aMergeCanBeTakenBackAndEverythingIsAsItWas() = runBlocking {
        val me = repo.addPerson(Person(name = "Me"))
        val mother = repo.addNewRelative(me.id, Person(name = "Asha"), RelativeKind.PARENT).getOrThrow()
        val father = repo.addNewRelative(me.id, Person(name = "Ravi"), RelativeKind.PARENT).getOrThrow()
        val copy = repo.addNewRelative(father.id, Person(name = "Asha", birthDate = "1960-02-11"), RelativeKind.SPOUSE).getOrThrow()
        val linksBefore = edges().size

        val receipt = repo.mergePeopleUndoably(mother.id, copy.id).getOrThrow()
        assertEquals(3, repo.allPeople().size)
        val back = repo.undoMerge(receipt).getOrThrow()

        val people = repo.allPeople()
        assertEquals("both entries are there again", 4, people.size)
        assertEquals("the kept one lost the borrowed birth date", null, people.single { it.id == mother.id }.birthDate)
        assertEquals("the returned one has its own", "1960-02-11", people.single { it.id == back.id }.birthDate)
        assertTrue(
            "the returned one is his wife again",
            edges().any { it.type == RelationshipType.SPOUSE && setOf(it.fromPersonId, it.toPersonId) == setOf(father.id, back.id) },
        )
        assertTrue(
            "and the kept one is no longer his wife",
            edges().none { it.type == RelationshipType.SPOUSE && setOf(it.fromPersonId, it.toPersonId) == setOf(father.id, mother.id) },
        )
        assertTrue("she is still my mother", mother.id to me.id in parentPairs())
        assertEquals("as many links as before", linksBefore, edges().size)
    }

    @Test fun aDetailChangedAfterTheMergeIsNotUndone() = runBlocking {
        val keep = repo.addPerson(Person(name = "Asha", notes = "mine"))
        val drop = repo.addPerson(Person(name = "Asha D", birthDate = "1961"))
        val receipt = repo.mergePeopleUndoably(keep.id, drop.id).getOrThrow()
        repo.updatePerson(repo.allPeople().single().copy(notes = "edited since"))

        repo.undoMerge(receipt).getOrThrow()

        val kept = repo.allPeople().single { it.id == keep.id }
        assertEquals("edited since", kept.notes)
        assertEquals("but the borrowed date goes back", null, kept.birthDate)
    }
}
