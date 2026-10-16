package com.vibethroughcode.ftree.ui.phonebook

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.core.content.ContextCompat
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.data.RecordedDate
import com.vibethroughcode.ftree.phonebook.AndroidPhonebook
import com.vibethroughcode.ftree.phonebook.ContactRelation
import com.vibethroughcode.ftree.phonebook.ContactMatcher
import com.vibethroughcode.ftree.phonebook.ContactsImport
import com.vibethroughcode.ftree.phonebook.ContactsImportResult
import com.vibethroughcode.ftree.phonebook.ContactsImporter
import com.vibethroughcode.ftree.phonebook.PhoneContact
import com.vibethroughcode.ftree.phonebook.Phonebook
import com.vibethroughcode.ftree.phonebook.PhonebookSource
import com.vibethroughcode.ftree.ui.common.FullScreenDialog
import com.vibethroughcode.ftree.ui.common.READABLE_MEASURE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ContactsImportTag = "contacts-import"
const val ContactsAllowTag = "contacts-allow"
const val ContactsAddTag = "contacts-add"
const val ContactsDoneTag = "contacts-done"
const val ContactsMeFieldTag = "contacts-me"
const val ContactsSearchTag = "contacts-search"
const val ContactRowTagPrefix = "contact-row-"

fun contactRelationTag(contactId: Long, relation: ContactRelation) = "contact-rel-$contactId-${relation.name}"

/**
 * Adds people from the phone's contacts, to fill a tree quickly.
 *
 * Contacts are read only after the reader taps Allow, and only names and birthdays. On an empty tree the reader
 * says who they are and how each person they tick is related to them, and the tree is built in one go; on a tree
 * that already has people, the ones ticked are added without connections, and anybody already there is skipped.
 * A page of its own rather than a destination, so nothing in navigation changed for it.
 */
@Composable
fun ContactsImportDialog(
    onDismiss: () -> Unit,
    /** False for a circle, which has no mothers or sons: everyone is then added without a connection. */
    allowRelations: Boolean = true,
    source: PhonebookSource? = null,
    /** When set, the page is for choosing one contact to fill a form in with: a tap picks and closes. Nothing is added. */
    pickOne: ((PhoneContact) -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FTreeApplication
    val family = app.container.familyRepository
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
    }

    var contacts by remember { mutableStateOf<List<PhoneContact>?>(null) }
    var existing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var possible by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var emptyTree by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        val people = family.allPeople()
        val read = try {
            (source ?: AndroidPhonebook(context.contentResolver)).contacts()
        } catch (_: SecurityException) {
            // Permission taken back while this was open.
            null
        }
        // Near-matches ("Asha K" for "Asha Kumar") are worked out off the main thread: contacts times people.
        val near = if (read == null) emptyMap() else withContext(Dispatchers.Default) {
            val matcher = ContactMatcher(people)
            buildMap { read.forEach { c -> matcher.possibleMatch(c)?.name?.let { put(c.id, it) } } }
        }
        // Written on the main thread, so the screen is sure to pick it up.
        withContext(Dispatchers.Main) {
            if (read == null) {
                granted = false
                failed = true
            } else {
                existing = people.map { Phonebook.nameKey(it.name) }.filter { it.isNotEmpty() }.toSet()
                emptyTree = people.isEmpty() && allowRelations && pickOne == null
                possible = near
                contacts = read
            }
        }
    }

    val picks = remember { mutableStateMapOf<Long, ContactRelation>() }
    var myName by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ContactsImportResult?>(null) }

    FullScreenDialog(onDismiss = { if (!busy) onDismiss() }, tag = ContactsImportTag) {
        val done = result
        val list = contacts
        when {
            done != null -> DoneStage(done, onDone = onDismiss)

            !granted -> AskStage(
                refused = refused || failed,
                onAllow = { ask.launch(Manifest.permission.READ_CONTACTS) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                onCancel = onDismiss,
            )

            list == null -> Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.contacts_loading), style = MaterialTheme.typography.titleMedium)
            }

            else -> ContactsPicker(
                contacts = list,
                existingNames = existing,
                possibleMatches = possible,
                emptyTree = emptyTree,
                myName = myName,
                onMyName = { myName = it.take(100) },
                picks = picks,
                onPick = { id, relation -> if (relation == null) picks.remove(id) else picks[id] = relation },
                query = query,
                onQuery = { query = it.take(100) },
                busy = busy,
                single = pickOne != null,
                onSingle = { contact -> pickOne?.invoke(contact); onDismiss() },
                onAdd = {
                    busy = true
                    scope.launch {
                        val plan = ContactsImport.plan(if (emptyTree) myName else null, picks.toMap(), list, existing)
                        val outcome = ContactsImporter(family).apply(plan)
                        withContext(Dispatchers.Main) {
                            busy = false
                            result = outcome
                        }
                    }
                },
                onCancel = onDismiss,
            )
        }
    }
}

