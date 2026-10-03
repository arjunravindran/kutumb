package com.vibethroughcode.ftree.ui.network

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.kutumb.messaging.DeliveryStatus
import com.vibethroughcode.ftree.kutumb.messaging.Message
import com.vibethroughcode.ftree.kutumb.messaging.Messaging
import com.vibethroughcode.ftree.ui.FTreeViewModels
import com.vibethroughcode.ftree.ui.common.displayName
import com.vibethroughcode.ftree.ui.common.readableMeasure

const val ThreadListTag = "thread-list"
const val ThreadInputTag = "thread-input"
const val ThreadSendTag = "thread-send"
const val ThreadLockedTag = "thread-locked"
fun threadResendTag(messageId: String) = "thread-resend-$messageId"

/**
 * A conversation with one relative met in person. Messages are saved on this phone first and sent
 * as an encrypted note over the family sync; one that could not be handed on says so, and can be
 * sent again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageThreadScreen(
    onBack: () -> Unit,
    viewModel: MessageThreadViewModel = viewModel(factory = FTreeViewModels.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }

    // Anything that arrives while the conversation is open is read the moment it is shown.
    LaunchedEffect(state.unread) { if (state.unread > 0) viewModel.markRead() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.other?.displayName() ?: stringResource(R.string.network_removed_person)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.edit_back))
                    }
                },
            )
        },
        bottomBar = {
            if (state.canMessage) {
                Row(
                    Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(Messaging.MAX_BODY) },
                        placeholder = { Text(stringResource(R.string.thread_input_hint)) },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f).testTag(ThreadInputTag),
                    )
                    IconButton(
                        onClick = { if (viewModel.send(draft)) draft = "" },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.testTag(ThreadSendTag),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.thread_send))
                    }
                }
            }
        },
    ) { padding ->
        when {
            !state.loaded -> Unit

            !state.canMessage -> Text(
                text = stringResource(R.string.thread_locked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp).testTag(ThreadLockedTag),
            )

            state.messages.isEmpty() -> Text(
                text = stringResource(R.string.thread_empty, state.other?.displayName().orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            )

            else -> LazyColumn(
                // Newest at the bottom, and the list stays there as more arrive.
                reverseLayout = true,
                modifier = Modifier.fillMaxSize().padding(padding).readableMeasure().testTag(ThreadListTag),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages.asReversed(), key = { it.id }) { message ->
                    Bubble(message, onResend = { viewModel.resend(message.id) })
                }
            }
        }
    }
}

@Composable
private fun Bubble(message: Message, onResend: () -> Unit) {
    val theirs = message.isFromThem
    val context = LocalContext.current
    val time = DateUtils.formatDateTime(
        context, message.sentAt,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (theirs) Alignment.Start else Alignment.End) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (theirs) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(message.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    theirs -> time
                    message.deliveryStatus == DeliveryStatus.NOT_SENT -> stringResource(R.string.thread_not_sent, time)
                    else -> stringResource(R.string.thread_queued, time)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (!theirs && message.deliveryStatus == DeliveryStatus.NOT_SENT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (!theirs && message.deliveryStatus == DeliveryStatus.NOT_SENT) {
                TextButton(onClick = onResend, modifier = Modifier.testTag(threadResendTag(message.id))) {
                    Text(stringResource(R.string.thread_resend), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
