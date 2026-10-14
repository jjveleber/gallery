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
