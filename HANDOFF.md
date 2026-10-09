# Handoff (updated 2026-10-09)

Kutumb is a standalone repo, no longer a GitHub fork: github.com/arjunravindran/kutumb. It began as a fork of f-tree by Ankit Srivastava and is used under f-tree's MIT licence, whose notice is kept in `LICENSE`. Per-feature history is in `NOTES.md`; the product spec is `docs/kutumb/SPEC.md`; the tree-sync design is `docs/kutumb/TREE-SYNC.md`.

**Branches:** the working branch is `kutumb`; on the remote it is `main`. Push with `git push origin kutumb:main` (a plain `git push` fails because the names differ). A separate local `main` holds the original f-tree history; ignore it.

## What exists

- **The app** (Android): family tree, chart and compact views, kinship names (English, Hindi), nearby sharing, family book PDF, birthdays, trust/pairing, facts game, messages, sync over Nostr relays (opt-in, empty relay list by default), Circles.
- **The website** is live at https://kutumb.arjun.contact (Cloudflare Pages: project `kutumb`, production branch `main`, no build command, output directory `site`; every push to `main` redeploys). Privacy policy: https://kutumb.arjun.contact/privacy/. Fonts (Literata, JetBrains Mono, OFL) are served from `site/fonts/`, not Google.
- **The web viewer** (`site/playground/`) is an installable PWA (manifest, icons, service worker). It is **view-only**: it never imports the `.ftree` writer. It keeps the last tree you opened in the browser's own storage (IndexedDB) so an installed copy reopens on it; **Close** or unticking "Keep my tree on this device" deletes it. `remember.js` and its tests cover this.
- **The desktop app** (`desktop/`, Electron) loads the same viewer code.
- **Tree sync** (new): see below. Reachable from **Settings > Your trees > Sharing** on the open tree.

## Tree sync: where it stands

Design: `docs/kutumb/TREE-SYNC.md` (read it before touching this). Built and tested:

1. **Pure core** (`kutumb-core/.../kutumb/treesync/`): hybrid logical clock, seven operations, last-writer-wins registers per person / field / relationship, a *view* that hides parent loops and edges to unknown people without dropping them, an edit recorder, a projection (view to database steps), a reconciler (database to operations), and a wire codec. Convergence is tested with shuffled, duplicated random streams and a three-phone simulation with skewed clocks.
2. **Room** (schema v4, `MIGRATION_3_4`, create-only): `tree_sync_meta` (present only if shared), `tree_sync_ops` (append-only log), `tree_sync_members`. `TreeSyncCoordinator` rebuilds the replica from the log, records changes (a Room invalidation observer, so imports and seeds are covered), and applies received operations in one transaction. v4 was never released, so the members table was added in place.
3. **Wire**: `TreeSyncHandler` accepts edits only from contacts **paired in person**, for **this** shared tree, who are **members**; `TreeSyncPublisher` sends in batches of 100 through the existing outbox; `shareTreeWith` adds a member and sends the whole history. `AppContainer` starts it only when a tree is shared.
4. **Sharing screen** (`ui/settings/TreeSharingDialog.kt`, rules in `kutumb/treesync/TreeSharing.kt`): a Sharing button on the open tree's row in Settings > Your trees. It switches sharing on, lists contacts paired in person with a switch each (on = member and is sent the tree), shows "N changes waiting" and a note when sync is off. Only the open tree can be managed (sharing lives in its own database). Tested on the JVM and on the emulator against a throwaway tree.

Not built: **phase 4** (start a big shared tree from a nearby snapshot with the same ids, instead of sending the whole history over the relays; catch-up for a phone that has been away), **phase 5** (photos), a way to **stop sharing** a tree altogether (members can be removed one by one), and the review inbox screen (`TreeSyncCoordinator.inbox()` exists).

## Verified (actual runs)

