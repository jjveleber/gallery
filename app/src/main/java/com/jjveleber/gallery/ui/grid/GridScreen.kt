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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
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
                        Text(if (items.size == 1) "1 image" else "%,d images".format(items.size), style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.openViewer(0, play = true) }, enabled = items.isNotEmpty()) { Icon(Icons.Default.PlayArrow, contentDescription = "Play slideshow") }
                    SortMenu(settings.sort, onChange = vm::setSort, onReshuffle = vm::reshuffle)
                    SizeMenu(settings.gridSize) { size -> vm.updateSettings { it.copy(gridSize = size) } }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    TextButton(onClick = vm::changeFolder) { Text("Folder") }
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
    var failed by remember(item.uri, animate) { mutableStateOf(false) }
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
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Sort: ${spec.order.label}") }
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
                    text = { Text(if (spec.ascending) "Direction: Ascending (tap for Descending)" else "Direction: Descending (tap for Ascending)") },
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
        IconButton(onClick = { open = true }) { Icon(Icons.Default.Add, contentDescription = "Size: ${size.label}") }
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
