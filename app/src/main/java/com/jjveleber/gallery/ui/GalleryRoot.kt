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
