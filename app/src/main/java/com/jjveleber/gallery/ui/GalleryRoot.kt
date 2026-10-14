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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.jjveleber.gallery.imaging.AnimatedRegistry
import com.jjveleber.gallery.scan.EXTERNAL_STORAGE_AUTHORITY
import com.jjveleber.gallery.scan.externalStorageDocId
import com.jjveleber.gallery.ui.grid.GridScreen
import com.jjveleber.gallery.ui.picker.PickerScreen
import com.jjveleber.gallery.ui.viewer.ViewerScreen

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

    // True from launch until the Open with request has been handed to the ViewModel, so the picker never shows
    // behind the permission dialog.
    var openWithPending by rememberSaveable(openWithUri) { mutableStateOf(openWithUri != null) }
    if (openWithUri != null) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.openWith(openWithUri, mayReadMedia = granted)
            openWithPending = false
        }
        LaunchedEffect(openWithUri) {
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                vm.openWith(openWithUri, mayReadMedia = true)
                openWithPending = false
            } else {
                requestPermission.launch(permission)
            }
        }
    }

    if (openWithPending) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    when (val s = screen) {
        Screen.Picker, is Screen.Scanning -> {
            if (s is Screen.Scanning) BackHandler(onBack = vm::cancelScan)
            PickerScreen(s, onPick = { pickTree.launch(null) }, onCancel = vm::cancelScan)
        }
        Screen.Loading -> Box(Modifier.fillMaxSize().background(Color.Black))
        Screen.Grid -> {
            BackHandler(onBack = vm::changeFolder)
            GridScreen(vm, registry)
        }
        is Screen.Viewer -> ViewerScreen(
            vm,
            registry,
            s.startIndex,
            onBrowseFolder = { hint ->
                browseTree.launch(hint?.let { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, it.externalStorageDocId()) })
            },
            onExit = onFinish,
        )
    }
}
