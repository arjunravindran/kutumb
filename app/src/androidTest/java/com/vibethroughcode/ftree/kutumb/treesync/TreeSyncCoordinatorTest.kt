package com.vibethroughcode.ftree.kutumb.treesync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibethroughcode.ftree.data.DeletionMode
import com.vibethroughcode.ftree.data.FTreeDatabase
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.PhotoStore
import com.vibethroughcode.ftree.data.RelativeKind
import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import com.vibethroughcode.ftree.kutumb.sync.UnsignedEvent
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tree sync over real Room databases: two phones, the real repository, and operations carried between them. */
@RunWith(AndroidJUnit4::class)
class TreeSyncCoordinatorTest {

    /** One phone: its own database, repository and coordinator, and a clock the test can move. */
    private inner class Phone(val name: String, start: Long) {
        var wall = start
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val db: FTreeDatabase = Room.inMemoryDatabaseBuilder(context, FTreeDatabase::class.java)
            .addCallback(FTreeDatabase.enforceForeignKeys)
            .allowMainThreadQueries()
            .build()
        val repo = FamilyRepository(db, PhotoStore(context))
        var ids = 0
        val sync = TreeSyncCoordinator(db, null, { wall }, { "$name-id${ids++}" })

        fun tick(ms: Long = 10) { wall += ms }

        suspend fun person(id: String, name: String? = null, gender: Gender = Gender.UNSPECIFIED) {
            repo.addPerson(Person(id = id, name = name, gender = gender))
            tick()
        }

        /** What the tables hold, reduced to what sync carries. */
        suspend fun tree(): DbTree = DbTree(
            db.personDao().allPeople().associate { it.id to PersonData(it.name, it.gender.name, it.birthDate, it.deathDate, it.deceased, it.notes) },
            db.relationshipDao().allRelationships().associate { EdgeKey.of(it.type.name, it.fromPersonId, it.toPersonId) to it.subtype },
        )
    }

    private val phones = ArrayList<Phone>()
    private fun phone(name: String, start: Long = 1_000_000L) = Phone(name, start).also { phones += it }

    @Before fun noPhones() = phones.clear()
    @After fun closeAll() = phones.forEach { it.db.close() }

    @Test fun aTreeThatIsNotSharedIsLeftAlone() = runBlocking {
        val a = phone("a")
        a.person("p", "Asha")
        assertFalse(a.sync.isShared())
        assertEquals(0, a.sync.reconcile())
        assertEquals(Received(0, 0, 0), a.sync.receive(listOf(TreeOp.PersonAdd("x", Hlc(1, 0, "z"), "ghost"))))
        assertEquals(0, a.db.treeSyncDao().opCount())
        assertEquals(setOf("p"), a.tree().people.keys)
    }

    @Test fun sharingAdoptsWhatIsAlreadyThereAndLeavesNothingToRecord() = runBlocking {
        val a = phone("a")
        a.person("me", "Me"); a.person("dad", "Dad")
        a.repo.addRelative("me", "dad", RelativeKind.PARENT)
        assertTrue(a.sync.share("tree-1"))
        assertFalse(a.sync.share("tree-1"))
        assertEquals("tree-1", a.sync.sharedId())
        assertTrue(a.db.treeSyncDao().opCount() >= 3)
        assertEquals("nothing changed since, so nothing to record", 0, a.sync.reconcile())
    }

    @Test fun everyWayTheUserChangesTheTreeIsRecorded() = runBlocking {
        val a = phone("a")
        a.sync.share("t")
        a.person("me", "Me"); a.person("dad", "Dad"); a.person("gran", "Gran")
        assertTrue("three people and their names", a.sync.reconcile() >= 4)

        a.repo.addRelative("me", "dad", RelativeKind.PARENT)
        assertEquals(1, a.sync.reconcile())

        a.repo.updatePerson(a.repo.person("me")!!.copy(name = "Me Myself"))
        assertEquals("only the changed field", 1, a.sync.reconcile())

        a.repo.deletePerson("gran", DeletionMode.KEEP_AS_UNKNOWN)
        assertEquals("the name is cleared", 1, a.sync.reconcile())

        a.repo.deletePerson("gran", DeletionMode.DELETE_COMPLETELY)
        assertEquals(1, a.sync.reconcile())

        val edge = a.db.relationshipDao().allRelationships().single()
        a.repo.removeRelationship(edge.id)
        assertEquals(1, a.sync.reconcile())
        assertEquals(0, a.sync.reconcile())
    }

