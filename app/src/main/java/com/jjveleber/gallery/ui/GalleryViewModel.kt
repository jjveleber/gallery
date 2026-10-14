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
                _items.value = sorted
                _folderName.value = name
                done(sorted)
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
