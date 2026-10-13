package com.jjveleber.gallery.ui

import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.model.SortOrder
import com.jjveleber.gallery.model.SortSpec
import com.jjveleber.gallery.scan.FolderLocation
import com.jjveleber.gallery.scan.FolderScanner
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

    @Test fun staleProgressAfterCancelDoesNotRevertPicker() = runTest {
        var late: ((Int) -> Unit)? = null
        val capturing = object : FolderScanner by scanner {
            override suspend fun scan(treeUri: String, recursive: Boolean, onProgress: (Int) -> Unit): List<ImageItem> {
                late = onProgress
                return scanner.result(treeUri).await()
            }
        }
        val vm = GalleryViewModel(capturing, resolver, store)
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
