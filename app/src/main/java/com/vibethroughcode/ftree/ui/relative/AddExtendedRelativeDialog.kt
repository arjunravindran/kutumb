package com.vibethroughcode.ftree.ui.relative

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RelationshipRejectedException
import com.vibethroughcode.ftree.graph.RelationshipRejection
import com.vibethroughcode.ftree.ui.common.displayName
import com.vibethroughcode.ftree.ui.common.rejectionMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ExtendedKindTagPrefix = "extended-kind-"
const val ExtendedNameTag = "extended-name"
const val ExtendedSaveTag = "extended-save"
const val ExtendedViaTagPrefix = "extended-via-"
const val ExtendedExistingTag = "extended-existing"
const val ExtendedSearchTag = "extended-search"
const val ExtendedPersonTagPrefix = "extended-person-"

/** The kinds of relative this reaches, each by way of somebody in between. */
enum class DistantKind { UNCLE_AUNT, COUSIN, NEPHEW_NIECE, GRANDPARENT, GRANDCHILD }

/**
 * The person in between, or a stand-in for one. [id] is who is in the tree already (a parent, an uncle, a
 * brother, a child) and null where nobody is yet and an unknown one will be made. [existing] says whether that
 * person is in the tree, which for a cousin decides between "through this uncle" and "through a new one beside
 * this parent".
 */
internal data class Via(val id: String?, val name: String?, val existing: Boolean)

