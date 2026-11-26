# Upload progress

**Status:** All three bricks are done and passed in the browser (2026-11-26),
including brick 1's recheck after the `development` merge. Progress shows on the
tile itself; the panel was dropped (option E on the mock-up canvas).
**Branch:** feature/frontend-implementation
**Started:** 2026-10-29

## Goal

A user who uploads a file sees its progress on the file's own tile: the status
chip fills left to right with the percent, and the tile shows bytes sent of the
total. They can keep using the app while it runs, and cancel from the tile. A
cancelled or failed upload leaves nothing behind, and a failure says so.

## Not doing

- **Progress anywhere but the dashboard.** The upload keeps running on other pages,
  but nothing shows it there. A small "Uploading · 48%" badge by the navbar's
  Dashboard link is the fix if it's missed.
- **Surviving a reload or a closed tab.** A reload kills the XHR; the leave
  warning only stops it happening by accident. Continuing after a reload needs GCS
  resumable uploads: the backend signs a `POST` with `x-goog-resumable: start`
  instead of a `PUT`, `gcs-cors.json` exposes `Location`, and the client sends
  256 KiB-aligned parts. Build it if large uploads fail on real networks.
- **Retry after a failure.** The toast says it failed; the user uploads again.
- **Picking several files in one dialog.** The dialog still takes one file. Several
  uploads come from opening it again; each gets its own tile.
- **Uploading from anywhere but the dashboard.** The dialog stays where it is.
- **A cap on parallel uploads.** The browser already limits connections per host.

## Decisions

- **How is progress measured?** — `XMLHttpRequest` with `upload.onprogress`.
  `fetch` reports nothing while it sends a body; a streamed `fetch` body is
  Chrome-only, needs HTTP/2, and still counts bytes by hand. Axios is XHR inside.
- **Where is it shown?** — On the file's tile, not a panel. The progress sits with
  the file it belongs to, and a panel would have needed a toast offset and a way
  to stay clear of the chat input. Tried as a plain panel in brick 1 and dropped.
- **What does the tile show while uploading?** — The status chip reads
  "Uploading · 48%" and fills left to right with the percent, in a solid,
  translucent light blue; a lighter wave runs left to right across the filled
  part (off under `prefers-reduced-motion`). A failure toast stays 6 s. Where
  Play and Chat go, "470 MB of 980 MB". The menu is hidden; in its place, on hover
  or focus, a cancel ✕.
- **Where does the upload state live?** — In `UploadsProvider` in the root layout,
  so an upload keeps running, and its tile picks it up again, across navigation.
- **Which PENDING files does the dashboard show?** — Only ones this tab's provider
  owns (uploading, or landed and waiting on the webhook). Any other PENDING row is
  an upload that never landed; the server's sweep deletes it (ADR-0016). The
  provider's list replaces the `landedIds` that came with the merge.
- **What does cancel do?** — Aborts the XHR, then the existing
  `DELETE /files/{id}`. Since the merge that only marks the file: the dashboard
  hides it, the webhook won't start a deleted file (`markUploaded` refuses), and
  the sweep removes the row and any object that landed. So an abort that lands
  after GCS has the object is safe.
- **What does a failure do?** — The same delete, plus an error toast naming the
  file. Without the toast the tile would just vanish. Cancel shows no toast: the
  user did it.
- **What happens to the success toast?** — Removed; the tile turning from
  Uploading to Processing says it. The size, type and duration checks before the
  upload still toast from the dialog.
- **Does the browser warn before leaving?** — Yes, the built-in "Leave site?"
  prompt while any upload is running.

## Open questions

None.

#### 1. Uploads move to a provider, with XHR progress — ✅ DONE

`uploadToGcs` sends with XHR and takes a progress callback and an `AbortSignal`.
`UploadsProvider` in the root layout owns the uploads: start (signed URL, then
send), progress, done, failed; a failed send deletes the file. The dialog hands
its file over and closes at once. The dashboard refetches on the provider's
`listVersion` and shows the PENDING files the provider owns. The plain panel from
this brick is removed in brick 2.

**DoD:** Checked 2026-11-26 before the merge: the dialog closes on click, the tile
appears at once, the percent climbs to 100, and navigating to `/chat` and back
doesn't disturb it. After the merge, recheck that a finished upload's tile turns
from PENDING to Processing within 5 s of its webhook.

#### 2. The uploading tile, cancel, and the failure toast — ✅ DONE

`FileCard` asks the provider whether it owns an upload for this file. If one is
running: the filling chip with its pulse, bytes sent of total, no menu, and a
cancel ✕ top-right on hover or focus. The provider gains `cancel`, drops a
cancelled or failed upload from its list, and toasts a failure. The panel goes.

**DoD:** On "Fast 4G", upload ~100 MB. The chip fills and pulses, the bytes climb,
and the ✕ appears on hover and on Tab. Cancel at ~30%: the `PUT` is cancelled, the
`DELETE` answers 204, the tile disappears, no toast. Upload again and cut the
network: a toast names the file, the tile disappears. With "Reduce motion" on in
the OS, the chip fills but doesn't pulse.

#### 3. Leave warning — ✅ DONE

A `beforeunload` handler is set while any upload is running.

**DoD:** Reload mid-upload: the browser asks before leaving. Reload after all
uploads finish: no prompt.

## Done when

1. Upload a large file on a throttled network: the dialog closes, the tile appears
   with its chip filling and the bytes climbing.
2. Navigate around the app and come back: the tile is still counting.
3. Start a second upload: two tiles, each with its own progress.
4. Cancel one: its tile disappears, and the sweep later leaves nothing of it in
   the database or the bucket.
5. Let the other finish: its chip turns to Processing and its pipeline runs.
6. Start a third and cut the network: a toast names it and its tile disappears.
7. Reloading during an upload asks first; after it, it doesn't.
