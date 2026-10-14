# MediaStore Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** With photo access granted, start at an in-app folder list built from MediaStore, scan a folder with one MediaStore query, remember the last folder across launches, load grid thumbnails from the system thumbnail cache, and prefetch about 2 screens of thumbnails ahead; without access, keep today's system folder picker flow.

**Architecture:** Pure Kotlin functions (LIKE escaping, folder grouping, relPath computation, last-folder preference mapping, column and prefetch math) are JVM-tested against a fake `MediaRowSource`, the same way `walkImages` is tested against a fake `DocumentSource`. A thin `AndroidMediaRowSource` runs the MediaStore queries and is covered by instrumented tests that seed images through MediaStore inserts owned by the app. A custom Coil `Fetcher` turns a `MediaThumbnail` model into `ContentResolver.loadThumbnail` (or into the file bytes for animated files when animation is on). `GalleryViewModel` gains a launch flow (`start`), `openFolder`, and a "home" that is the folder list or the picker.

**Tech Stack:** Kotlin 2.0.21, AGP 8.5.2, Gradle 8.7, Compose BOM 2024.09.00 (Material 3), Coil 3.0.4, APNG4Android 3.0.5, Telephoto 0.14.0, AndroidX DataStore Preferences 1.1.1, kotlinx.coroutines 1.9.0, okio 3.9.1, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-13-kindle-gallery-design.md`, section "Amendment 1 (2026-10-13): MediaStore access" (read it and the sections it supersedes).

## Global Constraints

- Package and applicationId: `com.jjveleber.gallery`. Source root: `app/src/main/java/com/jjveleber/gallery/`. App label: `Gallery`.
- `minSdk = 30`, `targetSdk = 34`, `compileSdk = 34` (Fire OS 8 is API 30; SDK platform 34 is the one installed).
- No Google Play Services, Firebase, or any `com.google.android.gms` dependency.
- Do not bump any library version and do not add dependencies; everything here uses what `app/build.gradle.kts` already has. Do not change `versionCode` / `versionName` (release is handled separately).
- Supported types: reuse `isSupportedImage(name, mime)` from `scan/SupportedTypes.kt`; do not add a second list.
- Hidden: a file whose name starts with '.', or any folder on its path whose name starts with '.', is never listed or scanned.
- The last folder opened from the folder list (volume + relative path) is persisted in DataStore. Document tree grants are never persisted (no `takePersistableUriPermission`).
- Grid presets (target cell size): S = 120 dp, M = 180 dp, L = 300 dp, XL = 480 dp. Prefetch depth: 2 screens.
- Every `./gradlew` command runs with `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` set in the shell (the default `java` is too new for Gradle 8.7).
- `ANDROID_HOME` is `/home/linuxbrew/.linuxbrew/share/android-commandlinetools`. Instrumented tests use the existing AVD: start it with `$ANDROID_HOME/emulator/emulator -avd videoeditor_api34 -no-window -no-audio &` then `$ANDROID_HOME/platform-tools/adb wait-for-device`.
- Never use the em-dash character anywhere: code, UI copy, comments, docs, commit messages. Use a colon, comma, parentheses, or period.
- Every commit message ends with a blank line then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never stage `.superpowers/`.
- If a command fails or an API does not match this plan (for example a Coil 3.0.4 signature), stop and report the exact error text; do not guess around it silently.

## Review Focus

1. **A folder whose name contains `_` or `%`** (`My_Photos`, `100%`): opening it must not pull in images from look-alike folders such as `MyXPhotos`. Tests: Task 2 `likePrefixEscapesWildcards`, `imagesInIgnoresLookAlikeFolders`; Task 3 `underscoreInPathIsLiteral` (real SQL).
2. **A sibling whose name extends the folder's** (`Pictures/Trip` and `Pictures/Trip2`): opening Trip must not include Trip2. Test: Task 2 `imagesInDoesNotMatchLongerSibling`.
3. **The remembered folder was emptied, deleted, or its SD card removed between launches:** the app must land on the folder list (with a message when unreadable), never an empty grid or a crash. Tests: Task 5 `startWithEmptiedLastFolderShowsFolderListAndForgetsIt`, `startWithUnreadableLastFolderShowsFolderListWithMessage`.
4. **The same path on two volumes** (`DCIM/Camera` on internal storage and on an SD card): two separate folders, and opening one shows only its own images. Tests: Task 2 `sameFolderOnTwoVolumesStaysSeparate`, `imagesInSkipsOtherVolumesHiddenAndUnsupported`.
5. **Phone JPEGs stored sideways with an EXIF orientation tag, through the system thumbnail path:** must show upright in the grid. Test: Task 4 `systemThumbnailIsUpright`.

---

## File Map

```
app/src/main/java/com/jjveleber/gallery/
  model/FolderRef.kt              NEW  PRIMARY_VOLUME, FolderRef (volume, relativePath, name, displayPath)
  model/Settings.kt               MOD  Settings.lastFolder
  settings/SettingsStore.kt       MOD  last_folder_volume / last_folder_path keys
  scan/MediaFolders.kt            NEW  MediaRow, MediaRowSource, MediaFolder, likePrefix, isHiddenPath, listFolders, imagesIn (pure)
  scan/MediaLibrary.kt            NEW  MediaLibrary, AndroidMediaLibrary, AndroidMediaRowSource (MediaStore queries)
  imaging/MediaThumbnails.kt      NEW  MediaThumbnail, mightBeAnimated, isMediaStoreUri, MediaThumbnailFetcher (+ Factory, CacheKeyer)
  imaging/ImageLoaders.kt         MOD  registers the fetcher and keyer
  imaging/Requests.kt             MOD  imageRequest(data = ...), gridRequest()
  AppContainer.kt                 MOD  library
  MainActivity.kt                 MOD  passes library to the ViewModel
  ui/GalleryViewModel.kt          MOD  Screen.Folders, initial Loading, start(), openFolder(), showHome()
  ui/GalleryRoot.kt               MOD  permission on launch, folder list screen
  ui/folders/FolderListScreen.kt  NEW  folder rows with cover, Browse other folder
  ui/grid/GridSizing.kt           MOD  gridColumns(), prefetchRange()
  ui/grid/GridScreen.kt           MOD  fixed column count, gridRequest per cell, PrefetchAhead
app/src/test/java/com/jjveleber/gallery/
  model/FolderRefTest.kt          NEW
  settings/SettingsStoreTest.kt   MOD
  scan/MediaFoldersTest.kt        NEW
  imaging/MediaThumbnailsTest.kt  NEW
  testing/Fakes.kt                MOD  FakeLibrary
  ui/GalleryViewModelTest.kt      MOD
  ui/grid/GridSizingTest.kt       MOD
app/src/androidTest/java/com/jjveleber/gallery/
  testing/MediaFixtures.kt        NEW  inserts test assets into MediaStore, deletes them
  scan/AndroidMediaLibraryTest.kt NEW
  imaging/MediaThumbnailTest.kt   NEW
README.md                         MOD  install/usage text
```

---

### Task 1: Remembered folder in settings

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/model/FolderRef.kt`
- Modify: `app/src/main/java/com/jjveleber/gallery/model/Settings.kt`
- Modify: `app/src/main/java/com/jjveleber/gallery/settings/SettingsStore.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/model/FolderRefTest.kt`, `app/src/test/java/com/jjveleber/gallery/settings/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `const val PRIMARY_VOLUME = "external_primary"`; `data class FolderRef(val volume: String, val relativePath: String)` with `val name: String` and `val displayPath: String`; `Settings.lastFolder: FolderRef? = null` persisted by `DataStoreSettingsStore`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/jjveleber/gallery/model/FolderRefTest.kt`:

```kotlin
package com.jjveleber.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderRefTest {
    @Test fun nameIsLastSegment() = assertEquals("Trip", FolderRef(PRIMARY_VOLUME, "Pictures/Trip/").name)

    @Test fun volumeRootIsCalledStorage() {
        assertEquals("Storage", FolderRef(PRIMARY_VOLUME, "").name)
        assertEquals("Storage", FolderRef(PRIMARY_VOLUME, "/").name)
    }

    @Test fun displayPathMarksSdCard() {
        assertEquals("Pictures/Trip", FolderRef(PRIMARY_VOLUME, "Pictures/Trip/").displayPath)
        assertEquals("SD card: DCIM", FolderRef("1a2b-3c4d", "DCIM/").displayPath)
        assertEquals("/", FolderRef(PRIMARY_VOLUME, "/").displayPath)
    }
}
```

In `app/src/test/java/com/jjveleber/gallery/settings/SettingsStoreTest.kt`, add these imports:

```kotlin
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.PRIMARY_VOLUME
import org.junit.Assert.assertNull
```

In `roundTripsEveryField`, add a field to `custom` after `transition = Transition.None,`:

```kotlin
            lastFolder = FolderRef("1a2b-3c4d", "DCIM/Trip_1/"),
```

and add these tests at the end of the class:

```kotlin
    @Test fun clearingLastFolderRemovesIt() = runBlocking {
        val store = store()
        store.update { it.copy(lastFolder = FolderRef(PRIMARY_VOLUME, "Pictures/")) }
        store.update { it.copy(lastFolder = null) }
        assertNull(store.settings.first().lastFolder)
    }

    @Test fun lastFolderNeedsBothKeys() {
        assertNull(mutablePreferencesOf(stringPreferencesKey("last_folder_volume") to PRIMARY_VOLUME).toSettings().lastFolder)
        assertNull(mutablePreferencesOf(stringPreferencesKey("last_folder_path") to "Pictures/").toSettings().lastFolder)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.gallery.model.FolderRefTest' --tests 'com.jjveleber.gallery.settings.SettingsStoreTest'`
Expected: FAIL, compilation error `Unresolved reference 'FolderRef'` (and `lastFolder`).

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/jjveleber/gallery/model/FolderRef.kt`:

```kotlin
package com.jjveleber.gallery.model

