package com.vibethroughcode.ftree.graph

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.Relationship
import com.vibethroughcode.ftree.data.RelationshipType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

class CircleGraphTest {
    private fun p(id: String) = Person(id = id, name = id.replaceFirstChar { it.uppercase() })
    private fun link(a: String, b: String, label: String? = null) =
        Relationship.of(a, b, RelationshipType.CONNECTED, label, id = "$a-$b")

    // Asha: Friends Ben and Cai, colleague Dara. Ben knows Eve and Fox; Dara knows Eve.
    private val people = listOf("asha", "ben", "cai", "dara", "eve", "fox", "lone").map(::p)
    private val edges = listOf(
        link("asha", "ben", "Friend"), link("asha", "cai", "Friend"), link("asha", "dara", "Colleague"),
        link("ben", "eve"), link("ben", "fox"), link("dara", "eve"), link("ben", "cai"),
    )

    private fun layout() = CircleGraphLayout.layout("asha", people, edges)!!

    private fun CircleGraph.dist(a: String, b: String) =
        hypot(node(a)!!.x - node(b)!!.x, node(a)!!.y - node(b)!!.y)

    @Test fun `the middle person is at the origin with their connections on a ring around them`() {
        val g = layout()
        assertEquals(GraphRing.EGO, g.ego.ring)
        assertEquals("asha", g.ego.id)
        assertEquals(0f, g.ego.x, 0f)
        val ring = g.nodes.filter { it.ring == GraphRing.DIRECT }
        assertEquals(setOf("ben", "cai", "dara"), ring.map { it.id }.toSet())
        val radii = ring.map { hypot(it.x, it.y) }
        assertTrue(radii.all { it > 0.5f && Math.abs(it - radii.first()) < 1e-3f })
    }

    @Test fun `people reached through others sit on an outer ring near whoever leads there`() {
        val g = layout()
        val outer = g.nodes.filter { it.ring == GraphRing.THROUGH }.associateBy { it.id }
        assertEquals(setOf("eve", "fox"), outer.keys)
        val ringRadius = hypot(g.node("ben")!!.x, g.node("ben")!!.y)
        assertTrue(outer.values.all { hypot(it.x, it.y) > ringRadius + 1f })
        // Fox is only reached through Ben, so Fox is closer to Ben than to anyone else on the ring.
        val nearest = listOf("ben", "cai", "dara").minByOrNull { g.dist("fox", it) }
        assertEquals("ben", nearest)
        // Nobody unconnected is drawn.
        assertNull(g.node("lone"))
    }

    @Test fun `each label gets its own block of the ring`() {
        val g = layout()
        val angles = g.nodes.filter { it.ring == GraphRing.DIRECT }.associate { it.id to atan2(it.y.toDouble(), it.x.toDouble()) }
        val friends = listOf("ben", "cai").map { angles.getValue(it) }
        val colleague = angles.getValue("dara")
        // The two friends are closer to each other than either is to the colleague.
        val gapFriends = Math.abs(friends[0] - friends[1])
        fun apart(a: Double, b: Double) = Math.min(Math.abs(a - b), 2 * PI - Math.abs(a - b))
        assertTrue(apart(friends[0], friends[1]) < apart(friends[0], colleague))
        assertTrue(gapFriends > 0)
        assertEquals(listOf("Colleague", "Friend"), layout().groups.map { it.label })
        assertEquals(listOf(1, 2), layout().groups.map { it.count })
        assertEquals(listOf(0, 1, 1), layout().nodes.filter { it.ring == GraphRing.DIRECT }.map { it.group }.sorted())
    }

    @Test fun `lines join the middle person to each connection and each connection to who they lead to`() {
        val g = layout()
        val pairs = g.edges.map { setOf(it.fromId, it.toId) }
        assertTrue(setOf("asha", "ben") in pairs)
        assertTrue(setOf("asha", "dara") in pairs)
        assertTrue(setOf("ben", "eve") in pairs)
        assertTrue(setOf("dara", "eve") in pairs) // Eve is reached two ways
        // Ben and Cai know each other: a plain line between two people on the ring.
        val bc = g.edges.single { setOf(it.fromId, it.toId) == setOf("ben", "cai") }
        assertEquals(-1, bc.group)
        // The middle person's own lines carry the colour of their label.
        assertEquals(g.node("ben")!!.group, g.edges.single { it.fromId == "asha" && it.toId == "ben" }.group)
    }

    @Test fun `nobody is drawn on top of anybody else`() {
        val many = (1..60).map { p("p$it") } + p("hub")
        val e = (1..40).map { link("hub", "p$it", if (it % 3 == 0) "A" else if (it % 3 == 1) "B" else null) } +
            (41..60).map { link("p${it - 40}", "p$it") }
        val g = CircleGraphLayout.layout("hub", many, e)!!
        val pts = g.nodes
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val d = hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y)
            assertTrue("${pts[i].id} and ${pts[j].id} are $d apart", d >= 1.0f)
        }
        // Everything is inside the square the view will scale to fit.
        assertTrue(pts.all { Math.abs(it.x) < g.extent && Math.abs(it.y) < g.extent })
    }

    @Test fun `a crowded circle draws the most it can and says how many it left out`() {
        val many = (1..80).map { p("p$it") } + p("hub")
        val e = (1..80).map { link("hub", "p$it", "Friend") }
        val g = CircleGraphLayout.layout("hub", many, e, maxDirect = 30)!!
        assertEquals(30, g.nodes.count { it.ring == GraphRing.DIRECT })
        assertEquals(50, g.hidden)
        assertEquals(1, g.nodes.count { it.ring == GraphRing.EGO })
    }

    @Test fun `a person with no connections is drawn alone, and an unknown person is not drawn at all`() {
        val alone = CircleGraphLayout.layout("lone", people, edges)!!
        assertEquals(1, alone.nodes.size)
        assertTrue(alone.edges.isEmpty())
        assertTrue(alone.extent > 0f)
        assertNull(CircleGraphLayout.layout("nobody", people, edges))
    }

    @Test fun `the same circle always draws the same picture`() {
        assertEquals(layout(), layout())
        assertEquals(layout(), CircleGraphLayout.layout("asha", people.reversed(), edges.reversed()))
    }

    @Test fun `a tap picks the nearest person within reach and nobody in empty space`() {
        val g = layout()
        val ben = g.node("ben")!!
        assertEquals("ben", g.nodeAt(ben.x + 0.2f, ben.y - 0.1f)?.id)
        assertNull(g.nodeAt(ben.x + 3f, ben.y + 3f))
        assertNotNull(g.nodeAt(0f, 0f))
    }
}
