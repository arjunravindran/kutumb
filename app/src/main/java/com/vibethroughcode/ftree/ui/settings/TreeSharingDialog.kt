package com.vibethroughcode.ftree.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.kutumb.treesync.SharingInviter
import com.vibethroughcode.ftree.kutumb.treesync.SharingState
import com.vibethroughcode.ftree.kutumb.treesync.TreeSharing
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val SharingButtonTag = "settings-trees-sharing"
const val SharingTurnOnTag = "sharing-turn-on"
const val SharingMemberTagPrefix = "sharing-member-"
const val SharingStopTag = "sharing-stop"
const val SharingStopConfirmTag = "sharing-stop-confirm"

/**
 * Who the open tree is shared with. Everything that decides anything lives in [TreeSharing]; this only
 * shows its state and passes the person's taps on to it.
 *
 * It works on the tree that is open, because each tree has its own database and sharing is stored in it.
 */
@Composable
fun TreeSharingDialog(treeName: String, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as FTreeApplication
    val container = app.container
    val scope = rememberCoroutineScope()
    val syncOn by container.syncPreferences.enabled.collectAsState()

    val sharing = remember(container) {
        TreeSharing(
            tree = container.treeSync,
            switch = container.treeSync,
            members = container.treeSync,
            contacts = { container.kutumbRepository.observeContacts().first() },
            publisher = container.treeSyncPublisher,
            inviter = SharingInviter(
                sharedId = { container.treeSync.sharedId() },
                treeName = { treeName },
                peopleCount = { container.familyRepository.allPeople().size },
                myPersonId = { container.kutumbRepository.observeIdentity().first()?.personId },
                transport = container.syncPublisher,
                invitations = container.invitations,
            ),
        )
    }
    var state by remember { mutableStateOf<SharingState?>(null) }
    var contacts by remember { mutableStateOf<Map<String, TrustedContact>>(emptyMap()) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var confirmingStop by remember { mutableStateOf(false) }

    suspend fun reload() {
        val next = sharing.state()
        val all = container.kutumbRepository.observeContacts().first().associateBy { it.personId }
        names = next.candidates.associate { c -> c.personId to (container.familyRepository.person(c.personId)?.name ?: c.personId) }
        contacts = all
        state = next
    }
    LaunchedEffect(sharing) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sharing_title, treeName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val s = state
                when {
                    s == null -> Text(stringResource(R.string.sharing_loading), style = MaterialTheme.typography.bodyMedium)

                    !s.shared -> {
                        Text(stringResource(R.string.sharing_off_body), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = {
                                scope.launch {
                                    if (sharing.turnOn()) container.startTreeSyncIfShared()
                                    reload()
                                }
                            },
                            modifier = Modifier.testTag(SharingTurnOnTag),
                        ) { Text(stringResource(R.string.sharing_turn_on)) }
                    }

                    else -> {
                        Text(stringResource(R.string.sharing_on_body), style = MaterialTheme.typography.bodyMedium)
                        if (s.candidates.isEmpty()) {
                            Text(
                                stringResource(R.string.sharing_nobody),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        s.candidates.forEach { candidate ->
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(names[candidate.personId] ?: candidate.personId, style = MaterialTheme.typography.bodyLarge)
                                    if (candidate.invitedWaiting) {
                                        Text(
                                            stringResource(R.string.sharing_invited_waiting),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Switch(
                                    checked = candidate.isMember,
                                    onCheckedChange = { on ->
                                        val contact = contacts[candidate.personId] ?: return@Switch
                                        scope.launch {
                                            sharing.setMember(contact, on)
                                            reload()
                                        }
                                    },
                                    modifier = Modifier.testTag(SharingMemberTagPrefix + candidate.personId),
                                )
                            }
                        }
                        if (s.waiting > 0) {
                            Text(
                                stringResource(R.string.sharing_waiting, s.waiting),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!syncOn) {
                            Text(
                                stringResource(R.string.sharing_sync_off),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { confirmingStop = true },
                            modifier = Modifier.testTag(SharingStopTag),
                        ) { Text(stringResource(R.string.sharing_stop), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sharing_done)) } },
    )

    if (confirmingStop) {
        AlertDialog(
            onDismissRequest = { confirmingStop = false },
            title = { Text(stringResource(R.string.sharing_stop_title, treeName)) },
            text = { Text(stringResource(R.string.sharing_stop_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingStop = false
                        scope.launch {
                            sharing.turnOff()
                            reload()
                        }
                    },
                    modifier = Modifier.testTag(SharingStopConfirmTag),
                ) { Text(stringResource(R.string.sharing_stop_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingStop = false }) { Text(stringResource(R.string.trees_cancel)) }
            },
        )
    }
}
