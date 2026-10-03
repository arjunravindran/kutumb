package com.vibethroughcode.ftree.ui.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.DeletionMode
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RelationshipType
import com.vibethroughcode.ftree.data.RelativeKind
import com.vibethroughcode.ftree.data.SpouseKind
import com.vibethroughcode.ftree.graph.Circle
import com.vibethroughcode.ftree.graph.Connection
import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.PlaceSource
import com.vibethroughcode.ftree.kutumb.geo.LocationLabel
import com.vibethroughcode.ftree.kutumb.geo.LocationSource
import com.vibethroughcode.ftree.kutumb.geo.PersonLocation
import com.vibethroughcode.ftree.kutumb.geo.Place
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PersonDetailUiState(
    val person: Person? = null,
    val relationshipCount: Int = 0,
    val loaded: Boolean = false,
    val relatives: Map<RelativeKind, List<Person>> = emptyMap(),
    /** The state of each marriage, by the spouse's id (#291), so a heading can say "Former wife". */
    val spouseKinds: Map<String, SpouseKind> = emptyMap(),
) {
    fun of(kind: RelativeKind): List<Person> = relatives[kind].orEmpty()
}

/** The places recorded for one person. [sharing] is whether they feed distance and "born close". */
data class PersonPlaces(
    val born: PersonLocation? = null,
    val lives: PersonLocation? = null,
    val sharing: Boolean = true,
) {
    val any: Boolean get() = born != null || lives != null
    fun of(label: LocationLabel): PersonLocation? = if (label == LocationLabel.BIRTHPLACE) born else lives
}

class PersonDetailViewModel(
    private val repository: FamilyRepository,
    private val kutumb: KutumbRepository,
    private val placeSource: PlaceSource,
    val personId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val places: StateFlow<PersonPlaces> = kutumb.observeLocations().map { all ->
        val mine = all.filter { it.personId == personId }
        PersonPlaces(
            born = mine.firstOrNull { it.label == LocationLabel.BIRTHPLACE },
            lives = mine.firstOrNull { it.label == LocationLabel.CURRENT },
            sharing = mine.all { it.sharingEnabled },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonPlaces())

    suspend fun searchPlaces(query: String): List<Place> = placeSource.index().search(query)

    /** "Chennai, IN" for a saved position, or its coordinates when no listed town is near. */
    suspend fun placeName(location: PersonLocation): String =
        placeSource.index().nearest(location.lat, location.lon)?.let { "${it.name}, ${it.country}" }
            ?: "%.2f, %.2f".format(java.util.Locale.ROOT, location.lat, location.lon)

    fun choosePlace(label: LocationLabel, place: Place) {
        viewModelScope.launch {
            kutumb.saveLocation(
                PersonLocation(personId, label, place.lat, place.lon, LocationSource.GEOCODED, clock(), places.value.sharing),
            )
        }
    }

    fun clearPlace(label: LocationLabel) {
        viewModelScope.launch { kutumb.deleteLocation(personId, label) }
    }

    /** Applies to every place of this person: the switch is per person, never per place. */
    fun setPlacesSharing(on: Boolean) {
        viewModelScope.launch {
            kutumb.observeLocations().first().filter { it.personId == personId }.forEach { kutumb.saveLocation(it.copy(sharingEnabled = on)) }
        }
    }

    private val relatives = combine(
        repository.observeParents(personId),
        repository.observeSpouses(personId),
        repository.observeChildren(personId),
        repository.observeSiblings(personId),
    ) { parents, spouses, children, siblings ->
        mapOf(
            RelativeKind.PARENT to parents,
            RelativeKind.SPOUSE to spouses,
            RelativeKind.CHILD to children,
            RelativeKind.SIBLING to siblings,
        )
    }

    /** Their lines to others in a Circle, labelled. Empty in a family tree, which has no such edges. */
    val connections: StateFlow<List<Connection>> = combine(
        repository.observeAllPeople(), repository.observeEdgesOf(personId),
    ) { people, edges -> Circle.connectionsOf(personId, people, edges) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setConnectionLabel(edgeId: String, label: String) {
        viewModelScope.launch { repository.setConnectionLabel(edgeId, label) }
    }

    val uiState: StateFlow<PersonDetailUiState> = combine(
        repository.observePerson(personId),
        repository.observeEdgesOf(personId),
        relatives,
    ) { person, edges, related ->
        PersonDetailUiState(
            person = person,
            relationshipCount = edges.size,
            loaded = true,
            relatives = related,
            spouseKinds = edges
                .filter { it.type == RelationshipType.SPOUSE }
                .mapNotNull { edge ->
                    val other = edge.other(personId) ?: return@mapNotNull null
                    SpouseKind.fromName(edge.subtype)?.let { other to it }
                }
                .toMap(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PersonDetailUiState(),
    )

    /**
     * Removes one connection without touching either person. Both stay in the tree; only the
     * link between them goes.
     */
    fun removeRelationshipWith(otherPersonId: String) {
        viewModelScope.launch {
            repository.edgesBetween(personId, otherPersonId).forEach {
                repository.removeRelationship(it.id)
            }
        }
    }

    /**
     * Deletion is a two-option decision rather than a yes/no, because losing one name should not
     * have to tear a hole in the family. The caller is told when the work is done so it can leave
     * the screen only after the row is actually gone.
     */
    fun delete(mode: DeletionMode, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deletePerson(personId, mode)
            onDone()
        }
    }
}
