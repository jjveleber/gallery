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

Two different apps can send Open with on the Fire, and they use different URI forms.

Fire built-in Files app:

- sends a document URI: isDocumentUri `true`
- documentId looks like `primary:folder/xyz.gif` (`<volume>:<relative path>`)
- no `_data` column
- the authority was not captured and may not be `com.android.externalstorage.documents`

The resolver handles this through `locationFromDocumentId`, which accepts any document authority whose ID has the `<volume>:<relative path>` shape.

ES File Explorer (a separate app):

- scheme: `content`
- authority: `com.estrongs.files`
- isDocumentUri: `false`
- documentId: none
- the query returns a `_data` column holding the absolute file path (e.g. `/storage/emulated/0/Pictures/<name>.jpg`)

The resolver handles this through the `_data` column and `locationFromPath`.
