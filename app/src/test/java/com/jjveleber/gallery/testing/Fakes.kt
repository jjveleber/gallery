package com.jjveleber.gallery.testing

import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.scan.FolderScanner
import com.jjveleber.gallery.scan.MediaFolder
import com.jjveleber.gallery.scan.MediaLibrary
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

/** Each folder's images wait until the test completes [result] for it. */
class FakeLibrary : MediaLibrary {
    var folderList: List<MediaFolder> = emptyList()
    var folderCalls = 0
    var failWith: Exception? = null
    var foldersFailWith: Exception? = null
    private val results = mutableMapOf<FolderRef, CompletableDeferred<List<ImageItem>>>()

    fun result(folder: FolderRef) = results.getOrPut(folder) { CompletableDeferred() }

    override suspend fun folders(): List<MediaFolder> {
        folderCalls++
        foldersFailWith?.let { throw it }
        return folderList
    }

    override suspend fun images(folder: FolderRef): List<ImageItem> {
        failWith?.let { throw it }
        return result(folder).await()
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
