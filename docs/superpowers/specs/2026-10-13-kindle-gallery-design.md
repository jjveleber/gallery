# Kindle Fire Image Gallery: Design

Date: 2026-10-13
Status: Approved for planning

## 1. Goal

A native Android image viewer for an Amazon Fire Max 11 that shows the images in a user-picked folder as a thumbnail grid and as a full-screen slideshow, and that opens images handed to it by other apps ("Open with"). View only, offline, personal use.

### Target device

- Amazon Fire Max 11 (13th gen), Fire OS 8 (Android 11 base, API 30)
- MediaTek Helio G99, 4 GB RAM, 2000x1200 display
- No Google Play Services: only AOSP, Jetpack/AndroidX and open-source libraries
- Distributed by sideloading an APK (same install routes as `video-editor`)

### v1 features

1. **Folder picker:** pick one folder per session; all images in it and its subfolders (recursive) form one flat set.
2. **Grid:** uniform square cells, image scaled to fit (no crop); 4 sizes switchable by menu or pinch; 5 sort orders with direction.
3. **Full-screen viewer:** swipe/tap navigation, zoom, optional auto advance at a chosen interval, loop at the end.
4. **Open with:** view an image from another app full screen and swipe through the other images in the same folder.
5. **Formats:** JPEG, PNG, WebP, GIF, BMP, HEIC/HEIF (if the device decodes it). Animated GIF, animated WebP and APNG play when animation is enabled for that view.
6. **Per-view animation settings:** grid and full screen each have their own on/off toggle.

### Out of scope for v1

Editing, deleting, sharing, remembered or multiple folders, EXIF date-taken sort, AVIF, SVG, RAW, video files.

## 2. Tech stack

- Kotlin, Jetpack Compose (Material 3), Navigation Compose, single Gradle module
- minSdk 30, targetSdk 34, Java 17 (same build setup as `video-editor`)
- **Coil 3** (`io.coil-kt.coil3`): image loading, memory/disk cache, static decoding, thumbnails
- **APNG4Android** (`com.github.penfeizhou.android.animation`: `apng`, `awebp`, `gif`; Apache 2.0): decodes all animated formats. Chosen because the framework decoder cannot animate APNG and has known rendering bugs with some animated WebP files (blend/dispose modes); one library gives consistent behavior for all three.
- **Telephoto** (`me.saket.telephoto`, Apache 2.0, Coil integration): pinch, double-tap zoom and pan inside a swipe pager
- AndroidX DataStore (Preferences) for settings
- kotlinx.coroutines / Flow
- JUnit + kotlinx-coroutines-test (JVM), AndroidX Test (instrumented)

