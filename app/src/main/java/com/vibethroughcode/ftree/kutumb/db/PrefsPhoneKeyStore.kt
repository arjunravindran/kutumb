package com.vibethroughcode.ftree.kutumb.db

import android.content.Context
import com.vibethroughcode.ftree.kutumb.PhoneKeyStore
import com.vibethroughcode.ftree.kutumb.trust.KeyPair

/**
 * The phone's key in a preferences file of its own, sealed by the Keystore like the per-tree copy
 * ([KeyVault]). Auto Backup may carry the file to another phone, where [read] finds nothing it can
 * open and the phone mints a fresh key, as it should.
 */
class PrefsPhoneKeyStore(context: Context, private val vault: KeyVault) : PhoneKeyStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): KeyPair? {
        val publicKey = prefs.getString(PUBLIC, null) ?: return null
        val secret = prefs.getString(SEALED, null)?.let(vault::open) ?: return null
        return KeyPair(publicKey, secret)
    }

    override fun write(key: KeyPair) {
        // commit(), not apply(): a key that was handed out but not yet on disk would be lost with the process.
        prefs.edit().putString(PUBLIC, key.publicKey).putString(SEALED, vault.seal(key.privateKey)).commit()
    }

    private companion object {
        const val FILE = "kutumb-phone-key"
        const val PUBLIC = "public"
        const val SEALED = "sealed"
    }
}
