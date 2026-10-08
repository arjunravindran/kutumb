package com.vibethroughcode.ftree.kutumb.treesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class EditBridgeTest {
    private val now = 1_000_000L

    // --- the recorder ---------------------------------------------------------------------------------

    private fun recorder(device: String = "a"): EditRecorder {
        var n = 0
        return EditRecorder(HybridClock(device, { now }), { "$device-op${n++}" })
    }

    @Test fun onlyTheFieldsThatChangedAreSent() {
        val before = PersonData(name = "Raj", birthDate = "1938")
        val ops = recorder().personChanged("p", before, before.copy(notes = "Pune"))
        val set = ops.single() as TreeOp.PersonSet
        assertEquals(PersonField.NOTES, set.field)
        assertEquals("Pune", set.value)
        assertTrue(recorder().personChanged("p", before, before).isEmpty())
    }

    @Test fun aNewPersonSendsOnlyWhatIsNotAlreadyTheDefault() {
        val ops = recorder().personAdded("p", PersonData(name = "Asha", gender = "FEMALE"))
        assertTrue(ops.first() is TreeOp.PersonAdd)
        assertEquals(setOf(PersonField.NAME, PersonField.GENDER), ops.filterIsInstance<TreeOp.PersonSet>().map { it.field }.toSet())
        assertEquals(1, recorder().personAdded("q", PersonData.EMPTY).size)
    }

    @Test fun keepAsUnknownClearsEveryKnownFieldAndNothingElse() {
        val before = PersonData("Raj", "MALE", "1938-04-17", "2001", true, "note")
        val sets = recorder().personCleared("p", before).filterIsInstance<TreeOp.PersonSet>()
        assertEquals(PersonField.entries.toSet(), sets.map { it.field }.toSet())
        assertEquals("UNSPECIFIED", sets.single { it.field == PersonField.GENDER }.value)
        assertEquals("false", sets.single { it.field == PersonField.DECEASED }.value)
        assertTrue(recorder().personCleared("p", PersonData.EMPTY).isEmpty())
    }

    @Test fun everyOperationInABatchGetsItsOwnLaterReading() {
        val ops = recorder().personAdded("p", PersonData("Raj", "MALE", "1938", "2001", true, "n"))
        assertEquals(ops.size, ops.map { it.hlc }.toSet().size)
        assertEquals(ops.map { it.hlc }, ops.map { it.hlc }.sorted())
        assertEquals(ops.size, ops.map { it.id }.toSet().size)
    }

    // --- the projection -------------------------------------------------------------------------------

    private fun replicaOf(vararg ops: TreeOp) = TreeReplica().also { r -> ops.forEach { r.apply(it, now) } }
    private val t = Hlc(10, 0, "a")

    @Test fun peopleGoInBeforeTheirEdgesAndEdgesGoBeforeThePeopleTheyHangFrom() {
        val r = replicaOf(
            TreeOp.PersonAdd("1", t, "a"), TreeOp.PersonAdd("2", t, "b"), TreeOp.PersonAdd("3", t, "c"),
            TreeOp.EdgeAdd("4", t, EdgeKey.of(EdgeKey.PARENT, "a", "b"), null),
            TreeOp.PersonDelete("5", Hlc(20, 0, "a"), "c"),
        )
        val db = DbTree(mapOf("c" to PersonData.EMPTY), mapOf(EdgeKey.of(EdgeKey.PARENT, "b", "c") to null))
        val steps = Projection.plan(r, db)
        fun at(match: (ProjectionStep) -> Boolean) = steps.indexOfFirst(match)
        assertTrue(at { it is ProjectionStep.InsertPerson } < at { it is ProjectionStep.InsertEdge })
        assertTrue("c" in steps.filterIsInstance<ProjectionStep.DeletePerson>().map { it.id })
        // b->c is not in the replica's knowledge, so it is neither inserted nor deleted by name; c's deletion takes it.
        assertTrue(steps.last() is ProjectionStep.DeletePerson)
    }

    @Test fun nothingTheReplicaHasNeverHeardOfIsEverRemoved() {
        val r = replicaOf(TreeOp.PersonAdd("1", t, "known"))
        val db = DbTree(
            people = mapOf("known" to PersonData.EMPTY, "local-only" to PersonData(name = "Not shared yet")),
            edges = mapOf(EdgeKey.of(EdgeKey.PARENT, "known", "local-only") to null),
        )
        assertTrue(Projection.plan(r, db).isEmpty())
    }

    @Test fun aSubtypeChangeIsAnUpdateNotARecreate() {
        val e = EdgeKey.of(EdgeKey.SPOUSE, "a", "b")
        val r = replicaOf(
            TreeOp.PersonAdd("1", t, "a"), TreeOp.PersonAdd("2", t, "b"), TreeOp.EdgeAdd("3", t, e, "MARRIED"),
            TreeOp.EdgeSubtype("4", Hlc(20, 0, "a"), e, "DIVORCED"),
        )
        val db = DbTree(mapOf("a" to PersonData.EMPTY, "b" to PersonData.EMPTY), mapOf(e to "MARRIED"))
        assertEquals(listOf<ProjectionStep>(ProjectionStep.SetEdgeSubtype(e, "DIVORCED")), Projection.plan(r, db))
    }

    // --- whole phones ---------------------------------------------------------------------------------

    /** A phone: its own clock (running a little fast or slow), replica, database and outbox. */
    private class Phone(val device: String, private val skewMs: Long, private val rnd: Random) {
        var wall = 1_000L
        private val clock = HybridClock(device, { wall + skewMs })
        private var opCounter = 0
        private var personCounter = 0
        val replica = TreeReplica()
        val recorder = EditRecorder(clock, { "$device-${opCounter++}" })
        var db = DbTree(emptyMap(), emptyMap())
        val outbox = ArrayList<TreeOp>()

        private fun pick(n: Int) = rnd.nextInt(n)
        private fun <T> choose(items: List<T>): T? = if (items.isEmpty()) null else items[pick(items.size)]
        private fun randomData() = PersonData(
            name = listOf("Raj", "Asha", "Kiran", null)[pick(4)],
            gender = listOf("MALE", "FEMALE", "UNSPECIFIED")[pick(3)],
            birthDate = listOf("1938", "1938-04", "--04-17", null)[pick(4)],
            deathDate = listOf("2001", null, null)[pick(3)],
            deceased = pick(4) == 0,
            notes = listOf("a", "b", null)[pick(3)],
        )

        /** Does one thing a person might do, if there is anything to do it to. */
        fun act() {
            wall += pick(50)
            val ids = db.people.keys.sorted()
            when (pick(9)) {
                0, 1 -> { val id = "$device-p${personCounter++}"; val d = randomData(); local { it.copy(people = it.people + (id to d)) } }
                2, 3 -> choose(ids)?.let { id ->
                    val before = db.people.getValue(id); val after = randomData()
                    local { it.copy(people = it.people + (id to after)) }
                }
                4 -> choose(ids)?.let { id ->
                    local { it.copy(people = it.people + (id to PersonData.EMPTY)) }
                }
                5 -> choose(ids)?.let { id ->
                    local { d ->
                        DbTree(d.people - id, d.edges.filterKeys { it.from != id && it.to != id })
                    }
                }
                6, 7 -> {
                    val a = choose(ids); val b = choose(ids)
                    if (a != null && b != null && a != b) {
                        val key = EdgeKey.of(listOf(EdgeKey.PARENT, EdgeKey.PARENT, EdgeKey.SPOUSE)[pick(3)], a, b)
                        val sub = listOf("BIOLOGICAL", null)[pick(2)]
                        val duplicate = key in db.edges
                        val loop = key.type == EdgeKey.PARENT && reaches(key.to, key.from)
                        if (!duplicate && !loop) local { it.copy(edges = it.edges + (key to sub)) }
                    }
                }
                else -> choose(db.edges.keys.sortedWith(compareBy({ it.type }, { it.from }, { it.to })))?.let { key ->
                    if (pick(2) == 0) local { it.copy(edges = it.edges - key) }
                    else { val sub = listOf("HALF", "FULL", null)[pick(3)]; local { it.copy(edges = it.edges + (key to sub)) } }
                }
            }
        }

        private fun reaches(from: String, target: String): Boolean {
            val seen = HashSet<String>(); val stack = arrayListOf(from)
            while (stack.isNotEmpty()) {
                val n = stack.removeAt(stack.lastIndex)
                if (n == target) return true
                if (seen.add(n)) db.edges.keys.filter { it.type == EdgeKey.PARENT && it.from == n }.forEach { stack += it.to }
            }
            return false
        }

        private fun heldLoop() = replica.view().inbox.any { it is InboxItem.RelationshipHeld && it.reason == HeldReason.CYCLE }

        /** The user changes the database; the reconciler works out the operations, as the app will. */
        private fun local(direct: (DbTree) -> DbTree) {
            val hadHeldLoop = heldLoop()
            db = direct(db)
            val ops = Reconciler.ops(replica, db, recorder)
            for (op in ops) check(replica.apply(op, wall + skewMs) == Verdict.Applied) { "own operation not applied: $op" }
            outbox += ops
            // Recording must reproduce exactly what the user did. The one allowed extra: breaking a loop
            // of parents lets the edge that was held back in it return.
            val extra = Projection.plan(replica, db)
            if (extra.isNotEmpty()) {
                check(hadHeldLoop) { "ops do not reproduce the edit: $extra" }
                check(extra.all { it is ProjectionStep.InsertEdge }) { "a held loop released more than an edge: $extra" }
                db = applySteps(db, extra)
            }
            check(Projection.plan(replica, db).isEmpty())
            check(Reconciler.ops(replica, db, recorder).isEmpty()) { "reconciling is not idempotent" }
        }

        fun receive(ops: List<TreeOp>) {
            wall += pick(20)
            for (op in ops) { clock.observe(op.hlc); replica.apply(op, wall + skewMs) }
            val steps = Projection.plan(replica, db)
            db = applySteps(db, steps)
            check(Projection.plan(replica, db).isEmpty()) { "projection did not settle: ${Projection.plan(replica, db)}" }
        }

        private fun applySteps(start: DbTree, steps: List<ProjectionStep>): DbTree {
            val people = start.people.toMutableMap(); val edges = start.edges.toMutableMap()
            for (s in steps) when (s) {
                is ProjectionStep.InsertPerson -> { check(s.id !in people); people[s.id] = s.data }
                is ProjectionStep.UpdatePerson -> { check(s.id in people); people[s.id] = s.data }
                is ProjectionStep.InsertEdge -> { check(s.edge.from in people && s.edge.to in people) { "edge before its people: $s" }; edges[s.edge] = s.subtype }
                is ProjectionStep.SetEdgeSubtype -> { check(s.edge in edges); edges[s.edge] = s.subtype }
                is ProjectionStep.DeleteEdge -> edges.remove(s.edge)
                is ProjectionStep.DeletePerson -> { people.remove(s.id); edges.keys.removeAll { it.from == s.id || it.to == s.id } } // the database cascades
            }
            return DbTree(people, edges)
        }
    }

    private fun scramble(ops: List<TreeOp>, rnd: Random): List<TreeOp> =
        ops.flatMap { op -> List(1 + if (rnd.nextInt(10) < 3) 1 else 0) { op } }.shuffled(rnd)

    @Test fun phonesThatEditAndExchangeOperationsEndUpWithTheSameDatabase() {
        for (seed in 1L..40L) {
            val rnd = Random(seed)
            val phones = listOf(Phone("a", 0, rnd), Phone("b", 3_000, rnd), Phone("c", -2_000, rnd))
            repeat(12) { // rounds of work, each followed by partial, scrambled, repeated delivery
                for (p in phones) repeat(1 + rnd.nextInt(5)) { p.act() }
                for (to in phones) {
                    val incoming = phones.filter { it !== to }.flatMap { from -> from.outbox.filter { rnd.nextInt(10) < 6 } }
                    to.receive(scramble(incoming, rnd))
                }
            }
            // Everyone finally hears everything.
            val everything = phones.flatMap { it.outbox }
            for (p in phones) p.receive(scramble(everything, rnd))
            val reference = phones.first()
            for (p in phones.drop(1)) {
                assertEquals("replicas differ (seed $seed)", reference.replica.snapshot(), p.replica.snapshot())
                assertEquals("databases differ (seed $seed)", reference.db, p.db)
            }
            assertTrue("the run did nothing (seed $seed)", reference.db.people.isNotEmpty() || everything.isNotEmpty())
        }
    }

    // --- the reconciler -------------------------------------------------------------------------------

    private class Rig(val replica: TreeReplica, val recorder: EditRecorder)

    private fun adopt(db: DbTree, device: String = "a"): Rig {
        val replica = TreeReplica()
        var n = 0
        val recorder = EditRecorder(HybridClock(device, { now }), { "$device-$n".also { n++ } })
        Reconciler.ops(replica, db, recorder).forEach { check(replica.apply(it, now) == Verdict.Applied) }
        return Rig(replica, recorder)
    }

    @Test fun sharingATreeThatAlreadyExistsAdoptsEveryPersonAndEdge() {
        val spouse = EdgeKey.of(EdgeKey.SPOUSE, "b", "a")
        val db = DbTree(
            people = mapOf("a" to PersonData(name = "Raj", gender = "MALE"), "b" to PersonData(name = "Asha", gender = "FEMALE", birthDate = "1940"), "c" to PersonData.EMPTY),
            edges = mapOf(EdgeKey.of(EdgeKey.PARENT, "a", "c") to "BIOLOGICAL", spouse to "MARRIED"),
        )
        val replica = adopt(db).replica
        assertTrue("projecting the adopted tree must change nothing", Projection.plan(replica, db).isEmpty())
        assertEquals(setOf("a", "b", "c"), replica.view().people.map { it.id }.toSet())
        assertEquals(2, replica.view().edges.size)
    }

    @Test fun reconcilingAnUnchangedDatabaseSaysNothing() {
        val db = DbTree(mapOf("a" to PersonData(name = "Raj")), emptyMap())
        val rig = adopt(db)
        assertTrue(Reconciler.ops(rig.replica, db, rig.recorder).isEmpty())
    }

    @Test fun anEditDeleteAndRemovalAreEachNoticed() {
        val parent = EdgeKey.of(EdgeKey.PARENT, "a", "b")
        val start = DbTree(mapOf("a" to PersonData(name = "Raj"), "b" to PersonData(name = "Kiran"), "c" to PersonData(name = "Asha")), mapOf(parent to null))
        val rig = adopt(start)
        val after = DbTree(mapOf("a" to PersonData(name = "Rajesh"), "b" to PersonData(name = "Kiran")), emptyMap())
        val ops = Reconciler.ops(rig.replica, after, rig.recorder)
        assertEquals(listOf("EdgeRemove", "PersonDelete", "PersonSet"), ops.map { it::class.simpleName!! }.sorted())
        ops.forEach { rig.replica.apply(it, now) }
        assertEquals(listOf("a", "b"), rig.replica.view().people.map { it.id })
        assertTrue(rig.replica.view().edges.isEmpty())
    }

    @Test fun deletingAPersonLeavesTheirRelationshipsToComeBackWithThem() {
        val parent = EdgeKey.of(EdgeKey.PARENT, "a", "b")
        val start = DbTree(mapOf("a" to PersonData(name = "Raj"), "b" to PersonData(name = "Kiran")), mapOf(parent to null))
        val rig = adopt(start)
        // The database cascades the edge away with the person.
        val ops = Reconciler.ops(rig.replica, DbTree(mapOf("a" to PersonData(name = "Raj")), emptyMap()), rig.recorder)
        assertEquals(listOf("PersonDelete"), ops.map { it::class.simpleName })
        ops.forEach { rig.replica.apply(it, now) }
        assertTrue(rig.replica.view().edges.isEmpty())
        rig.replica.apply(TreeOp.PersonRestore("r", Hlc(now + 1_000, 0, "a"), "b"), now + 5_000)
        assertEquals("the relationship returns with the person", 1, rig.replica.view().edges.size)
    }

    @Test fun anEdgeRemovedAndThenPutBackIsRecordedAgain() {
        val e = EdgeKey.of(EdgeKey.SPOUSE, "a", "b")
        val people = mapOf("a" to PersonData(name = "Raj"), "b" to PersonData(name = "Asha"))
        val rig = adopt(DbTree(people, mapOf(e to "MARRIED")))
        Reconciler.ops(rig.replica, DbTree(people, emptyMap()), rig.recorder).forEach { rig.replica.apply(it, now) }
        assertTrue(rig.replica.view().edges.isEmpty())
        val back = Reconciler.ops(rig.replica, DbTree(people, mapOf(e to "MARRIED")), rig.recorder)
        assertEquals(listOf("EdgeAdd"), back.map { it::class.simpleName })
        back.forEach { rig.replica.apply(it, now) }
        assertEquals(1, rig.replica.view().edges.size)
    }

    @Test fun somethingTheTreeHasDeletedIsNotReAddedJustBecauseTheDatabaseStillHoldsIt() {
        val replica = replicaOf(TreeOp.PersonAdd("1", t, "a"), TreeOp.PersonDelete("2", Hlc(20, 0, "b"), "a"))
        val db = DbTree(mapOf("a" to PersonData(name = "Stale")), emptyMap())
        assertTrue(Reconciler.ops(replica, db, recorder("a")).isEmpty())
        assertEquals(listOf<ProjectionStep>(ProjectionStep.DeletePerson("a")), Projection.plan(replica, db))
    }
}
