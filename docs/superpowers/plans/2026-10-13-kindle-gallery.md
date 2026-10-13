# Kindle Fire Image Gallery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloadable native Android image viewer for the Amazon Fire Max 11 that shows a picked folder (recursive) as a thumbnail grid and a full-screen slideshow, opens images from other apps via "Open with", and plays animated GIF, WebP and APNG per a per-view setting.

**Architecture:** Single-module Kotlin + Jetpack Compose app. Sorting, header sniffing, folder walking, Open with path parsing, the auto advance timer, settings mapping and the screen state machine are pure Kotlin and unit-tested on the JVM. Coil 3 loads every image; a custom Coil `Decoder` routes animated files to APNG4Android. Telephoto provides zoom inside a looping `HorizontalPager`. Settings persist in DataStore; the picked folder is never persisted.

**Tech Stack:** Kotlin 2.0.21, AGP 8.5.2, Gradle 8.7, Compose BOM 2024.09.00 (Material 3), Coil 3.0.4, APNG4Android 3.0.5 (`apng`, `awebp`, `gif`), Telephoto 0.14.0 (`zoomable-image-coil3`), AndroidX DataStore Preferences 1.1.1, kotlinx.coroutines 1.9.0, okio 3.9.1, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-13-kindle-gallery-design.md`

## Global Constraints

- Package and applicationId: `com.jjveleber.gallery`. Source root: `app/src/main/java/com/jjveleber/gallery/`. App label: `Gallery`.
- `minSdk = 30`, `targetSdk = 34`, `compileSdk = 34` (Fire OS 8 is API 30; SDK platform 34 is the one installed).
- No Google Play Services, Firebase, or any `com.google.android.gms` dependency.
- Supported MIME types: `image/jpeg`, `image/png`, `image/apng`, `image/webp`, `image/gif`, `image/bmp`, `image/x-ms-bmp`, `image/heic`, `image/heif`. Extension fallback: `jpg jpeg png apng webp gif bmp heic heif`.
- Grid presets (target cell size): S = 120 dp, M = 180 dp, L = 300 dp, XL = 480 dp.
- Intervals (seconds): 3, 5, 10, 15, 30, 60, 120, 300.
- Defaults: sort Name ascending, grid M, animateGrid off, animateFullScreen on, autoAdvance off, interval 5 s, transition Crossfade.
- Edge tap zones: outer 15% of the width on each side. Overlay auto-hides after 3 s. Crossfade lasts 400 ms.
- The picked folder and its URI grant are never persisted (no `takePersistableUriPermission`).
- Every `./gradlew` command runs with `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` set in the shell (the default `java` is too new for Gradle 8.7).
- `ANDROID_HOME` is `/home/linuxbrew/.linuxbrew/share/android-commandlinetools`. Instrumented tests use the existing AVD: start it with `$ANDROID_HOME/emulator/emulator -avd videoeditor_api34 -no-window -no-audio &` then `$ANDROID_HOME/platform-tools/adb wait-for-device`.
- Never use the em-dash character anywhere: code, UI copy, comments, docs, commit messages. Use a colon, comma, parentheses, or period.
- Every commit message ends with a blank line then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Hidden `.thumbnails` / `.trashed-*` folders inside a picked folder** (Android creates these, often with thousands of cached copies): must be skipped, so the grid shows no duplicates. Test: Task 7 `skipsHiddenFilesAndFolders`.
2. **A folder with exactly one image:** the viewer must not loop into blank pages, crash, or "advance" to itself. Test: Task 9 `singleItemDoesNotLoop`.
3. **Phone JPEGs stored sideways with an EXIF orientation tag:** must display upright in grid and viewer. Test: Task 4 `exifRotationIsApplied`.
4. **Picking another folder (or Cancel) while a large scan is still running:** the first scan's results must never appear. Test: Task 10 `pickingAgainDuringScanDiscardsFirstScan`.
5. **File names with very long digit runs** (camera timestamps such as `IMG_20240101_123456789012345`): natural sort must not overflow or crash. Test: Task 2 `naturalOrderHandlesHugeDigitRuns`.

## Spec deviation (agreed in review)

The spec lists an instrumented `FolderScanner` test on a temporary folder tree. A document-tree grant cannot be obtained in an instrumented test without driving the system picker UI, so the walk logic is split into a pure `walkImages` function (JVM-tested against a fake `DocumentSource`, Task 7) and a thin `AndroidDocumentSource` adapter verified manually on the device.

---

## File Map

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew, gradlew.bat, gradle/wrapper/*
app/build.gradle.kts
app/src/main/AndroidManifest.xml
tools/make_test_assets.py                  generates the image test fixtures
app/src/test/resources/images/*            fixtures for JVM tests
app/src/androidTest/assets/images/*        fixtures for instrumented tests
app/src/main/java/com/jjveleber/gallery/
  MainActivity.kt                 sets content, passes an "Open with" URI to GalleryRoot
  GalleryApp.kt                   Application, owns AppContainer, provides Coil's singleton ImageLoader
  AppContainer.kt                 manual DI
  model/ImageItem.kt              ImageItem
  model/Sort.kt                   SortOrder, SortSpec, NaturalOrder, sortItems
  model/Settings.kt               Settings, GridSize, Transition, INTERVALS_SEC
  imaging/AnimationSniffer.kt     ImageKind, AnimationSniffer (header parsing on okio)
  imaging/AnimatedRegistry.kt     which URIs were sniffed as animated (for the grid badge)
  imaging/Requests.kt             AnimateKey, SourceKey, imageRequest()
  imaging/AnimatedDecoder.kt      Coil Decoder + Factory using APNG4Android
  imaging/ImageLoaders.kt         buildImageLoader()
  settings/SettingsStore.kt       SettingsStore, DataStoreSettingsStore, toSettings/writeTo
  scan/SupportedTypes.kt          isSupportedImage
  scan/FolderWalk.kt              DocEntry, DocumentSource, walkImages
  scan/FolderScanner.kt           FolderScanner, AndroidDocumentSource, AndroidFolderScanner
  scan/OpenWithLocation.kt        FolderLocation, locationFromPath, locationFromDocumentId, externalStorageDocId
  scan/OpenWithResolver.kt        OpenWithResult, OpenWithResolver, AndroidOpenWithResolver
  slideshow/AutoAdvanceTimer.kt   PauseReason, AutoAdvanceTimer
  slideshow/ViewerMath.kt         LoopPaging, TapZone, tapZone, nextInterval, intervalLabel
  ui/GalleryViewModel.kt          Screen, OpenWithState, GalleryViewModel
  ui/GalleryRoot.kt               screen switch, folder pickers, permission request
  ui/picker/PickerScreen.kt
  ui/grid/GridSizing.kt           GridSize.step(zoom)
  ui/grid/GridScreen.kt           top bar, menus, lazy grid, thumbnail, pinch
  ui/settings/SettingsSheet.kt
  ui/viewer/ViewerScreen.kt       pager, overlay, crossfade, timer wiring
app/src/test/java/com/jjveleber/gallery/
  testing/Items.kt, testing/Fakes.kt, testing/MainDispatcherRule.kt
  model/SortTest.kt, imaging/AnimationSnifferTest.kt, settings/SettingsStoreTest.kt,
  scan/SupportedTypesTest.kt, scan/FolderWalkTest.kt, scan/OpenWithLocationTest.kt,
  slideshow/AutoAdvanceTimerTest.kt, slideshow/ViewerMathTest.kt,
  ui/GalleryViewModelTest.kt, ui/grid/GridSizingTest.kt
app/src/androidTest/java/com/jjveleber/gallery/imaging/ImagePipelineTest.kt
docs/device-check.md                       results of the Task 5 on-device check
README.md
```

---

### Task 1: Project scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `local.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*` (copied)
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/jjveleber/gallery/MainActivity.kt`

**Interfaces:**
- Consumes: nothing
- Produces: a building app module with every dependency later tasks use.

- [ ] **Step 1: Copy the Gradle wrapper from the sibling project**

```bash
cd /home/jjveleber/projects/android/gallery
cp -r ../video-editor/gradle ../video-editor/gradlew ../video-editor/gradlew.bat ../video-editor/local.properties .
```

`.gitignore` already exists (it ignores `local.properties`).

- [ ] **Step 2: Write the root build files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Gallery"
include(":app")
```

`build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
```

`gradle.properties`:

```
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 3: Write `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jjveleber.gallery"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jjveleber.gallery"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okio:okio:3.9.1")

    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("me.saket.telephoto:zoomable-image-coil3:0.14.0")
    val apng4android = "3.0.5"
    implementation("com.github.penfeizhou.android.animation:apng:$apng4android")
    implementation("com.github.penfeizhou.android.animation:awebp:$apng4android")
    implementation("com.github.penfeizhou.android.animation:gif:$apng4android")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

- [ ] **Step 4: Write the manifest and a minimal activity**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:label="Gallery"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`configChanges` keeps the activity (and the session-only folder grant) alive across rotation.

`app/src/main/java/com/jjveleber/gallery/MainActivity.kt`:

```kotlin
package com.jjveleber.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Text("Gallery")
                }
            }
        }
    }
}
```

- [ ] **Step 5: Build**

Run: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If dependency resolution fails because a library needs a newer `compileSdk`, report the exact error and stop (do not bump versions silently).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle app
git commit -m "chore: project scaffold

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Model and sorting

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/model/ImageItem.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/model/Sort.kt`
- Create: `app/src/test/java/com/jjveleber/gallery/testing/Items.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/model/SortTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `data class ImageItem(uri: String, name: String, relPath: String, sizeBytes: Long, modifiedMs: Long, mime: String)`
  - `enum class SortOrder { Name, Modified, Path, Size, Random }`
  - `data class SortSpec(order: SortOrder = Name, ascending: Boolean = true, randomSeed: Long = 0L)`
  - `object NaturalOrder : Comparator<String>`
  - `fun sortItems(items: List<ImageItem>, spec: SortSpec): List<ImageItem>`
  - test helper `fun item(relPath: String, modifiedMs: Long = 0, sizeBytes: Long = 0): ImageItem` (uri `content://test/<relPath>`)

- [ ] **Step 1: Write `ImageItem` and the test helper**

`model/ImageItem.kt`:

```kotlin
package com.jjveleber.gallery.model

/** One image file. [uri] is a content URI string. [relPath] is relative to the browsed folder, separated by '/'. */
data class ImageItem(
    val uri: String,
    val name: String,
    val relPath: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val mime: String,
)
```

`app/src/test/java/com/jjveleber/gallery/testing/Items.kt`:

```kotlin
package com.jjveleber.gallery.testing

import com.jjveleber.gallery.model.ImageItem

fun item(relPath: String, modifiedMs: Long = 0, sizeBytes: Long = 0) = ImageItem(
    uri = "content://test/$relPath",
    name = relPath.substringAfterLast('/'),
    relPath = relPath,
    sizeBytes = sizeBytes,
    modifiedMs = modifiedMs,
    mime = "image/jpeg",
)
```

- [ ] **Step 2: Write the failing tests**

`model/SortTest.kt`:

```kotlin
package com.jjveleber.gallery.model

import com.jjveleber.gallery.testing.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortTest {
    private fun names(items: List<ImageItem>) = items.map { it.relPath }

    @Test fun naturalOrderSortsDigitRunsByValue() {
        assertEquals(listOf("img1", "img2", "img10"), listOf("img10", "img2", "img1").sortedWith(NaturalOrder))
    }

    @Test fun naturalOrderIgnoresCase() {
        assertEquals(listOf("apple", "Banana", "cherry"), listOf("cherry", "Banana", "apple").sortedWith(NaturalOrder))
    }

    @Test fun naturalOrderHandlesHugeDigitRuns() {
        val small = "IMG_99999999999999999999999"
        val big = "IMG_100000000000000000000000"
        assertTrue(NaturalOrder.compare(small, big) < 0)
        assertTrue(NaturalOrder.compare(big, small) > 0)
    }

    @Test fun naturalOrderIsTotalForLeadingZeros() {
        val c = NaturalOrder.compare("img01", "img1")
        assertNotEquals(0, c)
        assertEquals(-c, NaturalOrder.compare("img1", "img01"))
    }

    @Test fun naturalOrderShorterPrefixFirst() {
        assertTrue(NaturalOrder.compare("a", "ab") < 0)
    }

    @Test fun nameAscendingAndDescending() {
        val items = listOf(item("b10.jpg"), item("b2.jpg"), item("A.jpg"))
        assertEquals(listOf("A.jpg", "b2.jpg", "b10.jpg"), names(sortItems(items, SortSpec(SortOrder.Name))))
        assertEquals(listOf("b10.jpg", "b2.jpg", "A.jpg"), names(sortItems(items, SortSpec(SortOrder.Name, ascending = false))))
    }

    @Test fun nameTiesBrokenByRelPath() {
        val items = listOf(item("z/x.jpg"), item("a/x.jpg"))
        assertEquals(listOf("a/x.jpg", "z/x.jpg"), names(sortItems(items, SortSpec(SortOrder.Name))))
    }

    @Test fun pathGroupsBySubfolderThenName() {
        val items = listOf(item("trip/b.jpg"), item("z.jpg"), item("trip/a.jpg"), item("a.jpg"), item("trip/day2/c.jpg"))
        assertEquals(
            listOf("a.jpg", "z.jpg", "trip/a.jpg", "trip/b.jpg", "trip/day2/c.jpg"),
            names(sortItems(items, SortSpec(SortOrder.Path))),
        )
    }

    @Test fun modifiedSortsByTimeThenName() {
        val items = listOf(item("c.jpg", modifiedMs = 5), item("b.jpg", modifiedMs = 1), item("a.jpg", modifiedMs = 5))
        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), names(sortItems(items, SortSpec(SortOrder.Modified))))
        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), names(sortItems(items, SortSpec(SortOrder.Modified, ascending = false))))
    }

    @Test fun sizeSortsBySizeThenName() {
        val items = listOf(item("b.jpg", sizeBytes = 10), item("a.jpg", sizeBytes = 10), item("c.jpg", sizeBytes = 1))
        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), names(sortItems(items, SortSpec(SortOrder.Size))))
    }

    @Test fun randomIsStableForASeedRegardlessOfInputOrder() {
        val items = (1..20).map { item("img$it.jpg") }
        val spec = SortSpec(SortOrder.Random, randomSeed = 42)
        assertEquals(sortItems(items, spec), sortItems(items.reversed(), spec))
    }

    @Test fun randomDiffersForAnotherSeed() {
        val items = (1..20).map { item("img$it.jpg") }
        assertNotEquals(
            sortItems(items, SortSpec(SortOrder.Random, randomSeed = 1)),
            sortItems(items, SortSpec(SortOrder.Random, randomSeed = 2)),
        )
    }

    @Test fun randomIgnoresDirection() {
        val items = (1..20).map { item("img$it.jpg") }
        assertEquals(
            sortItems(items, SortSpec(SortOrder.Random, ascending = true, randomSeed = 7)),
            sortItems(items, SortSpec(SortOrder.Random, ascending = false, randomSeed = 7)),
        )
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SortTest*'`
Expected: FAIL to compile with `Unresolved reference: NaturalOrder` (and `sortItems`, `SortSpec`).

- [ ] **Step 4: Implement `model/Sort.kt`**

