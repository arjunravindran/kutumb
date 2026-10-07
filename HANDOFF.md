# Handoff (updated 2026-10-07)

Kutumb is a **standalone repo**: github.com/arjunravindran/kutumb (private until the owner says otherwise). It is no longer a GitHub fork. The old fork `arjunravindran/f-tree` was **deleted** on 2026-10-07, and the `upstream` remote (`thisisankit27/f-tree`) is removed. The only remote is `origin`. Per-feature detail is in `NOTES.md` (newest section: "Two real phones, 2026-10-04"); the spec is `docs/kutumb/SPEC.md`.

**Branches:** the local branch is `kutumb`; on the remote it is `main`. Push with `git push origin kutumb:main` (a plain `git push` fails because the names differ). A separate local `main` exists and holds the original f-tree history at its 1.0.0 merge; ignore it.

## Done on 2026-10-07 (repo and site)

- **Detached from f-tree:** new repo created, `kutumb` pushed as `main` with full history (the MIT `LICENSE` keeps Ankit Srivastava's notice; the README, About text and site footers credit f-tree). Old fork deleted.
- **Links:** every `arjunravindran/f-tree` link now reads `arjunravindran/kutumb`, including the in-app updater URLs in `app/build.gradle.kts` and `desktop/main.js`, the issue templates, security and contributing docs. `CHANGELOG.md` version headings are no longer linked, and `docs/desktop.md` issue references read `f-tree#NNN` (those issues lived on the deleted fork). No version tags exist on the new repo; **do not recreate the old ones, because `release.yml` triggers on tag pushes and would build and publish them.**
- **Website:** live at **https://kutumb.arjun.contact** (Cloudflare Pages, project `kutumb`, production branch `main`, no build command, output directory `site`, custom domain on the owner's Cloudflare zone `arjun.contact`). Pushes to `main` redeploy it. The Cloudflare GitHub app is limited to the `kutumb` repo. **Privacy policy URL: https://kutumb.arjun.contact/privacy/.** Canonical URLs, sitemap, robots, README, docs, the share-card footer and the desktop menu links all use the new domain.
- **Family-book QR:** `site/book/qr.js` was a precomputed matrix for the old domain; regenerated for `kutumb.arjun.contact` with the vendored encoder, and `golden.txt` and `golden/sample-heirloom.json` regenerated. Site JS tests: 892 run, 891 pass, 0 fail, 1 skipped.

## Done earlier (2026-10-04/05)

- **Rename to Kutumb** (`f231306`), **applicationId `com.vibethroughcode.kutumb`** (`f36cd34`; debug `.kutumb.debug`; code namespace still `com.vibethroughcode.ftree`; the seeder command needs the full class `com.vibethroughcode.kutumb.debug/com.vibethroughcode.ftree.debug.SeedReceiver`), and all remaining f-tree text replaced (`c79d6d6`).
- **Relay dropdown** (`83d14fc`): Settings → Sync "Choose a relay…" adds one of `SyncPreferences.SUGGESTED_RELAYS` (max 5, no duplicates): nos.lol, relay.primal.net, offchain.pub, nostr.mom, relay.snort.social, nostr.einundzwanzig.space. "Verified" means each accepted a connection and answered a subscription with EOSE and no AUTH (probed 2026-10-05). **Only nos.lol has carried messages end to end between the two phones.** relay.damus.io is left out on purpose (its AUTH cannot be completed). Rejected on the probe: nostr.oxtr.dev, relay.mostr.pub, relay.nostr.band, relay.nostr.bg. There is no default relay. Not tried on a device.
- **Privacy policy** (`site/privacy/index.html`): the developer collects and shares nothing and runs no servers that receive user data. It deliberately does *not* claim nothing ever leaves the device: with sync on, encrypted messages go to relays the person picked. It contacts via GitHub issues (the owner may want an email instead).
- **Kept on purpose:** the `.ftree` format and `"format": "f-tree"`, nearby wire labels `f-tree/nearby/1/...`, `f-tree.db` and identity file names, `settings.gradle.kts` project name, desktop packaging names (`f-tree-desktop`, productName `f-tree`, `/opt/f-tree`), `CHANGELOG.md`/`NOTES.md` history. Changing the desktop packaging names needs a real installer build to verify.

## Verified (actual runs)

- Before the repo move: `testDebugUnitTest`, `:kutumb-core:test`, `lintDebug`, `assembleDebug` and `assembleRelease` (R8) passed. The full instrumented suite on the emulator had 0 failures. Desktop JS tests had 2 failures that also fail without these changes (`atomic` "target holds the old file" and `package.test.js`; likely Windows).
- 2026-10-07: site JS tests as above. Node 26 is installed at `C:\Program Files\nodejs` but is **not on the PowerShell or bash PATH**; use `export PATH="/c/Program Files/nodejs:$PATH"` in bash.

## NOT verified

- **Nothing Android-side has been rebuilt or tested since the 2026-10-07 URL changes.** They touched `app/build.gradle.kts` (updater and site URLs), `strings.xml` (`card_footer`) and `RelationCardTest.kt` (its expected footer). Run `./gradlew testDebugUnitTest lintDebug assembleDebug`, then the instrumented suite.
- The desktop JS tests were not rerun after the URL changes (`desktop/main.js`, `package.json`).
- **The live site was not opened by the assistant;** the owner reported it live. Worth checking `/`, `/privacy/`, `/playground/` and that images load.
- **The Kutumb build has not been installed on any device** (dropdown, new name, new applicationId). The release build with R8 has not been run since the rename (CLAUDE.md says to test it before publishing).
- **The updater points at a repo with no releases,** so it finds nothing until a release is published. It is off by default.
- Still unseen from before: the "Coming up" widget on a screen; the Play build on a device, signed; a message arriving with the app closed; recovery; an unexplained first pairing failure; Doze behaviour over days.

## Gotchas

- **Never run `connectedDebugAndroidTest` with a phone attached unless `ANDROID_SERIAL` names the emulator.** Use `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest` from bash.
- **The phones' installed app is `com.vibethroughcode.ftree.debug` (old identity).** Installing the new build adds a separate "Kutumb (debug)" with an empty tree and no pairing. Re-pair, or export and import.
- Start the emulator with `emulator -avd Medium_Phone_API_35 -no-snapshot -no-audio -memory 3072`. adb is at `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`.
- No `gh` CLI is installed, and the GitHub MCP token cannot create repositories (403).
- To regenerate the book goldens after an intended change: `UPDATE_GOLDEN=1 node --test book.test.mjs` in `site/book`. The QR is regenerated by re-encoding `SITE_URL` with `desktop/renderer/vendor/qr.js` (error correction M), as `qr.test` checks.
- Editing Kotlin with a Python script: write `'\n'` inside Kotlin via the Edit tool, not through a Python string.
- Gradle can report success when the device runner failed (read the result XML under `app/build/outputs/androidTest-results/connected/debug/`); `gradlew.bat` splits at commas; long heredocs with odd quotes fail in Bash here.
- A broad `sed` over the repo also rewrites ignored caches such as `.gradle/`; exclude `.gradle`, `build`, `node_modules` and `.git`.

## Owner decisions (don't re-ask)

Google Play distribution (F-Droid is not a target). Android to Android only. Sync is opt-in with an empty relay list. No recovery path for someone with no relative nearby and no key backup. One database per tree; kinds Family and Circle; one identity key per phone. A change made while sync is off is not queued. The memory-leak question is dropped for good. **Kutumb is a separate app from f-tree, with f-tree and its MIT licence acknowledged. Ship it as 1.0.0 (`versionName` 1.0.0, `versionCode` 34 already).** The repo is detached from f-tree's fork network and the old fork is deleted.

## Open: iOS

The owner wants an iOS version but has only an iPad and a Windows PC. Xcode needs macOS, so the options discussed were: build in GitHub Actions macOS runners and test on the iPad through TestFlight (needs the $99 Apple Developer Program); or rent a cloud Mac. For the app itself: Kotlin Multiplatform with Compose Multiplatform (`graph/` and `transfer/` are pure Kotlin and could be shared; a big refactor), or a SwiftUI rewrite (duplicates the kinship and layout rules). Cheapest first step: a PWA of the `site/` viewer, which would run on the iPad today. **Undecided:** view-only versus full editing on iOS, and whether to pay the $99. No iOS work has started.

## Left undone

1. **`store/feature-graphic.png` still says "f-tree"** (only the SVG was updated; nothing here renders SVG to PNG). `store/icon-512.*` has no text.
2. **Still f-tree:** `desktop/package.json` author/maintainer (thisisankit27 noreply email), desktop `appId` and packaging names, the README body (a note at the top says Kutumb is separate; the rest still describes f-tree), `release.yml` (check it against the new repo and add signing secrets to `kutumb`: they did not move from the deleted fork).
3. **Internals deliberately unchanged:** the `.ftree` format, nearby wire labels, database and identity file names, and the package namespace. Renaming them would break data and pairing; decide later whether Kutumb ever diverges.
4. Make the repo public when the owner is ready (currently private).

## Things a person should look at

1. **No tags or releases exist.** Tagging `v1.0.0` runs the release workflow and publishes; the owner has not said to. `release.yml` checks the tag against `versionName`, and signing needs `keystore.properties` or repo secrets.
2. **CLAUDE.md asks for an issue first** for changes to `update/`, the database schema, new screens or wire changes. None opened (db v3, the `MESSAGE` sync type, new screens, the 1.2 MB GeoNames asset, the Play build, the widget). CLAUDE.md is f-tree's contributor guide and may no longer apply to a separate app.
3. The Settings sync text should probably mention that relay.damus.io does not deliver messages.
4. Rough edges from before (see `NOTES.md`): after recovery the new phone gets no contact list back; a rotation arriving before the one it depends on is rejected, not held; the envelope carries no tree id; the 15-minute wake-up means a message can wait that long with the app closed; messages stay "queued" with no delivered state.

## Play Store: what is left

- **Account:** nothing done.
- **Listing:** the privacy policy (written and hosted at https://kutumb.arjun.contact/privacy/), the Data safety form, two or more phone screenshots (the debug seeder, `mode family`, makes them), a feature graphic PNG saying Kutumb. The README and `docs/faq.md` still say it is not on Play.
- **Code:** sign the Play bundle with the upload key.

## Suggested next steps

1. Run `./gradlew testDebugUnitTest lintDebug assembleDebug`, then the whole instrumented suite on the emulator (about 10 minutes), `./gradlew build` and a release (R8) build.
2. Open the live site and check it; install the Kutumb debug build on a phone (name, icon, relay dropdown, re-pair two phones).
3. Regenerate the feature graphic PNG; review `release.yml` and add the signing secrets.
4. Decide the iOS scope and the $99 account, then start with the PWA.
5. Rebrand the desktop app and the README body, or leave them for after 1.0.0; then tag `v1.0.0`.
