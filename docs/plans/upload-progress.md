# Upload progress

**Status:** Brick 1 is written and waiting on its DoD check in the browser.
Bricks 2–5 not started.
**Branch:** feature/frontend-implementation
**Started:** 2026-10-29

## Goal

A user who uploads a file sees its progress in a panel in the bottom-right corner,
in bytes and percent, and can keep using the app while it runs. They can cancel
an upload, and a cancelled or failed upload leaves nothing behind. Several uploads
can run at once, each with its own row. On the dashboard, a file being uploaded
shows as a dimmed tile that turns normal once the server has the file.

## Not doing

- **Surviving a reload or a closed tab.** A reload kills the XHR; the leave
  warning only stops it happening by accident. Continuing after a reload needs GCS
  resumable uploads: the backend signs a `POST` with `x-goog-resumable: start`
  instead of a `PUT`, `gcs-cors.json` exposes `Location`, and the client sends
  256 KiB-aligned parts. Build it if large uploads fail on real networks.
- **Retry after a failure.** The row shows the error; the user uploads again.
- **Cleaning up a file whose tab was closed mid-upload.** Its row stays `PENDING`
  with no object behind it, and the browser that could clean it up is gone. It
  shows as "Upload didn't finish" for the user to delete (brick 3). A server job
  deleting `PENDING` rows older than the signed URL's lifetime would remove it
  without the user; not built.
- **Cancel at the last moment.** If the abort lands after GCS has the whole object
  but before the browser hears back, the row is deleted and the object stays: the
  webhook throws "File metadata not found" and nothing removes the object. The
  window is the length of one response.
- **Upload percent on the tile.** The tile only says it is uploading; the numbers
  are in the panel.
- **Picking several files in one dialog.** The dialog still takes one file. Several
  uploads come from opening it again; the panel handles many rows.
- **Uploading from anywhere but the dashboard.** The dialog stays where it is; only
  the progress moves out of it.
- **A cap on parallel uploads.** The browser already limits connections per host.

## Decisions

- **How is progress measured?** — `XMLHttpRequest` with `upload.onprogress`.
  `fetch` reports nothing while it sends a body; a streamed `fetch` body is
  Chrome-only, needs HTTP/2, and still counts bytes by hand. Axios is XHR inside.
- **Where is it shown?** — A panel in the bottom-right corner, not the dialog. The
  dialog closes as soon as the upload starts, so the user isn't made to wait on it.
- **Where does the upload state live?** — In a provider in the root layout, next to
  `MediaPlayerProvider`, so an upload keeps its row across navigation.
- **What happens to a finished row?** — It shows ✓ and stays until the user closes
  the panel. The header's close button appears only when nothing is still
  uploading.
- **What does cancel do?** — Aborts the XHR, then calls the existing
  `DELETE /files/{id}`. That works on a `PENDING` file: `FilesService.deleteFile`
  removes the rows, and `GcsStorageService.deleteObject` doesn't throw when the
  object was never written. No backend change.
- **What does a failed upload do?** — The same delete as cancel. The row in the
  panel stays to show the error; the file itself is gone.
- **How does the dashboard show an upload?** — The file row exists as `PENDING`
  from the moment the signed URL is made, so the tile can show then. It is dimmed
  while `PENDING` and turns normal at `UPLOADED`. The dashboard's existing 5 s poll
  already treats `PENDING` as unsettled, so it picks up `UPLOADED` with no new
  signal. A cancelled or failed upload is deleted, so its tile disappears on the
  next list fetch.
