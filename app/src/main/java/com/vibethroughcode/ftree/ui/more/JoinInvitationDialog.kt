package com.vibethroughcode.ftree.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.JoinResult
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.kutumb.treesync.PendingInvite
import kotlinx.coroutines.launch

const val JoinOpenTag = "join-open"
const val JoinNotNowTag = "join-not-now"

/**
 * The one question an invitation asks: open the tree somebody shared, or not now. The words "sync", "relay"
 * and "replica" do not appear. Saying yes makes a new tree and opens it; saying no forgets the invitation,
 * and they can offer it again.
 */
@Composable
fun JoinInvitationDialog(invite: PendingInvite, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as FTreeApplication
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.join_title, invite.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.join_body, invite.people), style = MaterialTheme.typography.bodyMedium)
                if (working) Text(stringResource(R.string.sharing_loading), style = MaterialTheme.typography.bodyMedium)
                problem?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !working,
                onClick = {
                    working = true
                    problem = null
                    scope.launch {
                        when (app.joinSharedTree(invite)) {
                            is JoinResult.Joined -> { onDismiss(); app.relaunch() }
                            JoinResult.AlreadyJoined -> { app.container.invitations.dismiss(invite.tree); onDismiss() }
                            JoinResult.SyncOff -> { problem = R.string.join_sync_off; working = false }
                            JoinResult.Failed -> { problem = R.string.join_failed; working = false }
                        }
                    }
                },
                modifier = Modifier.testTag(JoinOpenTag),
            ) { Text(stringResource(R.string.join_open)) }
        },
        dismissButton = {
            TextButton(
                enabled = !working,
                onClick = { app.container.invitations.dismiss(invite.tree); onDismiss() },
                modifier = Modifier.testTag(JoinNotNowTag),
            ) { Text(stringResource(R.string.join_not_now)) }
        },
    )
}