    @Test fun twoPhonesEndUpWithTheSameTreeInBothDirections() = runBlocking {
        val a = phone("a"); val b = phone("b")
        a.person("me", "Me", Gender.MALE); a.person("dad", "Dad", Gender.MALE)
        a.repo.addRelative("me", "dad", RelativeKind.PARENT)
        a.sync.share("t")
        b.sync.share("t")
        b.tick(5_000)

        val toB = b.sync.receive(a.sync.allOps())
        assertTrue(toB.applied > 0)
        assertEquals(a.tree(), b.tree())

        // B adds a relative and edits someone.
        b.repo.addNewRelative("dad", Person(id = "gran", name = "Gran", gender = Gender.FEMALE), RelativeKind.PARENT)
        b.tick()
        b.repo.updatePerson(b.repo.person("me")!!.copy(birthDate = "1990-05"))
        b.sync.reconcile()
        a.tick(20_000)
        a.sync.receive(b.sync.unpublished())
        assertEquals(b.tree(), a.tree())
        assertEquals("1990-05", a.repo.person("me")!!.birthDate)
        assertEquals(setOf("me", "dad", "gran"), a.tree().people.keys)
    }

    @Test fun editsToDifferentFieldsOfOnePersonBothSurvive() = runBlocking {
        val a = phone("a"); val b = phone("b")
        a.person("p", "Asha"); a.sync.share("t"); b.sync.share("t")
        b.sync.receive(a.sync.allOps())

        a.tick(); a.repo.updatePerson(a.repo.person("p")!!.copy(notes = "Born in Pune")); a.sync.reconcile()
        b.tick(); b.repo.updatePerson(b.repo.person("p")!!.copy(birthDate = "1950")); b.sync.reconcile()
        val fromA = a.sync.unpublished(); val fromB = b.sync.unpublished()
        a.sync.receive(fromB); b.sync.receive(fromA)

        assertEquals(a.tree(), b.tree())
        val p = a.repo.person("p")!!
        assertEquals("Born in Pune", p.notes)
        assertEquals("1950", p.birthDate)
    }

    @Test fun aDeletionBeatsAnEditAndTheEditWaitsInTheInbox() = runBlocking {
        val a = phone("a"); val b = phone("b")
        a.person("p", "Asha"); a.sync.share("t"); b.sync.share("t"); b.sync.receive(a.sync.allOps())

        a.tick(100); a.repo.deletePerson("p", DeletionMode.DELETE_COMPLETELY); a.sync.reconcile()
        b.tick(500); b.repo.updatePerson(b.repo.person("p")!!.copy(notes = "after the delete")); b.sync.reconcile()
        val fromA = a.sync.unpublished(); val fromB = b.sync.unpublished()
        a.sync.receive(fromB); b.sync.receive(fromA)

        assertTrue(a.tree().people.isEmpty() && b.tree().people.isEmpty())
        for (p in listOf(a, b)) {
            val item = p.sync.inbox().filterIsInstance<InboxItem.EditAfterDelete>().single()
            assertEquals("p", item.personId)
        }
    }

    @Test fun deliveringTheSameBatchAgainChangesNothing() = runBlocking {
        val a = phone("a"); val b = phone("b")
        a.person("p", "Asha"); a.sync.share("t"); b.sync.share("t")
        val ops = a.sync.allOps()
        b.sync.receive(ops)
        val before = b.tree()
        val rows = b.db.treeSyncDao().opCount()
        val again = b.sync.receive(ops)
        assertEquals(0, again.applied)
        assertEquals(before, b.tree())
        assertEquals(rows, b.db.treeSyncDao().opCount())
    }

    @Test fun aRestartRebuildsEverythingFromTheLog() = runBlocking {
        val a = phone("a"); val b = phone("b")
        a.person("p", "Asha"); a.person("q", "Kiran"); a.sync.share("t"); b.sync.share("t")
        a.repo.addRelative("p", "q", RelativeKind.SPOUSE); a.sync.reconcile()
        b.sync.receive(a.sync.allOps())

        val restarted = TreeSyncCoordinator(b.db, null, { b.wall }, { "again-${b.ids++}" })
        assertEquals("the rebuilt tree already matches the tables", 0, restarted.reconcile())
        assertEquals(b.tree(), a.tree())
        assertNotNull(restarted.sharedId())
    }

    @Test fun operationsStampedFarAheadAreKeptAndAppliedOnceTheClockCatchesUp() = runBlocking {
        val a = phone("a"); val b = phone("b")
        a.sync.share("t"); b.sync.share("t")
        val threeDays = 3 * 24 * 3_600_000L
        val farFuture = TreeOp.PersonAdd("late", Hlc(b.wall + threeDays, 0, "a"), "late-p")
        assertEquals(1, b.sync.receive(listOf(farFuture)).deferred)
        assertTrue(b.tree().people.isEmpty())

        b.wall += threeDays
        b.sync.receive(emptyList())
        assertEquals(setOf("late-p"), b.tree().people.keys)
    }

