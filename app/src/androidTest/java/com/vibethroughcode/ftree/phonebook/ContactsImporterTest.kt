package com.vibethroughcode.ftree.phonebook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibethroughcode.ftree.data.FTreeDatabase
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.PhotoStore
import com.vibethroughcode.ftree.data.RelationshipType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Writing an import plan into a real tree: who ends up in it, and joined to whom. */
@RunWith(AndroidJUnit4::class)
class ContactsImporterTest {
    private lateinit var db: FTreeDatabase
    private lateinit var repo: FamilyRepository

    private val asha = PhoneContact(1, "Asha Devi", "1960-02-11")
    private val ravi = PhoneContact(2, "Ravi", null)
    private val meera = PhoneContact(3, "Meera", "--09-30")
    private val kiran = PhoneContact(4, "Kiran", null)
    private val dev = PhoneContact(5, "Dev", "2015-06-01")
    private val zoya = PhoneContact(6, "Zoya", null)
    private val all = listOf(asha, ravi, meera, kiran, dev, zoya)

    @Before fun anEmptyTree() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FTreeDatabase::class.java)
            .addCallback(FTreeDatabase.enforceForeignKeys)
            .allowMainThreadQueries()
            .build()
        repo = FamilyRepository(db, PhotoStore(context))
    }

    @After fun close() = db.close()

    private suspend fun byName(name: String): Person = repo.allPeople().single { it.name == name }

    private suspend fun edges() = db.relationshipDao().allRelationships()

    @Test fun anEmptyTreeIsBuiltAroundTheReaderWithEachPersonJoinedAsChosen() = runBlocking {
        val plan = ContactsImport.plan(
            me = "Priya",
            picks = mapOf(
                1L to ContactRelation.MOTHER, 2L to ContactRelation.FATHER, 3L to ContactRelation.SISTER,
                4L to ContactRelation.SPOUSE, 5L to ContactRelation.SON, 6L to ContactRelation.NOT_CONNECTED,
            ),
            contacts = all,
            existingNames = emptySet(),
        )
        val result = ContactsImporter(repo).apply(plan)

        assertEquals(7, repo.allPeople().size)
        assertEquals(ContactsImportResult(added = 7, connected = 5, notConnected = 1, alreadyThere = 0), result)

        val me = byName("Priya")
        val mother = byName("Asha Devi")
        val father = byName("Ravi")
        val sister = byName("Meera")
        val spouse = byName("Kiran")
        val son = byName("Dev")
        val stranger = byName("Zoya")

        val parentEdges = edges().filter { it.type == RelationshipType.PARENT }.map { it.fromPersonId to it.toPersonId }.toSet()
        assertTrue("mother is my parent", mother.id to me.id in parentEdges)
        assertTrue("father is my parent", father.id to me.id in parentEdges)
        assertTrue("I am my son's parent", me.id to son.id in parentEdges)
        assertTrue("the sister is attached to the same parents", mother.id to sister.id in parentEdges && father.id to sister.id in parentEdges)
        assertTrue("married", edges().any { it.type == RelationshipType.SPOUSE && setOf(it.fromPersonId, it.toPersonId) == setOf(me.id, spouse.id) })
        assertTrue("the one added without a connection has none", edges().none { stranger.id in listOf(it.fromPersonId, it.toPersonId) })

        assertEquals(Gender.FEMALE, mother.gender)
        assertEquals(Gender.MALE, father.gender)
        assertEquals(Gender.FEMALE, sister.gender)
        assertEquals(Gender.MALE, son.gender)
        assertEquals("1960-02-11", mother.birthDate)
        assertEquals("--09-30", sister.birthDate)
        assertEquals("2015-06-01", son.birthDate)
    }

    @Test fun aTreeThatAlreadyHasPeopleGetsThemUnconnectedAndWithoutDuplicates() = runBlocking {
        repo.addPerson(Person(name = "asha  devi"))
        val plan = ContactsImport.plan(
            me = null,
            picks = mapOf(1L to ContactRelation.MOTHER, 2L to ContactRelation.FATHER, 3L to ContactRelation.SISTER),
            contacts = all,
            existingNames = repo.allPeople().map { Phonebook.nameKey(it.name) }.toSet(),
        )
        val result = ContactsImporter(repo).apply(plan)

        assertEquals(setOf("asha  devi", "Ravi", "Meera"), repo.allPeople().map { it.name }.toSet())
        assertEquals(ContactsImportResult(added = 2, connected = 0, notConnected = 2, alreadyThere = 1), result)
        assertTrue("nobody was joined to anybody", edges().isEmpty())
    }

    @Test fun everyoneIsAddedEvenWhenTheTreesRulesRefuseAConnection() = runBlocking {
        val many = (1L..5L).map { PhoneContact(it, "Parent $it", null) }
        val plan = ContactsImport.plan("Me", many.associate { it.id to ContactRelation.MOTHER }, many, emptySet())
        val result = ContactsImporter(repo).apply(plan)

        assertEquals("the reader and all five", 6, repo.allPeople().size)
        assertEquals(6, result.added)
        assertEquals("nobody is lost, whether or not the rules connected them", 5, result.connected + result.notConnected)
    }

    @Test fun nothingChosenAddsNothing() = runBlocking {
        val result = ContactsImporter(repo).apply(ContactsImport.plan(null, emptyMap(), all, emptySet()))
        assertEquals(ContactsImportResult(0, 0, 0, 0), result)
        assertTrue(repo.allPeople().isEmpty())
    }
}
