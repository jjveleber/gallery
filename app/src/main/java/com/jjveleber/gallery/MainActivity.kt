package com.jjveleber.gallery

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jjveleber.gallery.ui.CrashReportDialog
import com.jjveleber.gallery.ui.GalleryRoot
import com.jjveleber.gallery.ui.GalleryViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as GalleryApp).container
        // Only on first creation: after a recreation the ViewModel already holds the result.
        val openWithUri = intent.takeIf { savedInstanceState == null && it.action == Intent.ACTION_VIEW }?.data?.toString()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val vm: GalleryViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { GalleryViewModel(container.scanner, container.library, container.openWith, container.settings, container.hasher, container.deleter) }
                        },
                    )
                    // The 64 KB cap keeps this read cheap enough for the main thread. Saved, so closing it
                    // survives rotation.
                    var crashReport by rememberSaveable { mutableStateOf(container.crashLog.read()) }
                    // After a crash, start at the folder list: the remembered folder may be what crashed.
                    val restoreLast = remember { crashReport == null }
                    GalleryRoot(vm, container.animated, openWithUri, restoreLast, onFinish = ::finish)
                    crashReport?.let { report ->
                        CrashReportDialog(
                            report,
                            onClose = { crashReport = null },
                            onDismiss = {
                                container.crashLog.clear()
                                crashReport = null
                            },
                        )
                    }
                }
            }
        }
    }
}