- **Two real phones, real relay (2026-10-08/09):** Pixel 7 (Android 17) and Galaxy S22+ (Android 14), debug build, sync on, relay `wss://nos.lol`. Both seeded with the same sample family (`--es mode family --ez stable true --es shared t1`), paired in person (they hold each other as DIRECT contacts), each added the other under Settings > Your trees > Sharing. A rename on the Samsung ("Meena" to "Meena Devi") reached the Pixel through nos.lol, and a database comparison showed identical people and relationships on both. The relay dropdown, the Sharing screen and the Nearby/QR pairing screens all worked on hardware.
- JVM: `testDebugUnitTest :kutumb-core:test :app:lintDebug` pass (925 tests when last run in full).
- Emulator (pinned with `ANDROID_SERIAL`): the 3-to-4 migration, 13 coordinator tests with two real databases joined by a fake relay, and the whole instrumented suite **before** the container wiring (270 of 271; the one failure, `PhotoViewerTest.tappingThePictureItselfPutsItAway`, passes alone: a known load flake).
- Site JS tests (906), desktop JS tests that CI runs (454), the Python/Node site checks.
- CI: one real failure was found from a pasted log and fixed (`NearbyRepository` released its pairing slot after announcing a failed attempt, so a quick retry was ignored).

## NOT verified

The full list, with what to do about each, is under **C. Verification gaps** in "What's left". In short: tree sync has had one real-device run and nothing beyond a single rename; the whole instrumented suite has not been re-run since the tree-sync wiring; the web viewer has not been tried on an iPad; the self-hosted fonts have not been looked at in a browser; the R8 release build and the Play build have not been run since the rename; and several older behaviours (the "Coming up" widget, a message arriving with the app closed, recovery, Doze over days) remain unseen. The debug build **has** been installed and run on a Pixel 7 and a Galaxy S22+ (2026-10-08).

## Gotchas

- **Real-phone testing over adb:** the Pixel's screen times out after 30 s and then screenshots come back black, so send `KEYCODE_WAKEUP` before each step. Never press Back repeatedly: it leaves the app and lands in whatever was open before. The app does not log pairing steps; the stored state is the evidence (`run-as com.vibethroughcode.kutumb.debug cat databases/f-tree.db` plus the `-wal` and `-shm` files, read with sqlite3).
- **What the first real run showed:** (a) a message from a phone that has not yet been added as a member is dropped by design, so add each other on **both** phones before editing; (b) after adding a member the Sharing screen keeps saying "N changes waiting" until the next pass sends the backlog, because sending a new member the whole history does not mark operations as sent; (c) when the relay is slow to reach (the Pixel measured 250 to 1100 ms to nos.lol while the Samsung measured about 230 ms) the websocket connect fails and is retried once a minute, and it took about nine minutes for a change to arrive; (d) `connect failed` is logged without the underlying error.
- **Never run `connectedDebugAndroidTest` with a phone attached unless `ANDROID_SERIAL` names the emulator**, e.g. `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`. Start it with `emulator -avd Medium_Phone_API_35 -no-snapshot -no-audio -memory 3072`.
- Gradle can report success when the device runner failed: read the result XML under `app/build/outputs/androidTest-results/connected/debug/`. `gradlew.bat` splits arguments at commas, so run one test class at a time.
- On Windows, **do not cut a Gradle run short with `Select-Object -First N`** (it can stop Gradle mid-run and leave odd results).
- Some JVM tests run the JavaScript composer and are skipped without Node; CI sets `FTREE_REQUIRE_NODE=1`. On Windows a few of them (and `desktop/atomic.test.js`, `tools/check_writer.mjs`) fail for path or file-replace reasons that do not occur on Linux.
- To regenerate the book goldens after an intended change: `UPDATE_GOLDEN=1 node --test book.test.mjs` in `site/book`. The QR is `SITE_URL` re-encoded with `desktop/renderer/vendor/qr.js` (error correction M), as `qr.test` checks.
- `.ftree`, nearby wire labels (`f-tree/nearby/1/...`), database and identity file names, and the package namespace `com.vibethroughcode.ftree` keep the f-tree name on purpose: renaming them would break data and pairing. The installed debug app of an older identity is `com.vibethroughcode.ftree.debug`; the new one is `com.vibethroughcode.kutumb.debug` and starts empty.
- A broad `sed` over the repo also rewrites ignored caches such as `.gradle/`; exclude `.gradle`, `build`, `node_modules` and `.git`.
- Tooling: there is no `gh` CLI; the GitHub token available to the assistant cannot create repositories or read Actions logs. Read a failing run on the Actions page and paste the log.

## Decisions (don't re-ask)

Google Play distribution (F-Droid is not a target). Android to Android only for the app; **iPad gets the view-only PWA, with no native iOS app for now** (revisit after the PWA has been used). Sync is opt-in with an empty relay list. No recovery path for someone with no relative nearby and no key backup. One database per tree; kinds Family and Circle; one identity key per phone. A change made while sync is off is not queued. Tree sync: everyone paired in person and added may edit; a delete beats a concurrent edit but is undoable; a second phone starts from a snapshot with the same ids; photos are not in v1. Kutumb is a separate app from f-tree, with f-tree and its MIT licence acknowledged. Ship it as 1.0.0 (`versionName` 1.0.0, `versionCode` 34 already).

