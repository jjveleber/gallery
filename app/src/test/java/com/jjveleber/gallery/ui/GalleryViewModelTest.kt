package com.jjveleber.gallery.ui

import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.model.PRIMARY_VOLUME
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.model.SortOrder
import com.jjveleber.gallery.model.SortSpec
import com.jjveleber.gallery.scan.FolderLocation
import com.jjveleber.gallery.scan.FolderScanner
import com.jjveleber.gallery.scan.MediaFolder
import com.jjveleber.gallery.scan.OpenWithResult
import com.jjveleber.gallery.storage.DeleteKind
import com.jjveleber.gallery.testing.FakeDeleter
import com.jjveleber.gallery.testing.FakeHasher
import com.jjveleber.gallery.testing.FakeLibrary
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
    private val library = FakeLibrary()
    private val resolver = FakeResolver()
    private val store = FakeSettingsStore()
    private val hasher = FakeHasher()
    private val deleter = FakeDeleter()
    private fun vm() = GalleryViewModel(scanner, library, resolver, store, hasher, deleter)
    private fun GalleryViewModel.names() = items.value.map { it.relPath }

    private val trip = FolderRef(PRIMARY_VOLUME, "Pictures/Trip/")
    private val tripFolder = MediaFolder(trip, count = 2, coverUri = "content://media/external_primary/images/media/1", coverMime = "image/jpeg")

    @Test fun startsLoading() = runTest {
        assertEquals(Screen.Loading, vm().screen.value)
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

    @Test fun staleProgressAfterCancelDoesNotRevertPicker() = runTest {
        var late: ((Int) -> Unit)? = null
        val capturing = object : FolderScanner by scanner {
            override suspend fun scan(treeUri: String, recursive: Boolean, onProgress: (Int) -> Unit): List<ImageItem> {
                late = onProgress
                return scanner.result(treeUri).await()
            }
        }
        val vm = GalleryViewModel(capturing, library, resolver, store, hasher, deleter)
        vm.pickFolder("tree/a")
        vm.cancelScan()
        late!!.invoke(5)
        assertEquals(Screen.Picker, vm.screen.value)
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

    @Test fun openViewerRecordsIndex() = runTest {
        val vm = vm()
        vm.openViewer(3, play = false)
        assertEquals(Screen.Viewer(3), vm.screen.value)
        assertEquals(3, vm.lastViewedIndex.value)
    }

    @Test fun openViewerSetsPlayingEveryTime() = runTest {
        val vm = vm()
        assertFalse(vm.playing.value)
        vm.openViewer(0, play = true)
        assertTrue(vm.playing.value)
        vm.closeViewer()
        vm.openViewer(4, play = false)
        assertFalse(vm.playing.value)
    }

    @Test fun togglePlayingFlipsIt() = runTest {
        val vm = vm()
        vm.openViewer(0, play = false)
        vm.togglePlaying()
        assertTrue(vm.playing.value)
        vm.togglePlaying()
        assertFalse(vm.playing.value)
    }

    @Test fun closeViewerStopsPlaying() = runTest {
        val vm = vm()
        vm.openViewer(0, play = true)
        vm.closeViewer()
        assertFalse(vm.playing.value)
    }

    @Test fun openWithLaunchOpensStatic() = runTest {
        val siblings = listOf(item("a.jpg"), item("b.jpg"))
        resolver.result = OpenWithResult.Resolved(siblings, startUri = siblings[0].uri, folderName = "Trip")
        val vm = vm()
        vm.openViewer(0, play = true)
        vm.openWith("content://x/a", mayReadMedia = true)
        assertEquals(Screen.Viewer(0), vm.screen.value)
        assertFalse(vm.playing.value)
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

    @Test fun startWithoutRestoringShowsFolderListAndKeepsLastFolder() = runTest {
        store.state.value = Settings(lastFolder = trip)
        library.folderList = listOf(tripFolder)
        val vm = vm()
        vm.start(mayReadMedia = true, restoreLast = false)
        assertEquals(Screen.Folders(listOf(tripFolder)), vm.screen.value)
        assertEquals(trip, store.state.value.lastFolder)
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
        assertNull(store.state.value.lastFolder)
    }

    @Test fun startWithVolumeErrorOnLastFolderFallsBackToFolderList() = runTest {
        store.state.value = Settings(lastFolder = trip)
        library.failWith = IllegalArgumentException("Volume not found")
        library.folderList = listOf(tripFolder)
        val vm = vm()
        vm.start(mayReadMedia = true)
        assertEquals(Screen.Folders(listOf(tripFolder)), vm.screen.value)
        assertNull(store.state.value.lastFolder)
    }

    @Test fun folderListFailureShowsEmptyList() = runTest {
        library.foldersFailWith = FileNotFoundException("no cursor")
        val vm = vm()
        vm.start(mayReadMedia = true)
        assertEquals(Screen.Folders(emptyList()), vm.screen.value)
        assertEquals("Couldn't read the photo library", vm.message.value)
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

    @Test fun openWithPhotoAccessThenEmptyBrowseLandsOnFolderList() = runTest {
        resolver.result = OpenWithResult.Single(item("b.jpg"), null)
        val vm = vm()
        vm.openWith("content://x/b", mayReadMedia = true)
        vm.browseOpenWithFolder("tree/Empty")
        scanner.result("tree/Empty").complete(emptyList())
        assertEquals(Screen.Folders(emptyList()), vm.screen.value)
    }

    @Test fun openWithPhotoAccessThenFailedBrowseLandsOnFolderList() = runTest {
        resolver.result = OpenWithResult.Single(item("b.jpg"), null)
        scanner.failWith = FileNotFoundException("gone")
        val vm = vm()
        vm.openWith("content://x/b", mayReadMedia = true)
        vm.browseOpenWithFolder("tree/Trip")
        assertEquals(Screen.Folders(emptyList()), vm.screen.value)
    }

    @Test fun openWithPhotoAccessThenCancelledBrowseLandsOnFolderList() = runTest {
        resolver.result = OpenWithResult.Single(item("b.jpg"), null)
        val vm = vm()
        vm.openWith("content://x/b", mayReadMedia = true)
        vm.browseOpenWithFolder("tree/Trip")
        vm.cancelScan()
        assertEquals(Screen.Folders(emptyList()), vm.screen.value)
    }

    private var nextMediaId = 1

    /** A MediaStore image whose bytes are [content] (for the fake hasher). */
    private fun media(relPath: String, size: Long = 10, content: String? = null): ImageItem =
        item(relPath, sizeBytes = size).copy(uri = "content://media/external_primary/images/media/${nextMediaId++}")
            .also { if (content != null) hasher.contents[it.uri] = content }

    /** A file in a folder picked in the system picker. */
    private fun doc(relPath: String, size: Long = 10, content: String? = null): ImageItem =
        item(relPath, sizeBytes = size).copy(uri = "content://com.android.externalstorage.documents/tree/primary%3AP/document/primary%3AP%2F$relPath")
            .also { if (content != null) hasher.contents[it.uri] = content }

    private fun openTrip(vararg images: ImageItem): GalleryViewModel {
        val vm = vm()
        vm.start(mayReadMedia = true)
        vm.openFolder(trip)
        library.result(trip).complete(images.toList())
        return vm
    }

    @Test fun mediaFolderFindsCopiesAnywhereInTheLibrary() = runTest {
        val a = media("a.jpg", content = "x")
        val b = media("b.jpg", content = "y")
        val elsewhere = media("Old/a.jpg", content = "x")
        library.all = listOf(a, b, elsewhere)
        val vm = openTrip(a, b)
        assertEquals(setOf(a.uri), vm.duplicates.value.keys)
        assertEquals(listOf(elsewhere), vm.duplicates.value.getValue(a.uri).others)
        assertNull(vm.duplicateProgress.value)
        assertEquals(1, hasher.saves)
    }

    @Test fun pickedFolderComparesOnlyItsOwnFiles() = runTest {
        val a = doc("a.jpg", content = "x")
        val b = doc("sub/a.jpg", content = "x")
        library.all = listOf(media("a.jpg", content = "x"))
        val vm = vm()
        vm.start(mayReadMedia = true)
        vm.pickFolder("tree/P")
        scanner.result("tree/P").complete(listOf(a, b))
        assertEquals(setOf(a.uri, b.uri), vm.duplicates.value.keys)
        assertEquals(listOf(b), vm.duplicates.value.getValue(a.uri).others)
    }

    @Test fun duplicatesOnlyKeepsCopiesGroupedTogether() = runTest {
        val a1 = media("a1.jpg", content = "x")
        val b1 = media("b1.jpg", content = "y")
        val c = media("c.jpg", content = "z", size = 99)
        val a2 = media("d-a2.jpg", content = "x")
        val b2 = media("e-b2.jpg", content = "y")
        library.all = listOf(a1, b1, c, a2, b2)
        val vm = openTrip(a1, b1, c, a2, b2)
        vm.setDuplicatesOnly(true)
        assertEquals(listOf("a1.jpg", "d-a2.jpg", "b1.jpg", "e-b2.jpg"), vm.names())
        vm.setDuplicatesOnly(false)
        assertEquals(5, vm.items.value.size)
    }

    @Test fun changeFolderClearsDuplicates() = runTest {
        val a = media("a.jpg", content = "x")
        val b = media("b.jpg", content = "x")
        library.all = listOf(a, b)
        val vm = openTrip(a, b)
        vm.setDuplicatesOnly(true)
        vm.changeFolder()
        assertTrue(vm.duplicates.value.isEmpty())
        assertFalse(vm.duplicatesOnly.value)
    }

    @Test fun deletingMediaAsksForTrashAndRemovesOnConfirm() = runTest {
        val a = media("a.jpg")
        val b = media("b.jpg")
        val vm = openTrip(a, b)
        vm.requestDelete(listOf(a.uri))
        assertEquals(DeleteRequest(listOf(a.uri), DeleteKind.Trash), vm.deleteRequest.value)
        vm.trashConfirmed()
        assertNull(vm.deleteRequest.value)
        assertEquals(listOf("b.jpg"), vm.names())
        assertEquals("Moved 1 image to the trash", vm.message.value)
    }

    @Test fun cancelledDeleteKeepsImages() = runTest {
        val a = media("a.jpg")
        val vm = openTrip(a)
        vm.requestDelete(listOf(a.uri))
        vm.cancelDelete()
        vm.trashConfirmed()
        assertNull(vm.deleteRequest.value)
        assertEquals(listOf("a.jpg"), vm.names())
    }

    @Test fun pickedFilesDeletePermanentlyAndReportFailures() = runTest {
        val a = doc("a.jpg")
        val b = doc("b.jpg")
        val c = doc("c.jpg")
        deleter.failing += c.uri
        val vm = vm()
        vm.pickFolder("tree/P")
        scanner.result("tree/P").complete(listOf(a, b, c))
        vm.requestDelete(listOf(a.uri, c.uri))
        assertEquals(DeleteKind.Permanent, vm.deleteRequest.value?.kind)
        vm.confirmPermanentDelete()
        assertEquals(listOf(listOf(a.uri, c.uri)), deleter.calls)
        assertEquals(listOf("b.jpg", "c.jpg"), vm.names())
        assertEquals("Deleted 1 of 2 images", vm.message.value)
    }

    @Test fun sharedFilesCannotBeDeleted() = runTest {
        resolver.result = OpenWithResult.Single(item("a.jpg"), null)
        val vm = vm()
        vm.openWith("content://x/a", mayReadMedia = false)
        vm.requestDelete(listOf("content://test/a.jpg"))
        assertNull(vm.deleteRequest.value)
        assertEquals("Can't delete this image", vm.message.value)
    }

    @Test fun deletingACopyUnmarksTheOneLeftWithoutHashingAgain() = runTest {
        val a = media("a.jpg", content = "x")
        val b = media("b.jpg", content = "x")
        val c = media("c.jpg", content = "x")
        library.all = listOf(a, b, c)
        val vm = openTrip(a, b, c)
        val hashes = hasher.hashed.size
        vm.requestDelete(listOf(a.uri))
        vm.trashConfirmed()
        assertEquals(listOf(c), vm.duplicates.value.getValue(b.uri).others)
        vm.requestDelete(listOf(b.uri))
        vm.trashConfirmed()
        assertTrue(vm.duplicates.value.isEmpty())
        assertEquals(hashes, hasher.hashed.size)
    }

    @Test fun deletingInTheViewerKeepsPositionAndLeavesWhenEmpty() = runTest {
        val a = media("a.jpg")
        val b = media("b.jpg")
        val vm = openTrip(a, b)
        vm.openViewer(1, play = false)
        vm.requestDelete(listOf(b.uri))
        vm.trashConfirmed()
        assertEquals(0, vm.lastViewedIndex.value)
        vm.requestDelete(listOf(a.uri))
        vm.trashConfirmed()
        assertEquals(Screen.Grid, vm.screen.value)
    }
}
