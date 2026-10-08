package com.vibethroughcode.ftree.kutumb.treesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The property the whole design rests on: phones that have seen the same operations hold the same
 * tree, whatever order they arrived in and however many times each one was delivered. The streams
 * here are random, deliberately nasty (deletes racing edits, loops, edges to people who do not exist,
 * clashing readings), and the seeds are fixed so a failure can be replayed.
 */
class ConvergenceTest {
    private val people = listOf("p0", "p1", "p2", "p3", "p4")
    private val devices = listOf("a", "b", "c")
    private val now = 1_000_000L

    private fun randomOps(rnd: Random, count: Int): List<TreeOp> {
        fun pick(n: Int) = rnd.nextInt(n)
        fun person() = people[pick(people.size)]
        fun hlc() = Hlc(1L + pick(40), pick(3), devices[pick(devices.size)])
        fun edge(): EdgeKey {
            val a = person()
            var b = person()
            while (b == a) b = person()
            return EdgeKey.of(listOf(EdgeKey.PARENT, EdgeKey.PARENT, EdgeKey.SPOUSE, EdgeKey.SIBLING)[pick(4)], a, b)
        }
        return List(count) { n ->
            val id = "op$n"
            when (pick(10)) {
                0 -> TreeOp.PersonAdd(id, hlc(), person())
                1 -> TreeOp.PersonDelete(id, hlc(), person())
                2 -> TreeOp.PersonRestore(id, hlc(), person())
                3, 4 -> TreeOp.PersonSet(id, hlc(), person(), PersonField.NAME, listOf("Raj", "Asha", "Kiran", null)[pick(4)])
                5 -> TreeOp.PersonSet(id, hlc(), person(), PersonField.NOTES, listOf("x", "y", null)[pick(3)])
                6, 7 -> TreeOp.EdgeAdd(id, hlc(), edge(), listOf("BIOLOGICAL", "ADOPTIVE", null)[pick(3)])
                8 -> TreeOp.EdgeRemove(id, hlc(), edge())
                else -> TreeOp.EdgeSubtype(id, hlc(), edge(), listOf("HALF", "FULL", null)[pick(3)])
            }
        }
    }

    private fun build(ops: List<TreeOp>) = TreeReplica().also { r -> ops.forEach { r.apply(it, now) } }

    /** [ops] shuffled, with some delivered twice or three times. */
    private fun scrambled(ops: List<TreeOp>, rnd: Random): List<TreeOp> {
        val delivered = ArrayList<TreeOp>()
        for (op in ops) { repeat(1 + (if (rnd.nextInt(10) < 3) 1 + rnd.nextInt(2) else 0)) { delivered += op } }
        return delivered.shuffled(rnd)
    }

    @Test fun anyOrderAndAnyNumberOfDeliveriesGiveTheSameTree() {
        for (seed in 1L..60L) {
            val rnd = Random(seed)
            val ops = randomOps(rnd, 70)
            val reference = build(ops)
            repeat(15) {
                val other = build(scrambled(ops, rnd))
                assertEquals("state differs (seed $seed)", reference.snapshot(), other.snapshot())
                assertEquals("view differs (seed $seed)", reference.view(), other.view())
            }
        }
    }

    @Test fun twoPhonesThatWorkedApartAgreeOnceTheyExchangeWhatTheyHold() {
        for (seed in 100L..140L) {
            val rnd = Random(seed)
            val ops = randomOps(rnd, 70)
            val left = ops.filter { rnd.nextBoolean() }
            val right = ops.filter { it !in left || rnd.nextInt(4) == 0 }
            val a = build(left)
            val b = build(right)
            val a0 = a.snapshot()
            a.join(b.snapshot())
            b.join(a0)
            assertEquals("snapshots differ (seed $seed)", a.snapshot(), b.snapshot())
            assertEquals("views differ (seed $seed)", a.view(), b.view())
            assertEquals("not the same as hearing everything (seed $seed)", build(ops).snapshot(), a.snapshot())
        }
    }

    @Test fun joiningIsHarmlessToRepeat() {
        val rnd = Random(7)
        val a = build(randomOps(rnd, 60))
        val b = build(randomOps(rnd, 60))
        a.join(b.snapshot())
        val once = a.snapshot()
        a.join(b.snapshot())
        a.join(a.snapshot())
        assertEquals(once, a.snapshot())
    }

    @Test fun whatPeopleSeeIsAlwaysAWellFormedTree() {
        for (seed in 200L..260L) {
            val view = build(randomOps(Random(seed), 80)).view()
            val visible = view.people.map { it.id }.toSet()
            for (e in view.edges) {
                assertTrue("edge to a person who is not shown (seed $seed)", e.edge.from in visible && e.edge.to in visible)
            }
            assertTrue("a loop of parents is shown (seed $seed)", isAcyclic(view.edges.map { it.edge }.filter { it.type == EdgeKey.PARENT }))
        }
    }

    @Test fun readingsThatCollideExactlyStillResolveTheSameWayEverywhere() {
        // Not something a correct phone does, but a replayed or hand-made log could: same reading, different content.
        val same = Hlc(5, 0, "a")
        val ops = listOf(
            TreeOp.PersonSet("1", same, "p", PersonField.NAME, "Raj"),
            TreeOp.PersonSet("2", same, "p", PersonField.NAME, "Rajesh"),
            TreeOp.PersonAdd("3", same, "p"),
            TreeOp.PersonDelete("4", same, "p"),
            TreeOp.EdgeAdd("5", same, EdgeKey.of(EdgeKey.PARENT, "p", "q"), "A"),
            TreeOp.EdgeAdd("6", same, EdgeKey.of(EdgeKey.PARENT, "p", "q"), "B"),
            TreeOp.EdgeRemove("7", same, EdgeKey.of(EdgeKey.PARENT, "p", "q")),
        )
        val reference = build(ops).snapshot()
        val rnd = Random(3)
        repeat(40) { assertEquals(reference, build(ops.shuffled(rnd)).snapshot()) }
    }

    private fun isAcyclic(parents: List<EdgeKey>): Boolean {
        val out = parents.groupBy({ it.from }, { it.to })
        val state = HashMap<String, Int>()
        fun visit(n: String): Boolean {
            when (state[n]) { 1 -> return false; 2 -> return true }
            state[n] = 1
            for (m in out[n].orEmpty()) if (!visit(m)) return false
            state[n] = 2
            return true
        }
        return out.keys.all { visit(it) }
    }
}