Alternatives considered: Glide + the APNG4Android Glide plugin (Glide's Compose support is beta and awkward), Fresco (no APNG, heavy, poor Compose fit).

## 3. Core model

```
ImageItem  = uri, name, relPath, sizeBytes, modifiedMs, mime
SortOrder  = Name | Modified | Path | Size | Random
SortSpec   = order, ascending, randomSeed
Settings   = sort: SortSpec, gridSize: S|M|L|XL, animateGrid, animateFullScreen,
             autoAdvance, intervalSec, transition: Crossfade|None
```

- `relPath` is the path of the image relative to the picked folder (for example `2024/trip/img_01.jpg`).
- **Name:** natural order, case-insensitive (`img2` before `img10`); ties broken by `relPath`.
- **Path:** by `relPath` with natural ordering per path segment, which groups images by subfolder.
- **Modified / Size:** by value, ties broken by name.
- **Random:** shuffle using `randomSeed`, so the order stays stable across recomposition, rotation and returning from the viewer. "Reshuffle" picks a new seed.
- Direction applies to every order except Random.
- Intervals: 3, 5, 10, 15, 30, 60, 120, 300 seconds.
- Defaults: sort Name ascending, grid size M, animateGrid off, animateFullScreen on, autoAdvance off, interval 5 s, transition Crossfade.

Settings persist across launches. The picked folder does not: the app always starts at the picker and takes no persistable URI grants.

## 4. UX

Works in landscape and portrait; rotation keeps the current image and scroll position.

### 4.1 Picker screen (start screen)

- App name, a large **Pick folder** button, one line of help text.
- Opens `ACTION_OPEN_DOCUMENT_TREE`. The tree grant is used for this session only.
- While scanning: spinner, live count ("Found 1,240 images…"), Cancel.

### 4.2 Grid screen

- Top bar: folder name, image count, **Play**, **Sort** menu (5 orders, ascending/descending toggle, **Reshuffle** when Random), **Size** menu (S/M/L/XL), **Settings**, **Change folder** (back to picker).
- Uniform square cells on a dark background, image scaled to fit (letterboxed within the cell). Column count derives from a target cell size per preset, roughly 12 / 8 / 5 / 3 columns in landscape on the Fire.
- Pinch in/out snaps to the next smaller/larger preset; the choice is saved.
- If animateGrid is on, visible animated thumbnails animate (lazy grid only composes visible cells). If off, they show the first frame plus a small "ANIM" badge.
- Returning from the viewer scrolls the grid to the last image viewed.
- Empty result: "No supported images in this folder" with a Change folder button.

### 4.3 Viewer (full screen)

Used for both slideshow and Open with.

- Black background, image scaled to fit, immersive mode (system bars hidden).
- **Navigation:** horizontal swipe pager; tap the left or right edge (outer 15% of width) for previous/next. Loops from last to first and first to last.
- **Zoom:** pinch, double-tap, pan while zoomed (Telephoto). Swiping pages only when not zoomed.
- **Overlay:** tap the middle to toggle. Shows back, file name, position "12 / 340", play/pause, interval, settings. Auto-hides after 3 s.
- **Auto advance:** when running, advances every `intervalSec`. Manual navigation resets the timer. Paused while zoomed, while the settings sheet is open, and while the app is in the background. Animated images use the interval only (no wait for the loop to finish).
- **Entry:** Play from the grid starts at the first image with auto advance on (and saves autoAdvance = on). Tapping a thumbnail opens at that image with the saved autoAdvance value. The play/pause button toggles and saves it.
- **Transition:** Crossfade (about 400 ms) for auto advance; swipes use the normal pager motion. None disables the fade.
- Screen stays on (`FLAG_KEEP_SCREEN_ON`) while auto advance is running.
- **Animation:** if animateFullScreen is on, animated images play; otherwise the first frame shows.
- **Back:** returns to the grid; in Open with mode, finishes the activity.

### 4.4 Open with

- The manifest declares an `ACTION_VIEW` intent filter for `image/*` (category DEFAULT, BROWSABLE not needed).
- On open, the app requests `READ_EXTERNAL_STORAGE` (and declares `READ_MEDIA_IMAGES` for API 33+). If granted, `OpenWithResolver` maps the URI to a folder and queries MediaStore for the image files in that same folder (not recursive), sorted by the saved sort, and opens the viewer at the incoming image.
- **Fallback** (permission denied, or the URI cannot be mapped to a folder): the viewer shows the single image, with a "Browse this folder" chip in the overlay. The chip opens `ACTION_OPEN_DOCUMENT_TREE` with `EXTRA_INITIAL_URI` set to the image's folder when known. After the user picks, the viewer shows that folder's images (not recursive), positioned at the incoming image if it is found there.
- Denying the permission is not re-prompted in the same session; it is asked again on the next Open with (Android's own "don't ask again" rules still apply).

### 4.5 Settings sheet

Bottom sheet, opened from the grid top bar or the viewer overlay:

- Grid: Animate thumbnails
- Full screen: Animate images, Auto advance, Interval, Transition

Sort and size are set from the grid menus, not here.

## 5. Architecture

| Package | Responsibility | Depends on |
|---|---|---|
| `model` | `ImageItem`, `SortSpec`, `Settings`; natural-order comparator; `sorted(items, spec)` | nothing |
| `scan` | `FolderScanner`: recursive walk of a tree URI using `DocumentsContract.buildChildDocumentsUriUsingTree` queries (name, mime, size, last modified), emitting progress; keeps supported types only. `OpenWithResolver`: incoming URI to folder (externalstorage document IDs `primary:Pictures/x.jpg`, MediaStore URIs via `RELATIVE_PATH`, `file://` paths), then MediaStore sibling query; returns `Resolved(items, startIndex)` or `Unresolvable(folderHint?)` | model |
| `imaging` | `AnimationSniffer`: classifies header bytes as animated GIF (more than one frame), animated WebP (`VP8X` animation flag / `ANIM` chunk), APNG (`acTL` before `IDAT`), or static. Coil `ImageLoader` factory. `AnimatedDecoder` (Coil `Decoder`): when the request allows animation and the sniffer says animated, decodes with APNG4Android into an animated drawable; otherwise defers to Coil's static decoding. Request size: screen size for the viewer, cell size for the grid | Coil, APNG4Android |
| `settings` | `SettingsRepository` over DataStore: `Flow<Settings>`, update functions; pure mapping between `Settings` and preference keys | model |
| `slideshow` | `AutoAdvanceTimer`: coroutine-based; `start`, `stop`, `reset`, `pause(reason)` / `resume(reason)`; emits advance events | nothing |
| `ui` | `PickerScreen`, `GridScreen`, `ViewerScreen`, `SettingsSheet`; activity-scoped `GalleryViewModel` holding scan state, the sorted item list, current index and settings | all above |

### Supported types

Recognized by MIME type, falling back to extension when the provider reports `application/octet-stream`: `image/jpeg`, `image/png`, `image/webp`, `image/gif`, `image/bmp`, `image/heic`, `image/heif`. Everything else is skipped.

### Data flow

```
Pick folder
  -> FolderScanner (Dispatchers.IO, progress Flow)
  -> GalleryViewModel.items
  -> combine(items, settings.sort) -> sorted list
       -> GridScreen (LazyVerticalGrid)
       -> ViewerScreen (HorizontalPager over the same list, start index)

Open with (ACTION_VIEW)
  -> permission check -> OpenWithResolver
  -> Resolved: GalleryViewModel in open-with mode -> ViewerScreen at startIndex
  -> Unresolvable: ViewerScreen with single item + "Browse this folder"

Settings change -> SettingsRepository (DataStore) -> Flow<Settings> -> UI recomposes
```

Process death returns the app to the picker (the folder is not persisted), which matches the "nothing remembered" decision.

## 6. Error handling

| Case | Behavior |
|---|---|
| File fails to decode (corrupt, unsupported codec such as HEIC on a device without it) | Grid cell shows a broken-image icon; viewer shows "Can't display <name>"; auto advance continues |
| Folder deleted or storage removed during the session | Toast "Folder no longer available", return to picker |
| Storage permission denied for Open with | Fallback (single image + Browse this folder), no repeat prompt that session |
| Open with URI not mappable to a folder | Fallback |
| Very large folder (10k+ images) | Scan streams progress; grid is lazy; sort runs once in memory after the scan |
| Very large image (for example 12000x8000) | Downsampled to the request size; never decoded at full resolution |
| APNG4Android fails on an animated file | Fall back to the static first frame through Coil |

## 7. Testing

### JVM unit tests (written test-first)

- `model`: natural comparator (digits, case, leading zeros, ties); every sort order in both directions; Random stable for a seed and different for another seed.
- `imaging`: `AnimationSniffer` on synthetic header bytes: static and animated GIF, static and animated WebP, PNG vs APNG, truncated and empty input.
- `scan`: `OpenWithResolver` URI/path parsing (externalstorage document IDs, MediaStore URIs, `file://`, unknown providers); supported-type filter including the extension fallback.
- `settings`: `Settings` to preferences and back, defaults for missing keys.
- `slideshow`: `AutoAdvanceTimer` with a test dispatcher: ticks at the interval, reset on manual navigation, pause and resume with overlapping reasons.
- `ui`: `GalleryViewModel` scan-to-sorted-list flow and index handoff, using a fake scanner.

### Instrumented tests

- `FolderScanner` against a temporary folder tree (nested subfolders, mixed supported and unsupported files) to check recursion and filtering.
- Decoder paths on bundled assets: an animated WebP with blend/dispose frames, an APNG, an animated GIF, and static versions of each; assert animated output when animation is allowed and a static bitmap when it is not.

### Plan task 1: on-device check (Fire Max 11)

Before building the UI, a throwaway screen that loads the bundled animated WebP, APNG and GIF through the Coil + APNG4Android pipeline on the Fire. Also log the URI the Fire Files app sends for "Open with", and check whether HEIC decodes. If any of these fail, revise the spec before continuing.

### Manual device checklist

- Large mixed folder (1,000+ images) with nested subfolders
- Every sort order and direction, plus Reshuffle
- Pinch and menu between all grid sizes
- Grid animation on and off; full-screen animation on and off
- Slideshow at several intervals; zoom pauses auto advance; loop at the end
- Open with from Files: with the permission granted, with it denied (fallback and Browse this folder)
- Rotation on the grid and the viewer
- Corrupt file in the folder

## 8. Success criteria

- APK installs and runs on the Fire Max 11 without Google Play Services.
- All listed formats display; animated GIF, WebP and APNG play when the view's animation setting is on and show a still frame when off.
- Grid, viewer, slideshow and Open with behave as specified above.
- All JVM unit tests and instrumented tests pass.
- Manual device checklist passes.
