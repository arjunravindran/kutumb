package com.vibethroughcode.ftree.ui.circle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.graph.CircleGraph
import com.vibethroughcode.ftree.graph.GraphRing
import com.vibethroughcode.ftree.ui.common.displayName
import kotlin.math.max
import kotlin.math.min

const val CircleGraphTag = "circle-graph"
const val CircleViewListTag = "circle-view-list"
const val CircleViewGraphTag = "circle-view-graph"

/**
 * The colour of a label's sector and lines. Unlabelled is neutral. The rest cycle through a fixed
 * set that keeps clear of brass, which in this app means "unknown", and of the green that the
 * middle person is drawn in; lighter in the dark theme so each still reads against it.
 */
internal fun circleGroupColor(index: Int, label: String?, scheme: ColorScheme): Color {
    if (label == null) return scheme.outline
    val dark = scheme.background.luminance() < 0.5f
    val accents = if (dark) DARK_ACCENTS else LIGHT_ACCENTS
    return accents[index % accents.size]
}

private val LIGHT_ACCENTS = listOf(Color(0xFF3B6EA8), Color(0xFF9E3B30), Color(0xFF7B4F8E), Color(0xFF2F7F7F), Color(0xFF6B5B3E))
private val DARK_ACCENTS = listOf(Color(0xFF8AB4E8), Color(0xFFE59A92), Color(0xFFC39BD3), Color(0xFF7FC8C8), Color(0xFFCBBA98))

/**
 * A circle as a picture, drawn on one Canvas like the family chart: the middle person, their
 * connections around them with a block of colour per label, and the people reached through those on
 * an outer ring. Tap anyone to move the picture to them.
 *
 * A Canvas says nothing to a screen reader, so it carries a summary and points to the list view,
 * which has every person and is the accessible way to read a circle.
 */
@Composable
fun CircleGraphView(graph: CircleGraph, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val initialStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val nameStyle = MaterialTheme.typography.labelSmall
    // displayName() is composable (an unnamed person reads "Unknown"), so it is read here, not while drawing.
    val names = graph.nodes.associate { it.id to it.person.displayName() }
    val description = stringResource(R.string.circle_graph_description, graph.ego.person.displayName(), graph.nodes.size - 1)
    val named = graph.nodes.count { it.ring == GraphRing.DIRECT } <= NAMES_UP_TO && graph.nodes.count { it.ring == GraphRing.THROUGH } <= NAMES_UP_TO

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .testTag(CircleGraphTag)
            .semantics { contentDescription = description }
            .pointerInput(graph) {
                detectTapGestures { tap ->
                    val scale = min(size.width, size.height) / (2 * graph.extent)
                    val x = (tap.x - size.width / 2f) / scale
                    val y = (tap.y - size.height / 2f) / scale
                    // A fingertip is bigger than a node on a crowded picture: reach at least 24dp.
                    val reach = max(0.6f, 24.dp.toPx() / scale)
                    graph.nodeAt(x, y, reach)?.takeIf { it.ring != GraphRing.EGO }?.let { onPick(it.id) }
                }
            },
    ) {
        val scale = min(size.width, size.height) / (2 * graph.extent)
        val centre = Offset(size.width / 2f, size.height / 2f)
        fun at(x: Float, y: Float) = Offset(centre.x + x * scale, centre.y + y * scale)

        // Lines first, so every node sits on top of them.
        for (e in graph.edges) {
            val a = graph.node(e.fromId) ?: continue
            val b = graph.node(e.toId) ?: continue
            val coloured = e.group >= 0
            drawLine(
                color = if (coloured) circleGroupColor(e.group, graph.groups.getOrNull(e.group)?.label, scheme).copy(alpha = 0.75f) else scheme.outlineVariant,
                start = at(a.x, a.y),
                end = at(b.x, b.y),
                strokeWidth = if (coloured) 2.5.dp.toPx() else 1.dp.toPx(),
            )
        }

        for (n in graph.nodes) {
            val p = at(n.x, n.y)
            val (radius, fill, edge) = when (n.ring) {
                GraphRing.EGO -> Triple(0.55f, scheme.primary, scheme.primary)
                GraphRing.DIRECT -> Triple(0.42f, scheme.surfaceContainerHigh, circleGroupColor(n.group, graph.groups.getOrNull(n.group)?.label, scheme))
                GraphRing.THROUGH -> Triple(0.32f, scheme.surfaceContainerLow, scheme.outline)
            }
            drawCircle(fill, radius * scale, p)
            drawCircle(edge, radius * scale, p, style = Stroke(width = (if (n.ring == GraphRing.EGO) 0f else 2.dp.toPx())))

            val text = if (n.ring == GraphRing.EGO) scheme.onPrimary else scheme.onSurface
            drawCentred(measurer, names.getValue(n.id).firstOrNull()?.uppercaseChar()?.toString() ?: "?", initialStyle.copy(color = text), p)
            if (named || n.ring == GraphRing.EGO) {
                val laid = measurer.measure(
                    names.getValue(n.id),
                    nameStyle.copy(color = scheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = max(1, (scale * 2.2f).toInt())),
                )
                drawText(laid, topLeft = Offset(p.x - laid.size.width / 2f, p.y + radius * scale + 2.dp.toPx()))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCentred(
    measurer: TextMeasurer,
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    centre: Offset,
) {
    val laid = measurer.measure(text, style, maxLines = 1)
    drawText(laid, topLeft = Offset(centre.x - laid.size.width / 2f, centre.y - laid.size.height / 2f))
}

/** What each colour in the picture is: a dot and the label. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CircleGraphLegend(graph: CircleGraph, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val unlabelled = stringResource(R.string.circle_unlabelled)
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        graph.groups.forEachIndexed { index, group ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.size(10.dp).background(circleGroupColor(index, group.label, scheme), CircleShape),
                ) {}
                Text(
                    text = "${group.label ?: unlabelled} · ${group.count}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** Anyone's name under their circle while there is room for it; beyond this the picture shows initials. */
private const val NAMES_UP_TO = 14
