package com.vibethroughcode.ftree.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** An empty screen is an invitation to act, so it carries the primary action rather than a shrug. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    illustration: @Composable (() -> Unit)? = null,
    /** Short numbered instructions shown under the body, for a first screen that has to teach by itself. */
    steps: List<String> = emptyList(),
    /** A second way in, shown under the main button. */
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        illustration?.invoke()
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp).widthIn(max = 360.dp),
        )
        if (steps.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 20.dp).widthIn(max = 360.dp),
            ) {
                steps.forEachIndexed { index, step ->
                    Text(
                        text = "${index + 1}.  $step",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        Button(
            onClick = onAction,
            modifier = Modifier.padding(top = 28.dp).heightIn(min = 56.dp),
        ) {
            Text(actionLabel, style = MaterialTheme.typography.titleMedium)
        }
        if (secondaryLabel != null && onSecondary != null) {
            OutlinedButton(
                onClick = onSecondary,
                modifier = Modifier.padding(top = 12.dp).heightIn(min = 56.dp),
            ) {
                Text(secondaryLabel, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