/**
 * Adds, or joins up, a relative more than one link away from [person]: an uncle, aunt, cousin, nephew, niece,
 * grandparent or grandchild.
 *
 * These are not new kinds of link: an uncle is a brother or sister of a parent, a cousin a child of one, and so
 * on. The reader says which way round (through which parent, which uncle), and whether the person is new or
 * already in the tree, such as somebody added earlier with no connection. The tree gets the same ordinary links
 * it would have had they added the people by hand, with an unknown stand-in where nobody is named yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddExtendedRelativeDialog(person: Person, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as FTreeApplication
    val family = app.container.familyRepository
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(DistantKind.UNCLE_AUNT) }
    var parents by remember { mutableStateOf<List<Person>?>(null) }
    var siblings by remember { mutableStateOf<List<Person>>(emptyList()) }
    var children by remember { mutableStateOf<List<Person>>(emptyList()) }
    var auntsAndUncles by remember { mutableStateOf<List<Person>>(emptyList()) }
    var everyone by remember { mutableStateOf<List<Person>>(emptyList()) }
    var chosen by remember { mutableStateOf(0) }
    var useExisting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Person?>(null) }
    var search by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf(Gender.UNSPECIFIED) }
    var saving by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<RelationshipRejection?>(null) }

    LaunchedEffect(person.id) {
        val mine = family.observeParents(person.id).first()
        val sibs = family.observeSiblings(person.id).first()
        val kids = family.observeChildren(person.id).first()
        val theirs = mine.flatMap { family.observeSiblings(it.id).first() }.distinctBy { it.id }
        val all = family.allPeople().filter { it.id != person.id }
        // Written on the main thread, so the screen is sure to pick it up.
        withContext(Dispatchers.Main) {
            parents = mine; siblings = sibs; children = kids; auntsAndUncles = theirs; everyone = all
        }
    }

    val known = parents
    @Composable fun vias(of: List<Person>) = of.map { Via(it.id, it.displayName(), true) }
    val options: List<Via> = when {
        known == null -> emptyList()
        else -> when (kind) {
            DistantKind.UNCLE_AUNT, DistantKind.GRANDPARENT ->
                if (known.isEmpty()) listOf(Via(null, null, false)) else vias(known)
            DistantKind.COUSIN ->
                vias(auntsAndUncles) + if (known.isEmpty()) listOf(Via(null, null, false))
                else known.map { Via(it.id, it.displayName(), false) }
            DistantKind.NEPHEW_NIECE -> vias(siblings) + Via(null, null, false)
            DistantKind.GRANDCHILD -> vias(children) + Via(null, null, false)
        }
    }
    val selected = options.getOrNull(chosen.coerceAtMost((options.size - 1).coerceAtLeast(0)))
    val candidates = remember(everyone, search) {
        val q = search.trim()
        everyone.filter { q.isEmpty() || (it.name?.contains(q, ignoreCase = true) == true) }.take(8)
    }
    val ready = selected != null && if (useExisting) picked != null else name.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.extended_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DistantKind.entries.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k; chosen = 0 },
                            label = { Text(stringResource(k.label())) },
                            modifier = Modifier.testTag(ExtendedKindTagPrefix + k.name.lowercase()),
                        )
                    }
                }
                Text(stringResource(R.string.extended_via), style = MaterialTheme.typography.labelLarge)
                options.forEachIndexed { index, via ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { chosen = index }.testTag(ExtendedViaTagPrefix + index),
                    ) {
                        RadioButton(selected = selected == via, onClick = null)
                        Text(via.label(kind), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !useExisting,
                        onClick = { useExisting = false },
                        label = { Text(stringResource(R.string.extended_who_new)) },
                    )
                    FilterChip(
                        selected = useExisting,
                        onClick = { useExisting = true },
                        label = { Text(stringResource(R.string.extended_who_existing)) },
                        modifier = Modifier.testTag(ExtendedExistingTag),
                    )
                }

                if (useExisting) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it.take(100) },
                        label = { Text(stringResource(R.string.people_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(ExtendedSearchTag),
                    )
                    candidates.forEach { candidate ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picked = candidate }
                                .testTag(ExtendedPersonTagPrefix + candidate.id),
                        ) {
                            RadioButton(selected = picked?.id == candidate.id, onClick = null)
                            Text(candidate.displayName(), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(100) },
                        label = { Text(stringResource(R.string.add_relative_name_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth().testTag(ExtendedNameTag),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Gender.MALE to R.string.gender_male,
                            Gender.FEMALE to R.string.gender_female,
                            Gender.OTHER to R.string.gender_other,
                            Gender.UNSPECIFIED to R.string.gender_unspecified,
                        ).forEach { (g, label) ->
                            FilterChip(selected = gender == g, onClick = { gender = g }, label = { Text(stringResource(label)) })
                        }
                    }
                }
                refused?.let {
                    Text(stringResource(rejectionMessage(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && ready,
                modifier = Modifier.testTag(ExtendedSaveTag),
                onClick = {
                    val via = selected ?: return@Button
                    saving = true
                    refused = null
                    val relative = if (useExisting) null else Person(name = name.trim(), gender = gender)
                    val existingId = if (useExisting) picked?.id else null
                    scope.launch {
                        val result = when (kind) {
                            DistantKind.UNCLE_AUNT -> family.addUncleOrAunt(person.id, via.id, relative, existingId)
                            DistantKind.COUSIN ->
                                if (via.existing) family.addCousin(person.id, via.id, null, relative, existingId)
                                else family.addCousin(person.id, null, via.id, relative, existingId)
                            DistantKind.NEPHEW_NIECE -> family.addNephewOrNiece(person.id, via.id, relative, existingId)
                            DistantKind.GRANDPARENT -> family.addGrandparent(person.id, via.id, relative, existingId)
                            DistantKind.GRANDCHILD -> family.addGrandchild(person.id, via.id, relative, existingId)
                        }
                        withContext(Dispatchers.Main) {
                            if (result.isSuccess) onDismiss()
                            else {
                                saving = false
                                refused = (result.exceptionOrNull() as? RelationshipRejectedException)?.reason
                                    ?: RelationshipRejection.DUPLICATE
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.extended_save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.contacts_cancel)) }
        },
    )
}

private fun DistantKind.label(): Int = when (this) {
    DistantKind.UNCLE_AUNT -> R.string.extended_kind_uncle
    DistantKind.COUSIN -> R.string.extended_kind_cousin
    DistantKind.NEPHEW_NIECE -> R.string.extended_kind_nephew
    DistantKind.GRANDPARENT -> R.string.extended_kind_grandparent
    DistantKind.GRANDCHILD -> R.string.extended_kind_grandchild
}

@Composable
private fun Via.label(kind: DistantKind): String = when (kind) {
    DistantKind.UNCLE_AUNT ->
        name?.let { stringResource(R.string.extended_via_sibling_of, it) } ?: stringResource(R.string.extended_via_parent_unknown)
    DistantKind.COUSIN -> when {
        existing && name != null -> stringResource(R.string.extended_via_child_of, name)
        name != null -> stringResource(R.string.extended_via_cousin_new, name)
        else -> stringResource(R.string.extended_via_cousin_unknown)
    }
    DistantKind.NEPHEW_NIECE ->
        name?.let { stringResource(R.string.extended_via_child_of, it) } ?: stringResource(R.string.extended_via_nephew_new)
    DistantKind.GRANDPARENT ->
        name?.let { stringResource(R.string.extended_via_parent_of, it) } ?: stringResource(R.string.extended_via_grandparent_new)
    DistantKind.GRANDCHILD ->
        name?.let { stringResource(R.string.extended_via_child_of, it) } ?: stringResource(R.string.extended_via_grandchild_new)
}
