package com.vibethroughcode.ftree.kutumb.treesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeReplicaTest {
    private var seq = 0
    private fun id() = "op${seq++}"
    private fun h(time: Long, counter: Int = 0, device: String = "a") = Hlc(time, counter, device)

    private fun add(p: String, at: Hlc) = TreeOp.PersonAdd(id(), at, p)
    private fun del(p: String, at: Hlc) = TreeOp.PersonDelete(id(), at, p)
    private fun restore(p: String, at: Hlc) = TreeOp.PersonRestore(id(), at, p)
    private fun set(p: String, f: PersonField, v: String?, at: Hlc) = TreeOp.PersonSet(id(), at, p, f, v)
    private fun link(type: String, a: String, b: String, at: Hlc, sub: String? = null) = TreeOp.EdgeAdd(id(), at, EdgeKey.of(type, a, b), sub)
    private fun unlink(type: String, a: String, b: String, at: Hlc) = TreeOp.EdgeRemove(id(), at, EdgeKey.of(type, a, b))

    private val now = 10_000L

    private fun replica(vararg ops: TreeOp) = TreeReplica().also { r -> ops.forEach { r.apply(it, now) } }
    private fun TreeView.name(p: String) = people.firstOrNull { it.id == p }?.fields?.get(PersonField.NAME)

    // --- fields ---------------------------------------------------------------------------------------

    @Test fun editsToDifferentFieldsOfOnePersonBothSurvive() {
        val ops = listOf(
            add("p", h(1)),
            set("p", PersonField.NAME, "Raj", h(2, 0, "a")),
            set("p", PersonField.NOTES, "Born in Pune", h(2, 0, "b")),
        )
        for (order in listOf(ops, ops.reversed())) {
            val v = replica(*order.toTypedArray()).view()
            assertEquals("Raj", v.name("p"))
            assertEquals("Born in Pune", v.people.single().fields[PersonField.NOTES])
        }
    }

    @Test fun theLaterEditToTheSameFieldWinsInEitherArrivalOrder() {
        val early = set("p", PersonField.NAME, "Raj", h(2))
        val late = set("p", PersonField.NAME, "Rajesh", h(3))
        val a = replica(add("p", h(1)), early, late)
        val b = replica(add("p", h(1)), late, early)
        assertEquals("Rajesh", a.view().name("p"))
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test fun clearingAFieldIsAnEditLikeAnyOther() {
        val v = replica(add("p", h(1)), set("p", PersonField.NOTES, "x", h(2)), set("p", PersonField.NOTES, null, h(3))).view()
        assertTrue(v.people.single().fields.isEmpty())
    }

    // --- deleting and restoring -----------------------------------------------------------------------

    @Test fun aDeletionBeatsEarlierEditsAndHidesThePerson() {
        val v = replica(add("p", h(1)), set("p", PersonField.NAME, "Raj", h(2)), del("p", h(3))).view()
        assertTrue(v.people.isEmpty())
        assertTrue("an edit older than the delete is not worth a human's attention", v.inbox.isEmpty())
    }

    @Test fun anEditAfterTheDeleteIsKeptInTheInboxAndRestoringBringsItBack() {
        val ops = listOf(add("p", h(1)), del("p", h(3)), set("p", PersonField.NAME, "Rajesh", h(4, 0, "b")))
        val deleted = replica(*ops.toTypedArray()).view()
        assertTrue(deleted.people.isEmpty())
        val item = deleted.inbox.single() as InboxItem.EditAfterDelete
        assertEquals("p", item.personId)
        assertEquals(setOf(PersonField.NAME), item.fields)

        val back = replica(*(ops + restore("p", h(5))).toTypedArray()).view()
        assertEquals("Rajesh", back.name("p"))
        assertTrue(back.inbox.isEmpty())
    }

    @Test fun aLateArrivingAddDoesNotResurrectAnAlreadyDeletedPerson() {
        val v = replica(del("p", h(3)), add("p", h(1))).view()
        assertTrue(v.people.isEmpty())
    }

    @Test fun aRestoreOlderThanTheDeleteChangesNothing() {
        val v = replica(add("p", h(1)), restore("p", h(2)), del("p", h(3))).view()
        assertTrue(v.people.isEmpty())
    }

    // --- relationships --------------------------------------------------------------------------------

    private fun twoPeople() = listOf(add("a", h(1)), add("b", h(1)))

    @Test fun removalBeatsAnOlderAdd_andALaterAddBringsTheEdgeBack() {
        val base = twoPeople()
        val gone = replica(*(base + unlink(EdgeKey.PARENT, "a", "b", h(5)) + link(EdgeKey.PARENT, "a", "b", h(3))).toTypedArray())
        assertTrue(gone.view().edges.isEmpty())
        val back = replica(*(base + link(EdgeKey.PARENT, "a", "b", h(3)) + unlink(EdgeKey.PARENT, "a", "b", h(5)) + link(EdgeKey.PARENT, "a", "b", h(7))).toTypedArray())
        assertEquals(1, back.view().edges.size)
    }

    @Test fun aSymmetricRelationshipIsOneEdgeWhicheverWayRound() {
        assertEquals(EdgeKey.of(EdgeKey.SPOUSE, "a", "b"), EdgeKey.of(EdgeKey.SPOUSE, "b", "a"))
        val v = replica(*(twoPeople() + link(EdgeKey.SPOUSE, "a", "b", h(2)) + link(EdgeKey.SPOUSE, "b", "a", h(3))).toTypedArray()).view()
        assertEquals(1, v.edges.size)
        // Parent edges are directed: the reverse is a different edge.
        assertTrue(EdgeKey.of(EdgeKey.PARENT, "a", "b") != EdgeKey.of(EdgeKey.PARENT, "b", "a"))
    }

    @Test fun theSubtypeIsItsOwnFieldOfTheEdge() {
        val v = replica(
            *(twoPeople() + link(EdgeKey.SPOUSE, "a", "b", h(2), "MARRIED") +
                TreeOp.EdgeSubtype(id(), h(4), EdgeKey.of(EdgeKey.SPOUSE, "a", "b"), "DIVORCED")).toTypedArray(),
        ).view()
        assertEquals("DIVORCED", v.edges.single().subtype)
    }

    @Test fun anEdgeToADeletedPersonIsHiddenQuietly() {
        val v = replica(*(twoPeople() + link(EdgeKey.PARENT, "a", "b", h(2)) + del("b", h(3))).toTypedArray()).view()
        assertTrue(v.edges.isEmpty())
        assertTrue(v.inbox.isEmpty())
    }

    @Test fun anEdgeToSomeoneNotHeardOfYetWaitsAndAppearsWhenTheyArrive() {
        val waiting = replica(add("a", h(1)), link(EdgeKey.PARENT, "a", "b", h(2))).view()
        assertTrue(waiting.edges.isEmpty())
        assertEquals(InboxItem.RelationshipHeld(EdgeKey.of(EdgeKey.PARENT, "a", "b"), HeldReason.MISSING_PERSON), waiting.inbox.single())

        val arrived = replica(add("a", h(1)), link(EdgeKey.PARENT, "a", "b", h(2)), add("b", h(3))).view()
        assertEquals(1, arrived.edges.size)
        assertTrue(arrived.inbox.isEmpty())
    }

    // --- loops ----------------------------------------------------------------------------------------

    @Test fun aLoopOfParentsHoldsTheNewestEdgeAndSaysSo() {
        val ops = listOf(add("a", h(1)), add("b", h(1)), add("c", h(1)),
            link(EdgeKey.PARENT, "a", "b", h(2)), link(EdgeKey.PARENT, "b", "c", h(3)), link(EdgeKey.PARENT, "c", "a", h(4)))
        val v = replica(*ops.toTypedArray()).view()
        assertEquals(2, v.edges.size)
        assertEquals(InboxItem.RelationshipHeld(EdgeKey.of(EdgeKey.PARENT, "c", "a"), HeldReason.CYCLE), v.inbox.single())
    }

    @Test fun twoPeopleEachRecordedAsTheOthersParentAreAlsoALoop() {
        val v = replica(add("a", h(1)), add("b", h(1)), link(EdgeKey.PARENT, "a", "b", h(2)), link(EdgeKey.PARENT, "b", "a", h(3))).view()
        assertEquals(1, v.edges.size)
        assertEquals(EdgeKey.of(EdgeKey.PARENT, "a", "b"), v.edges.single().edge)
    }

    // --- verdicts -------------------------------------------------------------------------------------

    @Test fun replaysAndOlderOperationsChangeNothing() {
        val r = TreeReplica()
        val op = set("p", PersonField.NAME, "Raj", h(5))
        assertEquals(Verdict.Applied, r.apply(op, now))
        assertEquals(Verdict.NoChange, r.apply(op, now))
        assertEquals(Verdict.NoChange, r.apply(set("p", PersonField.NAME, "Old", h(4)), now))
    }

    @Test fun anOperationFromTheFarFutureIsDeferredAndAppliesOnceTheClockCatchesUp() {
        val r = TreeReplica(maxFutureMs = 1_000)
        val op = set("p", PersonField.NAME, "Raj", h(20_000))
        assertEquals(Verdict.Deferred, r.apply(op, 10_000))
        assertTrue(r.snapshot().people.isEmpty())
        assertEquals(Verdict.Applied, r.apply(op, 19_500))
    }

    @Test fun malformedOperationsAreRefusedWithAReason() {
        val r = TreeReplica()
        fun reason(op: TreeOp) = (r.apply(op, now) as Verdict.Invalid).reason
        assertEquals(InvalidReason.BLANK_ID, reason(TreeOp.PersonAdd("", h(1), "p")))
        assertEquals(InvalidReason.BLANK_ID, reason(TreeOp.PersonAdd("x", h(1), " ")))
        assertEquals(InvalidReason.BAD_CLOCK, reason(TreeOp.PersonAdd("x", Hlc(1, 0, ""), "p")))
        assertEquals(InvalidReason.SELF_RELATIONSHIP, reason(link(EdgeKey.PARENT, "a", "a", h(1))))
        assertEquals(InvalidReason.BAD_VALUE, reason(set("p", PersonField.GENDER, "robot", h(1))))
        assertEquals(InvalidReason.BAD_VALUE, reason(set("p", PersonField.GENDER, null, h(1))))
        assertEquals(InvalidReason.BAD_VALUE, reason(set("p", PersonField.DECEASED, "maybe", h(1))))
        assertEquals(InvalidReason.BAD_VALUE, reason(set("p", PersonField.BIRTH_DATE, "1938-13", h(1))))
        assertEquals(InvalidReason.TOO_LONG, reason(set("p", PersonField.NAME, "x".repeat(TreeOpRules.MAX_NAME + 1), h(1))))
        assertEquals(InvalidReason.BAD_VALUE, reason(TreeOp.EdgeSubtype("x", h(1), EdgeKey.of(EdgeKey.PARENT, "a", "b"), " ")))
        assertTrue("an invalid operation must leave no trace", r.snapshot().people.isEmpty() && r.snapshot().edges.isEmpty())
    }

    @Test fun theDateFormsTheAppStoresAreAccepted_andNothingElse() {
        for (ok in listOf("1938", "1938-04", "1938-04-17", "--04-17", "--02-29", "1938-02-29")) assertTrue(ok, TreeOpRules.isPartialDate(ok))
        for (bad in listOf("19", "abcd", "1938-00", "1938-13", "1938-04-31", "1938-04-00", "--13-01", "--04-32", "1938-4-1", "")) assertFalse(bad, TreeOpRules.isPartialDate(bad))
    }

    // --- catching up from a snapshot ------------------------------------------------------------------

    @Test fun joiningASnapshotGivesTheSameTreeAsReplayingTheOperations() {
        val ops = listOf(
            add("a", h(1)), add("b", h(1)), set("a", PersonField.NAME, "Asha", h(2)), set("a", PersonField.NAME, "Asha K", h(4, 0, "b")),
            link(EdgeKey.PARENT, "a", "b", h(3)), del("b", h(5)), restore("b", h(6)), unlink(EdgeKey.PARENT, "a", "b", h(7)),
        )
        val source = replica(*ops.toTypedArray())
        val newcomer = TreeReplica().also { it.join(source.snapshot()) }
        assertEquals(source.snapshot(), newcomer.snapshot())
        assertEquals(source.view(), newcomer.view())
    }
}
