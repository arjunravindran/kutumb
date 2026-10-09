# Joining a shared tree: proposal (2026-10-09)

Status: **proposal, nothing built.** Needs the owner's yes before code, because it adds two message types
to the wire and makes the app create a tree on its own (CLAUDE.md: wire and schema changes are discussed first).

## The problem

Sharing a tree works only when two phones already hold the same people with the same ids, which today only
the debug seeder arranges. A real person has no way to say "join Asha's tree". For readers who are not
comfortable with technology this is the step that decides whether tree sync is usable at all.

## What the person sees

**Asha (shares):** Settings > Your trees > Sharing. Her relatives paired in person are listed with a switch
each (this already exists). Switching one on now means "invite", and the row says *Invited, waiting for
Ravi to accept* until he does.

**Ravi (joins):** a note arrives like a message does: *"Asha shared the Ravindran family with you."* Tapping
it opens one plain screen: the tree's name, who shared it, how many people are in it, and two big buttons:
**Open it** and **Not now**. Open it creates a new tree on his phone, and the people appear as they arrive.
He never sees the words "sync", "relay", "replica" or "snapshot".

Joining **never merges silently**. If Ravi's own tree is empty or holds only himself, joining fills that
tree (nothing to decide). Otherwise it makes a **new** tree beside his, and nothing of his is touched.

**Then, for someone with a larger tree of their own (owner's idea, 2026-10-09):** the shared tree offers
*"Add the people from your other tree?"*. It runs his tree through the **existing import review**: people
who match someone in the shared tree (name, dates, relatives in common) take the shared tree's id, and
the rest are added as new people. Ravi confirms or rejects each match, as he would for a file. Sync then
spreads the result like any other edit, and he decides whether to keep or delete his old tree. This needs
no `person.merge` and no alias: a match is resolved *before* anything enters the shared tree, so there is
only ever one id for a person. It is a second step, built after joining works.

## How it works (smallest version)

1. **`tree-invite`** (new envelope type, sent by Asha through the existing gift-wrap path to a contact paired
   in person): `{tree: <shared id>, name, people: <count>}`. Old builds ignore unknown types, as today.
2. Ravi's phone stores it as a pending invitation (a small table; see "What it touches") and shows the note. Invitations from anyone not paired in person are
   dropped, the same rule as edits.
3. **Open it** creates the tree through `TreeCatalog` (one database per tree, kind Family), marks it shared
   with that id, adds Asha as a member, and sends **`tree-join`** `{tree}` back to her.
4. Asha's phone, on `tree-join` from someone she invited, adds them as a member and calls
   `sendEverythingTo` (built). Ravi's replica fills in from the operations, which carry the same person ids by
   construction.
5. From then on it is the sync that already exists.

Only a trusted, in-person contact can invite or be invited, and only for a tree id that the inviter really
has shared. `Not now` just drops the invitation; Asha can invite again.

## What this deliberately leaves out

- **Nearby snapshot for big trees.** `sendEverythingTo` is many messages for a very large tree (about one
  message per 100 operations). That is slow but correct, and it needs no new code. A nearby snapshot is an
  optimisation to add only if real trees turn out to be too slow to join.
- **Catch-up for a long-away phone** (`sync-request`): a separate piece, listed in HANDOFF B1.
- **Photos** (phase 5).

## What it touches

- Wire: two new envelope types. No version bump; older builds drop them.
- Schema: one small table for pending invitations (Room v5, create-only migration), or no schema change if
  invitations are held in preferences; I prefer the table, so a restart cannot lose one.
- UI: one invitation screen and a state on the Sharing row. No new tab.
- Tests: JVM tests for invite and join validation (who may send, who is ignored, repeated messages), a
  coordinator test with two real databases joined by the existing fake relay (Asha shares, Ravi accepts,
  both trees match), and one instrumented test for the invitation screen.

## Decisions and open questions

1. **Decided:** joining does not merge. Empty tree: filled directly. Otherwise a new tree, plus the later
   "add the people from your other tree" offer above.
2. Open (proposed default): the relay path alone is enough for the first release; the nearby snapshot waits
   until large trees prove too slow.
3. Open (proposed default): Ravi is asked ("Open it" / "Not now") before the tree is created.