/** MediaStore's volume name for the device's built-in shared storage. */
const val PRIMARY_VOLUME = "external_primary"

/**
 * A folder in the MediaStore index. [volume] is a MediaStore volume name ([PRIMARY_VOLUME] or a lowercase SD card
 * ID such as "1a2b-3c4d"). [relativePath] is a MediaStore RELATIVE_PATH such as "Pictures/Trip/" (ends with '/').
 */
data class FolderRef(val volume: String, val relativePath: String) {
    /** The last path segment, for titles. */
    val name: String
        get() = relativePath.trim('/').substringAfterLast('/').ifEmpty { "Storage" }

    /** The path shown in the folder list, marked when the folder is not on the built-in storage. */
    val displayPath: String
        get() {
            val path = relativePath.trim('/').ifEmpty { "/" }
            return if (volume == PRIMARY_VOLUME) path else "SD card: $path"
        }
}
```

In `app/src/main/java/com/jjveleber/gallery/model/Settings.kt`, add the last field of `Settings`:

```kotlin
    val transition: Transition = Transition.Crossfade,
    /** The last folder opened from the folder list, reopened on the next launch. */
    val lastFolder: FolderRef? = null,
)
```

In `app/src/main/java/com/jjveleber/gallery/settings/SettingsStore.kt`:

Add the import `import com.jjveleber.gallery.model.FolderRef`. After `private val TRANSITION = ...` add:

```kotlin
private val LAST_FOLDER_VOLUME = stringPreferencesKey("last_folder_volume")
private val LAST_FOLDER_PATH = stringPreferencesKey("last_folder_path")
```

In `Preferences.toSettings()`, add after `transition = enumOr(this[TRANSITION], d.transition),`:

```kotlin
        lastFolder = lastFolder(),