@Composable
private fun AskStage(refused: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.widthIn(max = READABLE_MEASURE), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.contacts_ask_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.contacts_ask_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (refused) {
                Text(
                    stringResource(R.string.contacts_denied),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.contacts_open_settings), style = MaterialTheme.typography.titleMedium)
                }
            }
            Button(
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ContactsAllowTag),
            ) { Text(stringResource(R.string.contacts_allow), style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.contacts_cancel))
            }
        }
    }
}

@Composable
private fun DoneStage(result: ContactsImportResult, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.widthIn(max = READABLE_MEASURE), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.contacts_done_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.contacts_done_added, result.added),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (result.connected > 0) {
                Text(stringResource(R.string.contacts_done_connected, result.connected), style = MaterialTheme.typography.bodyLarge)
            }
            if (result.alreadyThere > 0) {
                Text(
                    stringResource(R.string.contacts_done_skipped, result.alreadyThere),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ContactsDoneTag),
            ) { Text(stringResource(R.string.contacts_done_button), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** The list of contacts to tick, as a plain function of what it is given so a test can drive it with a fake list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactsPicker(
    contacts: List<PhoneContact>,
    existingNames: Set<String>,
    emptyTree: Boolean,
    myName: String,
    onMyName: (String) -> Unit,
    picks: Map<Long, ContactRelation>,
    onPick: (Long, ContactRelation?) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    busy: Boolean,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    single: Boolean = false,
    onSingle: (PhoneContact) -> Unit = {},
    /** Contact id to the name of someone in the tree it might be. Only a hint: the contact can still be ticked. */
    possibleMatches: Map<Long, String> = emptyMap(),
) {
    val shown = remember(contacts, query) {
        val q = query.trim()
        if (q.isEmpty()) contacts else contacts.filter { it.name.contains(q, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(
                    when {
                        single -> R.string.contacts_pick_title_one
                        emptyTree -> R.string.contacts_pick_title_new
                        else -> R.string.contacts_pick_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            if (emptyTree) {
                OutlinedTextField(
                    value = myName,
                    onValueChange = onMyName,
                    label = { Text(stringResource(R.string.contacts_me_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(ContactsMeFieldTag),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text(stringResource(R.string.contacts_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ContactsSearchTag),
            )
        }
        HorizontalDivider()
        if (contacts.isEmpty()) {
            Text(
                stringResource(R.string.contacts_none),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(28.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { contact ->
                    val already = Phonebook.nameKey(contact.name) in existingNames
                    val chosen = picks[contact.id]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !already || single) {
                                if (single) onSingle(contact)
                                else onPick(contact.id, if (chosen == null) ContactRelation.NOT_CONNECTED else null)
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag(ContactRowTagPrefix + contact.id),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!single) Checkbox(checked = chosen != null, onCheckedChange = null, enabled = !already)
                            Column(Modifier.padding(start = if (single) 0.dp else 12.dp)) {
                                Text(contact.name, style = MaterialTheme.typography.bodyLarge)
                                val note = when {
                                    already && !single -> stringResource(R.string.contacts_already)
                                    !single && contact.id in possibleMatches ->
                                        stringResource(R.string.contacts_might_be, possibleMatches.getValue(contact.id))
                                    else -> contact.birthday?.let { RecordedDate.parse(it)?.display() }
                                }
                                if (note != null) {
                                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (emptyTree && chosen != null) {
                            Text(
                                stringResource(R.string.contacts_relation_prompt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 52.dp, top = 6.dp),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(start = 40.dp, top = 4.dp),
                            ) {
                                ContactRelation.entries.forEach { relation ->
                                    FilterChip(
                                        selected = chosen == relation,
                                        onClick = { onPick(contact.id, relation) },
                                        label = { Text(stringResource(relation.label())) },
                                        modifier = Modifier.testTag(contactRelationTag(contact.id, relation)),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.contacts_cancel)) }
            if (!single) Button(
                onClick = onAdd,
                enabled = picks.isNotEmpty() && !busy && (!emptyTree || myName.isNotBlank()),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(ContactsAddTag),
            ) { Text(stringResource(R.string.contacts_add, picks.size), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

private fun ContactRelation.label(): Int = when (this) {
    ContactRelation.MOTHER -> R.string.contacts_rel_mother
    ContactRelation.FATHER -> R.string.contacts_rel_father
    ContactRelation.SISTER -> R.string.contacts_rel_sister
    ContactRelation.BROTHER -> R.string.contacts_rel_brother
    ContactRelation.SPOUSE -> R.string.contacts_rel_spouse
    ContactRelation.DAUGHTER -> R.string.contacts_rel_daughter
    ContactRelation.SON -> R.string.contacts_rel_son
    ContactRelation.NOT_CONNECTED -> R.string.contacts_rel_none
}
