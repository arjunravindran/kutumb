package com.vibethroughcode.ftree.ui.network

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.graph.FamilySnapshot
import com.vibethroughcode.ftree.graph.Kinship
import com.vibethroughcode.ftree.graph.Relation
import com.vibethroughcode.ftree.kutumb.game.RedemptionStatus
import com.vibethroughcode.ftree.kutumb.game.RewardRedemption
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact

/**
 * One line of the family network: a trusted key, the person it belongs to, and how they are
 * related to the reader.
 *
 * [person] is null when the tree no longer has them (deleted after pairing); the row is still
 * shown, because the key is still trusted and hiding it would be a silent change to who may speak.
 */
data class ContactRow(
    val contact: TrustedContact,
    val person: Person?,
    val relation: Relation.Found?,
    /** Who vouched, for a [PairingMethod.VOUCHED] contact. */
    val voucher: Person?,
)

/** A reward that has been earned and not yet handed over, from the reader's side. */
data class OwedRow(
    val redemption: RewardRedemption,
    /** The other person; null when the tree no longer has them. */
    val other: Person?,
    /** True when the reader is the one who owes it. */
    val youOwe: Boolean,
)

object ContactsModel {
    /** What is still owed to or by the reader, what they owe first, then by the other person's name. */
    fun owed(meId: String, redemptions: List<RewardRedemption>, snapshot: FamilySnapshot): List<OwedRow> =
        redemptions.filter { it.status == RedemptionStatus.OWED && (it.fromPersonId == meId || it.toPersonId == meId) }
            .map { r ->
                val youOwe = r.fromPersonId == meId
                OwedRow(r, snapshot.people[if (youOwe) r.toPersonId else r.fromPersonId], youOwe)
            }
            .sortedWith(
                compareByDescending<OwedRow> { it.youOwe }
                    .thenBy { it.other?.name.isNullOrBlank() }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.other?.name.orEmpty() }
                    .thenBy { it.redemption.id }
            )

    /**
     * Everyone the reader trusts, those met in person first, then by name. A person without a name
     * sorts last, as they do in every other list in the app.
     */
    fun rows(meId: String, contacts: List<TrustedContact>, snapshot: FamilySnapshot): List<ContactRow> =
        contacts.filter { it.personId != meId }
            .map { contact ->
                ContactRow(
                    contact = contact,
                    person = snapshot.people[contact.personId],
                    relation = Kinship.relate(snapshot, meId, contact.personId) as? Relation.Found,
                    voucher = contact.vouchedByPersonId?.let(snapshot.people::get),
                )
            }
            .sortedWith(
                compareBy<ContactRow> { it.contact.pairingMethod != PairingMethod.DIRECT }
                    .thenBy { it.person?.name.isNullOrBlank() }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.person?.name.orEmpty() }
            )
}
