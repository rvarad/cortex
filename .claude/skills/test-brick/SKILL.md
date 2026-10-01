---
name: Test a brick
description: Write tests for a brick that has just been implemented, from the spec rather than the code. Run in a fresh session, never the one that wrote the implementation.
disable-model-invocation: true
allowed-tools: Read Grep Glob Edit Bash(mvn *)
---

# Test a brick

Run this in a **fresh session**, not the one that implemented the brick. A session
that wrote the code writes tests describing what the code does rather than what the
feature should do, and bugs get encoded as expected behaviour.

This skill is user-invoked on purpose. Firing in the implementing session would
undo the only thing the split exists for.

## Order matters

Do these in order. Do **not** read the implementation before step 3.

### 1. Read the spec, not the code

Open `docs/plans/<feature>.md`. Find the brick and read its **DoD**, the feature's
**Goal**, and **Not doing**. Read any ADR linked from the Decisions list that
touches this area. Read `CONTEXT.md` so test names use the project's words.

### 2. Propose edge cases from the spec alone

List the cases the DoD implies, including the ones it doesn't state: empty input,
absent optional data, the boundary of any window or limit, the path taken when a
dependency returns nothing, and the state after a failure partway through.

Present the list and **stop**. The user adds their own before any test is written.
This is the step the whole arrangement exists to protect — their cases are the ones
that come from knowing the system, and they can't be added after the fact.

### 3. Now read the implementation

Only now open the code, and only for what you can't get from the spec: real method
signatures, constructor dependencies, what's mockable. The cases are already fixed
by step 2 and do not get narrowed to fit what the code happens to do.

### 4. Name the seams before writing

A seam is the public boundary you test at. Say which ones you'll test and confirm
them. No test is written at an unconfirmed seam.

Test through public interfaces, never internals. Code should be able to change
completely without the tests changing.

### 5. Write the tests

Name each test as a sentence from the spec. Match the convention already in the
repo:

```
stream_emitsEachCitedSourceExactlyOnce_immediatelyBeforeTheSegmentThatCitesIt
emptyRetrieval_returnsDontKnow_andNeverCallsTheLlm
fileWithNoCompletedChunks_returnsEmpty_soChatCanSayItDoesNotKnow
```

`<condition>_<expected behaviour>_<why it matters>`. These names are what survives
when the plan doc is deleted at merge, so they carry the spec.

Read `ChatServiceTest`, `SearchServiceTest` and `SegmentParserTest` for the house
style before writing.

## After they pass

**Green on the first run proves nothing.** A test that has never failed is not
known to catch anything.

For each test guarding logic that actually matters, break the implementation
deliberately — flip a comparison, drop a null check, skip a branch — confirm the
right test goes red, then revert. Report any test that stayed green while the code
was broken, and say it's decoration rather than leaving it in.

Don't do this for every test. Do it for the ones whose failure you'd care about.

## When a test fails

Decide which side is wrong and say which:

- **The test is wrong** — fix it, and say that you did. A test you rewrote *after*
  watching it fail needs the user's eye on it: the question is whether it was wrong
  or merely inconvenient.
- **The code is wrong** — describe the failure precisely: what was asserted, what
  came back, and the smallest input that reproduces it. The user relays that to the
  implementing session. Do not fix the implementation here.

## Do not

- **Do not commit.** The implementing session commits the brick's code and its tests
  together, once green. Leave the working tree alone.
- Do not chase coverage. Untested code is better than a test that asserts nothing.
- Do not test framework behaviour — Spring's wiring, Jackson's serialisation,
  Lombok's getters.
- Do not add a test that can't fail.
