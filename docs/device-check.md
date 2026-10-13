# Device check (Fire Max 11)

Date: 2026-10-13

Build: GitHub pre-release `device-check-1`, installed on the Fire Max 11.

| Check | Result |
|---|---|
| anim.gif / anim.png / anim.webp animate, no trails | pass |
| Animate off shows still frames | pass |
| rotated.jpg upright | pass |
| Zoom inside Telephoto (static and animated) | pass, except still.bmp: the right-hand Telephoto `ZoomableAsyncImage` box stays black (the plain `AsyncImage` box shows it fine). Same on the emulator. To be fixed in the viewer task. |
| User's own animated WebP / GIF files (Open with) | pass |
| HEIC decodes | not tested (low priority). HEIC stays in the supported lists; the spec already covers undecodable files. |

## Open with URI from the Fire Files app

The Fire's Files app is ES File Explorer.

- scheme: `content`
- authority: `com.estrongs.files`
- isDocumentUri: `false`
- documentId: none

The query returns a `_data` column containing the absolute file path (e.g. `/storage/emulated/0/Pictures/<name>.jpg`). The full `uri=` line was not captured.

This is a provider exposing a `_data` path, which the resolver handles via `locationFromPath`.
