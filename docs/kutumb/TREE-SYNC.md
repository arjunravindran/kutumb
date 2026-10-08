# Tree sync: design (draft, 2026-10-08)

Status: **phases 1 and 2 are built and tested (the pure core, and the Room persistence under it); the wire, share and UI phases are not started.** Nothing in the running app uses it yet. The core is in
`kutumb-core/.../kutumb/treesync/`: `Hlc.kt` (clock), `TreeOp.kt` (operations and validation),
`TreeReplica.kt` (merge and view), `TreeOpCodec.kt` (the wire payload), `EditBridge.kt` (edits to operations, view to database steps). 48 JVM tests, including random
shuffled-and-duplicated operation streams that must converge, and two deliberate breakages of the merge
that the tests were checked to catch.

CLAUDE.md asks for an issue or discussion before changes to the database schema or the wire; this
document is that discussion. It changes the schema (db v3 → v4) and adds to the sync wire.

Two things changed from the first draft while building phase 1, both for the same reason (see
[Guardrails](#guardrails-worked-out-in-the-view-not-at-apply-time)): rules that need the whole tree
are computed in a **view**, not decided when an operation arrives; and `person.merge` is **deferred**
to a later phase.

## What this is for

Today each phone's tree is its own. Pairing in person is a trust handshake (keys only, no tree data), and
the relay sync carries game answers, resolutions, messages and key rotations. `SyncType.TREE_EDIT` exists
on the wire but nothing sends it and `GameSyncHandler` ignores it. Two relatives who each keep a tree
drift apart until somebody exports and imports again.

Goal: **paired relatives share one tree. An edit made on one phone appears on the others**, without
accounts or a server, over the relays that already carry the other sync messages.

Non-goals for the first version: photos (see below), real-time collaboration, sync for the web viewer
or desktop app (they stay snapshot readers), merging two independently built trees automatically.

## Decisions (owner, 2026-10-08)

| Question | Decision |
|---|---|
| Who may edit | **Everyone paired in person** and added to the shared tree. People only *vouched* for stay read-only. |
| Delete vs concurrent edit | **Delete wins, but undoable.** The person disappears everywhere; the clashing edit is kept in a review inbox and the person can be restored. |
| How a second phone starts | **Snapshot from one phone**, over nearby sharing, carrying the shared tree id and the same person ids. Two independently built trees are first joined by the existing reviewed import, then one is shared. |
| Photos | **Not in v1.** Text and relationships only. Photos stay on the phone that added them; the nearby transfer still carries them. |

## What is missing today

1. **No shared identity.** Ids are UUIDs, but two phones holding "the same" family generated different ones,
   so an edit cannot say who it is about. A shared tree needs one **shared tree id** and one set of person ids.
2. **No deletion history.** Deletes are hard deletes with `ON DELETE CASCADE`. A phone that was offline
   cannot tell "deleted" from "never received".
3. **Whole-row timestamps.** `people.updatedAt` covers every field, so edits to different fields of one
   person would overwrite each other.
4. **The envelope carries no tree id** (a known rough edge in NOTES.md), and a phone can hold several trees.
5. **Relays forget.** A new, recovered or long-offline phone needs a catch-up path that does not rely on
   relays keeping old events.

## The model

A shared tree is a **replica set**: every member phone holds a full copy and every change is an
**operation**. Applying the same set of operations, in any order and any number of times, must give the
same tree. That property is the whole design, and it is a pure function, so it lives in `kutumb-core` and is
tested on the JVM with shuffled and duplicated operation streams.

### Clock

A **hybrid logical clock** (wall time, a counter, and the device id as a tiebreak) stamps every operation.
It orders edits sensibly even when a phone's clock is wrong, and it never goes backwards on one device.
Total order: `(time, counter, deviceId)`.

### Operations

| Op | Meaning | Merge rule |
|---|---|---|
| `person.add` | creates a person with an id | idempotent on id |
| `person.set` | sets **one field** of a person (name, gender, birth/death date, deceased, notes) | last write wins **per field** by clock |
| `person.delete` | tombstones a person | wins over edits older than itself; later edits go to the review inbox |
| `person.restore` | clears a tombstone | wins if its clock is later than the delete |
| `edge.add` | adds an edge `(type, from, to)` with an optional subtype | an edge is identified by its kind and its two people, so two phones adding the same edge add one |
| `edge.remove` | removes an edge | removal wins over an add with an earlier clock; a later add brings it back |
| `edge.subtype` | changes only the edge's subtype | last write wins by clock |

Rules that follow:

- **State is a set of registers.** Whether each person exists, each of their fields, whether each edge
  exists and its subtype are each a value stamped with a clock reading; an operation can only replace
  a register with a later reading. That is what makes order and repetition irrelevant. Exact ties
  between two readings (which a correct phone never produces) are broken by comparing values, so even
  those resolve identically everywhere.
- **Tombstones are kept** (compacted only when every member has acknowledged them). A person's edges
  are not tombstoned with them; an edge whose endpoint is deleted is simply not shown, and returns if
  the person is restored.
- **No automatic de-duplication.** If two phones each add "Raj" at once, the tree gets two Rajs. The
  existing duplicate matching proposes a merge. *Deferred:* recording that merge as an operation
  (`person.merge(from → into)`, rewriting edges and keeping an alias so later operations on the old id
  still land) needs its own design and is not in the core yet.
- **Symmetric edges** keep the canonical id order already used for `SPOUSE`/`SIBLING`; building an
  `EdgeKey` out of order is an error, and `EdgeKey.of` sorts.

### Guardrails: worked out in the view, not at apply time

The first draft said an operation that would break an invariant is held when it arrives. That cannot
converge: whether it breaks something depends on what else the phone has heard so far, so two phones
would hold different things. Instead every operation is applied to the registers unconditionally, and
the **view** (a pure function of the registers, sorted so equal states compare equal) decides what is
shown:

- A person is shown if their existence register says so. Edits stamped **after** a delete are listed
  in the inbox as `EditAfterDelete`; restoring the person brings them back with those edits.
- An edge is shown if it exists and both people are shown. A deleted endpoint hides it quietly. An
  endpoint this phone has never heard of hides it and lists it as `RelationshipHeld(MISSING_PERSON)`,
  and it appears by itself when that person's operation arrives.
- If the shown parent edges contain a loop, the **newest** edge in the loop is hidden and listed as
  `RelationshipHeld(CYCLE)`, repeating until none remain. The search runs over sorted ids so every
  phone breaks the same loops the same way.

Operations that are **malformed** (blank ids, a relationship to oneself, a gender that is not a gender,
a date that is not a date, an oversized name) are refused with a reason and leave no trace. Operations
stamped more than a day ahead of the phone's clock are `Deferred`, not applied: keep them and offer them
again later. `apply` returns `Applied`, `NoChange`, `Deferred` or `Invalid(reason)`.

Not yet a rule: "a second biological mother". The core does not know the app's local relationship
limits; if the app refuses something locally, the matching view rule must be added here so other
phones agree.

### The bridge to the database (also pure, `EditBridge.kt`)

Between the registers and Room sit two pure pieces, so the risky logic is tested without a device:

- **`EditRecorder`** turns what the user did into operations. `updatePerson` hands over a whole row, so it
  is diffed against the row as it was **before** the edit (read in the same transaction) and only the
  fields that changed are sent. "Keep as unknown" is exactly a diff against an empty person. Each
  operation gets its own clock reading.
- **`Projection.plan(replica, db)`** turns the view into the list of insert / update / delete steps that make
  the `people` and `relationships` tables match, in an order that is safe to run as written. It only ever
  deletes what the replica **knows about**: a person or edge the replica has never heard of (a relative not
  shared yet) is left alone.

Rules the Room glue must follow, each pinned by the simulation in `EditBridgeTest` (three phones with
skewed clocks making random edits and exchanging scrambled, repeated, partial batches):

1. **Fold every received reading into the local clock** (`HybridClock.observe`) before the next local
   edit. Without it a phone's own edits can sort *before* what it has already seen and silently lose.
2. **Run the projection after every received batch**, in one transaction, with recording switched off, so
   applying someone else's change is never sent back out as your own.
3. **A local edit can change more than that edit.** If a loop of parents had an edge held back, removing
   another edge of the loop releases it, and the database gains that edge. This is the view doing its job,
   and the UI should be ready for it; the test allows exactly that and nothing more.
4. **Relationship row ids are local.** The sync identity of an edge is `(type, from, to)`; two phones will
   hold different `relationships.id` values for the same edge.

## Wire

Uses the existing envelope and the NIP-17 gift-wrap path, with **no version bump**: `tree-edit` is
already a known type in envelope v1, and builds without tree sync accept and ignore it (a test pins that),
so mixed fleets keep working. Within the payload, an operation a build does not recognise is skipped and
counted without spoiling the rest of the batch (`TreeOpCodec.parsePayload`).

```
{"v":1,"type":"tree-edit","payload":{
   "tree":  "<shared tree id>",
   "ops":   [ {"id":"<op id>","hlc":"<time>.<counter>.<device>","op":"person.set",
               "person":"<id>","field":"name","value":"Asha"}, ... ]
}}
```

- The clock reading travels as a **text** stamp, not a number, so no reader can round a millisecond clock.
- Operation kinds: `person.add|set|delete|restore`, `edge.add|remove|subtype`. A message carries at most 500.
- Delivery is **idempotent because the merge is** (re-applying an operation changes nothing). The `id`
  exists for the log, acknowledgements and catch-up ("everything after this"), not for correctness.
- Operations are batched, because relays rate-limit and each wrap costs a signature.
- Only paired, tree-member keys are accepted for a tree id; everything else is dropped as today's handlers do.
- `SyncOutbox` already retries; the outbox rows gain the tree id.

### Catch-up and bootstrap

- **Bootstrap:** the sharer sends a `.ftree` snapshot over nearby sharing with a `sharedTreeId` and its
  clock high-water mark. The receiver imports it **without remapping ids** (the one place the importer must
  keep them), becoming a replica.
- **Catch-up:** a phone that has been away (or was recovered) sends `sync-request(tree, since = its
  high-water marks per device)`. Any online member answers with the operations it holds after that point,
  or, if too much has been compacted, a fresh snapshot. This is how a new member and a long-offline member
  converge without the relays remembering anything.

### Membership

A shared tree has a member list (keys). Add a member by pairing in person, then "Share this tree". Removing
a member stops publishing to them and stops accepting their operations; **they keep the copy they have**,
which cannot be taken back. v1 has no roles and no group re-keying.

## Schema (db v3 → v4, built)

Two new tables, created by `MIGRATION_3_4` (create-only, so nothing existing is touched; the SQL is the one
Room generated into `app/schemas/.../4.json`, and `KutumbDatabaseTest` checks a v3 database with people,
relationships and messages comes through whole):

- `tree_sync_meta(id = 1, sharedId, deviceId, lastHlc)`: **present only if the tree is shared**. Each tree
  has its own database, so one row is enough. Its absence is what "not shared" means; an unshared tree
  carries two empty tables and every sync method returns at once.
- `tree_sync_ops(opId PK, json, local, applied, publishedAt)`: the append-only log of every operation made
  or received. `applied = 0` holds an operation stamped too far ahead of the clock, to be tried again;
  `publishedAt` is null until a local operation has been sent.

The first draft also had tables for field clocks, tombstones, members and a high-water mark. They are gone:
the registers are **rebuilt from the log** when a tree is first used, so the log is the single source of
truth and nothing can drift from it. (Rebuilding is a replay, linear in the log; compaction is deferred.)
Members and per-device marks come back in the share and catch-up phases if they turn out to be needed.

### How writes are noticed: the reconciler, not hooks

The first draft hooked `FamilyRepository`'s write methods. The built version does not: `Reconciler` compares
the tables with the shared tree and records the difference. That covers every way the tables change (edit,
delete, "keep as unknown", import, the debug seeder, and the first share of an existing tree) with no hook to
forget, and it is idempotent, so a crash between a write and its recording is repaired by the next pass.
`TreeSyncCoordinator.start(scope)` registers a Room invalidation observer on `people` and `relationships`
and runs a pass after changes settle (and once at start-up). Photographs are not synced.

`TreeSyncCoordinator` (app module) is the only class that reads or writes the sync tables:
`share`, `reconcile`, `receive` (log, apply, then rewrite the tables with `Projection`, in one transaction),
`unpublished` / `markPublished`, `allOps`, `inbox`. Instrumented tests drive two phones' worth of real Room
databases through the real `FamilyRepository`.

## Phases

1. **Pure core** (`kutumb-core`): hybrid clock, operation types, the merge/reduce function, validation
   verdicts, the edit recorder, the projection and the reconciler. **Done.** JVM tests: convergence under
   random reorderings and duplicates, delete-vs-edit, a three-phone simulation with skewed clocks.
2. **Database and recording:** migration v4, the operation log, `TreeSyncCoordinator` (share, reconcile,
   receive, restart from the log, observer). **Done**, verified on the emulator; nothing in the app starts it
   yet, because no tree can be shared until phase 4.
3. **Wire:** send `tree-edit` batches from the log (`unpublished`) to paired members over the existing
   relay path, and hand inbound ones to `receive`. Add the tree id to the sync routing. *Next.*
4. **Share and catch-up:** the share flow (snapshot with the same ids), members, `sync-request`, compaction rules.
5. **Photos:** resized, chunked, encrypted events, or a nearby-only path. Depends on relay size limits.
6. **UI:** "Share this tree", members, sync status ("3 changes waiting"), the review inbox.

Phases 1–4 carry the risk and should land in that order; each is shippable behind a switch that is off by
default, like the existing sync.

## Risks and open questions

- **Clock trust:** a phone with a wildly future clock can win every field for a long time. Mitigation: the
  hybrid clock refuses to run ahead of the last seen value by more than a bound, and the app shows a warning.
- **Invariants vs convergence:** held operations mean two phones can differ until the inbox is cleared.
  This is deliberate (never silently drop a family's edit) but must be visible in the UI.
- **Compaction:** tombstones and logs grow. v1 can keep everything; compaction needs acknowledgement
  tracking and is deferred.
- **Relay reliability** (see NOTES.md): only nos.lol has carried messages end to end between the two
  phones so far. Real use will show whether the relay list needs hardening.
- **Mixed platforms:** the web viewer and desktop app cannot join a shared tree (they have no keys). They
  read an exported snapshot, so they go stale. A later option is a read-only snapshot refresh.
- **CLAUDE.md** is f-tree's contributor guide; its "open an issue first" rule is followed here by writing
  this document first.
