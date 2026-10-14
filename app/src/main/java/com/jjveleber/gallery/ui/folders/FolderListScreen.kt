package com.jjveleber.gallery.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.size.Precision
import com.jjveleber.gallery.imaging.MediaThumbnail
import com.jjveleber.gallery.imaging.imageRequest
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.scan.MediaFolder

private val COVER = 72.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(folders: List<MediaFolder>, onOpen: (FolderRef) -> Unit, onBrowse: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gallery") },
                actions = { TextButton(onClick = onBrowse) { Text("Browse other folder") } },
            )
        },
    ) { padding ->
        if (folders.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No folders with images found")
                Button(onClick = onBrowse) { Text("Browse other folder") }
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(folders, key = { "${it.ref.volume}:${it.ref.relativePath}" }) { folder ->
                    FolderRow(folder) { onOpen(folder.ref) }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: MediaFolder, onClick: () -> Unit) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { COVER.roundToPx() }
    val cover = remember(folder.coverUri, sizePx) {
        imageRequest(context, folder.coverUri, animate = false, data = MediaThumbnail(folder.coverUri, folder.coverMime, sizePx))
            .newBuilder().size(sizePx).precision(Precision.INEXACT).build()
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(COVER).background(Color(0xFF1C1C1C)),
        )
        Column(Modifier.weight(1f)) {
            Text(folder.ref.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(folder.ref.displayPath, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Text("%,d".format(folder.count), style = MaterialTheme.typography.bodyMedium)
    }
}