- **Can an uploading tile be deleted or renamed?** — No; it has no actions. A delete
  mid-upload would let the object land with no row behind it (see "Cancel at the
  last moment"). Cancel lives in the panel.
- **What is a `PENDING` tile no upload owns?** — A leftover from a closed tab or a
  reload. A tile is "uploading" only while this browser's provider has an active
  upload for its `fileId`; any other `PENDING` tile shows "Upload didn't finish"
  with Delete as its only action, and counts as settled so it stops the poll. A
  file being uploaded from another tab or device shows this way too, wrongly.
- **Where do toasts go?** — They stay bottom-right, raised by the panel's height
  so they sit above it rather than under it.
- **What about the chat input?** — The panel is collapsible, and it collapses
  itself when the user opens a chat route. Expanded, it covers the right end of
  the chat input on a narrow window. The user can expand it again.
- **Does the browser warn before leaving?** — Yes, the built-in "Leave site?"
  prompt while any upload is running.
- **What happens to the success toast?** — Removed; the row's ✓ says the same
  thing. Upload errors go in the row, not a toast. The size, type and duration
  checks before the upload still toast from the dialog.

## Open questions

None.

#### 1. Uploads move to a provider, with XHR progress — 🚧 IN PROGRESS

`uploadToGcs` sends with XHR and takes a progress callback and an `AbortSignal`.
A new `UploadsProvider` in the root layout owns the list of uploads: start (ask
for the signed URL, then send), progress, done, failed. A failed send deletes the
file. `FileUploadDialog` hands its file to the provider and closes at once; its
unused `onFileUploaded` prop goes. A plain, unstyled panel lists each upload with
its percent. The provider tells the dashboard when an upload starts and when it
ends, so it fetches the list then; the dialog is no longer around to call
`onUploadComplete`.

**DoD:** With DevTools throttled to "Fast 4G", upload a file of ~100 MB. The dialog
closes on click, its tile appears at once, and the row's percent climbs to 100.
Go to `/chat` and back while it runs: the row is still there and still climbing.
When it finishes, the file's pipeline starts. Upload another and turn the network
off mid-way: the row shows a failure, the Network tab shows the `DELETE` answered
204, and the tile is gone.

#### 2. Cancel — ⬜

A cancel button on each uploading row aborts the XHR, then deletes the file.

**DoD:** Cancel at ~30%. The Network tab shows the `PUT` cancelled and then the
`DELETE` answered 204. The tile is gone from the dashboard, its `file_metadata`
row is gone, and its `uploads/` object isn't in the bucket. No pipeline events
arrive for it.

#### 3. The tile while uploading — ⬜

`FileCard` dims a `PENDING` tile and shows "Uploading" in place of the status chip
and the playback slot, with no actions, while the provider owns its upload. A
`PENDING` tile the provider doesn't own shows "Upload didn't finish" with Delete
only. The dashboard's `isSettled` counts that leftover as settled.

**DoD:** Start an upload: the tile is dimmed, says "Uploading", and has no menu.
Within 5 s of the webhook setting `UPLOADED`, it is normal and shows "Processing".
Start another and reload mid-way (accept the prompt once brick 5 is in): the tile
shows "Upload didn't finish", the Network tab shows no more list polling for it,
and Delete removes it.

#### 4. The panel's look and states — ⬜

Header with the summary ("Uploading 2 files · 48%"), collapse to that line alone,
close when nothing is still uploading. Rows with type icon, truncated name,
progress bar, bytes sent of total, and a done or failed state. It collapses itself
on entering a chat route. On a phone-width screen it fits inside a 16px gutter.

**DoD:** With two uploads running, collapse and expand the panel; the header's
percent covers both. Open a chat: the panel collapses and the send button is
clear. Let both finish: ✓ on both, close appears, and closing hides the panel.
Turn the network off mid-upload: that row shows a failure and the other row
carries on.

#### 5. Toast offset and leave warning — ⬜

The `<Toaster>` is raised by the panel's height while the panel shows, and drops
back when it closes. A `beforeunload` handler is set while any upload is running.

**DoD:** Fire a toast (for example, pick an unsupported file type) with the panel
open, then with it collapsed: the toast sits above the panel both times. Reload
mid-upload: the browser asks before leaving. Reload after all uploads finish: no
prompt.

## Done when

1. Upload a large file on a throttled network: the dialog closes, a dimmed tile
   appears, and the panel shows bytes and percent climbing.
2. Navigate around the app while it runs: the row keeps updating.
3. Open a chat: the panel collapses and the input is clear.
4. Start a second upload: two rows, and the header totals both.
5. Cancel one: its tile disappears, and nothing is left of it in the database or
   the bucket.
6. Let the other finish: ✓ in the panel, the tile turns normal, its pipeline runs.
7. Start a third and cut the network: the row shows the failure and the tile
   disappears.
8. Start a fourth and reload, accepting the prompt: its tile shows "Upload didn't
   finish", and Delete removes it.
9. A toast shown while the panel is up sits above it.
