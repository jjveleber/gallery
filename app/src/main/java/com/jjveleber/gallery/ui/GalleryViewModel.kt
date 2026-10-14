package com.jjveleber.gallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjveleber.gallery.dupes.Copies
import com.jjveleber.gallery.dupes.ImageHasher
import com.jjveleber.gallery.dupes.findDuplicates
import com.jjveleber.gallery.imaging.isMediaStoreUri
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
import com.jjveleber.gallery.storage.DeleteKind
import com.jjveleber.gallery.storage.DocumentDeleter
import com.jjveleber.gallery.storage.deleteKind
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

/**
 * Images waiting for the user to confirm their deletion. [kind] is never [DeleteKind.Unsupported]. [launched] is set
 * once the system trash dialog is up, so a recreated activity does not open a second one.
 */
data class DeleteRequest(val uris: List<String>, val kind: DeleteKind, val launched: Boolean = false)

/** The most images one system trash dialog takes: the request carries every URI and must fit in a binder call. */
const val MAX_TRASH_BATCH = 500

class GalleryViewModel(
    private val scanner: FolderScanner,
    private val library: MediaLibrary,
    private val resolver: OpenWithResolver,
    private val settingsStore: SettingsStore,
    private val hasher: ImageHasher,
    private val deleter: DocumentDeleter,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _playing = MutableStateFlow(false)
    /** Whether the viewer's slideshow is running. Only Play from the grid starts it; it is never saved. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

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

    private val _duplicates = MutableStateFlow<Map<String, Copies>>(emptyMap())
    /** The byte-identical copies of the current images, by image URI. Filled in the background after a load. */
    val duplicates: StateFlow<Map<String, Copies>> = _duplicates.asStateFlow()

    private val _duplicateProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Files hashed and files to hash while the duplicate search runs, else null. */
    val duplicateProgress: StateFlow<Pair<Int, Int>?> = _duplicateProgress.asStateFlow()

    private val _duplicatesOnly = MutableStateFlow(false)
    /** Whether [items] holds only the images that have copies, each group of copies together. */
    val duplicatesOnly: StateFlow<Boolean> = _duplicatesOnly.asStateFlow()

    private val _deleteRequest = MutableStateFlow<DeleteRequest?>(null)
    val deleteRequest: StateFlow<DeleteRequest?> = _deleteRequest.asStateFlow()

    /** False until [start] or [openWith] has run, so the launch flow runs once per ViewModel. */
    var started = false
        private set

    /** True when photo access is granted: home is the folder list instead of the picker. */
    private var mediaMode = false
    private var unsorted: List<ImageItem> = emptyList()
    private var job: Job? = null
    private var duplicateJob: Job? = null
    /** Bumped whenever a scan or resolve is superseded, so late results from the old one are dropped. */
    private var generation = 0

    /**
     * True once the permission dialog has been requested in this process. Held here, not saved, so a
     * process death resets it and the request is made again instead of leaving the Loading screen.
     */
    var permissionRequested = false

    /**
     * A normal launch: reopens the remembered folder if it still has images, else shows home.
     * [restoreLast] is false after a crash, so a folder that crashes cannot loop; it stays remembered.
     */
    fun start(mayReadMedia: Boolean, restoreLast: Boolean = true) {
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
            if (last == null || !restoreLast) showHome() else loadFolder(last, restoring = true)
        }
    }

    /** Opens a folder from the folder list, with its subfolders, and remembers it for the next launch. */
    fun openFolder(folder: FolderRef) = loadFolder(folder, restoring = false)

    private fun loadFolder(folder: FolderRef, restoring: Boolean) {
        _openWith.value = null
        load(
            fetch = { folder.name to library.images(folder) },
            onFailure = { forgetFolder(folder) },
        ) { sorted ->
            if (restoring && sorted.isEmpty()) {
                forgetFolder(folder)
                showHome()
            } else {
                updateSettings { it.copy(lastFolder = folder) }
                _lastViewedIndex.value = 0
                _screen.value = Screen.Grid
            }
        }
    }

    private fun forgetFolder(folder: FolderRef) = updateSettings { if (it.lastFolder == folder) it.copy(lastFolder = null) else it }

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
                _playing.value = false
                _screen.value = Screen.Viewer(sorted.indexOfFirst { it.name == incoming }.coerceAtLeast(0))
            }
        }
    }

    private fun scan(treeUri: String, recursive: Boolean, done: (List<ImageItem>) -> Unit) =
        load({ scanner.folderName(treeUri) to scanner.scan(treeUri, recursive, it) }, done = done)

    /** Runs [fetch] (folder name and images, reporting progress), sorts the result, then hands it to [done]. */
    private fun load(
        fetch: suspend (onProgress: (Int) -> Unit) -> Pair<String, List<ImageItem>>,
        onFailure: () -> Unit = {},
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
                _duplicatesOnly.value = false
                _items.value = sorted
                _folderName.value = name
                done(sorted)
                searchDuplicates()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                onFailure()
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
                _message.value = "Couldn't read the photo library"
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
        duplicateJob?.cancel()
        _duplicates.value = emptyMap()
        _duplicateProgress.value = null
        _duplicatesOnly.value = false
    }

    fun openWith(uri: String, mayReadMedia: Boolean) {
        started = true
        mediaMode = mayReadMedia
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
                    _duplicatesOnly.value = false
                    _items.value = sorted
                    _folderName.value = result.folderName
                    _openWith.value = OpenWithState(single = false, incomingName = sorted[start].name, folderHint = null)
                    _playing.value = false
                    _screen.value = Screen.Viewer(start)
                    searchDuplicates()
                }
                is OpenWithResult.Single -> {
                    duplicateJob?.cancel()
                    _duplicates.value = emptyMap()
                    _duplicateProgress.value = null
                    _duplicatesOnly.value = false
                    unsorted = listOf(result.item)
                    _items.value = unsorted
                    _folderName.value = ""
                    _openWith.value = OpenWithState(single = true, incomingName = result.item.name, folderHint = result.folderHint)
                    _playing.value = false
                    _screen.value = Screen.Viewer(0)
                }
            }
        }
    }

    fun openViewer(index: Int, play: Boolean) {
        _playing.value = play
        _lastViewedIndex.value = index
        _screen.value = Screen.Viewer(index)
    }

    fun togglePlaying() {
        _playing.value = !_playing.value
    }

    fun viewerShowing(index: Int) {
        _lastViewedIndex.value = index
    }

    /** Returns true when the activity should finish instead (opened through "Open with"). */
    fun closeViewer(): Boolean {
        _playing.value = false
        if (_openWith.value != null) return true
        _screen.value = Screen.Grid
        return false
    }

    fun setSort(spec: SortSpec) {
        publish(spec)
        updateSettings { it.copy(sort = spec) }
    }

    fun setDuplicatesOnly(on: Boolean) {
        _duplicatesOnly.value = on
        publish()
    }

    /** Sorts [unsorted] into [items]. With [duplicatesOnly], keeps the images that have copies, each group together. */
    private fun publish(sort: SortSpec = settings.value.sort) {
        val sorted = sortItems(unsorted, sort)
        val copies = _duplicates.value
        _items.value = if (!_duplicatesOnly.value) sorted
        else sorted.filter { it.uri in copies }.groupBy { copies.getValue(it.uri).hash }.values.flatten()
    }

    /**
     * Looks for byte-identical copies of the current images: across the whole photo library for MediaStore images,
     * else within the images loaded. A picked folder's files are never compared with MediaStore entries, which may
     * be the same files.
     */
    private fun searchDuplicates() {
        duplicateJob?.cancel()
        _duplicates.value = emptyMap()
        _duplicateProgress.value = null
        val targets = unsorted
        if (targets.isEmpty()) return
        _duplicateProgress.value = 0 to 0
        duplicateJob = viewModelScope.launch {
            try {
                val pool = if (mediaMode && targets.all { isMediaStoreUri(it.uri) }) library.allImages() else targets
                val found = findDuplicates(targets, pool, hasher) { done, total -> _duplicateProgress.value = done to total }
                hasher.save()
                // Images deleted while the search ran are still in its result: drop them, as removeDeleted does.
                val present = unsorted.mapTo(HashSet()) { it.uri }
                val gone = targets.mapNotNullTo(HashSet()) { it.uri.takeIf { uri -> uri !in present } }
                _duplicates.value = found.without(gone)
                if (_duplicatesOnly.value) publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the images unmarked: the search is a hint, not something to report.
            }
            _duplicateProgress.value = null
        }
    }

    /** Asks to delete [uris]: the UI shows the system trash dialog or the app's own confirmation for [deleteRequest]. */
    fun requestDelete(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val kind = uris.map(::deleteKind).distinct().singleOrNull()
        if (kind == null || kind == DeleteKind.Unsupported) {
            _message.value = "Can't delete " + if (uris.size == 1) "this image" else "these images"
            return
        }
        if (kind == DeleteKind.Trash && uris.size > MAX_TRASH_BATCH) {
            _message.value = "Select up to $MAX_TRASH_BATCH images at a time"
            return
        }
        _deleteRequest.value = DeleteRequest(uris.toList(), kind)
    }

    /** The system trash dialog for [deleteRequest] is showing. */
    fun trashLaunched() {
        _deleteRequest.value = _deleteRequest.value?.copy(launched = true)
    }

    fun cancelDelete() {
        _deleteRequest.value = null
    }

    /** The system trash dialog for [deleteRequest] was accepted. */
    fun trashConfirmed() {
        val request = _deleteRequest.value ?: return
        _deleteRequest.value = null
        removeDeleted(request.uris)
        _message.value = "Moved ${countLabel(request.uris.size)} to the trash"
    }

    /** The app's confirmation for a [DeleteKind.Permanent] [deleteRequest] was accepted. */
    fun confirmPermanentDelete() {
        val request = _deleteRequest.value ?: return
        _deleteRequest.value = null
        viewModelScope.launch {
            val deleted = deleter.delete(request.uris)
            removeDeleted(deleted)
            _message.value = if (deleted.size == request.uris.size) "Deleted ${countLabel(deleted.size)}"
            else "Deleted ${deleted.size} of ${countLabel(request.uris.size)}"
        }
    }

    /**
     * Drops deleted images from the list and from the duplicate groups (a copy left alone is no longer a duplicate),
     * without hashing again. The viewer stays at the same position, which now shows the next image.
     */
    private fun removeDeleted(uris: List<String>) {
        if (uris.isEmpty()) return
        val gone = uris.toSet()
        unsorted = unsorted.filter { it.uri !in gone }
        _duplicates.value = _duplicates.value.without(gone)
        val before = _items.value
        val position = _lastViewedIndex.value
        publish()
        // The first image at or after the old position that is still listed. More than the deleted images can leave
        // the list: with duplicatesOnly, a copy left alone drops out too.
        val newIndex = _items.value.withIndex().associate { (i, item) -> item.uri to i }
        _lastViewedIndex.value = before.drop(position).firstNotNullOfOrNull { newIndex[it.uri] }
            ?: (_items.value.size - 1).coerceAtLeast(0)
        if (_items.value.isEmpty() && _screen.value is Screen.Viewer) {
            _playing.value = false
            if (_openWith.value != null) {
                // Open with never shows the grid: go home instead.
                _openWith.value = null
                showHome()
            } else {
                _screen.value = Screen.Grid
            }
        }
    }

    /** These copies without [gone]: deleted images leave their groups, and a copy left alone is no longer a duplicate. */
    private fun Map<String, Copies>.without(gone: Set<String>): Map<String, Copies> =
        if (gone.isEmpty()) this
        else filterKeys { it !in gone }
            .mapValues { (_, copies) -> copies.copy(others = copies.others.filter { it.uri !in gone }) }
            .filterValues { it.others.isNotEmpty() }

    private fun countLabel(n: Int) = if (n == 1) "1 image" else "$n images"

    fun reshuffle() = setSort(settings.value.sort.copy(order = SortOrder.Random, randomSeed = Random.nextLong()))

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