```kotlin
package com.jjveleber.gallery.model

import kotlin.random.Random

enum class SortOrder { Name, Modified, Path, Size, Random }

data class SortSpec(
    val order: SortOrder = SortOrder.Name,
    val ascending: Boolean = true,
    val randomSeed: Long = 0L,
)

/**
 * Natural, case-insensitive order: digit runs compare by numeric value, so "img2" sorts before "img10".
 * Strings equal under these rules fall back to plain comparison, so the order is total.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val startA = i
                while (i < a.length && a[i].isAsciiDigit()) i++
                val startB = j
                while (j < b.length && b[j].isAsciiDigit()) j++
                // Compare digit runs as text after stripping leading zeros, so any length works without overflow.
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val c = numA.compareTo(numB)
                if (c != 0) return c
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}

private val byName: Comparator<ImageItem> =
    compareBy<ImageItem, String>(NaturalOrder) { it.name }.thenBy(NaturalOrder) { it.relPath }

private val byPath: Comparator<ImageItem> =
    compareBy<ImageItem, String>(NaturalOrder) { it.relPath.substringBeforeLast('/', "") }.thenBy(NaturalOrder) { it.name }

fun sortItems(items: List<ImageItem>, spec: SortSpec): List<ImageItem> {
    val comparator = when (spec.order) {
        SortOrder.Name -> byName
        SortOrder.Path -> byPath
        SortOrder.Modified -> compareBy<ImageItem> { it.modifiedMs }.then(byName)
        SortOrder.Size -> compareBy<ImageItem> { it.sizeBytes }.then(byName)
        // Start from a fixed order so a seed gives the same shuffle whatever order the scan returned.
        SortOrder.Random -> return items.sortedWith(byPath).shuffled(Random(spec.randomSeed))
    }
    return items.sortedWith(if (spec.ascending) comparator else comparator.reversed())
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SortTest*'`
Expected: PASS (13 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jjveleber/gallery/model app/src/test
git commit -m "feat(model): image item and natural sort orders

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Test fixtures and animation sniffer

**Files:**
- Create: `tools/make_test_assets.py`
- Create (generated): `app/src/test/resources/images/*`, `app/src/androidTest/assets/images/*`
- Create: `app/src/main/java/com/jjveleber/gallery/imaging/AnimationSniffer.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/imaging/AnimationSnifferTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `enum class ImageKind { AnimatedGif, AnimatedWebp, Apng, Static }`
  - `object AnimationSniffer { fun sniff(source: BufferedSource): ImageKind }` (consumes `source`; callers pass a `peek()`; never throws on bad input)
  - Fixtures (64x48 unless noted): `anim.gif`, `anim.png` (APNG), `anim.webp` (frames use dispose-to-background and alpha blending), `still.gif`, `still.png`, `still.webp`, `still.jpg`, `still.bmp`, `rotated.jpg` (stored 40x20 with EXIF orientation 6, displays 20x40), `corrupt.jpg`

- [ ] **Step 1: Write the fixture generator**

`tools/make_test_assets.py`:

```python
"""Generates image fixtures for JVM and instrumented tests.

Usage: python3 -I tools/make_test_assets.py
Needs Pillow and gif2webp (libwebp) on PATH.
"""
import os
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTS = [
    os.path.join(ROOT, "app/src/test/resources/images"),
    os.path.join(ROOT, "app/src/androidTest/assets/images"),
]
W, H = 64, 48


def frame(i):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(im).rectangle([i * 8, 8, i * 8 + 16, 40], fill=(255, 40 * i, 0, 255))
    return im


def build(out):
    frames = [frame(i) for i in range(4)]
    frames[0].save(os.path.join(out, "anim.gif"), save_all=True, append_images=frames[1:],
                   duration=200, loop=0, disposal=2, transparency=0)
    frames[0].save(os.path.join(out, "still.gif"))
    frames[0].save(os.path.join(out, "anim.png"), save_all=True, append_images=frames[1:],
                   duration=200, loop=0, disposal=1, blend=1)
    frames[0].save(os.path.join(out, "still.png"))
    frames[0].save(os.path.join(out, "still.webp"), lossless=True)
    frames[0].convert("RGB").save(os.path.join(out, "still.jpg"), quality=90)
    frames[0].convert("RGB").save(os.path.join(out, "still.bmp"))
    # gif2webp maps the GIF's dispose-to-background frames to WebP dispose and blend flags.
    subprocess.run(["gif2webp", "-mixed", "-quiet", os.path.join(out, "anim.gif"),
                    "-o", os.path.join(out, "anim.webp")], check=True)
    exif = Image.Exif()
    exif[0x0112] = 6  # Orientation: rotate 90 degrees clockwise to display.
    Image.new("RGB", (40, 20), (0, 128, 255)).save(os.path.join(out, "rotated.jpg"), exif=exif)
    with open(os.path.join(out, "corrupt.jpg"), "wb") as f:
        f.write(b"\xff\xd8\xff\xe0" + b"\x00" * 200)


def main():
    for out in OUTS:
        shutil.rmtree(out, ignore_errors=True)
        os.makedirs(out)
        build(out)
    print("wrote fixtures to", ", ".join(OUTS))


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Generate the fixtures and check the animated WebP**

Run: `python3 -I tools/make_test_assets.py && webpmux -info app/src/test/resources/images/anim.webp`
Expected: `Number of frames: 4`, and the frame table shows `background` in the dispose column and `yes` in the blend column for frames 2 to 4.

- [ ] **Step 3: Write the failing tests**

`imaging/AnimationSnifferTest.kt`:

```kotlin
package com.jjveleber.gallery.imaging

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationSnifferTest {
    private fun bytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/images/$name")!!.use { it.readBytes() }

    private fun sniff(data: ByteArray) = AnimationSniffer.sniff(Buffer().write(data))

    @Test fun animatedGif() = assertEquals(ImageKind.AnimatedGif, sniff(bytes("anim.gif")))
    @Test fun staticGif() = assertEquals(ImageKind.Static, sniff(bytes("still.gif")))
    @Test fun animatedWebp() = assertEquals(ImageKind.AnimatedWebp, sniff(bytes("anim.webp")))
    @Test fun staticWebp() = assertEquals(ImageKind.Static, sniff(bytes("still.webp")))
    @Test fun apng() = assertEquals(ImageKind.Apng, sniff(bytes("anim.png")))
    @Test fun plainPng() = assertEquals(ImageKind.Static, sniff(bytes("still.png")))
    @Test fun jpeg() = assertEquals(ImageKind.Static, sniff(bytes("still.jpg")))
    @Test fun bmp() = assertEquals(ImageKind.Static, sniff(bytes("still.bmp")))
    @Test fun empty() = assertEquals(ImageKind.Static, sniff(ByteArray(0)))
    @Test fun garbage() = assertEquals(ImageKind.Static, sniff(ByteArray(64) { it.toByte() }))

    @Test fun truncatedGifIsStatic() =
        assertEquals(ImageKind.Static, sniff(bytes("anim.gif").copyOf(20)))

    @Test fun truncatedPngIsStatic() =
        assertEquals(ImageKind.Static, sniff(bytes("anim.png").copyOf(20)))

    @Test fun truncatedWebpIsStatic() =
        assertEquals(ImageKind.Static, sniff(bytes("anim.webp").copyOf(16)))

    @Test fun peekLeavesSourceUnconsumed() {
        val data = bytes("anim.gif")
        val source = Buffer().write(data)
        AnimationSniffer.sniff(source.peek())
        assertEquals(data.size.toLong(), source.size)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*AnimationSnifferTest*'`
Expected: FAIL to compile with `Unresolved reference: AnimationSniffer`.

- [ ] **Step 5: Implement `imaging/AnimationSniffer.kt`**

```kotlin
package com.jjveleber.gallery.imaging

import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException

enum class ImageKind { AnimatedGif, AnimatedWebp, Apng, Static }

/** Classifies an image by its header bytes, never by file extension. Consumes [sniff]'s source: pass a peek(). */
object AnimationSniffer {
    private val GIF87 = "GIF87a".encodeUtf8()
    private val GIF89 = "GIF89a".encodeUtf8()
    private val RIFF = "RIFF".encodeUtf8()
    private val WEBP_VP8X = "WEBPVP8X".encodeUtf8()
    private val PNG = "89504e470d0a1a0a".decodeHex()
    private const val WEBP_ANIMATION_FLAG = 0x02

    fun sniff(source: BufferedSource): ImageKind = try {
        when {
            source.rangeEquals(0, GIF87) || source.rangeEquals(0, GIF89) ->
                if (gifHasTwoFrames(source)) ImageKind.AnimatedGif else ImageKind.Static
            source.rangeEquals(0, RIFF) && source.rangeEquals(8, WEBP_VP8X) -> {
                source.skip(20)
                if (source.readByte().toInt() and WEBP_ANIMATION_FLAG != 0) ImageKind.AnimatedWebp else ImageKind.Static
            }
            source.rangeEquals(0, PNG) -> if (pngHasActl(source)) ImageKind.Apng else ImageKind.Static
            else -> ImageKind.Static
        }
    } catch (e: IOException) {
        ImageKind.Static // Truncated or malformed: let the static decoder try.
    }

    /** Walks GIF blocks until a second image descriptor (animated) or the trailer (static). */
    private fun gifHasTwoFrames(s: BufferedSource): Boolean {
        s.skip(6 + 4) // signature, logical screen width and height
        val packed = s.readByte().toInt() and 0xFF
        s.skip(2) // background color index, pixel aspect ratio
        if (packed and 0x80 != 0) s.skip(3L * (1 shl ((packed and 0x07) + 1)))
        var frames = 0
        while (true) {
            when (s.readByte().toInt() and 0xFF) {
                0x21 -> { // extension: label byte, then sub-blocks
                    s.skip(1)
                    skipSubBlocks(s)
                }
                0x2C -> { // image descriptor
                    if (++frames == 2) return true
                    s.skip(8)
                    val p = s.readByte().toInt() and 0xFF
                    if (p and 0x80 != 0) s.skip(3L * (1 shl ((p and 0x07) + 1)))
                    s.skip(1) // LZW minimum code size
                    skipSubBlocks(s)
                }
                else -> return false // 0x3B trailer, or garbage
            }
        }
    }

    private fun skipSubBlocks(s: BufferedSource) {
        while (true) {
            val n = s.readByte().toInt() and 0xFF
            if (n == 0) return
            s.skip(n.toLong())
        }
    }

    /** APNG has an acTL chunk before the first IDAT. */
    private fun pngHasActl(s: BufferedSource): Boolean {
        s.skip(8)
        while (true) {
            val length = s.readInt().toLong() and 0xFFFFFFFFL
            when (s.readUtf8(4)) {
                "acTL" -> return true
                "IDAT", "IEND" -> return false
            }
            s.skip(length + 4) // data and CRC
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*AnimationSnifferTest*'`
Expected: PASS (14 tests).

- [ ] **Step 7: Commit**

```bash
git add tools app/src/test app/src/androidTest app/src/main/java/com/jjveleber/gallery/imaging
git commit -m "feat(imaging): sniff animated GIF, WebP and APNG headers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Image pipeline (Coil + APNG4Android)

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/imaging/AnimatedRegistry.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/imaging/Requests.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/imaging/AnimatedDecoder.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/imaging/ImageLoaders.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/AppContainer.kt`, `app/src/main/java/com/jjveleber/gallery/GalleryApp.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add `android:name=".GalleryApp"`)
- Test: `app/src/androidTest/java/com/jjveleber/gallery/imaging/ImagePipelineTest.kt`

**Interfaces:**
- Consumes: `AnimationSniffer.sniff`, `ImageKind` (Task 3)
- Produces:
  - `class AnimatedRegistry { fun record(key: String, animated: Boolean); fun isAnimated(key: String): Boolean }`
  - `val AnimateKey: Extras.Key<Boolean>`, `val SourceKey: Extras.Key<String>`
  - `fun imageRequest(context: Context, uri: String, animate: Boolean, listener: ImageRequest.Listener? = null): ImageRequest`
  - `fun buildImageLoader(context: PlatformContext, registry: AnimatedRegistry): ImageLoader`
  - `class AppContainer(context: Context) { val animated: AnimatedRegistry }` (Task 11 adds more)
  - `class GalleryApp : Application, SingletonImageLoader.Factory { val container: AppContainer }`

- [ ] **Step 1: Write the failing instrumented test**

`app/src/androidTest/java/com/jjveleber/gallery/imaging/ImagePipelineTest.kt`:

```kotlin
package com.jjveleber.gallery.imaging

import android.graphics.drawable.Animatable
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.asDrawable
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImagePipelineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val registry = AnimatedRegistry()
    private val loader = buildImageLoader(context, registry)

    private fun copyAsset(name: String): String {
        val file = File(context.cacheDir, "pipeline-$name")
        testAssets.open("images/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file).toString()
    }

    private fun load(uri: String, animate: Boolean): ImageResult = runBlocking {
        loader.execute(imageRequest(context, uri, animate))
    }

    private fun success(result: ImageResult): SuccessResult {
        assertTrue("expected success but got $result", result is SuccessResult)
        return result as SuccessResult
    }

    @Test fun animatedFilesDecodeAsAnimatableWhenAllowed() {
        for (name in listOf("anim.gif", "anim.png", "anim.webp")) {
            val image = success(load(copyAsset(name), animate = true)).image
            val drawable = image.asDrawable(context.resources)
            assertTrue("$name should be animatable", drawable is Animatable)
            assertEquals("$name width", 64, drawable.intrinsicWidth)
            assertEquals("$name height", 48, drawable.intrinsicHeight)
        }
    }

    @Test fun animatedFilesDecodeAsStillWhenNotAllowed() {
        for (name in listOf("anim.gif", "anim.png", "anim.webp")) {
            val image = success(load(copyAsset(name), animate = false)).image
            assertTrue("$name should be a bitmap", image is BitmapImage)
            assertEquals(64, image.width)
            assertEquals(48, image.height)
        }
    }

    @Test fun staticFormatsDecode() {
        for (name in listOf("still.gif", "still.png", "still.webp", "still.jpg", "still.bmp")) {
            val image = success(load(copyAsset(name), animate = true)).image
            assertTrue("$name should be a bitmap", image is BitmapImage)
            assertEquals("$name width", 64, image.width)
        }
    }

    @Test fun registryRecordsWhatWasSniffed() {
        val anim = copyAsset("anim.gif")
        val still = copyAsset("still.png")
        load(anim, animate = false)
        load(still, animate = false)
        assertTrue(registry.isAnimated(anim))
        assertFalse(registry.isAnimated(still))
    }

    @Test fun exifRotationIsApplied() {
        val image = success(load(copyAsset("rotated.jpg"), animate = false)).image
        assertEquals(20, image.width)
        assertEquals(40, image.height)
    }

    @Test fun corruptFileFails() {
        assertTrue(load(copyAsset("corrupt.jpg"), animate = true) is ErrorResult)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Start the emulator (see Global Constraints), then run: `./gradlew :app:connectedDebugAndroidTest`
Expected: FAIL to compile with `Unresolved reference: AnimatedRegistry` (and `buildImageLoader`, `imageRequest`).

- [ ] **Step 3: Implement the registry and request builder**

`imaging/AnimatedRegistry.kt`:

```kotlin
package com.jjveleber.gallery.imaging

import java.util.concurrent.ConcurrentHashMap

/** Remembers which images were sniffed as animated, so the grid can badge them without re-reading files. */
class AnimatedRegistry {
    private val animated = ConcurrentHashMap<String, Boolean>()

    fun record(key: String, animated: Boolean) {
        this.animated[key] = animated
    }

    fun isAnimated(key: String): Boolean = animated[key] == true
}
```

`imaging/Requests.kt`:

```kotlin
package com.jjveleber.gallery.imaging

import android.content.Context
import android.net.Uri
import coil3.Extras
import coil3.request.ImageRequest

/** Whether this request may produce an animated image. */
val AnimateKey = Extras.Key(default = false)

/** The request's URI string, so the decoder can record sniff results in [AnimatedRegistry]. */
val SourceKey = Extras.Key(default = "")

fun imageRequest(
    context: Context,
    uri: String,
    animate: Boolean,
    listener: ImageRequest.Listener? = null,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(uri))
        .apply {
            extras[AnimateKey] = animate
            extras[SourceKey] = uri
        }
        // Animated and still results for the same file must not share a memory cache entry.
        .memoryCacheKeyExtra("animate", animate.toString())
        .listener(listener)
        .build()
```

- [ ] **Step 4: Implement the decoder and loader**

`imaging/AnimatedDecoder.kt`:

```kotlin
package com.jjveleber.gallery.imaging

import android.graphics.BitmapFactory
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.Options
import coil3.size.pxOrElse
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.gif.GifDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import com.github.penfeizhou.animation.webp.WebPDrawable
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Decodes animated GIF, WebP and APNG with APNG4Android, which handles WebP blend/dispose frames and APNG
 * that the platform decoder gets wrong or cannot animate. Still images are left to Coil's default decoders.
 */
class AnimatedDecoder(
    private val source: ImageSource,
    private val kind: ImageKind,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val loader = object : ByteBufferLoader() {
            override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
        }
        val drawable = when (kind) {
            ImageKind.AnimatedGif -> GifDrawable(loader)
            ImageKind.AnimatedWebp -> WebPDrawable(loader)
            ImageKind.Apng -> APNGDrawable(loader)
            ImageKind.Static -> error("Static images are not routed to AnimatedDecoder")
        }
        // intrinsicWidth parses the header on APNG4Android's worker thread; 0 means it could not read the file.
        return if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            DecodeResult(drawable.asImage(shareable = false), isSampled = false)
        } else {
            DecodeResult(decodeStill(bytes), isSampled = true)
        }
    }

    /** Fallback when APNG4Android fails: the platform's first frame, downsampled to the requested size. */
    private fun decodeStill(bytes: ByteArray): Image {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxWidth = options.size.width.pxOrElse { bounds.outWidth }
        val maxHeight = options.size.height.pxOrElse { bounds.outHeight }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth && bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Cannot decode image")
        return bitmap.asImage()
    }

    class Factory(private val registry: AnimatedRegistry) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val kind = AnimationSniffer.sniff(result.source.source().peek())
            val key = options.getExtra(SourceKey)
            if (key.isNotEmpty()) registry.record(key, kind != ImageKind.Static)
            if (kind == ImageKind.Static || !options.getExtra(AnimateKey)) return null
            return AnimatedDecoder(result.source, kind, options)
        }
    }
}
```

Returning `null` from the factory hands the file to Coil's default decoder, which produces the first frame of an animated file as a still bitmap and applies EXIF orientation to JPEGs.

`imaging/ImageLoaders.kt`:

```kotlin
package com.jjveleber.gallery.imaging

import coil3.ImageLoader
import coil3.PlatformContext

fun buildImageLoader(context: PlatformContext, registry: AnimatedRegistry): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(AnimatedDecoder.Factory(registry)) }
        .build()
```

- [ ] **Step 5: Wire the singleton loader**

`AppContainer.kt`:

```kotlin
package com.jjveleber.gallery

import android.content.Context
import com.jjveleber.gallery.imaging.AnimatedRegistry

class AppContainer(context: Context) {
    val animated = AnimatedRegistry()
}
```

`GalleryApp.kt`:

```kotlin
package com.jjveleber.gallery

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.jjveleber.gallery.imaging.buildImageLoader

class GalleryApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = buildImageLoader(context, container.animated)
}
```

In `AndroidManifest.xml`, change `<application` to include `android:name=".GalleryApp"` as its first attribute.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: PASS (6 tests in `ImagePipelineTest`). Also run `./gradlew :app:testDebugUnitTest` (all JVM tests still PASS).

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "feat(imaging): Coil pipeline with APNG4Android for animated images

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: On-device check on the Fire Max 11 (throwaway, needs the human)

This task answers three questions before the UI is built: do animated WebP, APNG and GIF render correctly on the Fire through this pipeline (including inside Telephoto); does HEIC decode; and what URI does the Fire's Files app send for "Open with". The check activity is deleted at the end of the task; only `docs/device-check.md` is kept.

**Files:**
- Create (temporary): `app/src/debug/AndroidManifest.xml`, `app/src/debug/java/com/jjveleber/gallery/DeviceCheckActivity.kt`, `app/src/debug/assets/images/*`
- Create (kept): `docs/device-check.md`

**Interfaces:**
- Consumes: `imageRequest` (Task 4), Telephoto `ZoomableAsyncImage`
- Produces: `docs/device-check.md`, read by Task 8.

- [ ] **Step 1: Add the debug-only check activity**

```bash
mkdir -p app/src/debug/assets && cp -r app/src/androidTest/assets/images app/src/debug/assets/
```

`app/src/debug/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity
            android:name=".DeviceCheckActivity"
            android:exported="true"
            android:label="Gallery check"
            android:theme="@android:style/Theme.Material.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/debug/java/com/jjveleber/gallery/DeviceCheckActivity.kt`:

```kotlin
package com.jjveleber.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.jjveleber.gallery.imaging.imageRequest
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import java.io.File

/** Throwaway on-device check (plan Task 5). Deleted at the end of that task. */
class DeviceCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
        val report = incoming?.let { describe(it) }
        report?.let { Log.i("GalleryCheck", it) }
        val files = listOf("anim.gif", "anim.png", "anim.webp", "still.gif", "still.png", "still.webp", "still.jpg", "still.bmp", "rotated.jpg")
            .map { name ->
                val file = File(cacheDir, "check-$name")
                assets.open("images/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                name to Uri.fromFile(file).toString()
            }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var animate by remember { mutableStateOf(true) }
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                        if (incoming != null) {
                            Text(report.orEmpty())
                            Box(Modifier.fillMaxWidth().height(400.dp).background(Color.Black)) {
                                ZoomableAsyncImage(imageRequest(this@DeviceCheckActivity, incoming.toString(), animate), null, Modifier.fillMaxSize())
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Animate")
                            Switch(checked = animate, onCheckedChange = { animate = it })
                        }
                        files.forEach { (name, uri) ->
                            Text(name)
                            Row {
                                AsyncImage(imageRequest(this@DeviceCheckActivity, uri, animate), name, Modifier.size(160.dp).background(Color.Black))
                                Spacer(Modifier.width(16.dp))
                                ZoomableAsyncImage(imageRequest(this@DeviceCheckActivity, uri, animate), name, Modifier.size(160.dp).background(Color.Black))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun describe(uri: Uri): String = buildString {
        appendLine("uri=$uri")
        appendLine("scheme=${uri.scheme} authority=${uri.authority}")
        appendLine("type=${contentResolver.getType(uri)}")
        val isDocument = runCatching { DocumentsContract.isDocumentUri(this@DeviceCheckActivity, uri) }.getOrDefault(false)
        appendLine("isDocumentUri=$isDocument")
        if (isDocument) appendLine("documentId=${DocumentsContract.getDocumentId(uri)}")
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                appendLine("columns=${c.columnNames.joinToString()}")
                if (c.moveToFirst()) c.columnNames.forEachIndexed { i, col -> appendLine("  $col=${runCatching { c.getString(i) }.getOrNull()}") }
            }
        } catch (e: Exception) {
            appendLine("query failed: $e")
        }
    }
}
```

- [ ] **Step 2: Smoke-test on the emulator**

Run: `./gradlew :app:installDebug && $ANDROID_HOME/platform-tools/adb shell am start -n com.jjveleber.gallery/.DeviceCheckActivity && sleep 3 && $ANDROID_HOME/platform-tools/adb exec-out screencap -p > /tmp/claude-check.png`
Then view the screenshot (Read tool). Expected: nine labelled rows, each with two 160 dp boxes showing the red bar image; `rotated.jpg` is taller than wide. Fix any crash before involving the human.

- [ ] **Step 3: CHECKPOINT: ask the human to run the check on the Fire**

Stop and ask the human to do the following, then report back:

1. Install the debug APK on the Fire Max 11 (README of `../video-editor`, Route B: copy `app/build/outputs/apk/debug/app-debug.apk` to the Windows Downloads folder and `adb.exe install -r` it).
2. Open **Gallery check** from the app grid. With **Animate** on: the three `anim.*` rows should show one red/orange bar moving left to right in both boxes, with no trails or ghost copies left behind. With **Animate** off: all rows show a still first frame. `rotated.jpg` is upright (taller than wide). Pinch-zoom works in the right-hand boxes.
3. Also try a few of their own animated WebP and GIF files: in the Files app, long-press the file, choose **Open with**, pick **Gallery check**.
4. Open an iPhone HEIC photo the same way, if they have one.
5. Open a normal JPG from **Internal storage > Pictures** (or any folder) the same way. Copy the text shown at the top of the screen (the `uri=` through the column list), or run `adb.exe logcat -d -s GalleryCheck` and paste the output.

- [ ] **Step 4: Record the results**

Write `docs/device-check.md` with the human's answers, in this shape:

```markdown
# Device check (Fire Max 11)

Date: <date>

| Check | Result |
|---|---|
| anim.gif / anim.png / anim.webp animate, no trails | <pass or what was wrong> |
| Animate off shows still frames | <pass/fail> |
| rotated.jpg upright | <pass/fail> |
| Zoom inside Telephoto (static and animated) | <pass/fail> |
| User's own animated WebP / GIF files | <pass or which failed and how> |
| HEIC decodes | <pass / fail / not tested> |

## Open with URI from the Fire Files app

<paste of the GalleryCheck report: uri, authority, isDocumentUri, documentId, columns>
```

If any animation check failed, stop and tell the human: the spec's decoder choice needs revisiting before continuing.

If HEIC failed, remove `image/heic`, `image/heif`, `heic` and `heif` from the supported lists in the spec (section 5) and from the Global Constraints of this plan, and note it in `docs/device-check.md`.

- [ ] **Step 5: Delete the check activity and commit the notes**

```bash
rm -rf app/src/debug
git add docs/device-check.md docs/superpowers
git commit -m "docs: Fire Max 11 decoder and Open with check results

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Settings

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/model/Settings.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/settings/SettingsStore.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/settings/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: `SortSpec`, `SortOrder` (Task 2)
- Produces:
  - `enum class GridSize(val cellDp: Int) { S(120), M(180), L(300), XL(480) }`
  - `enum class Transition { Crossfade, None }`
  - `val INTERVALS_SEC: List<Int>`
  - `data class Settings(sort, gridSize, animateGrid, animateFullScreen, autoAdvance, intervalSec, transition)` with the defaults from Global Constraints
  - `interface SettingsStore { val settings: Flow<Settings>; suspend fun update(transform: (Settings) -> Settings) }`
  - `class DataStoreSettingsStore(store: DataStore<Preferences>) : SettingsStore`
  - `fun Preferences.toSettings(): Settings`

- [ ] **Step 1: Write `model/Settings.kt`**

```kotlin
package com.jjveleber.gallery.model

/** Grid presets by target cell size; the column count adapts to the screen width. */
enum class GridSize(val cellDp: Int) { S(120), M(180), L(300), XL(480) }

enum class Transition { Crossfade, None }

val INTERVALS_SEC = listOf(3, 5, 10, 15, 30, 60, 120, 300)

data class Settings(
    val sort: SortSpec = SortSpec(),
    val gridSize: GridSize = GridSize.M,
    val animateGrid: Boolean = false,
    val animateFullScreen: Boolean = true,
    val autoAdvance: Boolean = false,
    val intervalSec: Int = 5,
    val transition: Transition = Transition.Crossfade,
)
```

- [ ] **Step 2: Write the failing tests**

`settings/SettingsStoreTest.kt`:

```kotlin
package com.jjveleber.gallery.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jjveleber.gallery.model.GridSize
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.model.SortOrder
import com.jjveleber.gallery.model.SortSpec
import com.jjveleber.gallery.model.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun tearDown() = scope.cancel()

    private fun store() = DataStoreSettingsStore(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "settings.preferences_pb") }),
    )

    @Test fun defaultsWhenEmpty() = runBlocking {
        assertEquals(Settings(), store().settings.first())
    }

    @Test fun roundTripsEveryField() = runBlocking {
        val custom = Settings(
            sort = SortSpec(SortOrder.Random, ascending = false, randomSeed = 99),
            gridSize = GridSize.XL,
            animateGrid = true,
            animateFullScreen = false,
            autoAdvance = true,
            intervalSec = 120,
            transition = Transition.None,
        )
        val store = store()
        store.update { custom }
        assertEquals(custom, store.settings.first())
    }

    @Test fun updateTransformsCurrentValue() = runBlocking {
        val store = store()
        store.update { it.copy(intervalSec = 30) }
        store.update { it.copy(autoAdvance = true) }
        assertEquals(Settings(intervalSec = 30, autoAdvance = true), store.settings.first())
    }

    @Test fun unknownEnumNamesFallBackToDefaults() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("sort_order") to "Bogus",
            stringPreferencesKey("grid_size") to "Huge",
            stringPreferencesKey("transition") to "Spin",
        )
        assertEquals(Settings(), prefs.toSettings())
    }

    @Test fun intervalOutsideTheListFallsBackToDefault() {
        assertEquals(5, mutablePreferencesOf(intPreferencesKey("interval_sec") to 7).toSettings().intervalSec)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SettingsStoreTest*'`
Expected: FAIL to compile with `Unresolved reference: DataStoreSettingsStore`.

- [ ] **Step 4: Implement `settings/SettingsStore.kt`**

```kotlin
package com.jjveleber.gallery.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jjveleber.gallery.model.GridSize
import com.jjveleber.gallery.model.INTERVALS_SEC
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.model.SortSpec
import com.jjveleber.gallery.model.Transition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SettingsStore {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}

class DataStoreSettingsStore(private val store: DataStore<Preferences>) : SettingsStore {
    override val settings: Flow<Settings> = store.data.map { it.toSettings() }

    override suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs -> transform(prefs.toSettings()).writeTo(prefs) }
    }
}

private val SORT_ORDER = stringPreferencesKey("sort_order")
private val SORT_ASCENDING = booleanPreferencesKey("sort_ascending")
private val RANDOM_SEED = longPreferencesKey("random_seed")
private val GRID_SIZE = stringPreferencesKey("grid_size")
private val ANIMATE_GRID = booleanPreferencesKey("animate_grid")
private val ANIMATE_FULL_SCREEN = booleanPreferencesKey("animate_full_screen")
private val AUTO_ADVANCE = booleanPreferencesKey("auto_advance")
private val INTERVAL_SEC = intPreferencesKey("interval_sec")
private val TRANSITION = stringPreferencesKey("transition")

fun Preferences.toSettings(): Settings {
    val d = Settings()
    return Settings(
        sort = SortSpec(
            order = enumOr(this[SORT_ORDER], d.sort.order),
            ascending = this[SORT_ASCENDING] ?: d.sort.ascending,
            randomSeed = this[RANDOM_SEED] ?: d.sort.randomSeed,
        ),
        gridSize = enumOr(this[GRID_SIZE], d.gridSize),
        animateGrid = this[ANIMATE_GRID] ?: d.animateGrid,
        animateFullScreen = this[ANIMATE_FULL_SCREEN] ?: d.animateFullScreen,
        autoAdvance = this[AUTO_ADVANCE] ?: d.autoAdvance,
        intervalSec = this[INTERVAL_SEC]?.takeIf { it in INTERVALS_SEC } ?: d.intervalSec,
        transition = enumOr(this[TRANSITION], d.transition),
    )
}

private fun Settings.writeTo(prefs: MutablePreferences) {
    prefs[SORT_ORDER] = sort.order.name
    prefs[SORT_ASCENDING] = sort.ascending
    prefs[RANDOM_SEED] = sort.randomSeed
    prefs[GRID_SIZE] = gridSize.name
    prefs[ANIMATE_GRID] = animateGrid
    prefs[ANIMATE_FULL_SCREEN] = animateFullScreen
    prefs[AUTO_ADVANCE] = autoAdvance
    prefs[INTERVAL_SEC] = intervalSec
    prefs[TRANSITION] = transition.name
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SettingsStoreTest*'`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(settings): persisted settings in DataStore

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Folder scanning

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/scan/SupportedTypes.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/scan/FolderWalk.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/scan/FolderScanner.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/scan/SupportedTypesTest.kt`, `app/src/test/java/com/jjveleber/gallery/scan/FolderWalkTest.kt`

**Interfaces:**
- Consumes: `ImageItem` (Task 2)
- Produces:
  - `fun isSupportedImage(name: String, mime: String?): Boolean`
  - `data class DocEntry(id: String, name: String, mime: String?, sizeBytes: Long, modifiedMs: Long, isDir: Boolean)`
  - `interface DocumentSource { fun children(folderId: String): List<DocEntry>; fun uriOf(docId: String): String }`
  - `suspend fun walkImages(source: DocumentSource, rootId: String, recursive: Boolean, onProgress: (Int) -> Unit = {}): List<ImageItem>`
  - `interface FolderScanner { suspend fun folderName(treeUri: String): String; suspend fun scan(treeUri: String, recursive: Boolean, onProgress: (Int) -> Unit): List<ImageItem> }`
  - `class AndroidFolderScanner(resolver: ContentResolver) : FolderScanner` (throws on an unreadable tree)

- [ ] **Step 1: Write the failing tests**

`scan/SupportedTypesTest.kt`:

```kotlin
package com.jjveleber.gallery.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedTypesTest {
    @Test fun acceptsListedMimeTypes() {
        for (mime in listOf("image/jpeg", "image/png", "image/apng", "image/webp", "image/gif", "image/bmp", "image/x-ms-bmp", "image/heic", "image/heif")) {
            assertTrue(mime, isSupportedImage("x", mime))
        }
    }

    @Test fun rejectsOtherTypes() {
        assertFalse(isSupportedImage("a.avif", "image/avif"))
        assertFalse(isSupportedImage("a.svg", "image/svg+xml"))
        assertFalse(isSupportedImage("a.mp4", "video/mp4"))
        assertFalse(isSupportedImage("notes.txt", "text/plain"))
    }

    @Test fun fallsBackToExtensionForGenericMime() {
        assertTrue(isSupportedImage("IMG_1.JPG", "application/octet-stream"))
        assertTrue(isSupportedImage("a.webp", null))
        assertTrue(isSupportedImage("a.png", ""))
        assertTrue(isSupportedImage("a.jpeg", "image/jpg"))
        assertFalse(isSupportedImage("a.txt", "application/octet-stream"))
        assertFalse(isSupportedImage("noextension", null))
    }
}
```

`scan/FolderWalkTest.kt`:

```kotlin
package com.jjveleber.gallery.scan

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderWalkTest {
    /** A fake tree: folder id to its children. Folder ids are their relative paths ("" is the root). */
    private class FakeSource(private val tree: Map<String, List<DocEntry>>) : DocumentSource {
        val listed = mutableListOf<String>()
        override fun children(folderId: String): List<DocEntry> {
            listed += folderId
            return tree[folderId].orEmpty()
        }
        override fun uriOf(docId: String) = "content://tree/$docId"
    }

    private fun file(id: String, mime: String? = "image/jpeg") =
        DocEntry(id = id, name = id.substringAfterLast('/'), mime = mime, sizeBytes = 1, modifiedMs = 2, isDir = false)

    private fun dir(id: String) =
        DocEntry(id = id, name = id.substringAfterLast('/'), mime = "vnd.android.document/directory", sizeBytes = 0, modifiedMs = 0, isDir = true)

    private val tree = mapOf(
        "" to listOf(file("a.jpg"), dir("trip"), file("notes.txt", "text/plain")),
        "trip" to listOf(file("trip/b.png", "image/png"), dir("trip/day2")),
        "trip/day2" to listOf(file("trip/day2/c.gif", "image/gif")),
    )

    @Test fun recursesIntoSubfoldersWithRelativePaths() = runBlocking {
        val items = walkImages(FakeSource(tree), rootId = "", recursive = true)
        assertEquals(listOf("a.jpg", "trip/b.png", "trip/day2/c.gif"), items.map { it.relPath })
        val c = items.last()
        assertEquals("c.gif", c.name)
        assertEquals("content://tree/trip/day2/c.gif", c.uri)
        assertEquals("image/gif", c.mime)
        assertEquals(1L, c.sizeBytes)
        assertEquals(2L, c.modifiedMs)
    }

    @Test fun nonRecursiveIgnoresSubfolders() = runBlocking {
        val source = FakeSource(tree)
        val items = walkImages(source, rootId = "", recursive = false)
        assertEquals(listOf("a.jpg"), items.map { it.relPath })
        assertEquals(listOf(""), source.listed)
    }

    @Test fun skipsHiddenFilesAndFolders() = runBlocking {
        val hidden = mapOf(
            "" to listOf(file("a.jpg"), dir(".thumbnails"), dir(".trashed-123"), file(".hidden.jpg"), file(".nomedia", null)),
            ".thumbnails" to listOf(file(".thumbnails/t1.jpg")),
            ".trashed-123" to listOf(file(".trashed-123/old.jpg")),
        )
        val source = FakeSource(hidden)
        val items = walkImages(source, rootId = "", recursive = true)
        assertEquals(listOf("a.jpg"), items.map { it.relPath })
        assertEquals(listOf(""), source.listed)
    }

    @Test fun acceptsGenericMimeWithImageExtension() = runBlocking {
        val items = walkImages(FakeSource(mapOf("" to listOf(file("IMG.JPG", "application/octet-stream")))), "", recursive = true)
        assertEquals(1, items.size)
    }

    @Test fun reportsProgressAfterEachFolder() = runBlocking {
        val counts = mutableListOf<Int>()
        walkImages(FakeSource(tree), rootId = "", recursive = true) { counts += it }
        assertEquals(listOf(1, 2, 3), counts)
    }

    @Test fun emptyFolderGivesNoItems() = runBlocking {
        assertTrue(walkImages(FakeSource(emptyMap()), rootId = "", recursive = true).isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*scan*'`
Expected: FAIL to compile with `Unresolved reference: isSupportedImage` (and `walkImages`, `DocEntry`).

- [ ] **Step 3: Implement the pure parts**

`scan/SupportedTypes.kt`:

```kotlin
package com.jjveleber.gallery.scan

private val MIME_TYPES = setOf(
    "image/jpeg", "image/png", "image/apng", "image/webp", "image/gif",
    "image/bmp", "image/x-ms-bmp", "image/heic", "image/heif",
)
private val EXTENSIONS = setOf("jpg", "jpeg", "png", "apng", "webp", "gif", "bmp", "heic", "heif")

/** True for the formats the app displays. Uses the extension when a provider reports a generic or odd MIME type. */
fun isSupportedImage(name: String, mime: String?): Boolean {
    if (mime in MIME_TYPES) return true
    val genericMime = mime.isNullOrEmpty() || mime == "application/octet-stream" || mime.startsWith("image/")
    return genericMime && name.substringAfterLast('.', "").lowercase() in EXTENSIONS
}
```

Note: `image/avif` and `image/svg+xml` start with `image/` but their extensions are not listed, so they are rejected.

`scan/FolderWalk.kt`:

```kotlin
package com.jjveleber.gallery.scan

import com.jjveleber.gallery.model.ImageItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DocEntry(
    val id: String,
    val name: String,
    val mime: String?,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val isDir: Boolean,
)

/** Lists a folder's direct children. Implemented over DocumentsContract on the device and faked in tests. */
interface DocumentSource {
    fun children(folderId: String): List<DocEntry>
    fun uriOf(docId: String): String
}

/**
 * Collects supported images under [rootId], breadth first. Skips hidden entries (names starting with '.'),
 * such as the `.thumbnails` folders Android creates. Calls [onProgress] with the running count after each folder.
 */
suspend fun walkImages(
    source: DocumentSource,
    rootId: String,
    recursive: Boolean,
    onProgress: (Int) -> Unit = {},
): List<ImageItem> {
    val found = ArrayList<ImageItem>()
    val folders = ArrayDeque<Pair<String, String>>() // folder id, relative dir ("" for the root)
    folders.add(rootId to "")
    while (folders.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (folderId, dir) = folders.removeFirst()
        for (entry in source.children(folderId)) {
            if (entry.name.startsWith(".")) continue
            val relPath = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
            if (entry.isDir) {
                if (recursive) folders.add(entry.id to relPath)
            } else if (isSupportedImage(entry.name, entry.mime)) {
                found.add(ImageItem(source.uriOf(entry.id), entry.name, relPath, entry.sizeBytes, entry.modifiedMs, entry.mime.orEmpty()))
            }
        }
        onProgress(found.size)
    }
    return found
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*scan*'`
Expected: PASS (9 tests).

- [ ] **Step 5: Implement the Android adapter**

`scan/FolderScanner.kt`:

```kotlin
package com.jjveleber.gallery.scan

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.jjveleber.gallery.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** What the ViewModel needs from storage, so it can be tested with a fake. */
interface FolderScanner {
    suspend fun folderName(treeUri: String): String
    suspend fun scan(treeUri: String, recursive: Boolean, onProgress: (Int) -> Unit): List<ImageItem>
}

class AndroidFolderScanner(private val resolver: ContentResolver) : FolderScanner {
    override suspend fun folderName(treeUri: String): String = withContext(Dispatchers.IO) {
        AndroidDocumentSource(resolver, Uri.parse(treeUri)).rootName()
    }

    override suspend fun scan(treeUri: String, recursive: Boolean, onProgress: (Int) -> Unit): List<ImageItem> =
        withContext(Dispatchers.IO) {
            val source = AndroidDocumentSource(resolver, Uri.parse(treeUri))
            walkImages(source, source.rootId(), recursive, onProgress)
        }
}

/** Lists a picked document tree through DocumentsContract queries (much faster than DocumentFile). */
class AndroidDocumentSource(private val resolver: ContentResolver, private val tree: Uri) : DocumentSource {
    fun rootId(): String = DocumentsContract.getTreeDocumentId(tree)

    fun rootName(): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId())
        val name = resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        return name ?: rootId().substringAfterLast(':').substringAfterLast('/')
    }

    override fun children(folderId: String): List<DocEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val cursor = resolver.query(uri, COLUMNS, null, null, null) ?: throw FileNotFoundException("Cannot list $uri")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val mime = c.getString(2)
                    add(
                        DocEntry(
                            id = c.getString(0),
                            name = c.getString(1).orEmpty(),
                            mime = mime,
                            sizeBytes = if (c.isNull(3)) 0 else c.getLong(3),
                            modifiedMs = if (c.isNull(4)) 0 else c.getLong(4),
                            isDir = mime == Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
        }
    }

    override fun uriOf(docId: String): String = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString()

    private companion object {
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
```

- [ ] **Step 6: Build and run all JVM tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "feat(scan): recursive folder walk over a document tree

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Open with resolver

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/scan/OpenWithLocation.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/scan/OpenWithResolver.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/scan/OpenWithLocationTest.kt`

**Interfaces:**
- Consumes: `ImageItem` (Task 2), `isSupportedImage` (Task 7), `docs/device-check.md` (Task 5)
- Produces:
  - `data class FolderLocation(volume: String, relativePath: String, fileName: String)` (`relativePath` ends with `/` like `MediaStore.RELATIVE_PATH`, or is empty for the volume root)
  - `fun locationFromPath(path: String): FolderLocation?`
  - `fun locationFromDocumentId(authority: String, docId: String): FolderLocation?`
  - `fun FolderLocation.externalStorageDocId(): String`
  - `sealed interface OpenWithResult { data class Resolved(items: List<ImageItem>, startUri: String, folderName: String); data class Single(item: ImageItem, folderHint: FolderLocation?) }`
  - `interface OpenWithResolver { suspend fun resolve(uri: String, mayReadMedia: Boolean): OpenWithResult }` (never throws)
  - `class AndroidOpenWithResolver(context: Context) : OpenWithResolver`

- [ ] **Step 1: Read the device check notes**

Open `docs/device-check.md`, section "Open with URI from the Fire Files app". Note its scheme, authority and document ID or `_data` column. If that form is not one of the cases tested below (externalstorage document ID, downloads `raw:` ID, MediaStore URI, `file://` path, or a provider exposing a `_data` path), add a test for it in Step 2 and a matching branch in Step 4 (`locationFromDocumentId` for a document authority, `locationFromPath` for anything that yields a path).

- [ ] **Step 2: Write the failing tests**

`scan/OpenWithLocationTest.kt`:

```kotlin
package com.jjveleber.gallery.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenWithLocationTest {
    private val ext = "com.android.externalstorage.documents"

    @Test fun primaryDocumentId() = assertEquals(
        FolderLocation("external_primary", "Pictures/Trip/", "a.jpg"),
        locationFromDocumentId(ext, "primary:Pictures/Trip/a.jpg"),
    )

    @Test fun sdCardDocumentIdUsesLowercaseVolume() = assertEquals(
        FolderLocation("1a2b-3c4d", "DCIM/", "b.png"),
        locationFromDocumentId(ext, "1A2B-3C4D:DCIM/b.png"),
    )

    @Test fun downloadsRawDocumentId() = assertEquals(
        FolderLocation("external_primary", "Download/", "c.gif"),
        locationFromDocumentId("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download/c.gif"),
    )

    @Test fun downloadsNumericIdIsUnknown() =
        assertNull(locationFromDocumentId("com.android.providers.downloads.documents", "msf:1234"))

    @Test fun unknownAuthorityIsUnknown() =
        assertNull(locationFromDocumentId("com.example.files", "primary:Pictures/a.jpg"))

    @Test fun documentIdWithoutVolumeIsUnknown() =
        assertNull(locationFromDocumentId(ext, "Pictures/a.jpg"))

    @Test fun primaryPath() = assertEquals(
        FolderLocation("external_primary", "Pictures/", "a.jpg"),
        locationFromPath("/storage/emulated/0/Pictures/a.jpg"),
    )

    @Test fun sdcardAliasPath() = assertEquals(
        FolderLocation("external_primary", "DCIM/Camera/", "a.jpg"),
        locationFromPath("/sdcard/DCIM/Camera/a.jpg"),
    )

    @Test fun sdCardPath() = assertEquals(
        FolderLocation("1a2b-3c4d", "DCIM/", "a.jpg"),
        locationFromPath("/storage/1A2B-3C4D/DCIM/a.jpg"),
    )

    @Test fun fileAtVolumeRoot() = assertEquals(
        FolderLocation("external_primary", "", "a.jpg"),
        locationFromPath("/storage/emulated/0/a.jpg"),
    )

    @Test fun pathOutsideStorageIsUnknown() = assertNull(locationFromPath("/data/data/x/a.jpg"))

    @Test fun pathWithoutFileIsUnknown() = assertNull(locationFromPath("/storage/emulated/0/"))

    @Test fun externalStorageDocIdForPicker() {
        assertEquals("primary:Pictures/Trip", FolderLocation("external_primary", "Pictures/Trip/", "a.jpg").externalStorageDocId())
        assertEquals("1A2B-3C4D:DCIM", FolderLocation("1a2b-3c4d", "DCIM/", "a.jpg").externalStorageDocId())
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*OpenWithLocationTest*'`
Expected: FAIL to compile with `Unresolved reference: FolderLocation`.

- [ ] **Step 4: Implement `scan/OpenWithLocation.kt`**

```kotlin
package com.jjveleber.gallery.scan

/**
 * Where an opened image lives, in MediaStore terms. [volume] is a MediaStore volume name ("external_primary"
 * or a lowercase SD card ID). [relativePath] ends with '/' like MediaStore.RELATIVE_PATH, or is empty at the volume root.
 */
data class FolderLocation(val volume: String, val relativePath: String, val fileName: String)

private const val PRIMARY = "external_primary"
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

fun locationFromPath(path: String): FolderLocation? = when {
    path.startsWith("/storage/emulated/0/") -> split(PRIMARY, path.removePrefix("/storage/emulated/0/"))
    path.startsWith("/sdcard/") -> split(PRIMARY, path.removePrefix("/sdcard/"))
    path.startsWith("/storage/") -> {
        val afterStorage = path.removePrefix("/storage/")
        val volume = afterStorage.substringBefore('/')
        if (volume == afterStorage) null else split(volume.lowercase(), afterStorage.substringAfter('/'))
    }
    else -> null
}

fun locationFromDocumentId(authority: String, docId: String): FolderLocation? = when (authority) {
    EXTERNAL_STORAGE_AUTHORITY -> {
        val volume = docId.substringBefore(':', missingDelimiterValue = "")
        if (volume.isEmpty()) null
        else split(if (volume == "primary") PRIMARY else volume.lowercase(), docId.substringAfter(':'))
    }
    DOWNLOADS_AUTHORITY -> if (docId.startsWith("raw:")) locationFromPath(docId.removePrefix("raw:")) else null
    else -> null
}

/** The externalstorage document ID of this location's folder, for the folder picker's EXTRA_INITIAL_URI. */
fun FolderLocation.externalStorageDocId(): String =
    (if (volume == PRIMARY) "primary" else volume.uppercase()) + ":" + relativePath.trimEnd('/')

private fun split(volume: String, rest: String): FolderLocation? {
    val clean = rest.trim('/')
    if (clean.isEmpty()) return null
    val dir = clean.substringBeforeLast('/', missingDelimiterValue = "")
    return FolderLocation(volume, if (dir.isEmpty()) "" else "$dir/", clean.substringAfterLast('/'))
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*OpenWithLocationTest*'`
Expected: PASS (13 tests, plus any added in Step 1).

- [ ] **Step 6: Implement `scan/OpenWithResolver.kt`**

```kotlin
package com.jjveleber.gallery.scan

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.jjveleber.gallery.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface OpenWithResult {
    /** The incoming image's folder (not recursive). [startUri] is the incoming image's entry in [items]. */
    data class Resolved(val items: List<ImageItem>, val startUri: String, val folderName: String) : OpenWithResult

    /** Only the incoming image. [folderHint] points the folder picker at its folder when known. */
    data class Single(val item: ImageItem, val folderHint: FolderLocation?) : OpenWithResult
}

interface OpenWithResolver {
    /** [mayReadMedia] is true when the storage permission is granted. Never throws. */
    suspend fun resolve(uri: String, mayReadMedia: Boolean): OpenWithResult
}

class AndroidOpenWithResolver(private val context: Context) : OpenWithResolver {
    private val resolver = context.contentResolver

    override suspend fun resolve(uri: String, mayReadMedia: Boolean): OpenWithResult = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        val single = singleItem(parsed)
        val location = locate(parsed)
        if (!mayReadMedia || location == null) return@withContext OpenWithResult.Single(single, location)
        val siblings = runCatching { querySiblings(location) }.getOrDefault(emptyList())
        val start = siblings.firstOrNull { it.name == location.fileName }
            ?: return@withContext OpenWithResult.Single(single, location)
        val folderName = location.relativePath.trimEnd('/').substringAfterLast('/').ifEmpty { "Storage" }
        OpenWithResult.Resolved(siblings, start.uri, folderName)
    }

    private fun singleItem(uri: Uri): ImageItem {
        var name: String? = null
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "image"
        return ImageItem(uri.toString(), fileName, fileName, size, 0, resolver.getType(uri).orEmpty())
    }

    private fun locate(uri: Uri): FolderLocation? = runCatching {
        when {
            uri.scheme == "file" -> uri.path?.let(::locationFromPath)
            uri.authority == MediaStore.AUTHORITY -> mediaStoreLocation(uri)
            DocumentsContract.isDocumentUri(context, uri) ->
                locationFromDocumentId(uri.authority.orEmpty(), DocumentsContract.getDocumentId(uri))
            else -> null
        }
    }.getOrNull() ?: runCatching { dataColumnLocation(uri) }.getOrNull()

    private fun mediaStoreLocation(uri: Uri): FolderLocation? {
        val columns = arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
        return resolver.query(uri, columns, null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.isNull(1) || c.isNull(2)) null
            else FolderLocation(c.getString(0), c.getString(1), c.getString(2))
        }
    }

    /** Some file managers expose the file path in the deprecated _data column. */
    @Suppress("DEPRECATION")
    private fun dataColumnLocation(uri: Uri): FolderLocation? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) locationFromPath(c.getString(0)) else null
        }

    private fun querySiblings(location: FolderLocation): List<ImageItem> {
        val collection = MediaStore.Images.Media.getContentUri(location.volume)
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        return resolver.query(collection, columns, selection, arrayOf(location.relativePath), null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (name.startsWith(".") || !isSupportedImage(name, mime)) continue
                    val itemUri = ContentUris.withAppendedId(collection, c.getLong(0)).toString()
                    add(ImageItem(itemUri, name, name, c.getLong(3), c.getLong(4) * 1000, mime.orEmpty()))
                }
            }
        } ?: emptyList()
    }
}
```

- [ ] **Step 7: Build and run all JVM tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src
git commit -m "feat(scan): resolve an Open with image to its folder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Slideshow helpers

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/slideshow/AutoAdvanceTimer.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/slideshow/ViewerMath.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/slideshow/AutoAdvanceTimerTest.kt`, `app/src/test/java/com/jjveleber/gallery/slideshow/ViewerMathTest.kt`

**Interfaces:**
- Consumes: `INTERVALS_SEC` (Task 6)
- Produces:
  - `enum class PauseReason { Zoomed, Sheet, Background }`
  - `class AutoAdvanceTimer(scope: CoroutineScope, onAdvance: () -> Unit) { fun setEnabled(on: Boolean); fun setInterval(ms: Long); fun reset(); fun pause(reason: PauseReason); fun resume(reason: PauseReason); val isTicking: Boolean }`
  - `object LoopPaging { fun pageCount(n: Int): Int; fun startPage(index: Int, n: Int): Int; fun indexOf(page: Int, n: Int): Int }`
  - `enum class TapZone { Prev, Middle, Next }`, `fun tapZone(x: Float, width: Float): TapZone`
  - `fun nextInterval(current: Int): Int`, `fun intervalLabel(sec: Int): String`

- [ ] **Step 1: Write the failing tests**

`slideshow/AutoAdvanceTimerTest.kt`:

```kotlin
package com.jjveleber.gallery.slideshow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoAdvanceTimerTest {
    private var advances = 0

    private fun TestScope.timer(intervalMs: Long = 5_000) =
        AutoAdvanceTimer(backgroundScope) { advances++ }.apply { setInterval(intervalMs) }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun ticksEveryInterval() = runTest {
        val t = timer()
        t.setEnabled(true)
        advance(4_999); assertEquals(0, advances)
        advance(1); assertEquals(1, advances)
        advance(5_000); assertEquals(2, advances)
    }

    @Test fun disabledNeverTicks() = runTest {
        timer()
        advance(60_000)
        assertEquals(0, advances)
    }

    @Test fun resetRestartsTheCountdown() = runTest {
        val t = timer()
        t.setEnabled(true)
        advance(4_000)
        t.reset()
        advance(4_000); assertEquals(0, advances)
        advance(1_000); assertEquals(1, advances)
    }

    @Test fun overlappingPausesAllMustResume() = runTest {
        val t = timer()
        t.setEnabled(true)
        t.pause(PauseReason.Zoomed)
        t.pause(PauseReason.Sheet)
        t.resume(PauseReason.Zoomed)
        advance(20_000); assertEquals(0, advances)
        assertFalse(t.isTicking)
        t.resume(PauseReason.Sheet)
        assertTrue(t.isTicking)
        advance(5_000); assertEquals(1, advances)
    }

    @Test fun resumingAnUnpausedReasonDoesNotRestart() = runTest {
        val t = timer()
        t.setEnabled(true)
        advance(4_000)
        t.resume(PauseReason.Background)
        advance(1_000); assertEquals(1, advances)
    }

    @Test fun intervalChangeAppliesImmediately() = runTest {
        val t = timer()
        t.setEnabled(true)
        advance(2_000)
        t.setInterval(3_000)
        advance(2_999); assertEquals(0, advances)
        advance(1); assertEquals(1, advances)
    }
}
```

`slideshow/ViewerMathTest.kt`:

```kotlin
package com.jjveleber.gallery.slideshow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerMathTest {
    @Test fun startPageMapsToIndex() {
        for (n in listOf(2, 3, 7, 10_000)) for (i in listOf(0, 1, n - 1)) {
            assertEquals(i, LoopPaging.indexOf(LoopPaging.startPage(i, n), n))
        }
    }

    @Test fun swipingBackFromFirstWrapsToLast() {
        val n = 5
        assertEquals(n - 1, LoopPaging.indexOf(LoopPaging.startPage(0, n) - 1, n))
    }

    @Test fun swipingForwardFromLastWrapsToFirst() {
        val n = 5
        assertEquals(0, LoopPaging.indexOf(LoopPaging.startPage(n - 1, n) + 1, n))
    }

    @Test fun singleItemDoesNotLoop() {
        assertEquals(1, LoopPaging.pageCount(1))
        assertEquals(0, LoopPaging.startPage(0, 1))
        assertEquals(0, LoopPaging.indexOf(0, 1))
    }

    @Test fun manyItemsLeaveRoomToLoopBothWays() {
        val n = 10_000
        val start = LoopPaging.startPage(0, n)
        assertTrue(start >= 100 * n)
        assertTrue(LoopPaging.pageCount(n) - start >= 100 * n)
    }

    @Test fun tapZones() {
        assertEquals(TapZone.Prev, tapZone(10f, 1000f))
        assertEquals(TapZone.Prev, tapZone(149f, 1000f))
        assertEquals(TapZone.Middle, tapZone(150f, 1000f))
        assertEquals(TapZone.Middle, tapZone(850f, 1000f))
        assertEquals(TapZone.Next, tapZone(851f, 1000f))
    }

    @Test fun nextIntervalCyclesThroughTheList() {
        assertEquals(10, nextInterval(5))
        assertEquals(3, nextInterval(300))
        assertEquals(3, nextInterval(7)) // not in the list: start over
    }

    @Test fun intervalLabels() {
        assertEquals("3 s", intervalLabel(3))
        assertEquals("1 min", intervalLabel(60))
        assertEquals("5 min", intervalLabel(300))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*slideshow*'`
Expected: FAIL to compile with `Unresolved reference: AutoAdvanceTimer` (and `LoopPaging`).

- [ ] **Step 3: Implement `slideshow/AutoAdvanceTimer.kt`**

```kotlin
package com.jjveleber.gallery.slideshow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class PauseReason { Zoomed, Sheet, Background }

/** Calls [onAdvance] every interval while enabled and not paused for any reason. Any change restarts the countdown. */
class AutoAdvanceTimer(private val scope: CoroutineScope, private val onAdvance: () -> Unit) {
    private var job: Job? = null
    private var enabled = false
    private var intervalMs = 5_000L
    private val pauses = mutableSetOf<PauseReason>()

    val isTicking: Boolean get() = job?.isActive == true

    fun setEnabled(on: Boolean) {
        enabled = on
        restart()
    }

    fun setInterval(ms: Long) {
        intervalMs = ms
        restart()
    }

    /** Call after manual navigation so the next auto advance is a full interval away. */
    fun reset() = restart()

    fun pause(reason: PauseReason) {
        if (pauses.add(reason)) restart()
    }

    fun resume(reason: PauseReason) {
        if (pauses.remove(reason)) restart()
    }

    private fun restart() {
        job?.cancel()
        job = if (enabled && pauses.isEmpty()) {
            scope.launch {
                while (isActive) {
                    delay(intervalMs)
                    onAdvance()
                }
            }
        } else {
            null
        }
    }
}
```

- [ ] **Step 4: Implement `slideshow/ViewerMath.kt`**

```kotlin
package com.jjveleber.gallery.slideshow

import com.jjveleber.gallery.model.INTERVALS_SEC

/** Maps pager pages to item indexes over a large virtual page range, so swiping wraps around both ways. */
object LoopPaging {
    private const val VIRTUAL_PAGES = 10_000_000

    fun pageCount(n: Int): Int = if (n <= 1) n else VIRTUAL_PAGES

    fun startPage(index: Int, n: Int): Int {
        if (n <= 1) return 0
        val middle = VIRTUAL_PAGES / 2
        return middle - middle % n + index
    }

    fun indexOf(page: Int, n: Int): Int = if (n <= 1) 0 else page % n
}

enum class TapZone { Prev, Middle, Next }

/** The outer 15% on each side navigates; the middle toggles the overlay. */
fun tapZone(x: Float, width: Float): TapZone = when {
    x < width * 0.15f -> TapZone.Prev
    x > width * 0.85f -> TapZone.Next
    else -> TapZone.Middle
}

fun nextInterval(current: Int): Int {
    val i = INTERVALS_SEC.indexOf(current)
    return if (i < 0) INTERVALS_SEC.first() else INTERVALS_SEC[(i + 1) % INTERVALS_SEC.size]
}

fun intervalLabel(sec: Int): String = if (sec < 60) "$sec s" else "${sec / 60} min"
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*slideshow*'`
Expected: PASS (14 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(slideshow): auto advance timer and looping pager math

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: GalleryViewModel

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/ui/GalleryViewModel.kt`
- Create: `app/src/test/java/com/jjveleber/gallery/testing/Fakes.kt`, `app/src/test/java/com/jjveleber/gallery/testing/MainDispatcherRule.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/ui/GalleryViewModelTest.kt`

**Interfaces:**
- Consumes: `FolderScanner` (Task 7), `OpenWithResolver`, `OpenWithResult`, `FolderLocation` (Task 8), `SettingsStore`, `Settings` (Task 6), `sortItems`, `SortSpec` (Task 2)
- Produces:
  - `sealed interface Screen { Picker; Scanning(found: Int); Loading; Grid; Viewer(startIndex: Int) }`
  - `data class OpenWithState(single: Boolean, incomingName: String, folderHint: FolderLocation?)`
  - `class GalleryViewModel(scanner, resolver, settingsStore)` with: `settings: StateFlow<Settings>`, `screen: StateFlow<Screen>`, `items: StateFlow<List<ImageItem>>`, `folderName: StateFlow<String>`, `openWith: StateFlow<OpenWithState?>`, `message: StateFlow<String?>`, `lastViewedIndex: StateFlow<Int>`; methods `pickFolder(treeUri)`, `cancelScan()`, `changeFolder()`, `openWith(uri, mayReadMedia)`, `browseOpenWithFolder(treeUri)`, `openViewer(index, play)`, `viewerShowing(index)`, `closeViewer(): Boolean`, `setSort(spec)`, `reshuffle()`, `updateSettings(transform)`, `consumeMessage()`

- [ ] **Step 1: Write the test support files**

`testing/MainDispatcherRule.kt`:

```kotlin
package com.jjveleber.gallery.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

`testing/Fakes.kt`:

```kotlin
package com.jjveleber.gallery.testing

import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.scan.FolderScanner
import com.jjveleber.gallery.scan.OpenWithResolver
import com.jjveleber.gallery.scan.OpenWithResult
import com.jjveleber.gallery.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Each tree's scan waits until the test completes [result] for it. */
class FakeScanner : FolderScanner {
    private val results = mutableMapOf<String, CompletableDeferred<List<ImageItem>>>()
    val recursiveCalls = mutableListOf<Boolean>()
    var progressToReport: List<Int> = emptyList()
    var failWith: Exception? = null

    fun result(treeUri: String) = results.getOrPut(treeUri) { CompletableDeferred() }

    override suspend fun folderName(treeUri: String) = treeUri.substringAfterLast('/')

    override suspend fun scan(treeUri: String, recursive: Boolean, onProgress: (Int) -> Unit): List<ImageItem> {
        recursiveCalls += recursive
        failWith?.let { throw it }
        progressToReport.forEach(onProgress)
        return result(treeUri).await()
    }
}

class FakeResolver : OpenWithResolver {
    var result: OpenWithResult? = null
    val calls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun resolve(uri: String, mayReadMedia: Boolean): OpenWithResult {
        calls += uri to mayReadMedia
        return result!!
    }
}

class FakeSettingsStore(initial: Settings = Settings()) : SettingsStore {
    val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state
    override suspend fun update(transform: (Settings) -> Settings) = state.update(transform)
}
```

- [ ] **Step 2: Write the failing tests**

`ui/GalleryViewModelTest.kt`:

```kotlin
package com.jjveleber.gallery.ui

import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.model.SortOrder
import com.jjveleber.gallery.model.SortSpec
import com.jjveleber.gallery.scan.FolderLocation
import com.jjveleber.gallery.scan.OpenWithResult
import com.jjveleber.gallery.testing.FakeResolver
import com.jjveleber.gallery.testing.FakeScanner
import com.jjveleber.gallery.testing.FakeSettingsStore
import com.jjveleber.gallery.testing.MainDispatcherRule
import com.jjveleber.gallery.testing.item
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.FileNotFoundException

class GalleryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val scanner = FakeScanner()
    private val resolver = FakeResolver()
    private val store = FakeSettingsStore()
    private fun vm() = GalleryViewModel(scanner, resolver, store)
    private fun GalleryViewModel.names() = items.value.map { it.relPath }

    @Test fun startsAtPicker() = runTest {
        assertEquals(Screen.Picker, vm().screen.value)
    }

    @Test fun pickFolderScansRecursivelyAndShowsSortedGrid() = runTest {
        val vm = vm()
        vm.pickFolder("tree/Photos")
        assertEquals(Screen.Scanning(0), vm.screen.value)
        scanner.result("tree/Photos").complete(listOf(item("b10.jpg"), item("b2.jpg")))
        assertEquals(Screen.Grid, vm.screen.value)
        assertEquals(listOf("b2.jpg", "b10.jpg"), vm.names())
        assertEquals("Photos", vm.folderName.value)
        assertEquals(listOf(true), scanner.recursiveCalls)
    }

    @Test fun scanProgressShowsOnScanningScreen() = runTest {
        scanner.progressToReport = listOf(3, 7)
        val vm = vm()
        vm.pickFolder("tree/a")
        assertEquals(Screen.Scanning(7), vm.screen.value)
    }

    @Test fun scanFailureReturnsToPickerWithMessage() = runTest {
        scanner.failWith = FileNotFoundException("gone")
        val vm = vm()
        vm.pickFolder("tree/a")
        assertEquals(Screen.Picker, vm.screen.value)
        assertEquals("Folder no longer available", vm.message.value)
        vm.consumeMessage()
        assertNull(vm.message.value)
    }

    @Test fun pickingAgainDuringScanDiscardsFirstScan() = runTest {
        val vm = vm()
        vm.pickFolder("tree/a")
        vm.pickFolder("tree/b")
        scanner.result("tree/a").complete(listOf(item("a.jpg")))
        assertEquals(Screen.Scanning(0), vm.screen.value)
        scanner.result("tree/b").complete(listOf(item("b.jpg")))
        assertEquals(listOf("b.jpg"), vm.names())
        assertEquals("b", vm.folderName.value)
    }

    @Test fun cancelScanReturnsToPickerAndIgnoresLateResult() = runTest {
        val vm = vm()
        vm.pickFolder("tree/a")
        vm.cancelScan()
        scanner.result("tree/a").complete(listOf(item("a.jpg")))
        assertEquals(Screen.Picker, vm.screen.value)
        assertTrue(vm.items.value.isEmpty())
    }

    @Test fun scanUsesSavedSort() = runTest {
        store.state.value = Settings(sort = SortSpec(SortOrder.Name, ascending = false))
        val vm = vm()
        vm.pickFolder("tree/a")
        scanner.result("tree/a").complete(listOf(item("a.jpg"), item("b.jpg")))
        assertEquals(listOf("b.jpg", "a.jpg"), vm.names())
    }

    @Test fun setSortResortsAndSaves() = runTest {
        val vm = vm()
        vm.pickFolder("tree/a")
        scanner.result("tree/a").complete(listOf(item("a.jpg", sizeBytes = 9), item("b.jpg", sizeBytes = 1)))
        vm.setSort(SortSpec(SortOrder.Size))
        assertEquals(listOf("b.jpg", "a.jpg"), vm.names())
        assertEquals(SortSpec(SortOrder.Size), store.state.value.sort)
    }

    @Test fun reshuffleSelectsRandomWithANewSeed() = runTest {
        val vm = vm()
        val before = store.state.value.sort.randomSeed
        vm.reshuffle()
        assertEquals(SortOrder.Random, store.state.value.sort.order)
        assertNotEquals(before, store.state.value.sort.randomSeed)
    }

    @Test fun openViewerRecordsIndexAndPlayTurnsOnAutoAdvance() = runTest {
        val vm = vm()
        vm.openViewer(3, play = false)
        assertEquals(Screen.Viewer(3), vm.screen.value)
        assertEquals(3, vm.lastViewedIndex.value)
        assertFalse(store.state.value.autoAdvance)
        vm.openViewer(0, play = true)
        assertTrue(store.state.value.autoAdvance)
    }

    @Test fun closeViewerReturnsToGrid() = runTest {
        val vm = vm()
        vm.openViewer(0, play = false)
        vm.viewerShowing(5)
        assertFalse(vm.closeViewer())
        assertEquals(Screen.Grid, vm.screen.value)
        assertEquals(5, vm.lastViewedIndex.value)
    }

    @Test fun changeFolderClearsItemsAndShowsPicker() = runTest {
        val vm = vm()
        vm.pickFolder("tree/a")
        scanner.result("tree/a").complete(listOf(item("a.jpg")))
        vm.changeFolder()
        assertEquals(Screen.Picker, vm.screen.value)
        assertTrue(vm.items.value.isEmpty())
    }

    @Test fun openWithResolvedStartsAtIncomingImageInSavedSort() = runTest {
        store.state.value = Settings(sort = SortSpec(SortOrder.Name, ascending = false))
        val siblings = listOf(item("a.jpg"), item("b.jpg"), item("c.jpg"))
        resolver.result = OpenWithResult.Resolved(siblings, startUri = siblings[0].uri, folderName = "Trip")
        val vm = vm()
        vm.openWith("content://x/a", mayReadMedia = true)
        assertEquals(listOf("c.jpg", "b.jpg", "a.jpg"), vm.names())
        assertEquals(Screen.Viewer(2), vm.screen.value)
        assertEquals("Trip", vm.folderName.value)
        assertEquals(false, vm.openWith.value?.single)
        assertEquals(listOf("content://x/a" to true), resolver.calls)
    }

    @Test fun openWithSingleShowsOneImageWithHint() = runTest {
        val hint = FolderLocation("external_primary", "Pictures/", "a.jpg")
        resolver.result = OpenWithResult.Single(item("a.jpg"), hint)
        val vm = vm()
        vm.openWith("content://x/a", mayReadMedia = false)
        assertEquals(listOf("a.jpg"), vm.names())
        assertEquals(Screen.Viewer(0), vm.screen.value)
        assertEquals(OpenWithState(single = true, incomingName = "a.jpg", folderHint = hint), vm.openWith.value)
    }

    @Test fun closeViewerInOpenWithModeAsksToFinish() = runTest {
        resolver.result = OpenWithResult.Single(item("a.jpg"), null)
        val vm = vm()
        vm.openWith("content://x/a", mayReadMedia = false)
        assertTrue(vm.closeViewer())
    }

    @Test fun browseOpenWithFolderScansOnlyThatFolderAndPositionsAtIncoming() = runTest {
        resolver.result = OpenWithResult.Single(item("b.jpg"), null)
        val vm = vm()
        vm.openWith("content://x/b", mayReadMedia = false)
        vm.browseOpenWithFolder("tree/Trip")
        scanner.result("tree/Trip").complete(listOf(item("c.jpg"), item("b.jpg"), item("a.jpg")))
        assertEquals(listOf(false), scanner.recursiveCalls)
        assertEquals(Screen.Viewer(1), vm.screen.value)
        assertEquals(false, vm.openWith.value?.single)
    }

    @Test fun browseOpenWithFolderWithNoImagesShowsMessage() = runTest {
        resolver.result = OpenWithResult.Single(item("b.jpg"), null)
        val vm = vm()
        vm.openWith("content://x/b", mayReadMedia = false)
        vm.browseOpenWithFolder("tree/Empty")
        scanner.result("tree/Empty").complete(emptyList())
        assertEquals("No supported images in this folder", vm.message.value)
        assertEquals(Screen.Picker, vm.screen.value)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*GalleryViewModelTest*'`
Expected: FAIL to compile with `Unresolved reference: GalleryViewModel` (and `Screen`, `OpenWithState`).

- [ ] **Step 4: Implement `ui/GalleryViewModel.kt`**

```kotlin
package com.jjveleber.gallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.model.SortOrder
import com.jjveleber.gallery.model.SortSpec
import com.jjveleber.gallery.model.sortItems
import com.jjveleber.gallery.scan.FolderLocation
import com.jjveleber.gallery.scan.FolderScanner
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
    data object Picker : Screen
    data class Scanning(val found: Int) : Screen
    /** Resolving an "Open with" image. */
    data object Loading : Screen
    data object Grid : Screen
    data class Viewer(val startIndex: Int) : Screen
}

/** Set while the app was opened through "Open with". [single] means only the incoming image is shown. */
data class OpenWithState(val single: Boolean, val incomingName: String, val folderHint: FolderLocation?)

class GalleryViewModel(
    private val scanner: FolderScanner,
    private val resolver: OpenWithResolver,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _screen = MutableStateFlow<Screen>(Screen.Picker)
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

    private var unsorted: List<ImageItem> = emptyList()
    private var job: Job? = null
    /** Bumped whenever a scan or resolve is superseded, so late results from the old one are dropped. */
    private var generation = 0

    fun pickFolder(treeUri: String) {
        _openWith.value = null
        scan(treeUri, recursive = true) {
            _lastViewedIndex.value = 0
            Screen.Grid
        }
    }

    /** "Browse this folder" for a single Open with image: that folder only, positioned at the incoming image. */
    fun browseOpenWithFolder(treeUri: String) {
        val incoming = _openWith.value?.incomingName ?: return
        scan(treeUri, recursive = false) { sorted ->
            if (sorted.isEmpty()) {
                _message.value = "No supported images in this folder"
                Screen.Picker
            } else {
                _openWith.value = OpenWithState(single = false, incomingName = incoming, folderHint = null)
                Screen.Viewer(sorted.indexOfFirst { it.name == incoming }.coerceAtLeast(0))
            }
        }
    }

    private fun scan(treeUri: String, recursive: Boolean, done: (List<ImageItem>) -> Screen) {
        job?.cancel()
        val gen = ++generation
        _screen.value = Screen.Scanning(0)
        job = viewModelScope.launch {
            try {
                val name = scanner.folderName(treeUri)
                val found = scanner.scan(treeUri, recursive) { n -> if (gen == generation) _screen.value = Screen.Scanning(n) }
                val sorted = sortItems(found, settingsStore.settings.first().sort)
                if (gen != generation) return@launch
                unsorted = found
                _items.value = sorted
                _folderName.value = name
                _screen.value = done(sorted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _message.value = "Folder no longer available"
                _screen.value = Screen.Picker
            }
        }
    }

    fun cancelScan() {
        job?.cancel()
        generation++
        _screen.value = Screen.Picker
    }

    fun changeFolder() {
        cancelScan()
        unsorted = emptyList()
        _items.value = emptyList()
        _openWith.value = null
    }

    fun openWith(uri: String, mayReadMedia: Boolean) {
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

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*GalleryViewModelTest*'`
Expected: PASS (17 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(ui): gallery screen state machine

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Picker and grid screens

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/ui/grid/GridSizing.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/ui/grid/GridScreen.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/ui/picker/PickerScreen.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/ui/settings/SettingsSheet.kt`
- Create: `app/src/main/java/com/jjveleber/gallery/ui/GalleryRoot.kt`
- Modify: `app/src/main/java/com/jjveleber/gallery/AppContainer.kt`, `app/src/main/java/com/jjveleber/gallery/MainActivity.kt`
- Test: `app/src/test/java/com/jjveleber/gallery/ui/grid/GridSizingTest.kt`

**Interfaces:**
- Consumes: `GalleryViewModel`, `Screen` (Task 10), `AnimatedRegistry`, `imageRequest` (Task 4), `GridSize`, `Settings`, `Transition`, `INTERVALS_SEC` (Task 6), `SortOrder`, `SortSpec` (Task 2), `intervalLabel` (Task 9), `AndroidFolderScanner` (Task 7), `AndroidOpenWithResolver` (Task 8), `DataStoreSettingsStore` (Task 6)
- Produces:
  - `fun GridSize.step(zoom: Float): GridSize`
  - `@Composable fun GridScreen(vm: GalleryViewModel, registry: AnimatedRegistry)`
  - `@Composable fun PickerScreen(screen: Screen, onPick: () -> Unit, onCancel: () -> Unit)`
  - `@Composable fun SettingsSheet(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit)`
  - `@Composable fun GalleryRoot(vm: GalleryViewModel, registry: AnimatedRegistry, onFinish: () -> Unit)` (Tasks 12 and 13 extend it)
  - `AppContainer` gains `settings`, `scanner`, `openWith`

- [ ] **Step 1: Write the failing test for pinch steps**

`ui/grid/GridSizingTest.kt`:

```kotlin
package com.jjveleber.gallery.ui.grid

import com.jjveleber.gallery.model.GridSize
import org.junit.Assert.assertEquals
import org.junit.Test

class GridSizingTest {
    @Test fun spreadingFingersGrowsOneStep() = assertEquals(GridSize.L, GridSize.M.step(1.6f))
    @Test fun pinchingShrinksOneStep() = assertEquals(GridSize.S, GridSize.M.step(0.6f))
    @Test fun smallGestureKeepsSize() = assertEquals(GridSize.M, GridSize.M.step(1.1f))
    @Test fun clampsAtLargest() = assertEquals(GridSize.XL, GridSize.XL.step(3f))
    @Test fun clampsAtSmallest() = assertEquals(GridSize.S, GridSize.S.step(0.2f))
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*GridSizingTest*'`
Expected: FAIL to compile with `Unresolved reference: step`.

- [ ] **Step 3: Implement `ui/grid/GridSizing.kt`**

```kotlin
package com.jjveleber.gallery.ui.grid

import com.jjveleber.gallery.model.GridSize

/** One preset step per pinch gesture: spread fingers for bigger cells, pinch for smaller. */
fun GridSize.step(zoom: Float): GridSize = when {
    zoom > 1.25f -> GridSize.entries.getOrElse(ordinal + 1) { this }
    zoom < 0.8f -> GridSize.entries.getOrElse(ordinal - 1) { this }
    else -> this
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*GridSizingTest*'`
Expected: PASS (5 tests).

- [ ] **Step 5: Write the settings sheet**

`ui/settings/SettingsSheet.kt`:

```kotlin
package com.jjveleber.gallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jjveleber.gallery.model.INTERVALS_SEC
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.model.Transition
import com.jjveleber.gallery.slideshow.intervalLabel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Grid", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Animate thumbnails", settings.animateGrid) { v -> onUpdate { it.copy(animateGrid = v) } }
            Text("Full screen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            SwitchRow("Animate images", settings.animateFullScreen) { v -> onUpdate { it.copy(animateFullScreen = v) } }
            SwitchRow("Auto advance", settings.autoAdvance) { v -> onUpdate { it.copy(autoAdvance = v) } }
            Text("Interval", modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVALS_SEC.forEach { sec ->
                    FilterChip(
                        selected = settings.intervalSec == sec,
                        onClick = { onUpdate { it.copy(intervalSec = sec) } },
                        label = { Text(intervalLabel(sec)) },
                    )
                }
            }
            Text("Transition", modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Transition.entries.forEach { t ->
                    FilterChip(
                        selected = settings.transition == t,
                        onClick = { onUpdate { it.copy(transition = t) } },
                        label = { Text(t.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
```

- [ ] **Step 6: Write the picker screen**

`ui/picker/PickerScreen.kt`:

```kotlin
package com.jjveleber.gallery.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jjveleber.gallery.ui.Screen

@Composable
fun PickerScreen(screen: Screen, onPick: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Gallery", style = MaterialTheme.typography.headlineLarge)
        if (screen is Screen.Scanning) {
            CircularProgressIndicator()
            Text("Found ${"%,d".format(screen.found)} images…")
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        } else {
            Button(onClick = onPick, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp)) {
                Text("Pick folder", style = MaterialTheme.typography.titleLarge)
            }
            Text("Shows every image in the folder you pick, including its subfolders.")
        }
    }
}
```

- [ ] **Step 7: Write the grid screen**

`ui/grid/GridScreen.kt`:

```kotlin
package com.jjveleber.gallery.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.jjveleber.gallery.imaging.AnimatedRegistry
import com.jjveleber.gallery.imaging.imageRequest
import com.jjveleber.gallery.model.GridSize
import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.model.SortOrder
import com.jjveleber.gallery.model.SortSpec
import com.jjveleber.gallery.ui.GalleryViewModel
import com.jjveleber.gallery.ui.settings.SettingsSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridScreen(vm: GalleryViewModel, registry: AnimatedRegistry) {
    val items by vm.items.collectAsState()
    val settings by vm.settings.collectAsState()
    val folder by vm.folderName.collectAsState()
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = vm.lastViewedIndex.value)
    var showSettings by remember { mutableStateOf(false) }
    val currentSize by rememberUpdatedState(settings.gridSize)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(folder, maxLines = 1)
                        Text("${items.size} images", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    TextButton(onClick = { vm.openViewer(0, play = true) }, enabled = items.isNotEmpty()) { Text("Play") }
                    SortMenu(settings.sort, onChange = vm::setSort, onReshuffle = vm::reshuffle)
                    SizeMenu(settings.gridSize) { size -> vm.updateSettings { it.copy(gridSize = size) } }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    TextButton(onClick = vm::changeFolder) { Text("Change folder") }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No supported images in this folder")
                Button(onClick = vm::changeFolder) { Text("Change folder") }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(settings.gridSize.cellDp.dp),
                state = gridState,
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(Color(0xFF101010))
                    .pinchSteps { zoom -> vm.updateSettings { it.copy(gridSize = currentSize.step(zoom)) } },
            ) {
                itemsIndexed(items, key = { _, item -> item.uri }) { index, item ->
                    Thumbnail(item, settings.animateGrid, registry) { vm.openViewer(index, play = false) }
                }
            }
        }
    }
    if (showSettings) SettingsSheet(settings, vm::updateSettings) { showSettings = false }
}

/** Reports the total zoom of each two-finger gesture, and keeps two-finger moves from scrolling the grid. */
private fun Modifier.pinchSteps(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        var multiTouch = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                multiTouch = true
                zoom *= event.calculateZoom()
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (multiTouch) onZoom(zoom)
    }
}

@Composable
private fun Thumbnail(item: ImageItem, animate: Boolean, registry: AnimatedRegistry, onClick: () -> Unit) {
    val context = LocalContext.current
    var failed by remember(item.uri) { mutableStateOf(false) }
    var animated by remember(item.uri) { mutableStateOf(false) }
    val request = remember(item.uri, animate) {
        imageRequest(context, item.uri, animate, object : ImageRequest.Listener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                failed = false
                animated = registry.isAnimated(item.uri)
            }

            override fun onError(request: ImageRequest, result: ErrorResult) {
                failed = true
            }
        })
    }
    Box(
        Modifier.aspectRatio(1f).padding(2.dp).background(Color(0xFF1C1C1C)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Icon(Icons.Default.Warning, contentDescription = "Can't display ${item.name}", tint = Color.Gray)
        } else {
            AsyncImage(model = request, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (animated && !animate) {
            Text(
                "ANIM",
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 4.dp),
            )
        }
    }
}

private val SortOrder.label: String
    get() = when (this) {
        SortOrder.Name -> "Name"
        SortOrder.Modified -> "Date modified"
        SortOrder.Path -> "Folder path"
        SortOrder.Size -> "File size"
        SortOrder.Random -> "Random"
    }

@Composable
private fun SortMenu(spec: SortSpec, onChange: (SortSpec) -> Unit, onReshuffle: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Sort: ${spec.order.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    leadingIcon = { if (order == spec.order) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        // Choosing Random always draws a new seed, so it is not the same "random" order every time.
                        if (order == SortOrder.Random) onReshuffle() else onChange(spec.copy(order = order))
                        open = false
                    },
                )
            }
            HorizontalDivider()
            if (spec.order == SortOrder.Random) {
                DropdownMenuItem(text = { Text("Reshuffle") }, onClick = { onReshuffle(); open = false })
            } else {
                DropdownMenuItem(
                    text = { Text(if (spec.ascending) "Ascending" else "Descending") },
                    onClick = { onChange(spec.copy(ascending = !spec.ascending)) },
                )
            }
        }
    }
}

private val GridSize.label: String
    get() = when (this) {
        GridSize.S -> "Small"
        GridSize.M -> "Medium"
        GridSize.L -> "Large"
        GridSize.XL -> "Extra large"
    }

@Composable
private fun SizeMenu(size: GridSize, onChange: (GridSize) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Size: ${size.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GridSize.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    leadingIcon = { if (s == size) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { onChange(s); open = false },
                )
            }
        }
    }
}
```

- [ ] **Step 8: Write `GalleryRoot` (the viewer branch is filled in by Task 12)**

`ui/GalleryRoot.kt`:

```kotlin
package com.jjveleber.gallery.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.jjveleber.gallery.imaging.AnimatedRegistry
import com.jjveleber.gallery.ui.grid.GridScreen
import com.jjveleber.gallery.ui.picker.PickerScreen

@Composable
fun GalleryRoot(vm: GalleryViewModel, registry: AnimatedRegistry, onFinish: () -> Unit) {
    val screen by vm.screen.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.pickFolder(uri.toString())
    }
    when (val s = screen) {
        Screen.Picker, is Screen.Scanning -> PickerScreen(s, onPick = { pickTree.launch(null) }, onCancel = vm::cancelScan)
        Screen.Loading -> Box(Modifier.fillMaxSize().background(Color.Black))
        Screen.Grid -> {
            BackHandler(onBack = vm::changeFolder)
            GridScreen(vm, registry)
        }
        is Screen.Viewer -> {
            // Replaced by ViewerScreen in Task 12.
            BackHandler { if (vm.closeViewer()) onFinish() }
            Text("Image ${s.startIndex + 1}")
        }
    }
}
```

- [ ] **Step 9: Wire the container and activity**

`AppContainer.kt`:

```kotlin
package com.jjveleber.gallery

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.jjveleber.gallery.imaging.AnimatedRegistry
import com.jjveleber.gallery.scan.AndroidFolderScanner
import com.jjveleber.gallery.scan.AndroidOpenWithResolver
import com.jjveleber.gallery.scan.FolderScanner
import com.jjveleber.gallery.scan.OpenWithResolver
import com.jjveleber.gallery.settings.DataStoreSettingsStore
import com.jjveleber.gallery.settings.SettingsStore

class AppContainer(context: Context) {
    val animated = AnimatedRegistry()
    val settings: SettingsStore = DataStoreSettingsStore(
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") }),
    )
    val scanner: FolderScanner = AndroidFolderScanner(context.contentResolver)
    val openWith: OpenWithResolver = AndroidOpenWithResolver(context)
}
```

`MainActivity.kt`:

```kotlin
package com.jjveleber.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jjveleber.gallery.ui.GalleryRoot
import com.jjveleber.gallery.ui.GalleryViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as GalleryApp).container
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val vm: GalleryViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { GalleryViewModel(container.scanner, container.openWith, container.settings) }
                        },
                    )
                    GalleryRoot(vm, container.animated, onFinish = ::finish)
                }
            }
        }
    }
}
```

- [ ] **Step 10: Build, run JVM tests, and try it on the emulator**

Run: `./gradlew :app:testDebugUnitTest :app:installDebug`
Expected: all tests PASS, install succeeds.

Push test images with a nested folder and launch:

```bash
ADB=$ANDROID_HOME/platform-tools/adb
$ADB shell mkdir -p /sdcard/Pictures/GalleryTest/sub /sdcard/Pictures/GalleryTest/.thumbnails
for f in app/src/androidTest/assets/images/*; do $ADB push "$f" /sdcard/Pictures/GalleryTest/; done
$ADB push app/src/androidTest/assets/images/still.png /sdcard/Pictures/GalleryTest/sub/nested.png
$ADB push app/src/androidTest/assets/images/still.jpg /sdcard/Pictures/GalleryTest/.thumbnails/hidden.jpg
$ADB shell am start -n com.jjveleber.gallery/.MainActivity
```

On the emulator: tap **Pick folder**, choose `Pictures/GalleryTest`, tap **Use this folder** and allow. Expected (take screenshots with `adb exec-out screencap -p` to check): the grid shows 11 images (10 fixtures plus `sub/nested.png`; no `hidden.jpg`), `corrupt.jpg` shows the warning icon, the three `anim.*` cells show an `ANIM` badge, sort and size menus change the grid, and turning on **Animate thumbnails** in Settings animates them. Tapping a cell shows the interim "Image N" text; Back returns to the grid.

- [ ] **Step 11: Commit**

```bash
git add app/src
git commit -m "feat(ui): folder picker and thumbnail grid

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Full-screen viewer

**Files:**
- Create: `app/src/main/java/com/jjveleber/gallery/ui/viewer/ViewerScreen.kt`
- Modify: `app/src/main/java/com/jjveleber/gallery/ui/GalleryRoot.kt` (viewer branch)

**Interfaces:**
- Consumes: `GalleryViewModel`, `Screen` (Task 10), `AutoAdvanceTimer`, `PauseReason`, `LoopPaging`, `TapZone`, `tapZone`, `nextInterval`, `intervalLabel` (Task 9), `imageRequest` (Task 4), `SettingsSheet` (Task 11), `Transition` (Task 6), `FolderLocation` (Task 8)
- Produces: `@Composable fun ViewerScreen(vm: GalleryViewModel, startIndex: Int, onBrowseFolder: (FolderLocation?) -> Unit, onExit: () -> Unit)`

- [ ] **Step 1: Write `ui/viewer/ViewerScreen.kt`**

```kotlin
package com.jjveleber.gallery.ui.viewer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import com.jjveleber.gallery.imaging.imageRequest
import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.model.Transition
import com.jjveleber.gallery.scan.FolderLocation
import com.jjveleber.gallery.slideshow.AutoAdvanceTimer
import com.jjveleber.gallery.slideshow.LoopPaging
import com.jjveleber.gallery.slideshow.PauseReason
import com.jjveleber.gallery.slideshow.TapZone
import com.jjveleber.gallery.slideshow.intervalLabel
import com.jjveleber.gallery.slideshow.nextInterval
import com.jjveleber.gallery.slideshow.tapZone
import com.jjveleber.gallery.ui.GalleryViewModel
import com.jjveleber.gallery.ui.settings.SettingsSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState

@Composable
fun ViewerScreen(
    vm: GalleryViewModel,
    startIndex: Int,
    onBrowseFolder: (FolderLocation?) -> Unit,
    onExit: () -> Unit,
) {
    val items by vm.items.collectAsState()
    val settings by vm.settings.collectAsState()
    val openWith by vm.openWith.collectAsState()
    val close = { if (vm.closeViewer()) onExit() }
    BackHandler(onBack = close)
    val n = items.size
    if (n == 0) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = LoopPaging.startPage(startIndex.coerceIn(0, n - 1), n)) { LoopPaging.pageCount(n) }
    val current = LoopPaging.indexOf(pager.currentPage, n)
    var overlay by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var fadingFrom by remember { mutableStateOf<ImageItem?>(null) }
    val fade = remember { Animatable(0f) }

    val advance by rememberUpdatedState<suspend () -> Unit>(newValue = {
        if (n > 1) {
            if (settings.transition == Transition.Crossfade) {
                // Draw the outgoing image on top and fade it out over the incoming page.
                fadingFrom = items[LoopPaging.indexOf(pager.currentPage, n)]
                fade.snapTo(1f)
                pager.scrollToPage(pager.currentPage + 1)
                fade.animateTo(0f, tween(400))
                fadingFrom = null
            } else {
                pager.scrollToPage(pager.currentPage + 1)
            }
        }
    })
    // Advance in its own coroutine: the page change resets the timer, which must not cancel the fade.
    val timer = remember { AutoAdvanceTimer(scope) { scope.launch { advance() } } }

    LaunchedEffect(settings.autoAdvance, settings.intervalSec) {
        timer.setInterval(settings.intervalSec * 1000L)
        timer.setEnabled(settings.autoAdvance)
    }
    LaunchedEffect(current) {
        vm.viewerShowing(current)
        zoomed = false
        timer.reset()
    }
    LaunchedEffect(zoomed) { if (zoomed) timer.pause(PauseReason.Zoomed) else timer.resume(PauseReason.Zoomed) }
    LaunchedEffect(showSettings) { if (showSettings) timer.pause(PauseReason.Sheet) else timer.resume(PauseReason.Sheet) }
    LaunchedEffect(overlay, current) {
        if (overlay) {
            delay(3_000)
            overlay = false
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> timer.pause(PauseReason.Background)
                Lifecycle.Event.ON_START -> timer.resume(PauseReason.Background)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            timer.setEnabled(false)
        }
    }
    ImmersiveWindow(keepScreenOn = settings.autoAdvance)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed && n > 1,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ViewerPage(
                item = items[LoopPaging.indexOf(page, n)],
                animate = settings.animateFullScreen,
                onZoomChange = { if (page == pager.currentPage) zoomed = it },
                onTap = { x, width ->
                    // While zoomed, every tap toggles the overlay; navigation waits until zoomed out.
                    val zone = if (zoomed) TapZone.Middle else tapZone(x, width)
                    if (zone == TapZone.Middle) {
                        overlay = !overlay
                    } else if (n > 1) {
                        val step = if (zone == TapZone.Next) 1 else -1
                        scope.launch { pager.animateScrollToPage(pager.currentPage + step) }
                    }
                },
            )
        }
        fadingFrom?.let { item ->
            AsyncImage(
                model = imageRequest(context, item.uri, settings.animateFullScreen),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
            )
        }
        AnimatedVisibility(
            visible = overlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerOverlay(
                name = items[current].name,
                position = "${current + 1} / $n",
                playing = settings.autoAdvance,
                intervalSec = settings.intervalSec,
                showBrowse = openWith?.single == true,
                onBack = close,
                onPlayPause = { vm.updateSettings { it.copy(autoAdvance = !it.autoAdvance) } },
                onInterval = { vm.updateSettings { it.copy(intervalSec = nextInterval(it.intervalSec)) } },
                onSettings = { showSettings = true },
                onBrowse = { onBrowseFolder(openWith?.folderHint) },
            )
        }
    }
    if (showSettings) SettingsSheet(settings, vm::updateSettings) { showSettings = false }
}

@Composable
private fun ViewerPage(
    item: ImageItem,
    animate: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onTap: (x: Float, width: Float) -> Unit,
) {
    val context = LocalContext.current
    var failed by remember(item.uri) { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(1f) }
    val tap by rememberUpdatedState(onTap)
    val state = rememberZoomableImageState()
    val zoomed = (state.zoomableState.zoomFraction ?: 0f) > 0.01f
    LaunchedEffect(zoomed) { onZoomChange(zoomed) }
    val request = remember(item.uri, animate) {
        imageRequest(context, item.uri, animate, object : ImageRequest.Listener {
            override fun onError(request: ImageRequest, result: ErrorResult) {
                failed = true
            }
        })
    }
    Box(Modifier.fillMaxSize().onSizeChanged { width = it.width.toFloat() }) {
        if (failed) {
            Text("Can't display ${item.name}", color = Color.White, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.fillMaxSize().pointerInput(item.uri) { detectTapGestures { tap(it.x, width) } })
        } else {
            ZoomableAsyncImage(
                model = request,
                contentDescription = item.name,
                state = state,
                modifier = Modifier.fillMaxSize(),
                onClick = { tap(it.x, width) },
            )
        }
    }
}

@Composable
private fun ViewerOverlay(
    name: String,
    position: String,
    playing: Boolean,
    intervalSec: Int,
    showBrowse: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onInterval: () -> Unit,
    onSettings: () -> Unit,
    onBrowse: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(position, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
        if (showBrowse) AssistChip(onClick = onBrowse, label = { Text("Browse this folder") })
        TextButton(onClick = onPlayPause) { Text(if (playing) "Pause" else "Play") }
        TextButton(onClick = onInterval) { Text(intervalLabel(intervalSec)) }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White) }
    }
}

/** Hides the system bars while the viewer is shown, and keeps the screen on while auto advance runs. */
@Composable
private fun ImmersiveWindow(keepScreenOn: Boolean) {
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
```

- [ ] **Step 2: Replace the viewer branch in `GalleryRoot`**

In `ui/GalleryRoot.kt`, replace the whole `is Screen.Viewer -> { ... }` branch with:

```kotlin
        is Screen.Viewer -> ViewerScreen(vm, s.startIndex, onBrowseFolder = {}, onExit = onFinish)
```

and add `import com.jjveleber.gallery.ui.viewer.ViewerScreen`. Remove the now-unused `Text` import. (`onBrowseFolder` is wired in Task 13.)

- [ ] **Step 3: Build, test, and try it on the emulator**

Run: `./gradlew :app:testDebugUnitTest :app:installDebug`
Expected: all tests PASS, install succeeds.

On the emulator, with `Pictures/GalleryTest` from Task 11 picked, check each line and screenshot where useful:
- Tap a thumbnail: full screen, no system bars, overlay with name and `N / 11`, overlay hides after 3 s.
- Swipe left and right, and tap the outer edges: moves one image; from the last image, next goes to the first.
- Double-tap zooms in; while zoomed, swiping pans instead of changing image; double-tap again to zoom out.
- Tap **Play** in the overlay: advances every 5 s with a fade. Tap the interval button: cycles 5 s, 10 s, ... Open settings: auto advance pauses until the sheet closes.
- `corrupt.jpg` shows "Can't display corrupt.jpg" and auto advance continues past it.
- Back returns to the grid, scrolled to the last image viewed.
- **Play** in the grid starts at the first image with auto advance on.

- [ ] **Step 4: Commit**

```bash
git add app/src
git commit -m "feat(ui): full-screen viewer with zoom and auto advance

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Open with

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/jjveleber/gallery/MainActivity.kt`
- Modify: `app/src/main/java/com/jjveleber/gallery/ui/GalleryRoot.kt`

**Interfaces:**
- Consumes: `GalleryViewModel.openWith`, `browseOpenWithFolder`, `openWith` state (Task 10), `FolderLocation.externalStorageDocId` (Task 8), `ViewerScreen` (Task 12)
- Produces: `@Composable fun GalleryRoot(vm: GalleryViewModel, registry: AnimatedRegistry, openWithUri: String?, onFinish: () -> Unit)`

- [ ] **Step 1: Declare the intent filter and permissions**

In `AndroidManifest.xml`, add before `<application`:

```xml
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
```

and inside the `MainActivity` `<activity>`, after the launcher filter:

```xml
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
```

- [ ] **Step 2: Pass the incoming URI from `MainActivity`**

In `MainActivity.onCreate`, before `setContent`, add:

```kotlin
        // Only on first creation: after a recreation the ViewModel already holds the result.
        val openWithUri = intent.takeIf { savedInstanceState == null && it.action == Intent.ACTION_VIEW }?.data?.toString()
```

change the call to `GalleryRoot(vm, container.animated, openWithUri, onFinish = ::finish)`, and add `import android.content.Intent`.

- [ ] **Step 3: Rewrite `GalleryRoot` with the permission request and the browse picker**

`ui/GalleryRoot.kt`:

```kotlin
package com.jjveleber.gallery.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.jjveleber.gallery.imaging.AnimatedRegistry
import com.jjveleber.gallery.scan.externalStorageDocId
import com.jjveleber.gallery.ui.grid.GridScreen
import com.jjveleber.gallery.ui.picker.PickerScreen
import com.jjveleber.gallery.ui.viewer.ViewerScreen

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

@Composable
fun GalleryRoot(vm: GalleryViewModel, registry: AnimatedRegistry, openWithUri: String?, onFinish: () -> Unit) {
    val screen by vm.screen.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.pickFolder(uri.toString())
    }
    val browseTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.browseOpenWithFolder(uri.toString())
    }

    if (openWithUri != null) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.openWith(openWithUri, mayReadMedia = granted)
        }
        LaunchedEffect(openWithUri) {
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                vm.openWith(openWithUri, mayReadMedia = true)
            } else {
                requestPermission.launch(permission)
            }
        }
    }

    when (val s = screen) {
        Screen.Picker, is Screen.Scanning -> PickerScreen(s, onPick = { pickTree.launch(null) }, onCancel = vm::cancelScan)
        Screen.Loading -> Box(Modifier.fillMaxSize().background(Color.Black))
        Screen.Grid -> {
            BackHandler(onBack = vm::changeFolder)
            GridScreen(vm, registry)
        }
        is Screen.Viewer -> ViewerScreen(
            vm,
            s.startIndex,
            onBrowseFolder = { hint ->
                browseTree.launch(hint?.let { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, it.externalStorageDocId()) })
            },
            onExit = onFinish,
        )
    }
}
```

`LaunchedEffect(openWithUri)` runs once per activity instance; since `MainActivity` handles rotation itself (`configChanges`) and passes `null` after a recreation, the permission is asked at most once per Open with.

- [ ] **Step 4: Build, test, and try it on the emulator**

Run: `./gradlew :app:testDebugUnitTest :app:installDebug`
Expected: all tests PASS, install succeeds.

```bash
ADB=$ANDROID_HOME/platform-tools/adb
$ADB shell pm revoke com.jjveleber.gallery android.permission.READ_MEDIA_IMAGES || true
$ADB shell am start -a android.intent.action.VIEW -t image/png \
  -d "content://com.android.externalstorage.documents/document/primary%3APictures%2FGalleryTest%2Fstill.png" \
  -n com.jjveleber.gallery/.MainActivity --grant-read-uri-permission
