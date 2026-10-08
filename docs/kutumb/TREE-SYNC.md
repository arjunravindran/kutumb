# Tree sync: design (draft, 2026-10-08)

Status: **design only, nothing built.** CLAUDE.md asks for an issue or discussion before changes to the
database schema or the wire; this document is that discussion. It changes the schema (db v3 → v4) and
adds to the sync wire.

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
| `rel.add` | adds an edge `(from, to, type, subtype)` | set semantics, deduplicated by the existing unique key |
| `rel.remove` | tombstones an edge | removal wins over an add with an earlier clock; a later add re-creates it |
| `rel.set` | changes an edge's subtype | last write wins by clock |

Rules that follow:

- **Tombstones are kept** (compacted only when every member has acknowledged them). Deleting a person
  tombstones its edges too, so a late `rel.add` to a deleted person is held, not applied.
- **No automatic de-duplication.** If two phones each add "Raj" at once, the tree gets two Rajs. The
  existing duplicate matching proposes a merge; a merge becomes an operation `person.merge(from → into)`
  that rewrites edges and records an alias, so later operations on the old id still land.
- **Symmetric edges** keep the canonical id order already used for `SPOUSE`/`SIBLING`.

### Guardrails (apply-time validation)

An operation that would break an invariant is **held in the review inbox, not applied**, and never dropped:
a parent loop (A is an ancestor of A), an edge to a missing or deleted person, a second biological mother
(whatever the app already refuses locally), or a field value that fails validation (a partial date that is
not a date). The inbox shows what the other person tried to do and offers apply-anyway or dismiss. The pure
core returns a verdict per operation: `Applied`, `Duplicate`, `Held(reason)`.

## Wire

Reuses the envelope and the NIP-17 gift-wrap path; the envelope version is bumped so older apps skip
messages they cannot read (they already do for unknown versions).

```
{"v":2,"type":"tree-edit","payload":{
   "tree":  "<shared tree id>",
   "ops":   [ {"id":"<op id>","hlc":"<time>.<counter>.<device>","op":"person.set", ...}, ... ]
}}
```

- `id` makes delivery **idempotent**; the receiver keeps the ids it has applied.
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

## Schema (db v3 → v4)

New tables, nothing destructive:

- `shared_trees(treeId, sharedId, createdAt)`, `tree_members(sharedId, pubkey, addedAt)`
- `sync_ops(opId PK, sharedId, hlc, deviceId, kind, payload, applied, heldReason)` , the operation log
- `field_clocks(personId, field, hlc)` and `tombstones(entity, id, hlc)`, so the merge can compare without
  replaying the whole log
- `sync_state(sharedId, deviceId, highWater)`

Every local write already goes through `FamilyRepository`; that is where operations are **recorded**.
Anything that writes the database another way (import, the debug seeder) must be routed through, or marked
as a snapshot event, so no change is silent. Finding every such path is the first task of phase 2.

## Phases

1. **Pure core** (`kutumb-core`): hybrid clock, operation types, the merge/reduce function, validation verdicts.
   JVM tests: convergence under random reorderings and duplicates, delete-vs-edit, edge cases above. *No
   Android, no database.*
2. **Database and recording:** migration v4, record every local edit as an operation, field clocks,
   tombstones instead of hard deletes for shared trees. Instrumented tests.
3. **Wire:** envelope v2, send from the outbox, `TreeSyncHandler` applies inbound (idempotent), the review
   inbox table.
4. **Share and catch-up:** the share flow (snapshot with ids), `sync-request`, compaction rules.
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
