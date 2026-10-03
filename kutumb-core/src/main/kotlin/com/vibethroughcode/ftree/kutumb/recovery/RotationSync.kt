package com.vibethroughcode.ftree.kutumb.recovery

import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import com.vibethroughcode.ftree.kutumb.trust.IdentityRotationEvent

/**
 * A signed identity rotation as a sync message, and back.
 *
 * Decoding checks shape only (the event's own constructor does, and bounds the ids); whether the
 * signature holds and whether this device trusts the voucher is [RotationRules]' job, applied by
 * whoever receives it. Anything malformed decodes to null and is dropped.
 */
object RotationSync {
    const val MAX_ID = 200

    fun envelope(event: IdentityRotationEvent) = SyncEnvelope(
        SyncType.ROTATION,
        linkedMapOf(
            "person" to event.personId,
            "old" to event.oldPubKey,
            "new" to event.newPubKey,
            "by" to event.vouchedByPubKey,
            "sig" to event.signature,
            "at" to event.timestamp,
        ),
    )

    fun decode(payload: Map<String, Any?>): IdentityRotationEvent? = runCatching {
        val person = (payload["person"] as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return null
        IdentityRotationEvent(
            personId = person,
            oldPubKey = payload["old"] as? String ?: return null,
            newPubKey = payload["new"] as? String ?: return null,
            vouchedByPubKey = payload["by"] as? String ?: return null,
            signature = (payload["sig"] as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return null,
            timestamp = payload["at"] as? Long ?: return null,
        )
    }.getOrNull()
}
