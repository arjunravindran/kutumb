# Handoff (updated 2026-10-08)

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

- JVM: `testDebugUnitTest :kutumb-core:test :app:lintDebug` pass (925 tests when last run in full).
- Emulator (pinned with `ANDROID_SERIAL`): the 3-to-4 migration, 13 coordinator tests with two real databases joined by a fake relay, and the whole instrumented suite **before** the container wiring (270 of 271; the one failure, `PhotoViewerTest.tappingThePictureItselfPutsItAway`, passes alone: a known load flake).
- Site JS tests (906), desktop JS tests that CI runs (454), the Python/Node site checks.
- CI: one real failure was found from a pasted log and fixed (`NearbyRepository` released its pairing slot after announcing a failed attempt, so a quick retry was ignored).

## NOT verified

- **The whole instrumented suite has not been re-run since `AppContainer` gained the tree-sync wiring** (a run was killed for low memory). The wiring is gated on a tree being shared, but run it: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`.
- Nothing here has run on a physical phone or against real relays; tree sync has only been run with a fake relay.
- The web viewer's persistence, install and offline behaviour have not been tried on an iPad. Open question: whether Safari greys out `.ftree` files in the file picker.
- The self-hosted fonts and the pages have not been looked at in a browser by the assistant.
- The Kutumb build has not been installed on a device since the rename; the R8 release build has not been run since the rename (CLAUDE.md says to test it before publishing). The Play build was not rebuilt.
- Still unseen: the "Coming up" widget on a screen; a message arriving with the app closed; recovery; an unexplained first pairing failure; Doze behaviour over days.

## Gotchas

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

## Left undone

1. **`store/feature-graphic.png` still says "f-tree"** (only the SVG was updated; nothing here renders SVG to PNG).
2. **Still f-tree:** `desktop/package.json` author/maintainer and `appId`/packaging names, and the README body (a note at the top says Kutumb is separate). `release.yml` needs checking against this repo and signing secrets added to `kutumb`.
3. **No tags or releases exist.** Tagging `v1.0.0` runs the release workflow and publishes; do not recreate the old f-tree tags. The in-app updater points at this repo and finds nothing until a release exists (it is off by default).
4. CLAUDE.md asks for an issue first for changes to `update/`, the schema, new screens and wire changes; it is f-tree's contributor guide and may no longer apply. `docs/kutumb/TREE-SYNC.md` serves as the discussion for tree sync.
5. The Settings sync text should mention that relay.damus.io does not deliver messages. Rough edges (see `NOTES.md`): after recovery the new phone gets no contact list back; a rotation arriving before the one it depends on is rejected, not held; messages stay "queued" with no delivered state.
6. Play listing: the Data safety form, two or more phone screenshots (the debug seeder, `mode family`, makes them), a feature graphic saying Kutumb; the README and `docs/faq.md` still say it is not on Play; sign the Play bundle with the upload key.

## Suggested next steps

1. Re-run the whole instrumented suite on the emulator, then `./gradlew build` and a release (R8) build.
2. Tree sync phase 4 (nearby snapshot with the same ids, catch-up), the review inbox screen, and a way to stop sharing.
3. Try the PWA on an iPad and the Kutumb debug build on a phone.
4. Regenerate the feature graphic PNG; review `release.yml`; decide when to tag `v1.0.0`.