```

Add below `toSettings()`:

```kotlin
private fun Preferences.lastFolder(): FolderRef? {
    val volume = this[LAST_FOLDER_VOLUME] ?: return null
    val path = this[LAST_FOLDER_PATH] ?: return null
    return FolderRef(volume, path)
}
```

At the end of `Settings.writeTo`, add:

```kotlin
    if (lastFolder == null) {
        prefs.remove(LAST_FOLDER_VOLUME)
        prefs.remove(LAST_FOLDER_PATH)
    } else {
        prefs[LAST_FOLDER_VOLUME] = lastFolder.volume
        prefs[LAST_FOLDER_PATH] = lastFolder.relativePath
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (98 existing + 5 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/gallery/model/FolderRef.kt app/src/main/java/com/jjveleber/gallery/model/Settings.kt app/src/main/java/com/jjveleber/gallery/settings/SettingsStore.kt app/src/test/java/com/jjveleber/gallery/model/FolderRefTest.kt app/src/test/java/com/jjveleber/gallery/settings/SettingsStoreTest.kt
git commit -m "feat(settings): remember the last MediaStore folder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Folder list and folder scan logic (pure)

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/scan/MediaFolders.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/scan/MediaFoldersTest.kt`

**Interfaces:**
- Consumes: `FolderRef`, `PRIMARY_VOLUME` (Task 1); `isSupportedImage(name, mime)`; `NaturalOrder`; `ImageItem`.
- Produces:
  - `data class MediaRow(val id: Long, val volume: String, val relativePath: String, val name: String, val mime: String?, val sizeBytes: Long, val modifiedMs: Long)`
  - `interface MediaRowSource { fun rows(volume: String?, pathPrefix: String): List<MediaRow>; fun uriOf(row: MediaRow): String }`
  - `data class MediaFolder(val ref: FolderRef, val count: Int, val coverUri: String, val coverMime: String)`
  - `fun likePrefix(prefix: String): String`
  - `fun isHiddenPath(relativePath: String, name: String): Boolean`
  - `fun listFolders(source: MediaRowSource): List<MediaFolder>`
  - `fun imagesIn(source: MediaRowSource, folder: FolderRef): List<ImageItem>`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/jjveleber/gallery/scan/MediaFoldersTest.kt`:

```kotlin
package com.jjveleber.gallery.scan

import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.PRIMARY_VOLUME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFoldersTest {
    /** Returns every row whatever the query, like a provider that ignores the selection: callers must filter. */
    private class FakeRows(private val rows: List<MediaRow>) : MediaRowSource {
        val calls = mutableListOf<Pair<String?, String>>()
        override fun rows(volume: String?, pathPrefix: String): List<MediaRow> {
            calls += volume to pathPrefix
            return rows
        }
        override fun uriOf(row: MediaRow) = "content://media/${row.volume}/images/media/${row.id}"
    }

    private var nextId = 1L

    /** [path] is "relative/dir/name.ext". */
    private fun row(path: String, volume: String = PRIMARY_VOLUME, mime: String? = "image/jpeg", modifiedMs: Long = 0): MediaRow {
        val dir = path.substringBeforeLast('/', "")
        return MediaRow(nextId++, volume, if (dir.isEmpty()) "" else "$dir/", path.substringAfterLast('/'), mime, 10, modifiedMs)
    }

    @Test fun likePrefixEscapesWildcards() {
        assertEquals("Pictures/My\\_Photos/50\\%/%", likePrefix("Pictures/My_Photos/50%/"))
        assertEquals("a\\\\b/%", likePrefix("a\\b/"))
        assertEquals("%", likePrefix(""))
    }

    @Test fun hiddenPathsAreDetected() {
        assertTrue(isHiddenPath("Pictures/.thumbnails/", "a.jpg"))
        assertTrue(isHiddenPath(".trashed/x/", "a.jpg"))
        assertTrue(isHiddenPath("Pictures/", ".a.jpg"))
        assertFalse(isHiddenPath("Pictures/a.b/", "c.jpg"))
    }

    @Test fun listFoldersGroupsWithCountAndNewestCover() {
        val newest = row("Pictures/Trip/b.jpg", modifiedMs = 50)
        val source = FakeRows(listOf(row("Pictures/Trip/a.jpg", modifiedMs = 10), newest, row("Pictures/Trip/day2/c.png", mime = "image/png")))
        val folders = listFolders(source)
        assertEquals(
            listOf(FolderRef(PRIMARY_VOLUME, "Pictures/Trip/") to 2, FolderRef(PRIMARY_VOLUME, "Pictures/Trip/day2/") to 1),
            folders.map { it.ref to it.count },
        )
        assertEquals(source.uriOf(newest), folders[0].coverUri)
        assertEquals("image/jpeg", folders[0].coverMime)
        assertEquals(listOf<Pair<String?, String>>(null to ""), source.calls)
    }

    @Test fun listFoldersSkipsHiddenAndUnsupported() {
        val source = FakeRows(
            listOf(
                row("Pictures/.thumbnails/t.jpg"),
                row(".trashed/old/x.jpg"),
                row("Pictures/Shown/.hidden.jpg"),
                row("Pictures/Docs/scan.tiff", mime = "image/tiff"),
                row("Pictures/Shown/ok.jpg"),
            ),
        )
        assertEquals(listOf("Pictures/Shown/"), listFolders(source).map { it.ref.relativePath })
    }

    @Test fun listFoldersSortsByPathNaturally() {
        val source = FakeRows(listOf(row("Pictures/img10/a.jpg"), row("Pictures/img2/a.jpg"), row("DCIM/Camera/a.jpg")))
        assertEquals(
            listOf("DCIM/Camera/", "Pictures/img2/", "Pictures/img10/"),
            listFolders(source).map { it.ref.relativePath },
        )
    }

    @Test fun sameFolderOnTwoVolumesStaysSeparate() {
        val source = FakeRows(listOf(row("DCIM/Camera/a.jpg"), row("DCIM/Camera/b.jpg", volume = "1a2b-3c4d")))
        assertEquals(
            listOf(FolderRef("1a2b-3c4d", "DCIM/Camera/") to 1, FolderRef(PRIMARY_VOLUME, "DCIM/Camera/") to 1),
            listFolders(source).map { it.ref to it.count },
        )
    }

    @Test fun imagesInIsRecursiveWithRelativePaths() {
        val b = row("Pictures/Trip/day2/b.png", mime = "image/png", modifiedMs = 7)
        val source = FakeRows(listOf(row("Pictures/Trip/a.jpg"), b, row("Pictures/Other/c.jpg")))
        val items = imagesIn(source, FolderRef(PRIMARY_VOLUME, "Pictures/Trip/"))
        assertEquals(listOf("a.jpg", "day2/b.png"), items.map { it.relPath })
        val item = items[1]
        assertEquals("b.png", item.name)
        assertEquals(source.uriOf(b), item.uri)
        assertEquals("image/png", item.mime)
        assertEquals(10L, item.sizeBytes)
        assertEquals(7L, item.modifiedMs)
        assertEquals(listOf<Pair<String?, String>>(PRIMARY_VOLUME to "Pictures/Trip/"), source.calls)
    }

    @Test fun imagesInIgnoresLookAlikeFolders() {
        val source = FakeRows(listOf(row("Pictures/My_Photos/a.jpg"), row("Pictures/MyXPhotos/b.jpg"), row("Pictures/My_Photos2/c.jpg")))
        assertEquals(listOf("a.jpg"), imagesIn(source, FolderRef(PRIMARY_VOLUME, "Pictures/My_Photos/")).map { it.relPath })
    }

    @Test fun imagesInDoesNotMatchLongerSibling() {
        val source = FakeRows(listOf(row("Pictures/Trip/a.jpg"), row("Pictures/Trip2/b.jpg")))
        assertEquals(listOf("a.jpg"), imagesIn(source, FolderRef(PRIMARY_VOLUME, "Pictures/Trip/")).map { it.relPath })
    }

    @Test fun imagesInSkipsOtherVolumesHiddenAndUnsupported() {
        val source = FakeRows(
            listOf(
                row("DCIM/Camera/a.jpg"),
                row("DCIM/Camera/b.jpg", volume = "1a2b-3c4d"),
                row("DCIM/Camera/.thumbnails/t.jpg"),
                row("DCIM/Camera/clip.mp4", mime = "video/mp4"),
            ),
        )
        assertEquals(listOf("a.jpg"), imagesIn(source, FolderRef(PRIMARY_VOLUME, "DCIM/Camera/")).map { it.relPath })
    }

    @Test fun imagesInMatchesPathCaseInsensitivelyLikeTheStorage() {
        val source = FakeRows(listOf(row("pictures/trip/a.jpg")))
        assertEquals(listOf("a.jpg"), imagesIn(source, FolderRef(PRIMARY_VOLUME, "Pictures/Trip/")).map { it.relPath })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.gallery.scan.MediaFoldersTest'`
Expected: FAIL, compilation error `Unresolved reference 'MediaRow'`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/jjveleber/gallery/scan/MediaFolders.kt`:

```kotlin
package com.jjveleber.gallery.scan

import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.model.NaturalOrder

/** One MediaStore image row. [relativePath] is RELATIVE_PATH (ends with '/'). [modifiedMs] is in milliseconds. */
data class MediaRow(
    val id: Long,
    val volume: String,
    val relativePath: String,
    val name: String,
    val mime: String?,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

/** MediaStore image rows. Implemented over ContentResolver on the device and faked in tests. */
interface MediaRowSource {
    /**
     * Image rows on [volume] (every volume when null) whose RELATIVE_PATH starts with [pathPrefix] ("" for all).
     * May return extra rows: callers filter again.
     */
    fun rows(volume: String?, pathPrefix: String): List<MediaRow>

    fun uriOf(row: MediaRow): String
}

/** A folder-list entry: [count] supported images directly in the folder, [coverUri] the newest of them. */
data class MediaFolder(val ref: FolderRef, val count: Int, val coverUri: String, val coverMime: String)

/** A SQL LIKE pattern for strings starting with [prefix], to use with ESCAPE '\'. */
fun likePrefix(prefix: String): String =
    prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** True when the file, or any folder on its path, is hidden (name starts with '.'), as the folder walk skips them. */
fun isHiddenPath(relativePath: String, name: String): Boolean =
    name.startsWith(".") || relativePath.split('/').any { it.startsWith(".") }

private fun MediaRow.isShown() = !isHiddenPath(relativePath, name) && isSupportedImage(name, mime)

private val newestFirst = compareByDescending<MediaRow> { it.modifiedMs }.thenBy(NaturalOrder) { it.name }

/** Every folder holding supported, non-hidden images, sorted by path, then volume. */
fun listFolders(source: MediaRowSource): List<MediaFolder> =
    source.rows(volume = null, pathPrefix = "")
        .filter { it.isShown() }
        .groupBy { FolderRef(it.volume, it.relativePath) }
        .map { (ref, rows) ->
            val cover = rows.minWith(newestFirst)
            MediaFolder(ref, rows.size, source.uriOf(cover), cover.mime.orEmpty())
        }
        .sortedWith(compareBy<MediaFolder, String>(NaturalOrder) { it.ref.relativePath }.thenBy { it.ref.volume })

/**
 * The supported images in [folder] and its subfolders, with relPath relative to [folder]. The path match ignores
 * case, like the shared storage and SQL LIKE.
 */
fun imagesIn(source: MediaRowSource, folder: FolderRef): List<ImageItem> =
    source.rows(folder.volume, folder.relativePath)
        .filter { it.volume == folder.volume && it.relativePath.startsWith(folder.relativePath, ignoreCase = true) && it.isShown() }
        .map {
            val relPath = it.relativePath.substring(folder.relativePath.length) + it.name
            ImageItem(source.uriOf(it), it.name, relPath, it.sizeBytes, it.modifiedMs, it.mime.orEmpty())
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/gallery/scan/MediaFolders.kt app/src/test/java/com/jjveleber/gallery/scan/MediaFoldersTest.kt
git commit -m "feat(scan): MediaStore folder list and prefix scan logic

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: MediaStore queries on the device

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/scan/MediaLibrary.kt`
- Modify: `app/src/main/java/com/jjveleber/gallery/AppContainer.kt`
- Create: `app/src/androidTest/java/com/jjveleber/gallery/testing/MediaFixtures.kt`
- Test: `app/src/androidTest/java/com/jjveleber/gallery/scan/AndroidMediaLibraryTest.kt`

**Interfaces:**
- Consumes: everything Task 2 produces.
- Produces:
  - `interface MediaLibrary { suspend fun folders(): List<MediaFolder>; suspend fun images(folder: FolderRef): List<ImageItem> }`
  - `class AndroidMediaLibrary(resolver: ContentResolver) : MediaLibrary`
  - `class AndroidMediaRowSource(resolver: ContentResolver) : MediaRowSource` (item URIs look like `content://media/external_primary/images/media/42`)
  - `AppContainer.library: MediaLibrary`
  - androidTest `object MediaFixtures { fun insert(asset: String, relativePath: String, displayName: String, mime: String): Uri; fun delete(uris: List<Uri>) }`

Note: files an app inserts through MediaStore are visible to that app without the storage permission, so these tests need no permission grant. Each run uses a folder name with `System.nanoTime()` so leftovers from a crashed run never collide.

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/com/jjveleber/gallery/testing/MediaFixtures.kt`:

```kotlin
package com.jjveleber.gallery.testing

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

/** Puts test assets into MediaStore as images owned by the app under test, so it can read them without permission. */
object MediaFixtures {
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    /** Copies `images/[asset]` from the test assets to [relativePath][displayName] and returns its MediaStore URI. */
    fun insert(asset: String, relativePath: String, displayName: String, mime: String): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "insert failed for $relativePath$displayName" }
        requireNotNull(resolver.openOutputStream(uri)).use { out -> assets.open("images/$asset").use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    fun delete(uris: List<Uri>) {
        uris.forEach { runCatching { resolver.delete(it, null, null) } }
    }
}
```

Create `app/src/androidTest/java/com/jjveleber/gallery/scan/AndroidMediaLibraryTest.kt`:

```kotlin
package com.jjveleber.gallery.scan

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.PRIMARY_VOLUME
import com.jjveleber.gallery.testing.MediaFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaLibraryTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val run = System.nanoTime()
    private val folder = "Pictures/gt_$run/"
    private val lookAlike = "Pictures/gtX$run/"
    private val inserted = mutableListOf<Uri>()

    @Before fun seed() {
        inserted += MediaFixtures.insert("still.jpg", folder, "a.jpg", "image/jpeg")
        inserted += MediaFixtures.insert("still.png", "${folder}sub/", "b.png", "image/png")
        inserted += MediaFixtures.insert("still.jpg", lookAlike, "c.jpg", "image/jpeg")
    }

    @After fun cleanUp() = MediaFixtures.delete(inserted)

    @Test fun underscoreInPathIsLiteral() {
        val rows = AndroidMediaRowSource(resolver).rows(PRIMARY_VOLUME, folder)
        assertEquals(setOf("a.jpg", "b.png"), rows.map { it.name }.toSet())
    }

    @Test fun imagesAreRecursiveWithRelativePaths() = runBlocking {
        val items = AndroidMediaLibrary(resolver).images(FolderRef(PRIMARY_VOLUME, folder))
        assertEquals(listOf("a.jpg", "sub/b.png"), items.map { it.relPath }.sorted())
        assertTrue(items.all { it.uri.startsWith("content://media/") })
    }

    @Test fun foldersListEachFolderWithItsCount() = runBlocking {
        val mine = AndroidMediaLibrary(resolver).folders().filter { it.ref.relativePath.contains("$run/") }
        assertEquals(
            setOf(folder to 1, "${folder}sub/" to 1, lookAlike to 1),
            mine.map { it.ref.relativePath to it.count }.toSet(),
        )
        assertTrue(mine.all { it.ref.volume == PRIMARY_VOLUME && it.coverUri.startsWith("content://media/") })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Start the emulator if it is not running:

```bash
export ANDROID_HOME=/home/linuxbrew/.linuxbrew/share/android-commandlinetools
$ANDROID_HOME/emulator/emulator -avd videoeditor_api34 -no-window -no-audio &
$ANDROID_HOME/platform-tools/adb wait-for-device
```

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jjveleber.gallery.scan.AndroidMediaLibraryTest`
Expected: FAIL, compilation error `Unresolved reference 'AndroidMediaRowSource'`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/jjveleber/gallery/scan/MediaLibrary.kt`:

```kotlin
package com.jjveleber.gallery.scan

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** The device's image index, so the ViewModel can be tested with a fake. Needs photo access. */
interface MediaLibrary {
    suspend fun folders(): List<MediaFolder>
    suspend fun images(folder: FolderRef): List<ImageItem>
}

class AndroidMediaLibrary(resolver: ContentResolver) : MediaLibrary {
    private val source = AndroidMediaRowSource(resolver)

    override suspend fun folders(): List<MediaFolder> = withContext(Dispatchers.IO) { listFolders(source) }

    override suspend fun images(folder: FolderRef): List<ImageItem> = withContext(Dispatchers.IO) { imagesIn(source, folder) }
}

/** One MediaStore query per call, instead of one document-provider query per subfolder. */
class AndroidMediaRowSource(private val resolver: ContentResolver) : MediaRowSource {
    override fun rows(volume: String?, pathPrefix: String): List<MediaRow> {
        val collection = MediaStore.Images.Media.getContentUri(volume ?: MediaStore.VOLUME_EXTERNAL)
        val selection = if (pathPrefix.isEmpty()) null else "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        val args = if (pathPrefix.isEmpty()) null else arrayOf(likePrefix(pathPrefix))
        val cursor = resolver.query(collection, COLUMNS, selection, args, null)
            ?: throw FileNotFoundException("Cannot query $collection")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(3) ?: continue
                    add(
                        MediaRow(
                            id = c.getLong(0),
                            volume = c.getString(1).orEmpty(),
                            relativePath = c.getString(2).orEmpty(),
                            name = name,
                            mime = c.getString(4),
                            sizeBytes = if (c.isNull(5)) 0 else c.getLong(5),
                            modifiedMs = if (c.isNull(6)) 0 else c.getLong(6) * 1000,
                        ),
                    )
                }
            }
        }
    }

    override fun uriOf(row: MediaRow): String =
        ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(row.volume), row.id).toString()

    private companion object {
        val COLUMNS = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
    }
}
```

In `app/src/main/java/com/jjveleber/gallery/AppContainer.kt`, add the imports `import com.jjveleber.gallery.scan.AndroidMediaLibrary` and `import com.jjveleber.gallery.scan.MediaLibrary`, and add after the `scanner` line:

```kotlin
    val library: MediaLibrary = AndroidMediaLibrary(context.contentResolver)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jjveleber.gallery.scan.AndroidMediaLibraryTest && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL both times, 3 instrumented tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/gallery/scan/MediaLibrary.kt app/src/main/java/com/jjveleber/gallery/AppContainer.kt app/src/androidTest/java/com/jjveleber/gallery/testing/MediaFixtures.kt app/src/androidTest/java/com/jjveleber/gallery/scan/AndroidMediaLibraryTest.kt
git commit -m "feat(scan): MediaStore folder list and folder query

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: System thumbnails through Coil

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/imaging/MediaThumbnails.kt`
- Modify: `app/src/main/java/com/jjveleber/gallery/imaging/ImageLoaders.kt`
- Modify: `app/src/main/java/com/jjveleber/gallery/imaging/Requests.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/imaging/MediaThumbnailsTest.kt`, `app/src/androidTest/java/com/jjveleber/gallery/imaging/MediaThumbnailTest.kt`

**Interfaces:**
- Consumes: `AnimatedRegistry`, `AnimationSniffer`, `ImageKind`, `AnimateKey`, `SourceKey`, `AnimatedDecoder.Factory`; `MediaFixtures` (Task 3).
- Produces:
  - `data class MediaThumbnail(val uri: String, val mime: String, val sizePx: Int)` (a Coil model)
  - `fun mightBeAnimated(mime: String): Boolean`, `fun isMediaStoreUri(uri: String): Boolean`
  - `class MediaThumbnailFetcher` with nested `Factory(resolver, registry)` and `object CacheKeyer`
  - `fun imageRequest(context, uri, animate, listener = null, data: Any = Uri.parse(uri)): ImageRequest` (new last parameter, existing callers unchanged)
  - `fun gridRequest(context: Context, item: ImageItem, animate: Boolean, sizePx: Int, listener: ImageRequest.Listener? = null): ImageRequest`

Design notes for the implementer:
- The memory cache key is `thumb:<uri>@<sizePx>` (plus the existing `animate` extra), so a grid thumbnail never satisfies a full-screen request for the same URI, and the result is reported with `isSampled = false` because the size is already in the key.
- `ContentResolver.loadThumbnail` applies EXIF orientation; the instrumented test pins it.
- When animation is allowed and the sniffer says animated, the fetcher returns the file bytes as a `SourceFetchResult`, so `AnimatedDecoder` decodes the animation exactly as today.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/jjveleber/gallery/imaging/MediaThumbnailsTest.kt`:

```kotlin
package com.jjveleber.gallery.imaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaThumbnailsTest {
    @Test fun onlyGifWebpAndPngMightBeAnimated() {
        for (mime in listOf("image/gif", "image/webp", "image/png", "image/apng")) assertTrue(mime, mightBeAnimated(mime))
        for (mime in listOf("image/jpeg", "image/heic", "image/bmp", "")) assertFalse(mime, mightBeAnimated(mime))
    }

    @Test fun recognizesMediaStoreUris() {
        assertTrue(isMediaStoreUri("content://media/external_primary/images/media/12"))
        assertFalse(isMediaStoreUri("content://com.android.externalstorage.documents/tree/primary%3APictures/document/primary%3APictures%2Fa.jpg"))
        assertFalse(isMediaStoreUri("file:///sdcard/a.jpg"))
    }
}
```

Create `app/src/androidTest/java/com/jjveleber/gallery/imaging/MediaThumbnailTest.kt`:

```kotlin
package com.jjveleber.gallery.imaging

import android.graphics.drawable.Animatable
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.asDrawable
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.testing.MediaFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaThumbnailTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val registry = AnimatedRegistry()
    private val loader = buildImageLoader(context, registry)
    private val folder = "Pictures/gthumb${System.nanoTime()}/"
    private val inserted = mutableListOf<Uri>()

    @After fun cleanUp() = MediaFixtures.delete(inserted)

    private fun insert(asset: String, mime: String): Uri = MediaFixtures.insert(asset, folder, asset, mime).also { inserted += it }

    private fun load(uri: Uri, mime: String, animate: Boolean): ImageResult = runBlocking {
        val item = ImageItem(uri.toString(), "x", "x", 0, 0, mime)
        loader.execute(gridRequest(context, item, animate, sizePx = 256))
    }

    private fun success(result: ImageResult): SuccessResult {
        assertTrue("expected success but got $result", result is SuccessResult)
        return result as SuccessResult
    }

    // rotated.jpg is stored 40x20 with an EXIF tag that rotates it to portrait.
    @Test fun systemThumbnailIsUpright() {
        val image = success(load(insert("rotated.jpg", "image/jpeg"), "image/jpeg", animate = false)).image
        assertTrue("expected portrait, got ${image.width}x${image.height}", image.height > image.width)
    }

    @Test fun animatedFileAnimatesWhenAllowed() {
        val uri = insert("anim.gif", "image/gif")
        val drawable = success(load(uri, "image/gif", animate = true)).image.asDrawable(context.resources)
        assertTrue("expected an animation, got $drawable", drawable is Animatable)
    }

    @Test fun animatedFileIsStillAndRecordedWhenNotAllowed() {
        val uri = insert("anim.webp", "image/webp")
        val image = success(load(uri, "image/webp", animate = false)).image
        assertTrue("expected a bitmap, got $image", image is BitmapImage)
        assertTrue(registry.isAnimated(uri.toString()))
    }

    @Test fun staticPngIsRecordedAsStatic() {
        val uri = insert("still.png", "image/png")
        success(load(uri, "image/png", animate = true))
        assertTrue(registry.isStatic(uri.toString()))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.gallery.imaging.MediaThumbnailsTest'`
Expected: FAIL, compilation error `Unresolved reference 'mightBeAnimated'`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/jjveleber/gallery/imaging/MediaThumbnails.kt`:

```kotlin
package com.jjveleber.gallery.imaging

import android.content.ContentResolver
import android.net.Uri
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.key.Keyer
import coil3.request.Options
import okio.buffer
import okio.source
import java.io.FileNotFoundException

/** A square thumbnail of a MediaStore image, [sizePx] on each side. [mime] says whether it may be animated. */
data class MediaThumbnail(val uri: String, val mime: String, val sizePx: Int)

private val ANIMATABLE_MIME = setOf("image/gif", "image/webp", "image/png", "image/apng")

/** Only GIF, WebP and PNG files can be animated, so only they are sniffed. */
fun mightBeAnimated(mime: String): Boolean = mime in ANIMATABLE_MIME

/** MediaStore item URIs can use the system thumbnail cache; document (system picker) URIs cannot. */
fun isMediaStoreUri(uri: String): Boolean = uri.startsWith("content://media/")

/**
 * Loads a [MediaThumbnail] from the system thumbnail cache (ContentResolver.loadThumbnail, which also applies EXIF
 * orientation). An animated file whose request allows animation is handed to the decoders instead, so it animates.
 */
class MediaThumbnailFetcher(
    private val data: MediaThumbnail,
    private val options: Options,
    private val resolver: ContentResolver,
    private val registry: AnimatedRegistry,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val uri = Uri.parse(data.uri)
        val animated = isAnimated(uri) // also records the kind for the grid's ANIM badge
        if (animated && options.getExtra(AnimateKey)) {
            val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException(data.uri)
            return SourceFetchResult(ImageSource(stream.source().buffer(), options.fileSystem), data.mime, DataSource.DISK)
        }
        val bitmap = resolver.loadThumbnail(uri, Size(data.sizePx, data.sizePx), null)
        // The size is part of the cache key (CacheKeyer), so Coil need not compare sizes on a cache hit.
        return ImageFetchResult(bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
    }

    /** Sniffs the header once per file and records the result in [registry]. */
    private fun isAnimated(uri: Uri): Boolean {
        if (!mightBeAnimated(data.mime) || registry.isStatic(data.uri)) return false
        if (registry.isAnimated(data.uri)) return true
        val kind = resolver.openInputStream(uri)?.source()?.buffer()?.use { AnimationSniffer.sniff(it) } ?: ImageKind.Static
        registry.record(data.uri, kind != ImageKind.Static)
        return kind != ImageKind.Static
    }

    class Factory(private val resolver: ContentResolver, private val registry: AnimatedRegistry) : Fetcher.Factory<MediaThumbnail> {
        override fun create(data: MediaThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbnailFetcher(data, options, resolver, registry)
    }

    /** Keeps thumbnails apart from full-size entries for the same URI, one entry per thumbnail size. */
    object CacheKeyer : Keyer<MediaThumbnail> {
        override fun key(data: MediaThumbnail, options: Options): String = "thumb:${data.uri}@${data.sizePx}"
    }
}
```

Replace the body of `app/src/main/java/com/jjveleber/gallery/imaging/ImageLoaders.kt` with:

```kotlin
package com.jjveleber.gallery.imaging

import coil3.ImageLoader
import coil3.PlatformContext

fun buildImageLoader(context: PlatformContext, registry: AnimatedRegistry): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(MediaThumbnailFetcher.Factory(context.contentResolver, registry))
            add(MediaThumbnailFetcher.CacheKeyer)
            add(AnimatedDecoder.Factory(registry))
        }
        .build()
```

In `app/src/main/java/com/jjveleber/gallery/imaging/Requests.kt`, add the import `import com.jjveleber.gallery.model.ImageItem`, change `imageRequest` to take the model as a last parameter, and add `gridRequest` below it:

```kotlin
fun imageRequest(
    context: Context,
    uri: String,
    animate: Boolean,
    listener: ImageRequest.Listener? = null,
    data: Any = Uri.parse(uri),
): ImageRequest =
    ImageRequest.Builder(context)
        .data(data)
        .apply {
            extras[AnimateKey] = animate
            extras[SourceKey] = uri
        }
        // Animated and still results for the same file must not share a memory cache entry.
        .memoryCacheKeyExtra("animate", animate.toString())
        .listener(listener)
        .build()

/**
 * A grid cell's request, [sizePx] square. MediaStore images come from the system thumbnail cache; images from a
 * folder picked in the system picker decode the file. The cell and the prefetch build the same request, so they
 * share one memory cache entry.
 */
fun gridRequest(
    context: Context,
    item: ImageItem,
    animate: Boolean,
    sizePx: Int,
    listener: ImageRequest.Listener? = null,
): ImageRequest {
    val data: Any = if (isMediaStoreUri(item.uri)) MediaThumbnail(item.uri, item.mime, sizePx) else Uri.parse(item.uri)
    return imageRequest(context, item.uri, animate, listener, data).newBuilder().size(sizePx).build()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest && ./gradlew :app:connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL both times; all instrumented tests pass (8 existing + 3 from Task 3 + 4 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/gallery/imaging/MediaThumbnails.kt app/src/main/java/com/jjveleber/gallery/imaging/ImageLoaders.kt app/src/main/java/com/jjveleber/gallery/imaging/Requests.kt app/src/test/java/com/jjveleber/gallery/imaging/MediaThumbnailsTest.kt app/src/androidTest/java/com/jjveleber/gallery/imaging/MediaThumbnailTest.kt
git commit -m "feat(imaging): grid thumbnails from the system thumbnail cache

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Launch flow in the ViewModel

**Files:**
- Modify: `app/src/main/java/com/jjveleber/gallery/ui/GalleryViewModel.kt`
- Modify: `app/src/main/java/com/jjveleber/gallery/MainActivity.kt`
- Modify: `app/src/test/java/com/jjveleber/gallery/testing/Fakes.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/ui/GalleryViewModelTest.kt`

**Interfaces:**
- Consumes: `MediaLibrary`, `MediaFolder` (Tasks 2, 3); `FolderRef`, `Settings.lastFolder` (Task 1); `AppContainer.library`.
- Produces:
  - `Screen.Folders(val folders: List<MediaFolder>)`; the initial screen is now `Screen.Loading`.
  - `GalleryViewModel(scanner: FolderScanner, library: MediaLibrary, resolver: OpenWithResolver, settingsStore: SettingsStore)`
  - `val started: Boolean`, `fun start(mayReadMedia: Boolean)`, `fun openFolder(folder: FolderRef)`; `cancelScan()` and `changeFolder()` now go "home" (folder list with access, picker without).

After this task the app shows a black screen at launch until Task 6 calls `start`; the unit tests are this task's gate.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/java/com/jjveleber/gallery/testing/Fakes.kt`, add these imports:

```kotlin
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.scan.MediaFolder
import com.jjveleber.gallery.scan.MediaLibrary
```

and add this class after `FakeScanner`:

```kotlin
/** Each folder's images wait until the test completes [result] for it. */
class FakeLibrary : MediaLibrary {
    var folderList: List<MediaFolder> = emptyList()
    var folderCalls = 0
    var failWith: Exception? = null
    private val results = mutableMapOf<FolderRef, CompletableDeferred<List<ImageItem>>>()

    fun result(folder: FolderRef) = results.getOrPut(folder) { CompletableDeferred() }

    override suspend fun folders(): List<MediaFolder> {
        folderCalls++
        return folderList
    }

    override suspend fun images(folder: FolderRef): List<ImageItem> {
        failWith?.let { throw it }
        return result(folder).await()
    }
}
```

In `app/src/test/java/com/jjveleber/gallery/ui/GalleryViewModelTest.kt`:

Add these imports:

```kotlin
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.PRIMARY_VOLUME
import com.jjveleber.gallery.scan.MediaFolder
import com.jjveleber.gallery.testing.FakeLibrary
```

Replace the fixture lines and the first test:

```kotlin
    private val scanner = FakeScanner()
    private val library = FakeLibrary()
    private val resolver = FakeResolver()
    private val store = FakeSettingsStore()
    private fun vm() = GalleryViewModel(scanner, library, resolver, store)
    private fun GalleryViewModel.names() = items.value.map { it.relPath }

    private val trip = FolderRef(PRIMARY_VOLUME, "Pictures/Trip/")
    private val tripFolder = MediaFolder(trip, count = 2, coverUri = "content://media/external_primary/images/media/1", coverMime = "image/jpeg")

    @Test fun startsLoading() = runTest {
        assertEquals(Screen.Loading, vm().screen.value)
    }
```

(this replaces `startsAtPicker`). In `staleProgressAfterCancelDoesNotRevertPicker`, change the construction to `GalleryViewModel(capturing, library, resolver, store)`.

Add these tests at the end of the class:

```kotlin
    @Test fun startWithoutPhotoAccessShowsPicker() = runTest {
        val vm = vm()
        vm.start(mayReadMedia = false)
        assertEquals(Screen.Picker, vm.screen.value)
        assertEquals(0, library.folderCalls)
    }

    @Test fun startWithPhotoAccessShowsFolderList() = runTest {
        library.folderList = listOf(tripFolder)
        val vm = vm()
        vm.start(mayReadMedia = true)
        assertEquals(Screen.Folders(listOf(tripFolder)), vm.screen.value)
        assertTrue(vm.started)
    }

    @Test fun startRunsOnce() = runTest {
        val vm = vm()
        vm.start(mayReadMedia = true)
        vm.start(mayReadMedia = true)
        assertEquals(1, library.folderCalls)
    }

    @Test fun startReopensLastFolder() = runTest {
        store.state.value = Settings(lastFolder = trip)
        val vm = vm()
        vm.start(mayReadMedia = true)
        assertEquals(Screen.Scanning(0), vm.screen.value)
        library.result(trip).complete(listOf(item("b10.jpg"), item("day2/b2.jpg")))
        assertEquals(Screen.Grid, vm.screen.value)
        assertEquals(listOf("day2/b2.jpg", "b10.jpg"), vm.names())
        assertEquals("Trip", vm.folderName.value)
        assertEquals(0, library.folderCalls)
    }

    @Test fun startWithEmptiedLastFolderShowsFolderListAndForgetsIt() = runTest {
        store.state.value = Settings(lastFolder = trip)
        library.folderList = listOf(tripFolder)
        val vm = vm()
        vm.start(mayReadMedia = true)
        library.result(trip).complete(emptyList())
        assertEquals(Screen.Folders(listOf(tripFolder)), vm.screen.value)
        assertNull(store.state.value.lastFolder)
    }

    @Test fun startWithUnreadableLastFolderShowsFolderListWithMessage() = runTest {
        store.state.value = Settings(lastFolder = trip)
        library.failWith = SecurityException("gone")
        val vm = vm()
        vm.start(mayReadMedia = true)
        assertEquals(Screen.Folders(emptyList()), vm.screen.value)
        assertEquals("Folder no longer available", vm.message.value)
    }

    @Test fun openFolderShowsGridAndRemembersIt() = runTest {
        val vm = vm()
        vm.start(mayReadMedia = true)
        vm.openFolder(trip)
        assertEquals(Screen.Scanning(0), vm.screen.value)
        library.result(trip).complete(listOf(item("a.jpg")))
        assertEquals(Screen.Grid, vm.screen.value)
        assertEquals(trip, store.state.value.lastFolder)
    }

    @Test fun changeFolderWithPhotoAccessReturnsToRefreshedFolderList() = runTest {
        val vm = vm()
        vm.start(mayReadMedia = true)
        vm.openFolder(trip)
        library.result(trip).complete(listOf(item("a.jpg")))
        library.folderList = listOf(tripFolder)
        vm.changeFolder()
        assertEquals(Screen.Folders(listOf(tripFolder)), vm.screen.value)
        assertTrue(vm.items.value.isEmpty())
        assertEquals(2, library.folderCalls)
    }

    @Test fun cancelScanWithPhotoAccessReturnsToFolderList() = runTest {
        val vm = vm()
        vm.start(mayReadMedia = true)
        vm.openFolder(trip)
        vm.cancelScan()
        library.result(trip).complete(listOf(item("a.jpg")))
        assertEquals(Screen.Folders(emptyList()), vm.screen.value)
        assertTrue(vm.items.value.isEmpty())
    }

    @Test fun folderFromSystemPickerIsNotRemembered() = runTest {
        val vm = vm()
        vm.start(mayReadMedia = true)
        vm.openFolder(trip)
        library.result(trip).complete(listOf(item("a.jpg")))
        vm.pickFolder("tree/Other")
        scanner.result("tree/Other").complete(listOf(item("b.jpg")))
        assertEquals(Screen.Grid, vm.screen.value)
        assertNull(store.state.value.lastFolder)
        vm.changeFolder()
        assertEquals(Screen.Folders(emptyList()), vm.screen.value)
    }

    @Test fun openWithMarksStarted() = runTest {
        resolver.result = OpenWithResult.Single(item("a.jpg"), null)
        val vm = vm()
        vm.openWith("content://x/a", mayReadMedia = false)
        assertTrue(vm.started)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.gallery.ui.GalleryViewModelTest'`
Expected: FAIL, compilation errors (`Too many arguments for public constructor GalleryViewModel`, `Unresolved reference 'start'`).

- [ ] **Step 3: Implement**

Replace `app/src/main/java/com/jjveleber/gallery/ui/GalleryViewModel.kt` with:

```kotlin
package com.jjveleber.gallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.model.SortOrder
import com.jjveleber.gallery.model.SortSpec
import com.jjveleber.gallery.model.sortItems
import com.jjveleber.gallery.scan.FolderLocation
import com.jjveleber.gallery.scan.FolderScanner
import com.jjveleber.gallery.scan.MediaFolder
import com.jjveleber.gallery.scan.MediaLibrary
import com.jjveleber.gallery.scan.OpenWithResolver
import com.jjveleber.gallery.scan.OpenWithResult
import com.jjveleber.gallery.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

sealed interface Screen {
    /** The system folder picker screen: the start screen when photo access is denied. */
    data object Picker : Screen
    /** Folders from the MediaStore index: the start screen when photo access is granted. */
    data class Folders(val folders: List<MediaFolder>) : Screen
    data class Scanning(val found: Int) : Screen
    /** Starting up, loading the folder list, or resolving an "Open with" image. */
    data object Loading : Screen
    data object Grid : Screen
    data class Viewer(val startIndex: Int) : Screen
}

/** Set while the app was opened through "Open with". [single] means only the incoming image is shown. */
data class OpenWithState(val single: Boolean, val incomingName: String, val folderHint: FolderLocation?)

class GalleryViewModel(
    private val scanner: FolderScanner,
    private val library: MediaLibrary,
    private val resolver: OpenWithResolver,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _items = MutableStateFlow<List<ImageItem>>(emptyList())
    /** The current images in display order. */
    val items: StateFlow<List<ImageItem>> = _items.asStateFlow()

    private val _folderName = MutableStateFlow("")
    val folderName: StateFlow<String> = _folderName.asStateFlow()

    private val _openWith = MutableStateFlow<OpenWithState?>(null)
    val openWith: StateFlow<OpenWithState?> = _openWith.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _lastViewedIndex = MutableStateFlow(0)
    /** The last image shown full screen, so the grid can scroll back to it. */
    val lastViewedIndex: StateFlow<Int> = _lastViewedIndex.asStateFlow()

    /** False until [start] or [openWith] has run, so the launch flow runs once per ViewModel. */
    var started = false
        private set

    /** True when photo access is granted: home is the folder list instead of the picker. */
    private var mediaMode = false
    private var unsorted: List<ImageItem> = emptyList()
    private var job: Job? = null
    /** Bumped whenever a scan or resolve is superseded, so late results from the old one are dropped. */
    private var generation = 0

    /** A normal launch: reopens the remembered folder if it still has images, else shows home. */
    fun start(mayReadMedia: Boolean) {
        if (started) return
        started = true
        mediaMode = mayReadMedia
        if (!mayReadMedia) {
            _screen.value = Screen.Picker
            return
        }
        val gen = ++generation
        // Not assigned to job: what it starts (the folder load or the list) sets job itself, and gen guards it.
        viewModelScope.launch {
            val last = settingsStore.settings.first().lastFolder
            if (gen != generation) return@launch
            if (last == null) showHome() else loadFolder(last, restoring = true)
        }
    }

    /** Opens a folder from the folder list, with its subfolders, and remembers it for the next launch. */
    fun openFolder(folder: FolderRef) = loadFolder(folder, restoring = false)

    private fun loadFolder(folder: FolderRef, restoring: Boolean) {
        _openWith.value = null
        load({ folder.name to library.images(folder) }) { sorted ->
            if (restoring && sorted.isEmpty()) {
                updateSettings { it.copy(lastFolder = null) }
                showHome()
            } else {
                updateSettings { it.copy(lastFolder = folder) }
                _lastViewedIndex.value = 0
                _screen.value = Screen.Grid
            }
        }
    }

    /** A folder picked in the system picker. Its grant is not kept, so it is not remembered either. */
    fun pickFolder(treeUri: String) {
        _openWith.value = null
        updateSettings { it.copy(lastFolder = null) }
        scan(treeUri, recursive = true) {
            _lastViewedIndex.value = 0
            _screen.value = Screen.Grid
        }
    }

    /** "Browse this folder" for a single Open with image: that folder only, positioned at the incoming image. */
    fun browseOpenWithFolder(treeUri: String) {
        val incoming = _openWith.value?.incomingName ?: return
        scan(treeUri, recursive = false) { sorted ->
            if (sorted.isEmpty()) {
                _message.value = "No supported images in this folder"
                showHome()
            } else {
                _openWith.value = OpenWithState(single = false, incomingName = incoming, folderHint = null)
                _screen.value = Screen.Viewer(sorted.indexOfFirst { it.name == incoming }.coerceAtLeast(0))
            }
        }
    }

    private fun scan(treeUri: String, recursive: Boolean, done: (List<ImageItem>) -> Unit) =
        load({ onProgress -> scanner.folderName(treeUri) to scanner.scan(treeUri, recursive, onProgress) }, done)

    /** Runs [fetch] (folder name and images, reporting progress), sorts the result, then hands it to [done]. */
    private fun load(
        fetch: suspend (onProgress: (Int) -> Unit) -> Pair<String, List<ImageItem>>,
        done: (List<ImageItem>) -> Unit,
    ) {
        job?.cancel()
        val gen = ++generation
        _screen.value = Screen.Scanning(0)
        job = viewModelScope.launch {
            try {
                val (name, found) = fetch { n ->
                    // Called on the IO thread: hop to the main dispatcher so this check and write cannot
                    // interleave with cancelScan() or a newer pick.
                    viewModelScope.launch { if (gen == generation) _screen.value = Screen.Scanning(n) }
                }
                val sorted = sortItems(found, settingsStore.settings.first().sort)
                if (gen != generation) return@launch
                unsorted = found
                _items.value = sorted
                _folderName.value = name
                done(sorted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _message.value = "Folder no longer available"
                showHome()
            }
        }
    }

    /** The start screen: the refreshed folder list with photo access, else the picker. */
    private fun showHome() {
        if (!mediaMode) {
            _screen.value = Screen.Picker
            return
        }
        val gen = ++generation
        _screen.value = Screen.Loading
        job = viewModelScope.launch {
            val folders = try {
                library.folders()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (gen == generation) _screen.value = Screen.Folders(folders)
        }
    }

    fun cancelScan() {
        job?.cancel()
        generation++
        showHome()
    }

    fun changeFolder() {
        cancelScan()
        unsorted = emptyList()
        _items.value = emptyList()
        _openWith.value = null
    }

    fun openWith(uri: String, mayReadMedia: Boolean) {
        started = true
        job?.cancel()
        val gen = ++generation
        _screen.value = Screen.Loading
        job = viewModelScope.launch {
            val result = resolver.resolve(uri, mayReadMedia)
            if (gen != generation) return@launch
            when (result) {
                is OpenWithResult.Resolved -> {
                    val sorted = sortItems(result.items, settingsStore.settings.first().sort)
                    val start = sorted.indexOfFirst { it.uri == result.startUri }.coerceAtLeast(0)
                    unsorted = result.items
                    _items.value = sorted
                    _folderName.value = result.folderName
                    _openWith.value = OpenWithState(single = false, incomingName = sorted[start].name, folderHint = null)
                    _screen.value = Screen.Viewer(start)
                }
                is OpenWithResult.Single -> {
                    unsorted = listOf(result.item)
                    _items.value = unsorted
                    _folderName.value = ""
                    _openWith.value = OpenWithState(single = true, incomingName = result.item.name, folderHint = result.folderHint)
                    _screen.value = Screen.Viewer(0)
                }
            }
        }
    }

    fun openViewer(index: Int, play: Boolean) {
        if (play) updateSettings { it.copy(autoAdvance = true) }
        _lastViewedIndex.value = index
        _screen.value = Screen.Viewer(index)
    }

    fun viewerShowing(index: Int) {
        _lastViewedIndex.value = index
    }

    /** Returns true when the activity should finish instead (opened through "Open with"). */
    fun closeViewer(): Boolean {
        if (_openWith.value != null) return true
        _screen.value = Screen.Grid
        return false
    }

    fun setSort(spec: SortSpec) {
        _items.value = sortItems(unsorted, spec)
        updateSettings { it.copy(sort = spec) }
    }

    fun reshuffle() = setSort(settings.value.sort.copy(order = SortOrder.Random, randomSeed = Random.nextLong()))

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
```

In `app/src/main/java/com/jjveleber/gallery/MainActivity.kt`, change the initializer line to:

```kotlin
                            initializer { GalleryViewModel(container.scanner, container.library, container.openWith, container.settings) }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL both times; every existing ViewModel test still passes unchanged apart from the renamed first test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/gallery/ui/GalleryViewModel.kt app/src/main/java/com/jjveleber/gallery/MainActivity.kt app/src/test/java/com/jjveleber/gallery/testing/Fakes.kt app/src/test/java/com/jjveleber/gallery/ui/GalleryViewModelTest.kt
git commit -m "feat(ui): launch flow with folder list home and remembered folder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Folder list screen and photo access on launch

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/ui/folders/FolderListScreen.kt`
- Modify: `app/src/main/java/com/jjveleber/gallery/ui/GalleryRoot.kt`

**Interfaces:**
- Consumes: `Screen.Folders`, `vm.started`, `vm.start`, `vm.openFolder` (Task 5); `MediaFolder` (Task 2); `MediaThumbnail` (Task 4); `FolderRef.name` / `displayPath` (Task 1).
- Produces: `@Composable fun FolderListScreen(folders: List<MediaFolder>, onOpen: (FolderRef) -> Unit, onBrowse: () -> Unit)`.

This task is UI wiring with no new pure logic; its gate is a clean build plus the emulator check in Task 8.

- [ ] **Step 1: Create the folder list screen**

Create `app/src/main/java/com/jjveleber/gallery/ui/folders/FolderListScreen.kt`:

```kotlin
package com.jjveleber.gallery.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.jjveleber.gallery.imaging.MediaThumbnail
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.scan.MediaFolder

private val COVER = 72.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(folders: List<MediaFolder>, onOpen: (FolderRef) -> Unit, onBrowse: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gallery") },
                actions = { TextButton(onClick = onBrowse) { Text("Browse other folder") } },
            )
        },
    ) { padding ->
        if (folders.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No folders with images found")
                Button(onClick = onBrowse) { Text("Browse other folder") }
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(folders, key = { "${it.ref.volume}:${it.ref.relativePath}" }) { folder ->
                    FolderRow(folder) { onOpen(folder.ref) }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: MediaFolder, onClick: () -> Unit) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { COVER.roundToPx() }
    val cover = remember(folder.coverUri, sizePx) {
        ImageRequest.Builder(context).data(MediaThumbnail(folder.coverUri, folder.coverMime, sizePx)).build()
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(COVER).background(Color(0xFF1C1C1C)),
        )
        Column(Modifier.weight(1f)) {
            Text(folder.ref.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(folder.ref.displayPath, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Text("%,d".format(folder.count), style = MaterialTheme.typography.bodyMedium)
    }
}
```

- [ ] **Step 2: Ask for photo access on launch and show the folder list**

In `app/src/main/java/com/jjveleber/gallery/ui/GalleryRoot.kt`, add the import `import com.jjveleber.gallery.ui.folders.FolderListScreen`. Replace everything from the `// True from launch until the Open with request ...` comment through the closing brace of `if (openWithUri != null) { ... }` with:

```kotlin
    // True from launch until the Open with request has been handed to the ViewModel, so nothing shows
    // behind the permission dialog.
    var openWithPending by rememberSaveable(openWithUri) { mutableStateOf(openWithUri != null) }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (openWithUri != null) {
            vm.openWith(openWithUri, mayReadMedia = granted)
            openWithPending = false
        } else {
            vm.start(mayReadMedia = granted)
        }
    }
    // Photo access is asked for until granted; Android remembers the answer. Denied falls back to the picker.
    LaunchedEffect(openWithUri) {
        if (vm.started) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        when {
            !granted -> requestPermission.launch(permission)
            openWithUri != null -> {
                vm.openWith(openWithUri, mayReadMedia = true)
                openWithPending = false
            }
            else -> vm.start(mayReadMedia = true)
        }
    }
```

In the `when (val s = screen)` block, add this branch after the `Screen.Picker, is Screen.Scanning -> { ... }` branch:

```kotlin
        is Screen.Folders -> FolderListScreen(s.folders, onOpen = vm::openFolder, onBrowse = { pickTree.launch(null) })
```

- [ ] **Step 3: Build**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Quick emulator smoke**

With the emulator running (see Task 3 Step 2):

```bash
ADB=/home/linuxbrew/.linuxbrew/share/android-commandlinetools/platform-tools/adb
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:installDebug
$ADB shell pm grant com.jjveleber.gallery android.permission.READ_MEDIA_IMAGES
$ADB shell am start -n com.jjveleber.gallery/.MainActivity
sleep 3; $ADB exec-out screencap -p > /tmp/claude-folders.png
```

Open the screenshot with the Read tool. Expected: a "Gallery" top bar with **Browse other folder**, and either folder rows or "No folders with images found" (no images are seeded yet; Task 8 seeds them). Not a black screen.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/gallery/ui/folders/FolderListScreen.kt app/src/main/java/com/jjveleber/gallery/ui/GalleryRoot.kt
git commit -m "feat(ui): folder list start screen and photo access on launch

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Grid cell size and prefetch

**Files:**
- Modify: `app/src/main/java/com/jjveleber/gallery/ui/grid/GridSizing.kt`
- Modify: `app/src/main/java/com/jjveleber/gallery/ui/grid/GridScreen.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/ui/grid/GridSizingTest.kt`

**Interfaces:**
- Consumes: `gridRequest(context, item, animate, sizePx, listener)` (Task 4).
- Produces: `fun gridColumns(availablePx: Int, minCellPx: Int): Int`; `fun prefetchRange(firstVisible: Int, lastVisible: Int, itemCount: Int, forward: Boolean, pages: Int = 2): IntRange`.

Why the grid computes its own column count: the cell and the prefetch must request the same pixel size so they share one memory cache entry. `gridColumns` uses the same formula as `GridCells.Adaptive` with no spacing, so column counts do not change.

- [ ] **Step 1: Write the failing tests**

Append to the class in `app/src/test/java/com/jjveleber/gallery/ui/grid/GridSizingTest.kt` (add `import org.junit.Assert.assertTrue`):

```kotlin
    @Test fun columnsMatchAdaptiveGrid() {
        assertEquals(4, gridColumns(availablePx = 1000, minCellPx = 240))
        assertEquals(2, gridColumns(availablePx = 480, minCellPx = 240))
        assertEquals(1, gridColumns(availablePx = 100, minCellPx = 240))
    }

    @Test fun prefetchForwardIsTwoScreens() = assertEquals(20..39, prefetchRange(10, 19, itemCount = 1000, forward = true))

    @Test fun prefetchForwardClampsAtTheEnd() {
        assertEquals(990..999, prefetchRange(980, 989, itemCount = 1000, forward = true))
        assertTrue(prefetchRange(990, 999, itemCount = 1000, forward = true).isEmpty())
    }

    @Test fun prefetchBackwardIsTwoScreensClampedAtTheTop() {
        assertEquals(30..49, prefetchRange(50, 59, itemCount = 1000, forward = false))
        assertEquals(0..9, prefetchRange(10, 19, itemCount = 1000, forward = false))
        assertTrue(prefetchRange(0, 9, itemCount = 1000, forward = false).isEmpty())
    }

    @Test fun nothingVisibleMeansNoPrefetch() {
        assertTrue(prefetchRange(0, -1, itemCount = 0, forward = true).isEmpty())
        assertTrue(prefetchRange(0, -1, itemCount = 50, forward = true).isEmpty())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.gallery.ui.grid.GridSizingTest'`
Expected: FAIL, compilation error `Unresolved reference 'gridColumns'`.

- [ ] **Step 3: Implement the pure functions**

Append to `app/src/main/java/com/jjveleber/gallery/ui/grid/GridSizing.kt`:

```kotlin

/** Columns of at least [minCellPx] that fit in [availablePx]: the count GridCells.Adaptive picks with no spacing. */
fun gridColumns(availablePx: Int, minCellPx: Int): Int = maxOf(1, availablePx / maxOf(1, minCellPx))

/**
 * Cells to prefetch: up to [pages] screens past the visible cells ([firstVisible]..[lastVisible]) in the scroll
 * direction, clamped to the list. Empty when nothing is visible.
 */
fun prefetchRange(firstVisible: Int, lastVisible: Int, itemCount: Int, forward: Boolean, pages: Int = 2): IntRange {
    if (itemCount <= 0 || lastVisible < firstVisible) return IntRange.EMPTY
    val ahead = (lastVisible - firstVisible + 1) * pages
    return if (forward) (lastVisible + 1)..minOf(itemCount - 1, lastVisible + ahead)
    else maxOf(0, firstVisible - ahead)..(firstVisible - 1)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Use them in the grid**

In `app/src/main/java/com/jjveleber/gallery/ui/grid/GridScreen.kt`:

Add these imports:

```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import coil3.SingletonImageLoader
import com.jjveleber.gallery.imaging.gridRequest
```

and remove `import com.jjveleber.gallery.imaging.imageRequest` (no longer used in this file).

Replace the `else { LazyVerticalGrid(...) { ... } }` branch of `if (items.isEmpty())` with:

```kotlin
        } else {
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize().background(Color(0xFF101010))) {
                val density = LocalDensity.current
                val availablePx = constraints.maxWidth - with(density) { (GRID_PADDING * 2).roundToPx() }
                val columns = gridColumns(availablePx, with(density) { settings.gridSize.cellDp.dp.roundToPx() })
                // Square cells; the image area is the cell minus the thumbnail's 2 dp padding on each side.
                val thumbPx = (availablePx / columns - with(density) { 4.dp.roundToPx() }).coerceAtLeast(1)
                PrefetchAhead(gridState, items, settings.animateGrid, thumbPx)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    contentPadding = PaddingValues(GRID_PADDING),
                    modifier = Modifier
                        .fillMaxSize()
                        .pinchSteps { zoom -> vm.updateSettings { it.copy(gridSize = currentSize.step(zoom)) } },
                ) {
                    itemsIndexed(items, key = { _, item -> item.uri }) { index, item ->
                        Thumbnail(item, settings.animateGrid, thumbPx, registry) { vm.openViewer(index, play = false) }
                    }
                }
            }
        }
```

Add above `pinchSteps`:

```kotlin
private val GRID_PADDING = 4.dp

/**
 * Loads about [pages][prefetchRange] screens of thumbnails past the visible cells, in the scroll direction, into the
 * memory cache, so they show at once when scrolled in. Builds the same request as each cell.
 */
@Composable
private fun PrefetchAhead(state: LazyGridState, items: List<ImageItem>, animate: Boolean, sizePx: Int) {
    val context = LocalContext.current
    LaunchedEffect(state, items, animate, sizePx) {
        val loader = SingletonImageLoader.get(context)
        var previousFirst = -1
        var forward = true
        var previous = IntRange.EMPTY
        snapshotFlow {
            val visible = state.layoutInfo.visibleItemsInfo
            (visible.firstOrNull()?.index ?: 0) to (visible.lastOrNull()?.index ?: -1)
        }.collect { (first, last) ->
            if (previousFirst >= 0 && first != previousFirst) forward = first > previousFirst
            previousFirst = first
            val range = prefetchRange(first, last, items.size, forward)
            // Nearest cells first; cells already requested for the previous position are skipped.
            for (i in if (forward) range else range.reversed()) {
                if (i !in previous) loader.enqueue(gridRequest(context, items[i], animate, sizePx))
            }
            previous = range
        }
    }
}
```

Replace the `Thumbnail` signature and its `request`:

```kotlin
@Composable
private fun Thumbnail(item: ImageItem, animate: Boolean, sizePx: Int, registry: AnimatedRegistry, onClick: () -> Unit) {
    val context = LocalContext.current
    var failed by remember(item.uri, animate) { mutableStateOf(false) }
    var animated by remember(item.uri) { mutableStateOf(false) }
    val request = remember(item.uri, animate, sizePx) {
        gridRequest(context, item, animate, sizePx, object : ImageRequest.Listener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                failed = false
                animated = registry.isAnimated(item.uri)
            }

            override fun onError(request: ImageRequest, result: ErrorResult) {
                failed = true
            }
        })
    }
```

(the rest of `Thumbnail` is unchanged).

- [ ] **Step 6: Build and run all tests**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, no unused-import warnings for `GridCells` (still used by `Fixed`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/jjveleber/gallery/ui/grid/GridSizing.kt app/src/main/java/com/jjveleber/gallery/ui/grid/GridScreen.kt app/src/test/java/com/jjveleber/gallery/ui/grid/GridSizingTest.kt
git commit -m "feat(grid): fixed cell size requests and 2-screen prefetch

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Emulator verification, README, device checkpoint

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: the finished app from Tasks 1 to 7.
- Produces: an updated README. Nothing is published or released (the controller handles the release).

- [ ] **Step 1: Full test run**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/linuxbrew/.linuxbrew/share/android-commandlinetools
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest
```

Expected: BUILD SUCCESSFUL; all JVM and instrumented tests pass.

- [ ] **Step 2: Seed images on the emulator and index them**

```bash
ADB=$ANDROID_HOME/platform-tools/adb
IMG=app/src/test/resources/images
$ADB shell mkdir -p /sdcard/Pictures/GalleryCheck/sub /sdcard/Pictures/GalleryCheck/.hidden /sdcard/Pictures/My_Photos /sdcard/Pictures/MyXPhotos /sdcard/Pictures/GalleryBig
$ADB push $IMG/still.jpg /sdcard/Pictures/GalleryCheck/a.jpg
$ADB push $IMG/rotated.jpg /sdcard/Pictures/GalleryCheck/rotated.jpg
$ADB push $IMG/anim.gif /sdcard/Pictures/GalleryCheck/sub/anim.gif
$ADB push $IMG/still.png /sdcard/Pictures/GalleryCheck/.hidden/h.png
$ADB push $IMG/still.png /sdcard/Pictures/My_Photos/m.png
$ADB push $IMG/still.webp /sdcard/Pictures/MyXPhotos/x.webp
$ADB shell 'for i in $(seq 1 1200); do cp /sdcard/Pictures/GalleryCheck/a.jpg /sdcard/Pictures/GalleryBig/img_$i.jpg; done'
```

Files written through `/sdcard` are normally indexed at once on API 30+. Check:

```bash
$ADB shell "content query --uri content://media/external/images/media --projection relative_path:_display_name --where \"relative_path LIKE 'Pictures/GalleryCheck%' OR relative_path LIKE 'Pictures/My%'\""
$ADB shell "content query --uri content://media/external/images/media --projection _id --where \"relative_path = 'Pictures/GalleryBig/'\"" | grep -c Row
```

Expected: rows for `a.jpg`, `rotated.jpg`, `sub/anim.gif`, `m.png`, `x.webp` (`h.png` may or may not be indexed; the app must hide it either way), and `1200`. If rows are missing, trigger a scan with `$ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/GalleryCheck/a.jpg` (once per missing file), or for many files `$ADB reboot && $ADB wait-for-device && $ADB shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'`, then re-run the queries. Report the exact output if they still do not match.

- [ ] **Step 3: Walk the app on the emulator**

Use screenshots (`$ADB exec-out screencap -p > /tmp/claude-<name>.png`, then view with the Read tool) and taps found through `$ADB shell uiautomator dump /sdcard/ui.xml && $ADB shell cat /sdcard/ui.xml` (find the `text="..."` node and tap the centre of its `bounds` with `$ADB shell input tap X Y`). Check each item and note pass or fail:

1. Granted: `./gradlew :app:installDebug`, `$ADB shell pm grant com.jjveleber.gallery android.permission.READ_MEDIA_IMAGES`, `$ADB shell am start -n com.jjveleber.gallery/.MainActivity`. The folder list shows GalleryBig (1,200), GalleryCheck (2), sub with path `Pictures/GalleryCheck/sub` (1), My_Photos (1), MyXPhotos (1), each with a cover; no `.hidden` row.
2. Tap GalleryCheck: grid titled GalleryCheck, "3 images"; rotated.jpg upright; anim.gif shows the ANIM badge.
3. `$ADB shell am force-stop com.jjveleber.gallery` then start again: the GalleryCheck grid opens directly.
4. Tap **Folder**: back to the folder list. Tap My_Photos: "1 image" (m.png only, not x.webp).
5. Open GalleryBig: the grid appears within about a second; scroll down with `$ADB shell input swipe 600 1500 600 300 200` a few times; cells are filled when they scroll in.
6. Denied: `$ADB shell pm revoke com.jjveleber.gallery android.permission.READ_MEDIA_IMAGES`, start the app, press `$ADB shell input keyevent KEYCODE_BACK` on the permission dialog (deny). The picker screen with **Pick folder** shows, as before.

Fix any failure in the owning task's code (with a test where the failure is in pure logic), re-run Step 1, and commit the fix separately before continuing.

- [ ] **Step 4: Update README.md**

Make these exact edits to `README.md`:

Replace the intro paragraph (the one starting `Image viewer for Amazon Fire tablets`) with:

```markdown
Image viewer for Amazon Fire tablets (Fire OS 8 / Android 11+). Lists every folder on the tablet that has images, and shows a folder (with its subfolders) as a thumbnail grid or a full-screen slideshow. Opens images from other apps through **Open with**. Supports JPEG, PNG, WebP, GIF, BMP and HEIC (where the tablet can decode it), and plays animated GIF, WebP and APNG.
```

In "Using it", replace the **Pick folder** bullet with these three bullets:

```markdown
- **Photo access:** the first time you open Gallery it asks for access to your photos. Tap **Allow**; Android remembers it. If you tap **Don't allow**, Gallery works as before: tap **Pick folder** and choose a folder in the system picker each time. To change your mind later, go to **Settings > Apps & Notifications > Manage All Applications > Gallery > Permissions** and allow storage (photos and media) access.
- **Folders:** with access allowed, Gallery starts with a list of every folder that has images, with how many images are directly in it and a cover picture. Tap a folder to see it and all its subfolders. Gallery reopens the last folder you opened from this list the next time you start it. Tap **Folder** in the grid top bar to get back to the list.
- **Browse other folder:** if a folder is missing from the list (for example it has a `.nomedia` file, or it is on storage the tablet does not index), tap **Browse other folder** at the top of the list and pick it in the system picker. Folders picked this way are not reopened on the next start.
```

In the **Open with** bullet, replace `Allow photo access to swipe through the other images in the same folder.` with `With photo access allowed you can swipe through the other images in the same folder.`

Replace the paragraph starting `Your sort order and grid size` with:

```markdown
Your sort order and grid size (top bar), the options in the Settings sheet (animation switches, auto advance, interval, transition) and the last folder you opened from the folder list are all saved between launches.
```

Check: `grep -n "remembers nothing" README.md` prints nothing, and `grep -cP '\x{2014}' README.md` (the em-dash code point) prints `0`.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: README for the folder list and photo access

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Human device checkpoint (Fire Max 11)**

Stop here and hand over to the human with the APK `app/build/outputs/apk/debug/app-debug.apk` (or the controller's release build). Ask them to check on the Fire and report pass or fail for each:

1. First launch asks for photo access; **Allow** shows the folder list (names, paths, counts, covers; SD card folders marked "SD card", if a card is present). No hidden folders.
2. Open a folder, close the app (swipe it away from recents), start it again: the same folder's grid opens directly. **Folder** returns to the list.
3. Scan speed: open a folder with 1,000+ images (with subfolders); the grid appears in about a second, much faster than the old picker scan.
4. Thumbnails: sharp at every grid size (S, M, L, XL); phone photos upright; ANIM badges on animated files with Animate thumbnails off; animated thumbnails play with it on.
5. Prefetch: fling through a large folder; cells are mostly filled as they scroll in, both down and back up.
6. Denied fallback: uninstall and reinstall (or revoke the permission in Settings), tap **Don't allow**: the **Pick folder** screen appears and works as before.
7. **Browse other folder** opens the system picker; the picked folder shows in the grid; after a restart the app starts at the folder list.
8. The original spec section 7 manual checklist: large mixed folder with nested subfolders; every sort order and direction plus Reshuffle; pinch and menu between all grid sizes; grid and full-screen animation on and off; slideshow at several intervals, zoom pauses auto advance, loop at the end; Open with from Files with the permission granted and denied (fallback and Browse this folder); rotation on the grid and the viewer; a corrupt file in the folder.

Record the results for the controller. Do not publish a release.
