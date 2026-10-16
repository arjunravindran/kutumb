package com.vibethroughcode.ftree.ui.person

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.PhotoStore
import com.vibethroughcode.ftree.data.SquareCrop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A picked image on its way to becoming a portrait.
 *
 * Every photograph is framed before it is kept — there is no path that stores one uncropped — so
 * this is a step in picking a photo rather than an optional extra. The reason is that a face is
 * shown as a circle everywhere in this app, and letting the app choose the middle of somebody's
 * holiday snap is how you end up with a chart full of shoulders and hedges.
 */
sealed interface CropRequest {
    data object Loading : CropRequest
    data class Ready(val bitmap: Bitmap) : CropRequest
    data object Unreadable : CropRequest
}

data class PersonEditUiState(
    val name: String = "",
    val gender: Gender = Gender.UNSPECIFIED,
    val birthDate: String = "",
    val deathDate: String = "",
    val deceased: Boolean = false,
    val notes: String = "",
    val photoId: String? = null,
    val isNew: Boolean = true,
    val loaded: Boolean = false,
    val birthProblem: DateProblem? = null,
    val deathProblem: DateProblem? = null,
    val dirty: Boolean = false,
) {
    /**
     * Saving is blocked only by a date that cannot be understood. A completely blank form is
     * valid: it records a person whose details nobody knows yet.
     */
    val canSave: Boolean get() = birthProblem == null && deathProblem == null

    /**
     * Whether there is anything to make a person of. A new person is created the first time a field is completed,
     * and a form with nothing but a gender chip tapped is not yet anybody.
     */
    val hasContent: Boolean
        get() = name.isNotBlank() || birthDate.isNotBlank() || deathDate.isNotBlank() ||
            notes.isNotBlank() || photoId != null
}