    @Test fun theObserverRecordsChangesMadeAnyWayAtAll() = runBlocking {
        val a = phone("a")
        a.sync.share("t")
        val handle = a.sync.start(CoroutineScope(SupervisorJob() + Dispatchers.IO), settleMs = 20)
        try {
            a.repo.addPerson(Person(id = "seen", name = "Seen"))
            val deadline = System.currentTimeMillis() + 5_000
            while (a.db.treeSyncDao().opCount() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertTrue("a change was recorded without anyone calling reconcile", a.db.treeSyncDao().opCount() > 0)
        } finally {
            handle.close()
        }
    }

    // --- members and the wire, end to end -------------------------------------------------------------

    @Test fun membersCanOnlyBeAddedToASharedTree() = runBlocking {
        val a = phone("a")
        assertFalse(a.sync.addMember("kiran"))
        a.sync.share("t")
        assertTrue(a.sync.addMember("kiran"))
        assertFalse("already a member", a.sync.addMember("kiran"))
        assertTrue(a.sync.isMember("kiran"))
        a.sync.removeMember("kiran")
        assertFalse(a.sync.isMember("kiran"))
    }

    /** A relay that hands each envelope to the other phone's real handler, after a real trip through JSON. */
    private inner class Link(val from: Phone, val to: Phone, val fromAsSeenByTo: TrustedContact, val toPersonId: String) : SyncPublisher {
        val handler = TreeSyncHandler(to.sync)
        var up = true
        override suspend fun publish(envelope: SyncEnvelope, personIds: Collection<String>): Boolean {
            if (!up || toPersonId !in personIds) return false
            val onTheWire = SyncEnvelope.parse(envelope.toJson())!!
            handler.onEnvelope(onTheWire, fromAsSeenByTo, UnsignedEvent("c".repeat(64), 1, 30078, emptyList(), ""))
            return true
        }
    }

    @Test fun phonesKeepEachOthersTreesInStepThroughTheRealWire() = runBlocking {
        val a = phone("a"); val b = phone("b")
        val aAsSeenByB = TrustedContact("anna", "a".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.DIRECT)
        val bAsSeenByA = TrustedContact("ben", "b".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.DIRECT)
        val aToB = Link(a, b, aAsSeenByB, toPersonId = "ben")
        val bToA = Link(b, a, bAsSeenByA, toPersonId = "anna")
        val aPublisher = TreeSyncPublisher(a.sync, aToB)
        val bPublisher = TreeSyncPublisher(b.sync, bToA)

        // Anna has a tree and shares it with Ben, who starts with an empty tree of the same id.
        a.person("anna", "Anna"); a.person("dad", "Dad")
        a.repo.addRelative("anna", "dad", RelativeKind.PARENT)
        a.sync.share("t"); b.sync.share("t")
        b.sync.addMember("anna")
        assertTrue(shareTreeWith(bAsSeenByA, a.sync, aPublisher))
        assertEquals("Ben now has Anna's whole tree", a.tree(), b.tree())

        // Ben adds someone; the change reaches Anna with no one calling anything but reconcile and flush.
        b.tick(1_000)
        b.repo.addNewRelative("dad", Person(id = "gran", name = "Gran"), RelativeKind.PARENT)
        b.sync.reconcile()
        assertTrue(bPublisher.flush() > 0)
        assertEquals(b.tree(), a.tree())
        assertEquals(0, bPublisher.flush())

        // Anna edits while the link is down: nothing is lost, and it goes when the link is back.
        aToB.up = false
        a.tick(1_000)
        a.repo.updatePerson(a.repo.person("anna")!!.copy(birthDate = "1990"))
        a.sync.reconcile()
        assertEquals(0, aPublisher.flush())
        assertTrue(a.tree() != b.tree())
        aToB.up = true
        assertTrue(aPublisher.flush() > 0)
        assertEquals(a.tree(), b.tree())
    }

    @Test fun someoneNotAMemberCannotWriteIntoTheTree() = runBlocking {
        val a = phone("a"); val b = phone("b")
        a.sync.share("t"); b.sync.share("t")
        // Ben was never added by Anna, so his edit is refused.
        val bAsSeenByA = TrustedContact("ben", "b".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.DIRECT)
        b.person("sneaky", "Sneaky"); b.sync.reconcile()
        val link = Link(b, a, bAsSeenByA, toPersonId = "anna")
        b.sync.addMember("anna")
        assertTrue(TreeSyncPublisher(b.sync, link).flush() > 0)
        assertTrue("nothing got in", a.tree().people.isEmpty())
    }
}