```

Expected: the permission dialog appears. **Deny**: the viewer shows only `still.png` with a **Browse this folder** chip; tapping it opens the folder picker at `Pictures/GalleryTest`; choosing it shows that folder's 10 images (not `sub/nested.png`), positioned at `still.png`. Run the `am start` again and **Allow**: the viewer opens at `still.png` and swiping goes through the folder's other images. Back closes the app in both cases. If the `am start` URI is refused by the externalstorage provider on the emulator, use the Files app instead: long-press `still.png`, **Open with**, **Gallery**.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat(ui): Open with, with a browse-folder fallback

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: README and final verification

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: everything
- Produces: install and usage docs

- [ ] **Step 1: Write `README.md`**

```markdown
# Gallery (Fire Max 11)

Image viewer for Amazon Fire tablets (Fire OS 8 / Android 11+). Pick a folder and browse every image in it and its subfolders as a thumbnail grid or a full-screen slideshow. Opens images from other apps through **Open with**. Supports JPEG, PNG, WebP, GIF, BMP and HEIC, and plays animated GIF, WebP and APNG.

## Build

    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
    ./gradlew :app:assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
    ./gradlew :app:testDebugUnitTest    # JVM tests
    ./gradlew :app:connectedDebugAndroidTest   # needs an emulator or device on adb

