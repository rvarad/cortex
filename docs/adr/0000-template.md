# NNNN. <The decision, as a sentence>

**Status:** Accepted, YYYY-MM-DD
**Verified by:** <optional — how it was checked: read the source jar, timed one
real file, ran it five times>

<!-- Later, replace Status with "Amended YYYY-MM-DD" or
     "Superseded by ADR-NNNN, YYYY-MM-DD". Never renumber a file. -->

## The question

What was actually being decided, in one or two sentences, written so someone who
wasn't there understands what was at stake.

## Decision

What was chosen. Plain and short.

## Why

The constraint or reasoning that forced it. This is the section that has to
survive; everything else is scaffolding around it.

## Alternatives rejected

Each with the evidence that killed it, not just a preference.

- **<Alternative>** — <what was found, with the number or observation>

## What this costs

The honest downside. Delete the section if there genuinely isn't one, but be
suspicious of that.

## Invariants this creates

What must stay true from now on, written so a future session can check it and
notice when it's been broken.

## What would change our mind

Required when this decision is a deferral. A named, observable trigger — not
"when we have time". Delete the section if it isn't a deferral.

## Amendments

Appended, dated, never edited into the sections above.

### YYYY-MM-DD — <what changed>

What was learned, and what it changed. The original text stays.

<!-- Rules:
     - One decision per file. Numbers are permanent, even when superseded.
     - No code blocks. The code is in the repo; a copy here starts lying the day
       someone edits the real thing.
     - Write one when reversing the decision later would be expensive, OR when a
       reader would look at the code and think it's a mistake. Otherwise the code
       is the record.
-->
