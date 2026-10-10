package com.vibethroughcode.ftree.ui.relative

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RelativeKind
import com.vibethroughcode.ftree.graph.LinkSuggestions
import com.vibethroughcode.ftree.graph.RelationshipRejection
import com.vibethroughcode.ftree.transfer.nameKey
import com.vibethroughcode.ftree.data.RelationshipRejectedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AddRelativeUiState(
    val anchor: Person? = null,
    val candidates: List<Person> = emptyList(),
    val query: String = "",
    val newName: String = "",
    val newGender: Gender = Gender.UNSPECIFIED,
    val rejection: RelationshipRejection? = null,
    /** Who the person being added may already be, from the shape of the tree and the name typed. */
    val suggestions: List<LinkSuggestions.Suggestion> = emptyList(),
    /** People already in the tree with the name just typed, asked about before a second one is made. */
    val sameNameAsk: List<Person> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class AddRelativeViewModel(
    private val repository: FamilyRepository,
    private val anchorId: String,
    val kind: RelativeKind,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val form = MutableStateFlow(FormState())
    private val rejection = MutableStateFlow<RelationshipRejection?>(null)
    private val sameNameAsk = MutableStateFlow<List<Person>>(emptyList())

    private data class FormState(val name: String = "", val gender: Gender = Gender.UNSPECIFIED, val birthDate: String? = null)

    /**
     * People this relationship could never legally reach.
     *
     * Offering someone the app would then refuse is a dead end, so the anchor and anyone who would
     * close an ancestor loop — their descendants when adding a parent, their ancestors when adding
     * a child — are left out of the list rather than rejected after the tap. Duplicates are still
     * shown, because "he is already your father" is clearer said out loud than by a missing row.
     */
    private val ineligible = flow {
        emit(
            buildSet {
                add(anchorId)
                when (kind) {
                    RelativeKind.PARENT -> addAll(repository.descendantIdsOf(anchorId))
                    RelativeKind.CHILD -> addAll(repository.ancestorIdsOf(anchorId))
                    else -> Unit
                }
            }
        )
    }

    private val candidates = combine(
        query
            .debounce { if (it.isBlank()) 0L else 180L }
            .flatMapLatest { text ->
                if (text.isBlank()) repository.observeAllPeople() else repository.searchPeople(text)
            },
        ineligible,
    ) { people, excluded -> people.filterNot { it.id in excluded } }

    val uiState: StateFlow<AddRelativeUiState> = combine(
        repository.observePerson(anchorId),
        candidates,
        query,
        form,
        rejection,
    ) { anchor, people, text, formState, rejected ->
        AddRelativeUiState(
            anchor = anchor,
            candidates = people,
            query = text,
            newName = formState.name,
            newGender = formState.gender,
            rejection = rejected,
        )
    }.combine(
        combine(repository.observeWholeGraph(), form, ineligible) { tree, formState, excluded ->
            LinkSuggestions.forNewRelative(kind, anchorId, formState.name, tree, excluded)
        },
    ) { ui, suggestions -> ui.copy(suggestions = suggestions) }
        .combine(sameNameAsk) { ui, ask -> ui.copy(sameNameAsk = ask) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AddRelativeUiState(),
        )

    val currentRejection: StateFlow<RelationshipRejection?> = rejection.asStateFlow()

    fun onQueryChange(value: String) { query.value = value }
    fun onNewNameChange(value: String) = form.update { it.copy(name = value) }
    fun onNewGenderChange(value: Gender) = form.update { it.copy(gender = value) }

    /** A contact was chosen to fill the form in: their name, and their birthday if the phone has one. */
    fun onContactChosen(name: String, birthDate: String?) = form.update { it.copy(name = name, birthDate = birthDate) }
    fun dismissRejection() { rejection.value = null }

    private inline fun MutableStateFlow<FormState>.update(block: (FormState) -> FormState) {
        value = block(value)
    }

    /** Links someone already in the tree. */
    fun linkExisting(personId: String, onDone: () -> Unit) = attempt(onDone) {
        repository.addRelative(anchorId, personId, kind).map { }
    }

    /**
     * Creates a named person and links them, unless somebody of that name is already in the tree, in which case
     * the reader is asked first ([AddRelativeUiState.sameNameAsk]): a second Asha is nearly always the first one
     * entered again from another page.
     */
    fun createAndLink(onDone: () -> Unit, confirmed: Boolean = false) {
        val state = form.value
        viewModelScope.launch {
            val key = nameKey(state.name)
            if (!confirmed && key != null) {
                val excluded = ineligible.first()
                val same = repository.allPeople().filter { nameKey(it.name) == key && it.id !in excluded }
                if (same.isNotEmpty()) {
                    sameNameAsk.value = same
                    return@launch
                }
            }
            sameNameAsk.value = emptyList()
            attempt(onDone) {
                repository.addNewRelative(
                    anchorId = anchorId,
                    person = Person(name = state.name.trim().ifBlank { null }, gender = state.gender, birthDate = state.birthDate),
                    kind = kind,
                ).map { }
            }
        }
    }

    fun dismissSameNameAsk() { sameNameAsk.value = emptyList() }

    /**
     * Records the connection to someone whose name is not known.
     *
     * The placeholder is a real person in the graph, not a note on someone else, which is what
     * lets them be named years later without rebuilding the relationship.
     */
    fun createUnknownAndLink(onDone: () -> Unit) = attempt(onDone) {
        repository.addNewRelative(anchorId, Person(), kind).map { }
    }

    private fun attempt(onDone: () -> Unit, block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            block()
                .onSuccess { onDone() }
                .onFailure { failure ->
                    rejection.value = (failure as? RelationshipRejectedException)?.reason
                        ?: RelationshipRejection.DUPLICATE
                }
        }
    }
}
