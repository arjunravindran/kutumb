package com.vibethroughcode.ftree.ui.person

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RelationshipRejectedException
import com.vibethroughcode.ftree.graph.RelationshipRejection
import com.vibethroughcode.ftree.ui.common.displayName
import com.vibethroughcode.ftree.ui.common.rejectionMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MergeConfirmTag = "merge-person-confirm"
const val MergeSearchTag = "merge-person-search"
const val MergePersonTagPrefix = "merge-person-"

/**
 * Folds two entries for one person into one, keeping [person]. With [other] given it only asks to be sure; with
 * none it first lets the reader find the other entry. The other's links and details move onto [person] and the
 * other entry is removed (see `FamilyRepository.mergePeople`), so it says so before anything happens.
 */
@Composable
fun MergePersonDialog(person: Person, other: Person?, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as FTreeApplication
    val family = app.container.familyRepository
    val scope = rememberCoroutineScope()

    var chosen by remember { mutableStateOf(other) }
    var everyone by remember { mutableStateOf<List<Person>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<RelationshipRejection?>(null) }

    LaunchedEffect(person.id) {
        val all = family.allPeople().filter { it.id != person.id }
        withContext(Dispatchers.Main) { everyone = all }
    }
    val shown = remember(everyone, search) {
        val q = search.trim()
        everyone.filter { q.isEmpty() || it.name?.contains(q, ignoreCase = true) == true }.take(8)
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.merge_person_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (other == null) {
                    Text(stringResource(R.string.merge_person_pick, person.displayName()), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it.take(100) },
                        label = { Text(stringResource(R.string.people_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(MergeSearchTag),
                    )
                    shown.forEach { candidate ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { chosen = candidate }
                                .testTag(MergePersonTagPrefix + candidate.id),
                        ) {
                            RadioButton(selected = chosen?.id == candidate.id, onClick = null)
                            Text(candidate.displayName(), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                chosen?.let {
                    Text(
                        stringResource(R.string.merge_person_body, it.displayName(), person.displayName()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                refused?.let {
                    Text(stringResource(rejectionMessage(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = chosen != null && !working,
                modifier = Modifier.testTag(MergeConfirmTag),
                onClick = {
                    val drop = chosen ?: return@Button
                    working = true
                    refused = null
                    scope.launch {
                        val result = family.mergePeople(keepId = person.id, dropId = drop.id)
                        withContext(Dispatchers.Main) {
                            if (result.isSuccess) onDismiss()
                            else {
                                working = false
                                refused = (result.exceptionOrNull() as? RelationshipRejectedException)?.reason
                                    ?: RelationshipRejection.CONTRADICTS_EXISTING
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.merge_person_confirm)) }
        },
        dismissButton = {
            TextButton(enabled = !working, onClick = onDismiss) { Text(stringResource(R.string.contacts_cancel)) }
        },
    )
}
