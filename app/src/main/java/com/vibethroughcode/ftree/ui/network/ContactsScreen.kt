package com.vibethroughcode.ftree.ui.network

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.game.RewardType
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.ui.FTreeViewModels
import com.vibethroughcode.ftree.ui.common.EmptyState
import com.vibethroughcode.ftree.ui.common.PersonAvatar
import com.vibethroughcode.ftree.ui.common.PersonPicker
import com.vibethroughcode.ftree.ui.common.displayName
import com.vibethroughcode.ftree.ui.common.kinshipName

const val NetworkPairTag = "network-pair"
const val NetworkListTag = "network-list"
const val NetworkWhoTag = "network-who"
fun networkRowTag(personId: String) = "network-row-$personId"
fun networkMessageTag(personId: String) = "network-message-$personId"
fun networkPreviewTag(personId: String) = "network-preview-$personId"
fun owedRowTag(redemptionId: String) = "owed-row-$redemptionId"
fun owedSettleTag(redemptionId: String) = "owed-settle-$redemptionId"

/**
 * The family network: everyone this phone trusts, and whether they were met or vouched for.
 *
 * [onMessage] is null while messaging does not exist yet; the control is then drawn unavailable
 * rather than made to look as though it works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onPair: () -> Unit,
    onOpenContact: (personId: String) -> Unit,
    onMessage: ((personId: String) -> Unit)? = null,
    viewModel: ContactsViewModel = viewModel(factory = FTreeViewModels.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.network_title)) },
            actions = {
                if (state.me != null) {
                    IconButton(onClick = onPair, modifier = Modifier.testTag(NetworkPairTag)) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.network_pair))
                    }
                }
            },
        )
        val me = state.me
        when {
            !state.loaded -> Unit

            me == null -> WhoAreYou(
                everyone = viewModel.candidates(state.everyone, query),
                hasAnyone = state.everyone.isNotEmpty(),
                query = query,
                onQueryChange = viewModel::onQueryChange,
                onPick = viewModel::chooseMe,
            )

            state.rows.isEmpty() -> EmptyState(
                title = stringResource(R.string.network_empty_title),
                body = stringResource(R.string.network_empty_body),
                actionLabel = stringResource(R.string.network_empty_action),
                onAction = onPair,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(NetworkListTag),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.owed.isNotEmpty()) {
                    item(key = "owed-title") {
                        Text(
                            text = stringResource(R.string.owed_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    items(state.owed, key = { "owed-" + it.redemption.id }) { owed ->
                        OwedCard(owed, onSettle = { viewModel.settle(owed.redemption.id) })
                    }
                }
                items(state.rows, key = { it.contact.personId }) { row ->
                    ContactCard(
                        row = row,
                        meGender = me.gender,
                        onOpen = { onOpenContact(row.contact.personId) },
                        onMessage = onMessage?.let { send -> { send(row.contact.personId) } },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.network_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OwedCard(owed: OwedRow, onSettle: () -> Unit) {
    val other = owed.other?.displayName() ?: stringResource(R.string.network_removed_person)
    val reward = stringResource(
        when (owed.redemption.rewardType) {
            RewardType.BEER -> R.string.reward_beer
            RewardType.COFFEE -> R.string.reward_coffee
            RewardType.ICE_CREAM -> R.string.reward_ice_cream
            RewardType.CUSTOM -> R.string.reward_custom
        },
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag(owedRowTag(owed.redemption.id)),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(if (owed.youOwe) R.string.owed_you_owe else R.string.owed_owes_you, other, reward),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSettle, modifier = Modifier.testTag(owedSettleTag(owed.redemption.id))) {
                Text(stringResource(R.string.owed_settle))
            }
        }
    }
}

@Composable
private fun WhoAreYou(
    everyone: List<Person>,
    hasAnyone: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag(NetworkWhoTag)) {
        Text(
            text = stringResource(R.string.network_who_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
        )
        Text(
            text = stringResource(if (hasAnyone) R.string.network_who_body else R.string.network_who_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
        )
        if (hasAnyone) {
            PersonPicker(
                people = everyone,
                query = query,
                onQueryChange = onQueryChange,
                onPick = onPick,
                onCancel = { onQueryChange("") },
                hint = stringResource(R.string.network_who_hint),
                noneFound = stringResource(R.string.network_who_none),
                searchTag = "network-who-search",
                listTag = "network-who-list",
                cancelTag = "network-who-clear",
            )
        }
    }
}

@Composable
private fun ContactCard(
    row: ContactRow,
    meGender: Gender,
    onOpen: () -> Unit,
    onMessage: (() -> Unit)?,
) {
    val person = row.person
    val name = person?.displayName() ?: stringResource(R.string.network_removed_person)
    val relation = row.relation
    val term = relation?.term?.let { found ->
        kinshipName(found, relation.path, meGender, person?.gender ?: Gender.UNSPECIFIED)?.term
    }
    val direct = row.contact.pairingMethod == PairingMethod.DIRECT
    val statusColor = if (direct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val status = if (direct) {
        stringResource(R.string.network_status_direct)
    } else {
        row.voucher?.let { stringResource(R.string.network_status_vouched, it.displayName()) }
            ?: stringResource(R.string.network_status_vouched_unknown)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag(networkRowTag(row.contact.personId)),
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (person != null) {
                PersonAvatar(person, diameter = 42.dp, decorative = true)
            } else {
                Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape))
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                if (term != null) {
                    Text(term, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                row.lastMessage?.let { last ->
                    Text(
                        text = last.body,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (row.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp).testTag(networkPreviewTag(row.contact.personId)),
                    )
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Filled for met-in-person, hollow for vouched: shape as well as colour, so the
                    // difference survives a colour-blind reader.
                    Box(
                        Modifier.size(7.dp).then(
                            if (direct) Modifier.background(statusColor, CircleShape)
                            else Modifier.border(1.5.dp, statusColor, CircleShape)
                        )
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            MessageControl(row.contact.personId, name, enabled = row.contact.messagingEnabled, unread = row.unread, onMessage = onMessage)
        }
    }
}

@Composable
private fun MessageControl(personId: String, name: String, enabled: Boolean, unread: Int, onMessage: (() -> Unit)?) {
    if (!enabled) {
        // Locked: only an in-person pairing unlocks messaging, never a vouch.
        Icon(
            Icons.Outlined.Lock,
            contentDescription = stringResource(R.string.network_message_locked),
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(8.dp).testTag(networkMessageTag(personId)),
        )
    } else {
        IconButton(
            onClick = { onMessage?.invoke() },
            enabled = onMessage != null,
            modifier = Modifier.testTag(networkMessageTag(personId)),
        ) {
            BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) {
                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = stringResource(R.string.network_message, name))
            }
        }
    }
}
