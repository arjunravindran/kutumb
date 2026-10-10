package com.vibethroughcode.ftree.ui.relative

import com.vibethroughcode.ftree.graph.LinkSuggestions
import androidx.compose.foundation.clickable
import com.vibethroughcode.ftree.ui.phonebook.ContactsImportDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.ui.FTreeViewModels
import com.vibethroughcode.ftree.ui.common.PersonRow
import com.vibethroughcode.ftree.ui.common.SectionRule
import com.vibethroughcode.ftree.ui.common.addRelativeTitle
import com.vibethroughcode.ftree.ui.common.displayName
import com.vibethroughcode.ftree.ui.common.readableMeasure
import com.vibethroughcode.ftree.ui.common.rejectionMessage

const val AddRelativeFromContactTag = "add-relative-from-contact"
const val AddRelativeSuggestionTagPrefix = "add-relative-suggestion-"
const val AddRelativeUseExistingTag = "add-relative-use-existing"
const val AddRelativeAddAnywayTag = "add-relative-add-anyway"
const val AddRelativeNameTag = "add-relative-name"
const val AddRelativeSaveTag = "add-relative-save"
const val AddRelativeUnknownTag = "add-relative-unknown"

/**
 * One screen to answer "who?".
 *
 * The three ways to answer sit together: name someone new, record someone whose name is not known,
 * or pick a person already in the tree. Recording an unknown relative is a single tap, because the
 * whole point is to capture a connection you know about before the details you don't.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddRelativeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddRelativeViewModel = viewModel(factory = FTreeViewModels.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val anchorName = state.anchor?.displayName().orEmpty()
    var choosingContact by remember { mutableStateOf(false) }
    if (choosingContact) {
        ContactsImportDialog(
            onDismiss = { choosingContact = false },
            pickOne = { contact -> viewModel.onContactChosen(contact.name, contact.birthday) },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(addRelativeTitle(viewModel.kind), anchorName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.edit_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxHeight().padding(padding).readableMeasure(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.suggestions.isNotEmpty()) {
                        SectionRule(stringResource(R.string.add_relative_maybe))
                        state.suggestions.forEach { suggestion ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.linkExisting(suggestion.person.id, onBack) }
                                    .padding(vertical = 8.dp)
                                    .testTag(AddRelativeSuggestionTagPrefix + suggestion.person.id),
                            ) {
                                Text(suggestion.person.displayName(), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    suggestion.reasonText(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    SectionRule(stringResource(R.string.add_relative_new))

                    OutlinedButton(
                        onClick = { choosingContact = true },
                        modifier = Modifier.fillMaxWidth().testTag(AddRelativeFromContactTag),
                    ) { Text(stringResource(R.string.contacts_fill)) }

                    OutlinedTextField(
                        value = state.newName,
                        onValueChange = viewModel::onNewNameChange,
                        label = { Text(stringResource(R.string.add_relative_name_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag(AddRelativeNameTag),
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            Gender.MALE to R.string.gender_male,
                            Gender.FEMALE to R.string.gender_female,
                            Gender.OTHER to R.string.gender_other,
                            Gender.UNSPECIFIED to R.string.gender_unspecified,
                        ).forEach { (gender, label) ->
                            FilterChip(
                                selected = state.newGender == gender,
                                onClick = { viewModel.onNewGenderChange(gender) },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.createAndLink(onBack) },
                        enabled = state.newName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag(AddRelativeSaveTag),
                    ) {
                        Icon(Icons.Default.PersonAddAlt, contentDescription = null)
                        Text(
                            text = stringResource(R.string.add_relative_save),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    UnknownPersonChoice(
                        onClick = { viewModel.createUnknownAndLink(onBack) },
                    )

                    SectionRule(stringResource(R.string.add_relative_existing))

                    if (state.candidates.isEmpty() && state.query.isBlank()) {
                        Text(
                            text = stringResource(R.string.add_relative_nobody_else),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            label = { Text(stringResource(R.string.people_search_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            items(state.candidates, key = { it.id }) { person ->
                PersonRow(
                    person = person,
                    onClick = { viewModel.linkExisting(person.id, onBack) },
                )
            }
        }
    }

    if (state.sameNameAsk.isNotEmpty()) {
        val first = state.sameNameAsk.first()
        AlertDialog(
            onDismissRequest = viewModel::dismissSameNameAsk,
            title = { Text(stringResource(R.string.add_relative_same_title, first.displayName())) },
            text = { Text(stringResource(R.string.add_relative_same_body)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissSameNameAsk(); viewModel.linkExisting(first.id, onBack) },
                    modifier = Modifier.testTag(AddRelativeUseExistingTag),
                ) { Text(stringResource(R.string.add_relative_same_use)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.createAndLink(onBack, confirmed = true) },
                    modifier = Modifier.testTag(AddRelativeAddAnywayTag),
                ) { Text(stringResource(R.string.add_relative_same_new)) }
            },
        )
    }

    state.rejection?.let { reason ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRejection,
            text = { Text(stringResource(rejectionMessage(reason))) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRejection) {
                    Text(stringResource(R.string.delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun UnknownPersonChoice(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(AddRelativeUnknownTag),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.HelpOutline, contentDescription = null)
            Column {
                Text(
                    text = stringResource(R.string.add_relative_unknown),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(R.string.add_relative_unknown_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LinkSuggestions.Suggestion.reasonText(): String {
    val via = via?.displayName()
    return when (reason) {
        LinkSuggestions.Reason.SHARES_CHILD -> stringResource(R.string.add_relative_reason_shares_child, via.orEmpty())
        LinkSuggestions.Reason.PARTNER_OF_PARENT -> stringResource(R.string.add_relative_reason_partner_of_parent, via.orEmpty())
        LinkSuggestions.Reason.CHILD_OF_PARTNER -> stringResource(R.string.add_relative_reason_child_of_partner, via.orEmpty())
        LinkSuggestions.Reason.SAME_NAME -> stringResource(R.string.add_relative_reason_same_name)
    }
}