Test fixtures are generated by `python3 -I tools/make_test_assets.py` (needs Pillow and `gif2webp`) and are committed.

## Install on the Fire tablet (adb from this WSL2 PC)

1. On the tablet: **Settings > Device Options > About Fire Tablet**, tap **Serial Number** 7 times. This unlocks Developer Options.
2. Go to **Settings > Device Options > Developer Options** and turn on **USB debugging** (ADB).
3. Connect the tablet to the Windows PC by USB and tap **Allow** on the debugging prompt on the tablet.
4. WSL cannot see USB devices directly, so use the Windows adb. From the repo folder in WSL:

       cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/jjvel/Downloads/gallery.apk
       /mnt/c/Users/jjvel/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r 'C:\Users\jjvel\Downloads\gallery.apk'

5. When it prints `Success`, find **Gallery** in the app grid on the tablet.

To update, install the newer APK the same way. Your settings are kept.

## Using it

- **Pick folder:** choose a folder and tap **Use this folder**. The app remembers nothing between launches, so it always starts here.
- **Grid:** pinch to change the thumbnail size, or use **Size**. **Sort** offers name, date modified, folder path, file size and random (with **Reshuffle**), ascending or descending. Animated images show an **ANIM** badge unless **Animate thumbnails** is on in Settings.
- **Full screen:** tap a thumbnail. Swipe, or tap the left or right edge, to move between images; it wraps around at the ends. Pinch or double-tap to zoom. Tap the middle to show the controls: **Play/Pause** auto advance, the interval (tap to change), and Settings.
- **Play** in the grid starts a slideshow from the first image.
- **Open with:** in the Files app, long-press an image, choose **Open with**, then **Gallery**. Allow photo access to swipe through the other images in the same folder. If you deny it, tap **Browse this folder** and pick the folder once.

Settings (sort, size, auto advance, interval, transition, and the two animation switches) are saved between launches.
```

- [ ] **Step 2: Run every test and the build**

Run: `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; all JVM tests and the 6 instrumented tests PASS. Paste the test summary in the task report.

- [ ] **Step 3: Check for em-dashes**

Run: `grep -rn $'\u2014' --include='*.kt' --include='*.kts' --include='*.md' --include='*.xml' --include='*.py' . || echo clean`
Expected: `clean`.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: build, install and usage

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: CHECKPOINT: manual device checklist (human)**

Ask the human to install the APK on the Fire Max 11 (README) and go through the spec's manual checklist (section 7): a large mixed folder (1,000+ images) with nested subfolders; every sort and direction plus Reshuffle; pinch and menu between grid sizes; grid and full-screen animation on and off; slideshow at several intervals, zoom pausing auto advance, looping at the end; Open with from Files with the permission granted and denied; rotation on the grid and the viewer; a corrupt file in the folder. Record failures as follow-up issues; the plan is done when the checklist passes.
