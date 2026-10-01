# <Feature name>

**Status:** Where this is right now, in two or three sentences. Rewritten each
time, not appended — the brick markers below carry the history.
**Branch:** feature/<name>
**Started:** YYYY-MM-DD

## Goal

What a user can do when this is finished. Two or three sentences, concrete enough
that "Done when" below can be checked against it.

## Not doing

What someone could reasonably assume is in scope and isn't. The most valuable
section in the document and the one nobody writes.

## Decisions

Settled choices, one line each. When a decision becomes expensive to reverse, or a
reader would mistake it for an oversight, it graduates to `docs/adr/` and this list
links to it rather than restating it.

- **<the question>** — <the choice>. <one-line why>
- **<the question>** — see ADR-0007

## Open questions

Not yet decided, and blocking a brick. Say which brick. Delete the entry when it's
answered; the answer moves up to Decisions or out to an ADR.

## Slices

A **slice** is one planning pass: the chunk you'd break into bricks in a single
planning session. The boundary falls where you don't yet know enough to plan
further — you have to build this part and find out. A feature small enough to plan
in one go has one slice and doesn't need the heading; list bricks directly.

A **brick** is one unit of work inside a slice, and one commit.

**Brick sizing:** as much code as you can read carefully, question, and correct in
one sitting. If you'd skim it rather than review it, it's two bricks. The review is
the point — a brick too big to review is a brick that doesn't get reviewed.

**Resuming:** tick the brick marker in the same commit as its code. A fresh session
can then read this file and pick up at the next ⬜. Do that deliberately when a
slice session gets long, not only when one falls over — by the fifth brick a
session is still carrying the first four, and quality slides quietly.

### Slice 1 — <name>

**When this slice is done:** <what works that didn't before>

#### 1. <brick name> — ✅ DONE YYYY-MM-DD

What it does, in a line or two.

**DoD:** How you know — checked before you commit, not after. Not "it compiles":
a log line, a curl, a thing you can watch happen. Written so someone else could
check it without asking you.

#### 2. <brick name> — 🚧 IN PROGRESS

#### 3. <brick name> — ⬜

### Slice 2 — <name>

**When this slice is done:** ...

## Done when

The feature-level check, as a numbered script someone can actually run. Distinct
from a brick's DoD ("can I commit this?") and from a slice's statement ("what works
now?"): this one asks "is the feature real?"

<!-- Rules:
     - This file dies at merge. Delete it in the final commit before squash-merge.
     - It is not an archive. Findings go straight to docs/findings/ as they happen —
       a fact is true whether or not this feature ships. Decisions graduate to
       docs/adr/ when they stop being live questions. If at merge you're staring at
       900 lines you don't know what to do with, graduation was being skipped.
     - No code blocks. The plan says which bricks exist and how you'll know each one
       works, not how to implement them.
-->
