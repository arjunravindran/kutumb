package com.vibethroughcode.ftree.ui.tree

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.data.TreeKind

const val TreeSwitcherTag = "tree-switcher"
const val TreeSwitcherItemTagPrefix = "tree-switcher-item-"

/**
 * The title of a tree's main screen. With one tree it is just [title]; with several it is that
 * tree's name with a menu of the others under it, so moving between a family and a circle does not
 * mean going to Settings. Opening one restarts the UI on it, as Settings does: every screen and
 * view model belongs to exactly one tree.
 */
@Composable
fun TreeTitle(title: String, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as FTreeApplication
    val trees = remember { app.catalog.trees }
    if (trees.size < 2) {
        Text(title, modifier = modifier)
        return
    }

    val openId = app.container.tree.id
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.clickable { open = true }.testTag(TreeSwitcherTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(app.container.tree.name, maxLines = 1)
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = stringResource(R.string.trees_switch),
            modifier = Modifier.padding(start = 2.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            trees.forEach { tree ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                tree.name,
                                style = if (tree.id == openId) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(if (tree.kind == TreeKind.CIRCLE) R.string.trees_kind_circle_short else R.string.trees_kind_family_short),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        if (tree.id != openId && app.switchTo(tree.id)) app.relaunch()
                    },
                    modifier = Modifier.testTag(TreeSwitcherItemTagPrefix + tree.id),
                )
            }
        }
    }
}
