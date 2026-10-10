package com.vibethroughcode.ftree.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.ui.common.FullScreenDialog
import com.vibethroughcode.ftree.ui.common.READABLE_MEASURE

const val WelcomeTag = "welcome"
const val WelcomeStartTag = "welcome-start"

/**
 * Said once, on a new install, before the empty tree asks for a name. Three short promises in plain words
 * and one big button. It covers the whole screen and scrolls, so a large system font cannot push the
 * button out of reach. Going back is the same as pressing the button: there is nothing to decide here.
 */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    FullScreenDialog(onDismiss = onStart, tag = WelcomeTag) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.widthIn(max = READABLE_MEASURE),
            ) {
                Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.welcome_lead),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Point(R.string.welcome_private_title, R.string.welcome_private_body)
                Point(R.string.welcome_names_title, R.string.welcome_names_body)
                Point(R.string.welcome_share_title, R.string.welcome_share_body)
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(WelcomeStartTag),
                ) { Text(stringResource(R.string.welcome_start), style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
private fun Point(title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
