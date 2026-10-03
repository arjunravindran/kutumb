package com.vibethroughcode.ftree.ui.person

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.kutumb.geo.LocationLabel
import com.vibethroughcode.ftree.kutumb.geo.PersonLocation
import com.vibethroughcode.ftree.kutumb.geo.Place
import com.vibethroughcode.ftree.ui.common.SectionRule
import java.util.Locale

fun placeChangeTag(label: LocationLabel) = "place-change-${label.name}"
fun placeNameTag(label: LocationLabel) = "place-name-${label.name}"
fun placeClearTag(label: LocationLabel) = "place-clear-${label.name}"
fun placeResultTag(index: Int) = "place-result-$index"
const val PlaceSearchTag = "place-search"
const val PlacesSharingTag = "places-sharing"

/**
 * Where a person was born and where they live now. Both optional, like everything else here: a
 * line with nothing recorded says so and offers to add one. The places feed the distance on the
 * relationship screen and the "born close" fact, which show only when both people have them.
 */
@Composable
internal fun PlacesSection(
    places: PersonPlaces,
    nameOf: suspend (PersonLocation) -> String,
    search: suspend (String) -> List<Place>,
    onChoose: (LocationLabel, Place) -> Unit,
    onClear: (LocationLabel) -> Unit,
    onSharing: (Boolean) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf<LocationLabel?>(null) }

    SectionRule(label = stringResource(R.string.person_places_title), modifier = Modifier.padding(horizontal = 20.dp))
    PlaceLine(LocationLabel.BIRTHPLACE, R.string.person_place_born, places.born, nameOf, onPick = { picking = it }, onClear = onClear)
    PlaceLine(LocationLabel.CURRENT, R.string.person_place_lives, places.lives, nameOf, onPick = { picking = it }, onClear = onClear)
    if (places.any) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.person_places_share), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.person_places_share_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = places.sharing, onCheckedChange = onSharing, modifier = Modifier.testTag(PlacesSharingTag))
        }
    }

    picking?.let { label ->
        PlacePickerDialog(
            title = stringResource(if (label == LocationLabel.BIRTHPLACE) R.string.person_place_pick_born else R.string.person_place_pick_lives),
            search = search,
            onPick = { place -> onChoose(label, place); picking = null },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun PlaceLine(
    label: LocationLabel,
    caption: Int,
    location: PersonLocation?,
    nameOf: suspend (PersonLocation) -> String,
    onPick: (LocationLabel) -> Unit,
    onClear: (LocationLabel) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(stringResource(caption), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (location == null) {
                Text(
                    stringResource(R.string.person_place_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(placeNameTag(label)),
                )
            } else {
                val name by produceState("", location.lat, location.lon) { value = nameOf(location) }
                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(placeNameTag(label)))
            }
        }
        if (location != null) {
            TextButton(onClick = { onClear(label) }, modifier = Modifier.testTag(placeClearTag(label))) {
                Text(stringResource(R.string.person_place_clear))
            }
        }
        TextButton(onClick = { onPick(label) }, modifier = Modifier.testTag(placeChangeTag(label))) {
            Text(stringResource(if (location == null) R.string.person_place_add else R.string.person_place_change))
        }
    }
}

@Composable
private fun PlacePickerDialog(
    title: String,
    search: suspend (String) -> List<Place>,
    onPick: (Place) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by produceState(emptyList<Place>(), query) { value = if (query.isBlank()) emptyList() else search(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.place_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(PlaceSearchTag),
                )
                if (query.isNotBlank() && results.isEmpty()) {
                    Text(
                        stringResource(R.string.place_search_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                results.forEachIndexed { index, place ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onPick(place) }.padding(vertical = 8.dp).testTag(placeResultTag(index)),
                    ) {
                        Text(place.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            Locale("", place.country).getDisplayCountry(Locale.getDefault()).ifBlank { place.country },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.trees_cancel)) } },
    )
}