class PersonEditViewModel(
    private val repository: FamilyRepository,
    private val photos: PhotoStore,
    private val personId: String?,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonEditUiState(isNew = personId == null))
    val uiState: StateFlow<PersonEditUiState> = _uiState.asStateFlow()

    private val _crop = MutableStateFlow<CropRequest?>(null)

    /** The photograph being framed, if one is. Null the rest of the time, which is most of it. */
    val crop: StateFlow<CropRequest?> = _crop.asStateFlow()

    /** The row being edited, kept so unedited fields (photo, timestamps, id) survive a save. */
    private var original: Person? = null

    /**
     * Photographs written while this form has been open.
     *
     * Framing three photographs and keeping the third writes three files; the two that lost are
     * cleaned up when the form is left, whichever way it is left. Deleting each one as the next
     * arrives would be simpler and wrong — backing out of the form must leave the person with the
     * picture they had.
     */
    private val written = mutableSetOf<String>()

    init {
        if (personId == null) {
            _uiState.update { it.copy(loaded = true) }
        } else {
            viewModelScope.launch {
                val person = repository.person(personId)
                original = person
                if (person != null) {
                    _uiState.update {
                        it.copy(
                            name = person.name.orEmpty(),
                            gender = person.gender,
                            birthDate = person.birthDate.orEmpty(),
                            deathDate = person.deathDate.orEmpty(),
                            deceased = person.deceased,
                            notes = person.notes.orEmpty(),
                            photoId = person.photoId,
                            isNew = false,
                            loaded = true,
                        )
                    }
                } else {
                    _uiState.update { it.copy(loaded = true) }
                }
            }
        }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, dirty = true) }

    /** Opens the picked image for framing. Nothing is written until the frame is confirmed. */
    fun onPhotoPicked(uri: Uri) {
        _crop.value = CropRequest.Loading
        viewModelScope.launch {
            val bitmap = photos.decodeForCrop(uri)
            _crop.value = if (bitmap == null) CropRequest.Unreadable else CropRequest.Ready(bitmap)
        }
    }

    fun onCropCancelled() {
        _crop.value = null
    }

    /** Writes the square the reader framed and hangs it on the form, not yet on the person. */
    fun onCropConfirmed(square: SquareCrop) {
        val ready = _crop.value as? CropRequest.Ready ?: return
        _crop.value = null
        viewModelScope.launch {
            val saved = photos.saveCrop(ready.bitmap, square) ?: return@launch
            written += saved
            _uiState.update { it.copy(photoId = saved, dirty = true) }
            autosave()
        }
    }

    fun onPhotoRemoved() {
        // The file is not deleted until the change is saved, so backing out leaves it intact.
        _uiState.update { it.copy(photoId = null, dirty = true) }
        autosave()
    }

    /** Leaving without saving: everything written while framing goes, the person keeps what they had. */
    fun onDiscarded() {
        val orphans = written.toList()
        written.clear()
        _crop.value = null
        viewModelScope.launch { orphans.forEach { photos.delete(it) } }
    }
    fun onGenderChange(value: Gender) {
        _uiState.update { it.copy(gender = value, dirty = true) }
        autosave()
    }
    fun onNotesChange(value: String) = _uiState.update { it.copy(notes = value, dirty = true) }

    fun onDeceasedChange(value: Boolean) {
        _uiState.update {
            // Clearing "no longer living" would leave a death date stranded, so it goes with it.
            val next = it.copy(deceased = value, dirty = true, deathDate = if (value) it.deathDate else "")
            next.copy(deathProblem = validate(next).second)
        }
        autosave()
    }

    fun onBirthDateChange(value: String) = _uiState.update {
        val next = it.copy(birthDate = value, dirty = true)
        val (birth, death) = validate(next)
        next.copy(birthProblem = birth, deathProblem = death)
    }

    fun onDeathDateChange(value: String) = _uiState.update {
        // Recording a death date says the person has died, so the switch follows the fact.
        val next = it.copy(deathDate = value, dirty = true, deceased = it.deceased || value.isNotBlank())
        val (birth, death) = validate(next)
        next.copy(birthProblem = birth, deathProblem = death)
    }

    private fun validate(state: PersonEditUiState): Pair<DateProblem?, DateProblem?> {
        // The rules live in DateEntry, shared with the desktop through one table (#90).
        val birthProblem = DateEntry.problem(state.birthDate)
        val deathProblem = DateEntry.problem(state.deathDate)
            ?: DateProblem.DEATH_BEFORE_BIRTH.takeIf {
                birthProblem == null && DateEntry.isDeathBeforeBirth(state.birthDate, state.deathDate)
            }
        return birthProblem to deathProblem
    }

    /**
     * A date as it is written to the record: settled, so a month left at "4" is kept as April rather
     * than refused (#90). A value nobody touched that this app cannot read — written by some other
     * program — is kept exactly as it was, not settled away to nothing.
     */
    private fun kept(text: String): String? =
        (if (DateEntry.problem(text) == null) DateEntry.settle(text) else text.trim()).ifBlank { null }

    private val writing = Mutex()

    /**
     * Keeps the person up to date as the form is changed: called when a field loses focus, when a chip or switch
     * is flipped, and on the way out. [onDone] is told whether nothing is left unsaved; it is false when a date
     * cannot be understood (never written; the form keeps saying so) or when a new form has nothing in it yet.
     * A new person is created by the first commit that has something to record, so a field the reader finished
     * is never lost, and a form opened and abandoned empty leaves nobody behind.
     */
    fun commit(onDone: (clean: Boolean) -> Unit = {}) {
        val state = _uiState.value
        if (!state.dirty) { onDone(true); return }
        if (!state.canSave || (state.isNew && original == null && !state.hasContent)) { onDone(false); return }
        viewModelScope.launch {
            writing.withLock {
                val seen = _uiState.value
                if (!seen.canSave) { onDone(false); return@withLock }
                persist(seen)
                // Only forget the change if nothing else was typed while it was being written.
                _uiState.update { if (it == seen) it.copy(dirty = false) else it }
            }
            onDone(!_uiState.value.dirty)
        }
    }

    private fun autosave() = commit()

    /** Writes whatever is on the form (a blank one records a person nobody knows yet) and reports who it is. */
    fun save(onSaved: (String) -> Unit) {
        if (!_uiState.value.canSave) return

        viewModelScope.launch {
            // Behind the same lock as commit(): a field written a moment ago must not make a second person.
            val person = writing.withLock {
                val seen = _uiState.value
                if (!seen.canSave) null else persist(seen).also { _uiState.update { now -> if (now == seen) now.copy(dirty = false) else now } }
            }
            if (person != null) onSaved(person.id)
        }
    }

    private suspend fun persist(state: PersonEditUiState): Person {
        run {
            val base = original ?: Person()
            val updated = base.copy(
                name = state.name.trim().ifBlank { null },
                gender = state.gender,
                birthDate = kept(state.birthDate),
                deathDate = kept(state.deathDate),
                deceased = state.deceased,
                notes = state.notes.trim().ifBlank { null },
                photoId = state.photoId,
                updatedAt = System.currentTimeMillis(),
            )
            if (original == null) repository.addPerson(updated) else repository.updatePerson(updated)
            val replaced = original?.photoId
            original = updated

            // Only now is a discarded photo actually removed from disk: the one the person had if
            // it has been replaced, and every frame tried on the way to the one being kept.
            if (replaced != null && replaced != state.photoId) photos.delete(replaced)
            written.filterNot { it == state.photoId }.forEach { photos.delete(it) }
            written.clear()

            return updated
        }
    }
}
