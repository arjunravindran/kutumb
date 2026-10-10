package com.vibethroughcode.ftree.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.kutumb.treesync.MergeSuggestion
import com.vibethroughcode.ftree.kutumb.treesync.PendingInvite

const val MoreInvitationTagPrefix = "more-invitation-"
const val MoreMergeTag = "more-merge"
const val MoreNetworkTag = "more-network"
const val MoreFactsTag = "more-facts"
const val MoreSettingsTag = "more-settings"

/**
 * Everything that is not the family itself, as a short list of big plain rows.
 *
 * The bottom bar has three places instead of five, because many readers are not at ease with a
 * crowded bar. The family quiz is left out for a Circle, which has no family to ask about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    showFacts: Boolean,
    onNetwork: () -> Unit,
    onFacts: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** Trees somebody has offered to share, at the top because they are waiting for an answer. */
    invitations: List<PendingInvite> = emptyList(),
    onInvitation: (PendingInvite) -> Unit = {},
    /** Another tree on this phone that could be lined up with this one. */
    mergeOffer: MergeSuggestion.Candidate? = null,
    onMergeOffer: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.nav_more)) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            invitations.forEach { invite ->
                MoreRow(
                    icon = Icons.Default.Share,
                    title = invite.fromName?.let { stringResource(R.string.more_invitation_title, it) }
                        ?: stringResource(R.string.more_invitation_title_unknown),
                    body = invite.name,
                    tag = MoreInvitationTagPrefix + invite.tree,
                    onClick = { onInvitation(invite) },
                )
            }
            mergeOffer?.let { offer ->
                MoreRow(
                    icon = Icons.Default.Share,
                    title = stringResource(R.string.more_merge_title, offer.name),
                    body = stringResource(R.string.more_merge_body, offer.people),
                    tag = MoreMergeTag,
                    onClick = onMergeOffer,
                )
            }
            MoreRow(Icons.Default.Group, R.string.nav_network, R.string.more_network_body, MoreNetworkTag, onNetwork)
            if (showFacts) {
                MoreRow(Icons.Default.Quiz, R.string.nav_facts, R.string.more_facts_body, MoreFactsTag, onFacts)
            }
            MoreRow(Icons.Default.Settings, R.string.nav_settings, R.string.more_settings_body, MoreSettingsTag, onSettings)
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, title: Int, body: Int, tag: String, onClick: () -> Unit) =
    MoreRow(icon, stringResource(title), stringResource(body), tag, onClick)

@Composable
private fun MoreRow(icon: ImageVector, title: String, body: String, tag: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .testTag(tag),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}