## What's left

Grouped by what each item is waiting on. Within a group, roughly in the order to do them.

### A. Waiting on the owner (nothing the assistant can do alone)

1. **Publish the cleaned git history.** The serials and business notes were removed from all 14 commits that carried them (local branch `kutumb`), but the force-push was refused by the permission check, so **GitHub still has the old history**. Run it yourself: `git push --force-with-lease=main:e7f310289746226aeca8d6947a1c94103eaa96fe origin kutumb:main` (it carries every local commit; the lease makes it fail safely if `main` moved). Then delete the local safety branch (`git branch -D backup-before-history-rewrite`; it still holds the old history). GitHub may keep old commits reachable by URL for a while; contact GitHub Support to purge them before going public if that matters. Until this is done, **do not push anything else** (a normal push is rejected).
2. **Make the repo public** (Settings > Danger Zone) once item 1 is done. The tip is already clean: no secrets, keystores, serial numbers or business notes in tracked files. Public repos also get free Actions minutes.
3. **Read the next GitHub Actions run** (https://github.com/arjunravindran/kutumb/actions) and paste any red step's log. The assistant cannot read Actions logs. One real failure was fixed that way; the emulator job in `android-ci.yml` and the desktop packaging jobs are the ones not reproduced locally.
4. **Try the web viewer on the iPad**: install from https://kutumb.arjun.contact/playground/, open a `.ftree`, close the app and reopen it, and see whether Safari greys out `.ftree` files in the picker (if it does, widen the `accept` list). Also glance at the live pages to check the self-hosted fonts.
5. **Free memory before the next emulator run.** A full instrumented run was killed once for low memory.
6. **Accounts and money** (not started, owner's call): the Play developer account and which route to take. The $99 Apple Developer account is **not** needed now (decision: no native iOS yet).

### B. Tree sync: still to build, most important first

Design and status: `docs/kutumb/TREE-SYNC.md`. What works today is in "Tree sync: where it stands" above, including one real two-phone run.

1. **The real "join a shared tree" step (phase 4).** Today a second phone can only join because the *debug* seeder gives both phones the same ids and the same shared-tree id (`--es mode family --ez stable true --es shared t1`). A real user has no way to say "join Asha's tree". Needed: an invitation from the phone that shares, accepted on the other, carrying the shared-tree id and a **snapshot with the same person ids** (planned over the nearby transfer, because sending a big tree's whole history through relays is many messages), plus `sync-request` / catch-up for a phone that was away or recovered. `sendEverythingTo` (the whole history over relays) is the fallback and is what the Sharing switch does now.
2. **Fix the "N changes waiting" count.** After adding a member the screen keeps saying e.g. "71 changes waiting" until the next pass, because sending a new member the whole history does not mark operations as sent. Simplest fix: call `publisher.flush()` after a successful add in `TreeSharing.setMember`, and accept the harmless duplicate delivery.
3. **Log why a relay connect failed.** `SyncEngine.ensureLink` logs `connect failed: <url>` without the cause (the exception reaches `Log.i` but nothing useful shows). Log the exception class and message, and consider a longer connect timeout than the current 10 s (`WebSocketRelay.CONNECT_TIMEOUT_MS`) for slow routes; the Pixel needed about nine minutes of once-a-minute retries to get one message through while its route to nos.lol was slow.
4. **Stop sharing.** There is no control to stop sharing a tree altogether (only to remove members one by one). Decide what it deletes (the marker and log; the tree itself stays).
5. **The review inbox screen.** `TreeSyncCoordinator.inbox()` returns edits made to someone after they were deleted and relationships held back (loops, missing people); nothing shows it yet. It needs a screen and apply-or-dismiss actions.
6. **`person.merge`.** Two phones that each add the same relative end up with two people. The existing duplicate matching proposes a merge, but recording a merge as an operation (rewrite edges, keep an alias so later edits to the old id still land) has no design yet.
7. **Photos (phase 5).** Not synced: resized, chunked, encrypted events, or a nearby-only path; depends on relay size limits.
8. **Smaller rules and polish:**
   - The app's local relationship limits (for example "a second biological mother") are not mirrored in the sync view, so another phone could show something this one would refuse.
   - A name over 500 characters or notes over 20,000 are skipped as invalid operations on every pass and never sync.
   - There is no clock-drift warning in the UI: an operation stamped more than a day ahead is deferred silently.
   - Compaction of the operation log and tombstones is deferred, so the log only grows.
   - Removing a member does not take back what they already hold; say so in the UI.
9. **Keep the docs honest:** `docs/kutumb/TREE-SYNC.md` phases 4 to 6 and its risks list. CLAUDE.md still asks for an issue first for schema, screen and wire changes.

### C. Verification gaps (run these before trusting it)

- **Tree sync on real phones, beyond one rename:** deleting a person on one phone (the other should lose them, and an edit made after the delete should land in the inbox), edits to the same person on both phones at once, a phone that was offline while edits happened, restarting the app mid-sync, adding a child or a relationship, a third phone, and a tree with photographs.
- **The whole instrumented suite** has not been re-run since `AppContainer` gained the tree-sync wiring: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest` (about 14 minutes). Then `./gradlew build` and a **release (R8) build** (CLAUDE.md: R8 once broke the app, and the rename and the new Room entities have not been through it). `app/proguard-rules.pro` may need keep rules for the new `TreeSync*` entities and for enums that are stored or sent by name (`HeldReason`, `PersonField`).
- **Still unseen from before:** the "Coming up" widget on a screen; a message arriving with the app closed; identity recovery; Doze behaviour over days; the signed Play build on a device.
- The relay list is verified only for connection and subscription; **only nos.lol has carried real messages**.

### D. Release and housekeeping

1. **`store/feature-graphic.png` still says "f-tree"** (only the SVG was updated; nothing here renders SVG to PNG).
2. **Still f-tree:** `desktop/package.json` author/maintainer and `appId` / packaging names, and the README body (a note at the top says Kutumb is separate). The desktop installers need a real build to verify any rename.
3. **`release.yml`** needs checking against this repo, and the signing secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, alias `ftree`) added to `kutumb`: they did not move from the deleted fork. **No tags or releases exist.** Tagging `v1.0.0` runs the release workflow and publishes; do not recreate the old f-tree tags. The in-app updater points at this repo and finds nothing until a release exists (it is off by default).
4. **Play listing:** the Data safety form, two or more phone screenshots (the debug seeder, `mode family`, makes them), a feature graphic saying Kutumb, and signing the bundle with the upload key. The privacy policy URL is already live. The README and `docs/faq.md` still say the app is not on Play.
5. **Settings text and rough edges:** the sync section should mention that relay.damus.io does not deliver messages (the dropdown steers around it). Known rough edges, from `NOTES.md`: after recovery the new phone gets no contact list back; a rotation arriving before the one it depends on is rejected, not held; messages stay "queued" with no delivered state; the 15-minute wake-up means a message can wait that long with the app closed.
6. **CI tidy-up (optional):** `ci.yml` and `android-ci.yml` both run the same unit tests, lint and assemble on every push (both now skip docs-only changes); consider merging them, and decide whether the ~15 minute emulator job should run on every push.
7. **Windows-only test noise:** a few JVM tests that call the JavaScript composer, `desktop/atomic.test.js` and `tools/check_writer.mjs` fail on Windows for path or file-replace reasons that do not happen on Linux. Fixing the path handling would make local runs trustworthy.

### E. Later, not now

- A native iOS app (decision: stay with the view-only PWA; revisit after it has been used). If it happens: Kotlin Multiplatform with Compose Multiplatform for the shared pure logic, GitHub Actions macOS runners and TestFlight, which needs the Apple account.
- Editing on the iPad viewer (decision: view-only; the writer exists but is not wired into the web viewer, and with tree sync there would be two ways to change a tree).
- Roles beyond "member", and group re-keying when a member is removed.

## Suggested order

1. Run the force-push (A1), then make the repo public (A2) when ready.
2. Re-run the full instrumented suite and an R8 release build (C), fixing anything the new tree-sync tables or enums break.
3. Fix the waiting count and the connect-failure logging (B2, B3): small, and they make real-phone testing less confusing.
4. Run the real-phone checks in C (delete, concurrent edits, offline, restart) and fix what they find.
5. Build the join step (B1), then stop sharing (B4) and the review inbox (B5).
6. Release housekeeping (D) and the Play listing, then tag `v1.0.0`.
