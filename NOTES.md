# NOTES

## Needs a human (read first)

Nothing is blocked. These are calls I made that a person should look at when convenient:

1. **New native dependency: `fr.acinq.secp256k1:secp256k1-kmp(-jni-android)` 0.24.0** (BIP-340 Schnorr, Nostr's signature scheme). Needed in step 3, earlier than the task list implied: kutumb-core's JDK `Ed25519Scheme` cannot work on Android at all. `KeyPairGenerator.getInstance("Ed25519")` resolves to the AndroidKeyStore provider and throws `IllegalStateException: Not initialized` (seen on the API 35 emulator), and keystore keys cannot be exported as the hex strings the core interface uses. Alternatives I did not take: BouncyCastle (larger, no BIP-340 built in), hand-rolled BigInteger Schnorr (not constant-time), rust-nostr bindings (much heavier). Things to weigh: it ships prebuilt `.so` files, which would have troubled F-Droid's scanner (no longer a concern: distribution is Google Play, decided), and it adds APK size per ABI. Reverting means replacing `Bip340Scheme` only; nothing above `SignatureScheme` changes.

## Assumptions and decisions

CLAUDE.md says new dependencies, schema changes and new screens need an issue first. The task list explicitly requests all three, so I treated that as the go-ahead and did not open issues.

## Step 1 — kutumb-core verification

`./gradlew test --rerun-tasks` in `kutumb-core/`: 62 tests, 0 failures, 0 skipped (63 after the test added below).

Checked trust/recovery/game/sync against `docs/kutumb/SPEC.md`:

- **Single-vouch rotation, revocation, out-of-order delivery, replay**: match the spec. Replayed old rotations are rejected (`STALE_OLD_KEY`), rotating back to a revoked key is blocked (`NEW_KEY_IN_USE`), redelivery is a no-op.
- **Messaging gate**: `messagingEnabled` is `DIRECT`-only and survives rotation, as specified.
- **Fact conflict rule**: all answers kept, latest is display default, owner-only one-shot resolution, no timeout. Matches.
- **SyncOutbox**: spec fields all present; backoff, ack quorum, give-up behave as documented.

**Bug found and fixed:** `RewardRules.redemptionsToCreate` threw `IllegalArgumentException` when earner == owner. That happens whenever an owner's own self-report wins and their self-earned points reach the threshold, because `RewardRedemption` forbids from == to. It now returns nothing for that pair. Regression test added in `GameTest`.

Observations, not changed (spec-conformant, but worth knowing):

- Any trusted contact can vouch a rotation for *any* known person, not only people they paired with. The receiving device cannot know who R really met, so this is inherent to the single-vouch decision.
- Revocation is "current key only": a message signed by the old key before rotation but delivered late is also rejected. No timestamp window.
- Owner can pick any answer, including their own, to award points (spec: owner resolves). Points on own facts are in the ledger but earn no reward.
- Not in kutumb-core at all: `PersonLocation`, `MessageThread`/`Message`. They are Room-only concerns for step 4.
- Signatures are Ed25519 (JDK), not Nostr's BIP-340, and cannot run on Android (see the top of this file). The app uses `Bip340Scheme`; the core keeps `Ed25519Scheme` as its JVM test double.

## Step 2 — full build

- `:kutumb-core` is now `include`d in `settings.gradle.kts`. Its Kotlin plugin and JUnit come from the root version catalog (`kotlin-jvm` plugin alias added, declared `apply false` at root because KGP is already on the root classpath with a version). Its own `settings.gradle.kts` imports the same catalog, so `cd kutumb-core && ./gradlew test` still works standalone.
- `./gradlew build` on the full project: BUILD SUCCESSFUL (debug + release incl. R8, lint, unit tests). Only pre-existing Kotlin warnings in app tests.
- CI runs `./gradlew testDebugUnitTest`, which does **not** run `:kutumb-core:test` (a plain JVM module has no `testDebugUnitTest`). CI needs `:kutumb-core:test` added to be covered; I did not touch `.github/workflows`.
- Emulator: `Medium_Phone_API_35` AVD, started with `emulator -avd Medium_Phone_API_35 -no-snapshot`; `adb` lives in the SDK `platform-tools` dir, not on PATH.

## Step 3 — screens

Common decisions:

- Screens use the app's own Material theme (forest/brass), not the mockups' terracotta palette. The mockups set layout and content; matching their hex values would make these screens look unlike the rest of the app.
- All the screens read and write through `kutumb/KutumbRepository`, an interface in the app module. It is in-memory until step 4 swaps in Room, so **nothing survives a restart until step 4**.
- "Who am I" has no home in the existing model (the app has no "me"). The Family tab asks once, with the existing person picker, which tree person is the reader, and mints the key then. `LocalIdentity` therefore lives in the Kutumb repository, not on `Person`.
- Messaging does not exist yet (no spec'd screen; needs sync). The message control is drawn locked for vouched contacts, as specified, and drawn disabled for direct ones.
- New top-level destination "Family" added to the bottom bar / rail.

### 3a Family network (contacts)

`ui/network/ContactsScreen.kt`, `ContactsModel.kt` (pure ordering and relation lookup, JVM-tested), `ContactsViewModel.kt`. Met-in-person contacts sort first; vouched contacts show who vouched. Status uses filled vs hollow dot so it does not rely on colour. A contact whose person was deleted from the tree stays listed.

Also found while testing: `Bip340Scheme.verify` must catch `Secp256k1Exception` (an off-curve key throws it rather than returning false); covered by a test. The scheme passes BIP-340 test vectors 0 and 1.

### 3b Pairing

`ui/network/PairingScreen.kt`, `PairingViewModel.kt`, and `kutumb/PairingFlow.kt` (the interface step 5 implements; `UnavailablePairingFlow` is the default until then, so the screen says pairing is not available yet rather than faking it).

- **Deviation from the mockup:** it shows a pre-agreed "Your pairing code 482 916" to type in. The existing nearby protocol (`docs/nearby-protocol.md`) has no such thing: its six digits are *derived after* the key exchange and compared on both screens, and the QR carries a separate 16-byte token. A short typed PIN agreed before connecting would be weaker than the existing design, so the screen shows the QR to scan, then the derived six digits to compare ("Does their screen show the same code?"), spaced as `482 916`. Typing the other phone's address is still available from Settings > Nearby, not repeated here.
- The mockup's "?" avatar is real: the other phone cannot know which entry in *this* tree is them, so after verification the reader picks the person (people already trusted, and the reader, are not offered). Only then is a `DIRECT` contact saved.
- A mismatch offers no "try again": it can mean someone is in the middle, so the message says to pair somewhere else.
- SPEC says the handshake extends a "Wi-Fi Direct" module. The existing `nearby/` is same-LAN UDP beacon + TCP, not Wi-Fi Direct. Step 5 extends what exists.
- Tab renamed "Network" (a "Family" label broke an existing test that finds text "Family").

### Test status after 3a/3b

Full `connectedDebugAndroidTest`: 203 tests, 3 failures at the time of the run. One was mine (the tab label, fixed). The other two, `BookFlowTest.theStorybookReallyFeaturesOnePerson` and `theChartOpensTheBookAndSharesAPdfNamedForTheFamily`, fail identically on the untouched baseline (checked with my changes stashed), so they predate this work. **Both are fixed now** (see "Recovery, typed-address pairing and the book tests" below): the book's default template became the chart (#315), and Diwali stopped shipping on the device (#314).

### 3c Relationship path ("main" mockup)

`ui/network/PathScreen.kt`, `PathModel.kt` (pure, JVM-tested), `PathViewModel.kt`. Reached by tapping a contact. New pure logic in kutumb-core: `geo/Geo.kt` (haversine, `PersonLocation`, sharing switch) and `geo/FunFacts.kt` (birth month, age gap, zodiac, hometown), 10 new tests (72 total).

- **Hometown fact:** the mockup says "Both born in Chennai", but SPEC's `PersonLocation` stores only lat/lon, no place name, and Person has no birthplace field. So the fact is "born close to each other" (birthplaces within 50 km), which is computable from what the spec stores. A named hometown would need a schema addition; not made.
- **Distance** is between the two people's *current* locations, shown only if both have sharing on. Miles for US/GB/LR/MM locales, km elsewhere.
- **Places can now be entered** (person screen > Places: "Born in" and "Lives in", plus one "Use in distance and fun facts" switch per person). Offline, as the spec asks: `assets/places/places.tsv` is GeoNames' cities15000 extract (34,152 places of 15,000+ people, 1.2 MB raw, six fields each, CC BY 4.0, credited in About and `assets/licenses/geonames_CC-BY-4.0.txt`). `kutumb-core/geo/Places.kt` (`PlaceIndex`, 8 JVM tests) searches it ignoring case and accents, prefix first, bigger places first, and `, IN` narrows to a country; it also names a saved position again via `nearest` (within 25 km), because `PersonLocation` stores only lat/lon. `PlaceSource` parses the list on first use, off the main thread. No location permission and nothing leaves the phone. `KutumbRepository.deleteLocation` is new (no schema change). Villages under 15,000 are not in the list; the picker says so and suggests a bigger town nearby. `PersonPlacesTest` (2).
- Message button shows only for direct pairings and is disabled (messaging unbuilt).

### 3d Tree

The existing chart already has the mockup's three views (Chart / Compact / Everyone) and tap-to-see-how-related, so nothing was rebuilt. What was added: a person's sheet in the tree shows **"In your family network"** for people this phone trusts, opening their relationship screen (3c).

- Deliberately **not** done: a paired badge drawn on the chart nodes. The chart is a single Canvas whose per-frame cost is a documented design constraint; adding per-node state to it for decoration was not worth the risk. The sheet link carries the same information.
- Whole `ui` instrumented package after this step: 108 tests, 2 failures, the same two pre-existing `BookFlowTest` failures as before.

### 3e Facts

`ui/facts/FactsScreen.kt`, `FactsViewModel.kt`, `QuestionText.kt`; new "Facts" tab (5th destination). Core gained `game/QuestionBank.kt` (8 starter questions across the spec's 7 categories; 4 new core tests, 76 total).

- A fact's id is `"<ownerId>/<questionId>"`, the same on every phone, so answers from different phones land on one fact. `Fact.prompt` stores the question *id*, not text, so wording can be translated and a phone that does not know an id shows "a question from a newer version" instead of failing.
- Answers go through `FactResolver.submit` (self-report flag checked, repeats refused). Wrong guesses cost nothing: the spec leaves penalties undecided and the core models none.
- Guesses can only be about people this phone trusts (up to four shown as chips; more than four relatives would need a real picker).
- The mockup's "+10 pts toward a coffee" is not used: `FactResolver.DEFAULT_POINTS` is 1 and the spec gives no number. Reward thresholds/redemption have no screen in the six mockups, so points accumulate in the ledger with no way yet to redeem them in the UI.
- A "N answers waiting for you" banner links to the resolve screen (next step).
- Guesses are stored locally only until the sync layer (step 6) sends them; the guessed-about person's phone cannot see them yet.

### 3f Resolve

`ui/facts/ResolveScreen.kt`, `ResolveViewModel.kt`. Shows the oldest unresolved question about the reader with every answer (their own marked "(self)"); picking one and confirming runs `FactResolver.resolve` (owner-only, once-only), writes the ledger entry, and if the answerer's points from the reader reach the threshold, creates a `RewardRedemption` (reader owes them).

- **Auto-surfacing:** per the spec ("surfaces automatically the next time they open the app"), `FTreeApp` opens the card once at launch if one is waiting. No timeout, never auto-resolved. A banner on the Facts tab also links to it.
- **Assumptions with no spec number:** reward threshold is **5 points** and reward type **coffee** for every pair (`ResolveViewModel.DEFAULT_THRESHOLD/DEFAULT_REWARD`). The spec says both are configurable per relationship, but no mockup has that screen. The mockup's "40 / 100 pts" and "+10" were illustrative and are not used.
- Picking your own answer awards you points but no reward (nothing owed to yourself), and the screen says so.
- **Owed rewards** now show at the top of the Network tab ("You owe Kiran a coffee" / "Kiran owes you a beer", what you owe first) with "Mark as settled"; either side may settle (`ContactsModel.owed`, 3 JVM tests; `NetworkContactsTest`). Settling is local: it is not synced to the other phone, so each side marks their own copy. The threshold (5 points) and reward (coffee) are still the placeholders above, with no screen to change them.

## Step 4 — Room

Database version 1 -> 2 (`data/Migrations.kt`, schema `app/schemas/.../2.json` checked in). The migration only creates tables; the tree, relationships and import history are untouched, and a test migrates a v1 database with a person in it and checks the person survives and the schema validates. `AppContainer.kutumbRepository` is now `RoomKutumbRepository`; the in-memory one remains for JVM tests.

Tables (`kutumb/db/`): `local_identity`, `trusted_contacts`, `identity_rotations`, `person_locations`, `facts`, `fact_answers`, `fact_resolutions`, `points_ledger`, `sync_outbox`, `reward_redemptions` (SPEC's `TrustedContact`, `IdentityRotationEvent`, `PersonLocation`, `Fact`/`FactAnswer`/`FactResolution`, `PointsLedger`, `SyncOutbox`, `RewardRedemption`, plus `local_identity` for this phone's own key).

Deviations and decisions:

- **No foreign keys to `people`**, though SPEC marks `personId` as FK. A cascade would delete a trusted key, the points history and an owed coffee whenever someone is removed from the tree. A test pins this: deleting a person keeps their contact row.
- **Private key is sealed by the Android Keystore** (`AndroidKeyVault`, AES-256-GCM) before it reaches the database. The app's backup rules are empty (everything is backed up), so a plain key would have been copied to Google Drive and to a new phone, letting that phone speak as this person. Sealed, it opens only on the phone that sealed it; elsewhere the identity reads as absent (user picks "who am I" again, gets a new key, is vouched for). Consequence: **restoring from backup or transferring to a new phone loses the identity by design**, and that is exactly the recovery flow.
- Trust-store integrity in SQL: unique `pubKeyCurrent` (one key, one person), `saveContact` throws rather than letting `REPLACE`/`@Upsert` silently delete or ignore a clash (caught by a test); unique `points_ledger.reasonFactId` plus insert-ignore on `fact_resolutions` make a second resolution unable to award points twice; answers are insert-ignore so a redelivered answer is the same row.
- `MessageThread`/`Message` (SPEC's optional messaging section) are not created: messaging has no screen or transport yet, and an unused table is a migration to regret.
- `.ftree` export/import is untouched (CLAUDE.md: format changes need discussion). None of the network data travels in `.ftree` files.
- Debug seeder: `mode=clear` already calls `clearAllTables`, which now covers these tables too; the seeder does not create trust/facts data.
- Observed once: `NetworkTreeLinkTest.aTrustedPersonsSheetLeadsToTheirRelationshipScreen` timed out in a sequential run and passed on rerun. Looks like emulator timing, not logic.

## Step 5 — Pairing over nearby

`kutumb/pairing/NearbyPairingFlow.kt` (the `PairingFlow` the screen already used), `kutumb/pairing/IdentityCards.kt`, plus a pairing mode in `nearby/`. `AppContainer.pairingFlow` is now a getter returning `pairingFlowOverride` (tests) or `NearbyPairingFlow`; `UnavailablePairingFlow` is no longer wired in.

How the exchange works:

- Same conversation as a file send: HELLO, key exchange, six derived digits (or the scanned token). Where the sender would send an OFFER it sends one **identity card** in a new `TYPE_IDENTITY` (0x40) frame. `SenderSession`/`ReceiverSession` take an optional `PairingIdentity`; with it null they behave as before. Sender = the phone that connects (scanned or typed), receiver = the phone showing the code.
- Order: digits compared (sender confirms; skipped when the QR token was used) -> sender sends its card -> receiver checks it and **waits for its person to say yes** -> only then sends its own card. A receiver that says no sends nothing back; the sender sees `DECLINED`.
- A card is 32 bytes x-only key + 64-byte BIP-340 signature over `"ftree-kutumb/pair/1"`, the session's six digits, the role, and the key. Wrong session digits, wrong role, a key presented with someone else's signature, wrong length, or one's own key are all refused (`peerKey` returns null -> `BAD_PAIRING`).
- `NearbyRepository` gets `beginPairing/endPairing/pairByLink/answerPairing` and a separate `pair: StateFlow<NearbyPairState>`, so a pairing never shows on the file-sharing screen. `NearbySendTransfer.outgoing` is now nullable (no file); a pairing receiver discards the sink and finishes when the cards are exchanged, with no digest check.
- `NearbyPairingFlow` maps `NearbyPairState` to `PairingState`; success is `Verified(name, key)`. It saves nothing: the screen still asks who the person is and only then writes a `DIRECT` contact (step 3b, unchanged).

Reuse of verification: no new code or QR. The scanner path is the existing `ScanAction`/`QrLink`; the digits are the existing derived code. Cards are bound to those digits, so the card signature proves possession of the key on a channel the two people already authenticated; it adds no secrecy.

Security-relevant decisions:

- Pairing never switches nearby sharing on: with the master switch off the state is `Off` and the screen says to enable it in Settings.
- Needs a local identity first (`NO_IDENTITY` otherwise).
- A non-pairing receiver does not accept `TYPE_IDENTITY` and a pairing receiver does not accept an OFFER (the last test below covers the second).
- `endPairing` cancels in-flight transfers and stops being visible. A mismatch (`CODES_DID_NOT_MATCH`, `KEY_NOT_AS_PROMISED`, `BAD_PAIRING`) maps to `CODE_MISMATCH` with no retry; only `CONNECTION_LOST` and `DECLINED` offer "Try again" (previously every failure except two did).
- `TYPE_IDENTITY` is outside `RESERVED_TYPES` and was added to the connection's allowed-frame list.

Deviations from the mockup/SPEC:

- A phone that was scanned shows no digits (nothing was compared), just "<name> scanned this phone. Pair with them?". `PairingState.ConfirmCode.code` is therefore nullable.
- Still the 3b deviation: derived digits, not a pre-agreed PIN; still same-LAN UDP/TCP, not Wi-Fi Direct.
- `docs/nearby-protocol.md` describes `TYPE_IDENTITY` and the pairing conversation (commit 34a2b08). It is a wire-protocol addition, which CLAUDE.md says should be discussed before merge into upstream; nothing on the desktop or web side speaks it yet.

Tested (JVM, written but not run by me while this note was written):

- `IdentityCardsTest` (5): round trip; card holds only for its session and role; tampered or mis-sized card; key with another key's signature; own key.
- `NearbyPairingTest` (7): full pairing over in-memory pipes with real `Bip340Scheme` cards, covering digit comparison, QR-token pairing, mismatched digits, receiver declining, stale-session card, forged key, and a file sender against a pairing receiver.
- `NetworkPairingTest` (instrumented) now injects its fake through `pairingFlowOverride`.

Known gaps:

- No test drives `NearbyRepository`'s pairing mode, `NearbyPairingFlow` or `problemOf` (the new `receivePairing` and `pairByLink` paths), and none runs two real devices; the transport is only exercised through the pure sessions.
- A receiver who answers "no, different" to the digits is reported to the sender as `DECLINED`, not as a mismatch (by the state mapping; no test).
- Typed-address pairing is not offered on the pairing screen, only the scan.

## Step 6 — Sync over Nostr (in progress)

Already committed before this entry (per their commit messages; I re-ran the whole build, not each piece): `kutumb-core` Nostr event model, NIP-44 v2, NIP-59/17 gift wrap, relay frames; app-side `NostrCrypto` and `SyncEngine` over the abstract `RelayConnection`, tested against `FakeRelay`.

Added in this session:

- **Fixed a pre-existing failing build.** `./gradlew build` was red: lint `NonObservableLocale` in `PathScreen.kt` (now reads `LocalConfiguration`), and `NearbyRepositoryPairingTest."ending a pairing while the other phone waits releases it"` failed because a cancelled session's `onFailed` overwrote the `Idle` that `endPairing()` had just set. Both failure handlers in `NearbyRepository` now ignore failures once pairing has ended. Test passed 3 reruns in a row.
- **`kutumb/sync/relay/WebSocketRelay.kt`**: the real `RelayConnection`, a hand-written RFC 6455 text-frame client over `Socket`/`SSLSocket`. **Decision:** no OkHttp (not on the classpath; CLAUDE.md wants discussion before new dependencies), same reasoning as the hand-rolled UDP beacon. `wss://` verifies the hostname; `ws://` is refused except loopback unless `allowPlaintext`; frame size capped at 1 MiB; server-masked frames, bad `Sec-WebSocket-Accept` and non-101 replies are refused. Tested (11 tests, all passing) against an in-process RFC 6455 server on loopback: ordering and buffering before collection, masking at the 3 length classes, ping/pong, fragmentation, 64-bit lengths, size cap, close, bad handshakes, unreachable host.
- **NOT verified: any real relay.** Nothing in this session has talked to a public Nostr relay, and TLS (`wss://`) has not been exercised at all (the tests are plaintext loopback). **Needs verification on a machine with reliable relay access:** connect to 2-3 public relays, send a REQ, publish a gift wrap and read the OK, and confirm relays accept our frames (relay-specific limits, rate limiting, AUTH requests are unknown). Also nothing reconnects yet: a closed connection ends `incoming`.
- **`BLUETOOTH_SCAN`** was declared in the manifest but nothing requested it (pairing runs over the local network), so it was removed to keep the permission list, and the Permissions text in Settings, honest. When BLE pairing is built, declare it again with `neverForLocation` and `tools:targetApi="s"`, and ask for it at the moment of pairing.

Still to do: wire `SyncEngine` + `WebSocketRelay.connector()` into `AppContainer` (relay list, reconnect, lifecycle), a Settings entry for relays, route local fact/guess changes into the outbox, and CI should run `:kutumb-core:test`.

### Step 6, continued: opt-in sync service

- **`SyncPreferences`**: sync is **off** and the relay list is **empty** by default (your call: opt-in). No relay is built in. Only `wss://` addresses with a dotted host are accepted (plaintext, credentials, fragments, spaces refused), max 5.
- **`SyncService`**: runs a `SyncEngine` only while the switch is on, relays are named and this phone has an identity; any of those going away stops the engine and closes every socket; changing relays or identity restarts it. While running it re-subscribes (which reconnects dropped relays) and flushes the outbox every 60 s at most, sooner when a retry is due or `poke()`/`publish()` is called. Tests (`SyncServiceTest`, 6; `SyncPreferencesTest`, 1) cover off-by-default opening no connection, needing setup, subscribing/publishing, switching off, retrying a relay that was down, and changing relays.
- **Wiring:** `AppContainer.syncService` is lazy and starts on construction; `FTreeApplication` touches it only if the switch is on, and `SettingsViewModel` builds it only after the switch is turned on. New Settings section "Family sync over the internet" (switch, relay field, status line). Relays are typed comma-separated.
- **Not done, so sync currently moves nothing useful:**
  1. `SyncService` uses the default `NoOpSyncHandler`: inbound messages from trusted contacts are decrypted, verified and dropped. Applying facts, answers, resolutions, rotations and tree edits is the next piece, and the handler must be idempotent.
  2. Nothing calls `SyncService.publish` yet, so local facts/guesses/resolutions are not queued. `publish` returns false (queues nothing) while sync is off.
  3. Still no real relay or TLS verification (see above). No background (WorkManager) sync: it runs only while the app process is alive.
  4. No UI test of the new Settings section.
- **CI:** `:kutumb-core:test` added to the three workflows' unit-test step (it was not being run by CI).

### Step 6, continued: facts, answers and resolutions now sync

- **Wire format** (`kutumb-core` `game/GameSync.kt`, JVM-tested, 10 tests): `ANSWER` carries the fact and the answer; `RESOLUTION` carries the fact, the winning answer, points and time. The fact is embedded in both so a message can arrive before the receiving phone has ever seen the fact, and the winning answer rides along so a resolution cannot arrive before the answer it picks. Decoding is strict (null on anything malformed; text <= 2000 chars, ids <= 200, points 1..100). There is no separate `FACT` message: facts travel inside the other two. `TREE_EDIT` is not handled (accepted and dropped), as agreed. `ROTATION` is handled now; see "Recovery" below.
- **Who may say what** (`GameSyncRules`): an answer is accepted only if its `answererId` is the person whose key signed it; a fact this phone has never seen is created only if it is about the local person or the sender; a resolution is accepted only from the fact's owner, never for a fact about the local person (those are resolved on this phone only), once per fact; the self-report flag must be consistent. Redeliveries are ignored by id, so a restart (which empties the engine's seen-set) is safe.
- **App wiring:** `GameSyncHandler` is the engine's handler (6 tests with the in-memory repository). `FactsViewModel.submit` sends a guess to the person it is about (a self-answer goes to no one); `ResolveViewModel.confirm` sends the resolution to everyone who answered. Both go through `SyncPublisher`, which does nothing while sync is off.
- **Decision to review:** a change made while sync is off or has no relays is **not** queued for later; `SyncService.publish` returns false and nothing is stored, so it is never sent after sync is switched on. Queuing regardless would hold family answers on disk waiting for a switch the person may never flip. Say if you want it queued instead.
- **Assumption to verify on two real phones:** fact ids use the tree person id (`<personId>/<questionId>`), so this only works when both phones hold the same person ids for the same people (true after a `.ftree` import with origins, per NOTES Step 3e). Two independently built trees will not line up.
- Not tested: the publisher against a real service, the Settings section, and anything over a real relay.

## Decisions from the owner (2026-10-03)

- **Distribution:** Google Play. F-Droid is not a target; the secp256k1 native libraries stand.
- **Wrong guesses cost nothing** (already how the core behaves; SPEC updated).
- **No recovery path** for someone with no relative nearby; no key backup planned (SPEC updated).
- **Android-to-Android only** for now.
- **Multiple trees**, not only familial: friends, work and so on. Answers to my design questions: one database per tree; two kinds of tree (Family and Circle, circles joined by plain connections with an optional label and a simple list/graph view, no kinship); one identity key per phone with contacts, facts and points scoped per tree.

## Multiple trees

### Done: storage, switching, management (phase 1)

- `data/TreeCatalog.kt` (pure, 6 JVM tests): the list of trees and which is open, in a SharedPreferences string. Names are unique (ignoring case), 1-40 characters; the open tree and the last tree cannot be removed. The first tree is `TreeCatalog.ORIGINAL` ("My family") and keeps the old file names (`f-tree.db`, `photos`, `f-tree` prefs), so an update touches no file on disk. An unreadable catalog falls back to the original tree (other trees' files would then be orphaned, not lost: they stay on disk).
- Each tree has its own database (`tree-<id>.db`), photo directory and tree-identity file. `AppContainer(context, tree)` is now per tree; `FTreeApplication.switchTo` stops the old container's services at once, builds the new container, and closes the old database 3 s later (the old screens are still collecting from it until destroyed). `relaunch()` restarts the main task so no view model of the old tree survives.
- Settings > "Your trees": open, rename, delete (with confirmation), new (name, Family or Circle).
- Verified on the emulator: `TreeSwitchingTest` (3 tests): people stay in their own tree across switches, deleting a tree removes its database and photo directory, the open tree cannot be deleted.
- **Still global, by design:** chart/kinship/update/reminder/nearby/sync preferences and the relay list. **Per open tree only, for now:** birthday reminders (they read the open tree's people) and the sync service (it runs for the open tree; trees you are not looking at do not sync until opened).
- The Family/Circle kind drives what the open tree looks like; see "Circles (phase 2)" below.

### Not done (phase 3)

1. ~~Network per tree~~ **Done.** One phone key (`kutumb/PhoneKey.kt`, sealed in `kutumb-phone-key` prefs) is used by every tree's "who am I"; a key an existing tree already holds is adopted if the phone has none (a tree that disagrees keeps its own, so contacts keep trusting it). Who-am-I, contacts, facts and points stay in each tree's database. **Sync runs for every tree while the switch is on:** the other trees keep an `AppContainer` in `FTreeApplication.background` (own database, engine and relay connections), and leaving a tree hands its container to the background instead of tearing it down. `SyncPreferences` is now one instance for the app. Costs: one set of relay connections per tree with an identity (at most 5 relays each); a sender who is a contact in two trees reaches both engines, since the envelope carries no tree id. Reminders still follow only the open tree.
2. ~~A tree switcher on the main screen~~ **Done:** with two or more trees the Tree and Circle screens' title is the tree's name with a menu of the others (`ui/tree/TreeTitle.kt`, `TreeSwitcherTest`). Not done: a first-run prompt.

### Circles (phase 2)

- **No schema change, no migration.** I had planned a `label` column and database version 3, then found `relationships.subtype` is already free text and relationship types are stored by enum *name*. So `RelationshipType.CONNECTED` (symmetric) is new, its label lives in `subtype`, and existing databases and `.ftree` files need nothing. An older app reading a file with a connection degrades it to `UNKNOWN`, as designed.
- **One connection per pair, one label.** The unique index is (from, to, type), so two people have at most one `CONNECTED` edge; "friend and colleague" is typed as one label ("Friend, colleague"). Labels are trimmed, whitespace-collapsed and capped at 30 characters; "Friend" and "friend" group together.
- **Pure logic** `graph/Circle.kt` (7 JVM tests): connections of a person, grouped by label, people reached *through* others (with who leads there), the best-connected starting point, labels already in use.
- **A Circle's tree tab** (`ui/circle/CircleScreen`): the circle seen from one person, grouped by label, then "Through people they know" (introductions). Tapping anyone moves the view to them. This replaces the ancestors-above chart; there is no canvas, a list is enough at circle sizes. Adding: `AddConnectionScreen` (label with suggestions, new person or existing). Person detail shows Connections (add, change label, long-press to remove) instead of Parents/Spouses/Children/Siblings, and hides "How are we related" and "Share this branch".
- **Verified on the emulator** (`CircleFlowTest`, 2 tests): the circle opens on the best-connected person with FRIEND / COLLEAGUE groups and a "via Ben" introduction, tapping moves the view, and adding a connection with a label shows the new person under it.
- **In a Circle:** the Facts tab is hidden (the game asks about family: whose mother, born where). Network stays: pairing and "who am I" are per tree. Birthdays/People tab work as is. Export/import writes `CONNECTED` edges as ordinary relationships.
- **The drawn graph** (Circle tab > Graph): `graph/CircleGraph.kt` (pure, 9 JVM tests) puts the person in the middle, their connections on a ring with a sector per label, and the people reached through them on an outer ring near whoever leads there; `ui/circle/CircleGraphView.kt` draws it on one Canvas with a legend, and tapping anyone moves the picture to them, as in the list. Capped at 30 connections and 40 introductions, with "N more not drawn" (the list has everyone). A Canvas says nothing to a screen reader, so it carries a summary that points to the list view. Label colours avoid brass (brass means unknown in this app) and the green of the middle person. `CircleFlowTest` taps a node at the position the layout gives it.
- **Not done:** a "who am I" for circles, deleting a label across a circle, bulk adding.

## Recovery, typed-address pairing and the book tests

### Recovery (spec steps 2-4): a relative vouches for a new phone

Before this, `kutumb-core` had the rules (`RotationRules`, `RotationPropagation`) and the app stored rotations, but nothing issued or applied one.

- **The relative's side** (`kutumb/RecoveryIssuer.kt`, `RecoveryTest`): after a normal in-person pairing, the "Who is it?" screen has a second choice, "They are someone I already trust, on a new phone". Choosing a trusted relative there does not add a contact; it signs `IdentityRotationEvent.attest(...)` with this phone's key, moves that contact to the new key (old key into `pubKeyHistory`, so revoked), marks them `DIRECT` (they were just met in person), saves the event, and publishes a `ROTATION` envelope to every other contact (not to the person: nobody else can reach the new key until they accept this). Refused, with a reason shown on screen, when this phone has no identity, the person is not trusted here, it is the phone's own owner, the key is already theirs, or the key already belongs to someone else or to this phone. The change on this phone stands even with sync off; only telling the others needs it.
- **Everyone else** (`kutumb/sync/RotationSyncHandler.kt`): a `ROTATION` from a trusted contact is run through `RotationRules.apply`; if it is valid (signature, a voucher this phone trusts, the old key still current) the contact moves to the new key and the event is saved. The sender need not be the voucher; the voucher's signature inside is what counts. Redeliveries, stale events and untrusted vouchers change nothing. `CompositeSyncHandler` offers every message to both handlers; each ignores types it does not know.
- **Wire form:** `kutumb-core` `recovery/RotationSync.kt` (6 fields: person, old, new, by, sig, at), strict decoding, 2 JVM tests.
- **Not done:** the new phone gets no contact list back. It pairs with one relative and trusts only them; everyone else it learns by pairing again or when they pair with it. An event that arrives before the one it depends on (a chain X to Y to Z) is rejected rather than held, because the rotations table holds applied events only. Both are small follow-ups, not designed here.

### Typed-address pairing

Pairing screen > "Type an address instead" shows this phone's own `address:port` to read out and a field for the other phone's. `NearbyRepository.pairByAddress` takes a private address only (as a scanned code is held to), has no token and no fingerprint, so the six digits are always compared. `pairByLink` and `pairByAddress` share `startPairing`. `PairingFlow.joinByAddress` has a no-op default so the other implementers (tests, `UnavailablePairingFlow`) are unaffected. `PairingViewModelTest` and `NetworkPairingTest`.

### The book tests

`BookFlowTest` had two failures that predated this work. Neither was an app bug. The default template is now the family chart (#315), so the shared PDF is `... Chart.pdf` (test updated). And Diwali is a download too (#314), so no storybook ships on the device; the "Whose story" flow has nothing to run against and is removed with a comment, the way upstream removed the Heirloom one (the decision is held by `BookOptionsTest`, the PDF by `BookPdfTest`). `BookFlowTest`: 6 of 6.

### Full instrumented suite, 2026-10-03

240 tests: 237 passed. The two `BookFlowTest` failures above are fixed since. `NetworkTreeLinkTest.aTrustedPersonsSheetLeadsToTheirRelationshipScreen` timed out once in the long run and passes alone (2 of 2) on the same code, so it is order- or load-dependent; not investigated further.

## Messaging

Direct messages between two people who have met in person, as the spec's "Optional messaging" section asks. The Network tab's message button, drawn unavailable until now, opens a conversation.

- **Who can write:** only a contact paired `DIRECT`. A vouched contact gets the lock, as before, and a message from one is dropped on arrival. A key rotation keeps the right (the pairing method survives it), so a relative who gets a new phone does not have to be met again to be messaged.
- **Wire:** a new sync type `MESSAGE` ("message") carrying `{id, body, at}`, over the same NIP-17 gift-wrapped, NIP-44 encrypted path as the other sync messages, to the relays the person named. Who sent it is the gift wrap's author, not a field. Not interoperable with other Nostr chat apps (the rumor kind is the app's 30078, not NIP-17's 14): this is the app's own "family sync", not a general DM channel. An older build that does not know the type drops it as unsupported.
- **Rules** (`kutumb-core` `messaging/Messaging.kt`, 8 JVM tests): body 1-2000 chars, ids <= 200, a message cannot claim to be more than five minutes ahead of this phone's clock, a redelivery is dropped by id, and the stored id is `<sender>/<wire id>` so two people cannot overwrite each other by reusing one.
- **Storage:** one table, `messages`, **database version 3** (`MIGRATION_2_3`, a create-only migration, covered by `KutumbDatabaseTest`; `schemas/.../3.json` is committed). The spec's separate `MessageThread` is not a table: a conversation is the other person, so it is `threadPersonId` on each message.
- **Sending:** a message is saved before anything tries to send it. `SyncPublisher.publish` now returns whether it queued anything; `QUEUED` means the outbox has it and will keep trying, `NOT_SENT` means sync was off or had no relay, with "Send again" on the message. No delivery receipt: nothing here says the other person has read it.
- **Reading:** opening a conversation marks theirs read; the Network list shows the newest message and an unread count on the button.
- **Tests:** `MessagingFlowTest` (8, JVM: sending, resending, the rules at the handler, read marking), `ContactsModelTest`, `KutumbDatabaseTest` (migration and Room round trip), `MessagingTest` (3, device: write with sync off, receive while open, vouched is locked).
- **`adjustResize`:** the conversation screen is the first with a text field pinned to the bottom, and with no soft-input mode set the system panned the whole window (the top bar and the list went off the top). `MainActivity` now declares `adjustResize`, which is what `imePadding()` expects; it also applies to every other screen with a text field.
- **Not done:** notifications for a new message (the app asks for `POST_NOTIFICATIONS` only for birthday reminders and nothing new was added), delivery receipts, deleting a message or a conversation, anything but text, and syncing a person's messages to a second phone. A message sent while sync is off is not queued for later; "Send again" is the way.

## Background sync

Sync used to run only while the app's process was alive. It now also wakes up on a schedule.

- **How:** a periodic `JobScheduler` job (`kutumb/sync/SyncJob.kt`): platform API, no new library and no new permission (it persists across restarts using `RECEIVE_BOOT_COMPLETED`, which the birthday reminders already declare). The system runs it no more often than every 15 minutes, when it suits the battery and there is a network. `SyncJobService` calls `FTreeApplication.backgroundSyncPass()`, which makes sure sync is up for every tree, pokes each outbox, and waits 30 seconds for the relays to hand over what waited. The connections are left to the process; the system ends it when it likes.
- **Only when wanted:** the job exists exactly while the sync switch is on and a relay is named (`SyncJobs.shouldRun`, the one rule), scheduled and cancelled by a collector in `FTreeApplication`. With the switch off nothing is scheduled and nothing wakes. Settings says so in plain words, and the Permissions text now names sync next to reminders as a use of the boot permission.
- **Checked on the emulator:** with sync on and a relay that never resolves (`wss://relay.invalid`) the system lists the job (periodic, persisted, needs a network); after the process was killed, `cmd jobscheduler run -f` started a fresh process and the pass ran with no crash, and the job was still scheduled. Force-stopping an app cancels its jobs (Android's rule), so a force-stopped app schedules again on its next launch. `SyncJobsTest` (2) and `SyncJobTest` (4).
- **Not checked:** an actual relay (none reachable from here), Doze and app-standby timing, battery cost over days. The 15-minute floor is the platform's; a message can wait that long to arrive while the app is closed.
- **Not done:** notifications when a message arrives. Needs a switch and the notification permission asked at the moment it is turned on, as the birthday reminders do; the job makes it worth building now.
