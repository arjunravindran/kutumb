package com.vibethroughcode.ftree.ui.relative

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import com.vibethroughcode.ftree.ui.common.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ExtendedUncleTag = "extended-uncle"
const val ExtendedCousinTag = "extended-cousin"
const val ExtendedNameTag = "extended-name"
const val ExtendedSaveTag = "extended-save"
const val ExtendedViaTagPrefix = "extended-via-"

/** Where in the family the new person hangs: through one of the person's parents, or through an uncle or aunt. */
internal sealed interface ExtendedVia {
    /** An uncle or aunt: a sibling of this parent, or of one not in the tree yet when [parentId] is null. */
    data class SiblingOfParent(val parentId: String?, val parentName: String?) : ExtendedVia

    /** A cousin through an uncle or aunt already in the tree. */
    data class ChildOf(val auntUncleId: String, val name: String) : ExtendedVia

    /** A cousin through an uncle or aunt not in the tree yet, a sibling of this parent (or of one not in the tree). */
    data class ChildOfNewSibling(val parentId: String?, val parentName: String?) : ExtendedVia
}

/**
 * Adds an uncle, aunt or cousin to [person] without making the reader build the generation between.
 *
 * These are not new kinds of link: an uncle is a brother or sister of a parent, a cousin a child of one. The
 * reader says which parent's side (or which uncle or aunt), and the tree gets the same ordinary links it would
 * have had they added the people by hand, an unknown parent or aunt standing in where nobody is named yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddExtendedRelativeDialog(person: Person, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as FTreeApplication
    val family = app.container.familyRepository
    val scope = rememberCoroutineScope()

    var cousin by remember { mutableStateOf(false) }
    var parents by remember { mutableStateOf<List<Person>?>(null) }
    var auntsAndUncles by remember { mutableStateOf<List<Person>>(emptyList()) }
    var chosen by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf(Gender.UNSPECIFIED) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(person.id) {
        val mine = family.observeParents(person.id).first()
        val theirs = mine.flatMap { family.observeSiblings(it.id).first() }.distinctBy { it.id }
        // Written on the main thread, so the screen is sure to pick it up.
        withContext(Dispatchers.Main) {
            parents = mine
            auntsAndUncles = theirs
        }
    }

    val known = parents
    val options: List<ExtendedVia> = when {
        known == null -> emptyList()
        !cousin ->
            if (known.isEmpty()) listOf(ExtendedVia.SiblingOfParent(null, null))
            else known.map { ExtendedVia.SiblingOfParent(it.id, it.displayName()) }
        else ->
            auntsAndUncles.map { ExtendedVia.ChildOf(it.id, it.displayName()) } +
                if (known.isEmpty()) listOf(ExtendedVia.ChildOfNewSibling(null, null))
                else known.map { ExtendedVia.ChildOfNewSibling(it.id, it.displayName()) }
    }
    val selected = options.getOrNull(chosen.coerceAtMost((options.size - 1).coerceAtLeast(0)))

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.extended_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !cousin,
                        onClick = { cousin = false; chosen = 0 },
                        label = { Text(stringResource(R.string.extended_kind_uncle)) },
                        modifier = Modifier.testTag(ExtendedUncleTag),
                    )
                    FilterChip(
                        selected = cousin,
                        onClick = { cousin = true; chosen = 0 },
                        label = { Text(stringResource(R.string.extended_kind_cousin)) },
                        modifier = Modifier.testTag(ExtendedCousinTag),
                    )
                }
                Text(stringResource(R.string.extended_via), style = MaterialTheme.typography.labelLarge)
                options.forEachIndexed { index, via ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chosen = index }
                            .testTag(ExtendedViaTagPrefix + index),
                    ) {
                        RadioButton(selected = selected == via, onClick = null)
                        Text(via.label(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
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
        },
        confirmButton = {
            Button(
                enabled = !saving && selected != null && name.isNotBlank(),
                modifier = Modifier.testTag(ExtendedSaveTag),
                onClick = {
                    val via = selected ?: return@Button
                    saving = true
                    val relative = Person(name = name.trim(), gender = gender)
                    scope.launch {
                        when (via) {
                            is ExtendedVia.SiblingOfParent -> family.addUncleOrAunt(person.id, via.parentId, relative)
                            is ExtendedVia.ChildOf -> family.addCousin(person.id, via.auntUncleId, null, relative)
                            is ExtendedVia.ChildOfNewSibling -> family.addCousin(person.id, null, via.parentId, relative)
                        }
                        withContext(Dispatchers.Main) { onDismiss() }
                    }
                },
            ) { Text(stringResource(R.string.extended_save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.contacts_cancel)) }
        },
    )
}

@Composable
private fun ExtendedVia.label(): String = when (this) {
    is ExtendedVia.SiblingOfParent ->
        parentName?.let { stringResource(R.string.extended_via_sibling_of, it) }
            ?: stringResource(R.string.extended_via_parent_unknown)
    is ExtendedVia.ChildOf -> stringResource(R.string.extended_via_child_of, name)
    is ExtendedVia.ChildOfNewSibling ->
        parentName?.let { stringResource(R.string.extended_via_cousin_new, it) }
            ?: stringResource(R.string.extended_via_cousin_unknown)
}
