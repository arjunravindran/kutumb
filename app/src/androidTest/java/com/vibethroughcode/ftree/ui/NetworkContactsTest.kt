package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.game.RedemptionStatus
import com.vibethroughcode.ftree.kutumb.game.RewardRedemption
import com.vibethroughcode.ftree.kutumb.game.RewardType
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import com.vibethroughcode.ftree.ui.network.NetworkListTag
import com.vibethroughcode.ftree.ui.network.NetworkWhoTag
import com.vibethroughcode.ftree.ui.network.networkMessageTag
import com.vibethroughcode.ftree.ui.network.networkRowTag
import com.vibethroughcode.ftree.ui.network.owedRowTag
import com.vibethroughcode.ftree.ui.network.owedSettleTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The family network list: choosing who you are, then who is trusted and how they came to be. */
@RunWith(AndroidJUnit4::class)
class NetworkContactsTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FTreeApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication

    private val repo get() = app.container.familyRepository
    private val kutumb get() = app.container.kutumbRepository

    private lateinit var me: Person
    private lateinit var devika: Person
    private lateinit var asha: Person

    @Before
    fun seed() {
        app.container.database.clearAllTables()
        runBlocking {
            kutumb.clearAll()
            me = repo.addPerson(Person(name = "Yash Rao"))
            devika = repo.addPerson(Person(name = "Devika Nair"))
            asha = repo.addPerson(Person(name = "Asha Menon"))
        }
        rule.onNodeWithTag(NavNetworkTag).performClick()
    }

    private fun contact(person: Person, method: PairingMethod, voucher: Person? = null) = TrustedContact(
        personId = person.id,
        pubKeyCurrent = Bip340Scheme.generateKeyPair().publicKey,
        pairedAt = 1,
        pairingMethod = method,
        vouchedByPersonId = voucher?.id,
    )

    @Test
    fun theReaderSaysWhoTheyAreOnceAndTheKeyIsMintedOnThePhone() {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(NetworkWhoTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Yash Rao").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTag(NetworkWhoTag).fetchSemanticsNodes().isEmpty() }
        val identity = runBlocking { kutumb.observeIdentity().first { it != null } }
        assertEquals(me.id, identity!!.personId)
        assertNotNull(identity.keyPair.publicKey)
        rule.onNodeWithText("No one paired yet").assertIsDisplayed()
    }

    @Test
    fun metContactsAreMarkedAndVouchedOnesAreLockedOutOfMessaging() {
        runBlocking {
            kutumb.setIdentity(LocalIdentity(me.id, Bip340Scheme.generateKeyPair()))
            kutumb.saveContact(contact(devika, PairingMethod.DIRECT))
            kutumb.saveContact(contact(asha, PairingMethod.VOUCHED, voucher = devika))
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(NetworkListTag).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(networkRowTag(devika.id)).assertIsDisplayed()
        rule.onNodeWithText("Paired in person").assertIsDisplayed()
        rule.onNodeWithText("Vouched by Devika Nair").assertIsDisplayed()

        // Someone met in person can be messaged; the vouched one gets a lock instead.
        rule.onNodeWithTag(networkMessageTag(devika.id)).assertIsEnabled()
        // A plain icon merges into its row for a screen reader; look for it unmerged.
        rule.onNodeWithTag(networkMessageTag(asha.id), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aRewardOwedShowsWhoOwesWhatAndCanBeMarkedSettled() {
        runBlocking {
            kutumb.setIdentity(LocalIdentity(me.id, Bip340Scheme.generateKeyPair()))
            kutumb.saveContact(contact(devika, PairingMethod.DIRECT))
            kutumb.saveRedemption(RewardRedemption("r1", me.id, devika.id, RewardType.COFFEE, 5))
            kutumb.saveRedemption(RewardRedemption("r2", asha.id, me.id, RewardType.BEER, 5))
            // Settled already, or between other people: neither is the reader's business.
            kutumb.saveRedemption(RewardRedemption("r3", me.id, asha.id, RewardType.CUSTOM, 5, RedemptionStatus.REDEEMED, redeemedAt = 3))
            kutumb.saveRedemption(RewardRedemption("r4", devika.id, asha.id, RewardType.CUSTOM, 5))
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(owedRowTag("r1")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("You owe Devika Nair a coffee").assertIsDisplayed()
        rule.onNodeWithText("Asha Menon owes you a beer").assertIsDisplayed()
        rule.onNodeWithTag(owedRowTag("r3")).assertDoesNotExist()
        rule.onNodeWithTag(owedRowTag("r4")).assertDoesNotExist()

        rule.onNodeWithTag(owedSettleTag("r1")).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(owedRowTag("r1")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag(owedRowTag("r2")).assertIsDisplayed()
        val stored = runBlocking { kutumb.observeRedemptions().first().first { it.id == "r1" } }
        assertEquals(RedemptionStatus.REDEEMED, stored.status)
    }
}
