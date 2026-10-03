package com.vibethroughcode.ftree.graph

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.Relationship
import com.vibethroughcode.ftree.data.RelationshipType
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Which ring a node sits on: the person in the middle, their connections, or people reached through those. */
enum class GraphRing { EGO, DIRECT, THROUGH }

/**
 * One person in the drawing. Positions are in node diameters, with the middle person at the origin
 * and y growing downward as on a screen; the view scales them to pixels.
 */
data class GraphNode(
    val person: Person,
    val x: Float,
    val y: Float,
    val ring: GraphRing,
    /** Index into [CircleGraph.groups] for a direct connection (its colour), -1 otherwise. */
    val group: Int,
) {
    val id: String get() = person.id
}

/** A line between two drawn people. [group] colours the middle person's own lines; -1 draws a plain one. */
data class GraphEdge(val fromId: String, val toId: String, val group: Int)

/** A label shared by some of the middle person's connections, for the legend. Null is "unlabelled". */
data class GraphGroup(val label: String?, val count: Int)

/**
 * A circle drawn as a picture instead of a list: the same people, seen from one of them.
 *
 * [hidden] counts people the picture leaves out to stay readable; the list view has them all.
 */
data class CircleGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val groups: List<GraphGroup>,
    val hidden: Int,
    /** Half the width and height of the square that holds every node, in node diameters. */
    val extent: Float,
) {
    val ego: GraphNode get() = nodes.first { it.ring == GraphRing.EGO }

    fun node(id: String): GraphNode? = nodes.firstOrNull { it.id == id }

    /** The node whose centre is nearest (x, y), if it is within [reach] node diameters; ties go to the closer. */
    fun nodeAt(x: Float, y: Float, reach: Float = 0.6f): GraphNode? =
        nodes.minByOrNull { hypot(it.x - x, it.y - y) }?.takeIf { hypot(it.x - x, it.y - y) <= reach }
}

/**
 * Lays a circle out around one person: their connections on a ring, a sector to each label so a
 * label reads as a block, and the people reached through them on an outer ring, each near whoever
 * leads there. Pure and deterministic, so it is tested on the JVM like the family layout.
 */
object CircleGraphLayout {
    const val MAX_DIRECT = 30
    const val MAX_THROUGH = 40

    /** The nearest two neighbours on a ring are this many node diameters apart, at least. */
    private const val SPACING = 1.15

    /** A gap between sectors, in node slots, so two labels never run together. */
    private const val SECTOR_GAP = 0.6

    private const val RING_STEP = 2.0
    private const val MIN_RING = 2.0
    private const val MARGIN = 0.9
    private const val TWO_PI = 2 * PI

    /** Null when [egoId] is not among [people]. */
    fun layout(
        egoId: String,
        people: Collection<Person>,
        edges: Collection<Relationship>,
        maxDirect: Int = MAX_DIRECT,
        maxThrough: Int = MAX_THROUGH,
    ): CircleGraph? {
        val ego = people.firstOrNull { it.id == egoId } ?: return null

        val connections = Circle.connectionsOf(egoId, people, edges)
        val groups = Circle.grouped(connections)
        val shown = groups.flatMapIndexed { gi, g -> g.connections.map { gi to it } }.take(maxDirect)

        val nodes = ArrayList<GraphNode>()
        val outEdges = ArrayList<GraphEdge>()
        nodes += GraphNode(ego, 0f, 0f, GraphRing.EGO, -1)

        // ---- the ring of connections, a sector per label
        val sectorCount = shown.map { it.first }.distinct().size
        val gap = if (sectorCount > 1) SECTOR_GAP else 0.0
        val slots = shown.size + gap * sectorCount
        val r1 = max(MIN_RING, slots * SPACING / TWO_PI)

        val angleOf = HashMap<String, Double>()
        var slot = 0.0
        var previous = -1
        for ((gi, c) in shown) {
            if (previous != -1 && gi != previous) slot += gap
            previous = gi
            val a = -PI / 2 + TWO_PI * (slot + 0.5) / slots
            slot += 1.0
            angleOf[c.other.id] = a
            nodes += GraphNode(c.other, (r1 * cos(a)).toFloat(), (r1 * sin(a)).toFloat(), GraphRing.DIRECT, gi)
            outEdges += GraphEdge(egoId, c.other.id, gi)
        }

        // ---- friends who know each other
        val shownIds = shown.mapTo(HashSet()) { it.second.other.id }
        edges.filter { it.type == RelationshipType.CONNECTED && it.fromPersonId != egoId && it.toPersonId != egoId }
            .filter { it.fromPersonId in shownIds && it.toPersonId in shownIds }
            .sortedBy { it.id }
            .forEach { outEdges += GraphEdge(it.fromPersonId, it.toPersonId, -1) }

        // ---- people reached through them, on an outer ring near whoever leads there
        val through = Circle.throughOthers(egoId, people, edges)
        val placeable = through.mapNotNull { (person, via) ->
            val angles = via.mapNotNull { angleOf[it.id] }
            if (angles.isEmpty()) null else Triple(person, via.filter { it.id in angleOf }, angles)
        }
        val drawnThrough = placeable.take(maxThrough)

        var r2 = r1
        if (drawnThrough.isNotEmpty()) {
            r2 = max(r1 + RING_STEP, drawnThrough.size * SPACING / TWO_PI)
            val minSep = SPACING / r2

            val wanted = drawnThrough.map { (person, _, angles) ->
                person to circularMean(angles)
            }.sortedWith(compareBy({ it.second }, { it.first.id }))

            val placed = spread(wanted.map { it.second }, minSep)
            wanted.forEachIndexed { i, (person, _) ->
                val a = placed[i]
                nodes += GraphNode(person, (r2 * cos(a)).toFloat(), (r2 * sin(a)).toFloat(), GraphRing.THROUGH, -1)
            }
            for ((person, via, _) in drawnThrough) via.forEach { outEdges += GraphEdge(it.id, person.id, -1) }
        }

        val hidden = (connections.size - shown.size) + (through.size - drawnThrough.size)
        val outer = if (drawnThrough.isNotEmpty()) r2 else if (shown.isNotEmpty()) r1 else 0.0
        return CircleGraph(
            nodes = nodes,
            edges = outEdges,
            groups = groups.map { GraphGroup(it.label, it.connections.size) },
            hidden = hidden,
            extent = (outer + 0.5 + MARGIN).toFloat(),
        )
    }

    /** The direction that the angles, taken as points on a circle, average to. */
    private fun circularMean(angles: List<Double>): Double {
        val s = angles.sumOf { sin(it) }
        val c = angles.sumOf { cos(it) }
        val a = if (s == 0.0 && c == 0.0) angles.first() else atan2(s, c)
        return if (a < 0) a + TWO_PI else a
    }

    /**
     * Keeps [sorted] angles (in [0, 2 pi)) at least [minSep] apart, moving each forward only as far
     * as it must. If that would wrap round onto the first, they are spread evenly instead.
     */
    private fun spread(sorted: List<Double>, minSep: Double): List<Double> {
        if (sorted.isEmpty()) return sorted
        val out = ArrayList<Double>(sorted.size)
        for (a in sorted) out += if (out.isEmpty()) a else max(a, out.last() + minSep)
        val wraps = out.last() + minSep - TWO_PI > out.first() + 1e-9
        if (!wraps) return out
        val start = sorted.first()
        return List(sorted.size) { i -> start + TWO_PI * i / sorted.size }
    }
}
