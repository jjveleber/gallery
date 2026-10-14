package com.jjveleber.gallery.ui.viewer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.DrawableImage
import coil3.Image as CoilImage
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.jjveleber.gallery.imaging.imageRequest
import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.model.Transition
import com.jjveleber.gallery.scan.FolderLocation
import com.jjveleber.gallery.slideshow.AutoAdvanceTimer
import com.jjveleber.gallery.slideshow.LoopPaging
import com.jjveleber.gallery.slideshow.PauseReason
import com.jjveleber.gallery.slideshow.TapZone
import com.jjveleber.gallery.slideshow.intervalLabel
import com.jjveleber.gallery.slideshow.nextInterval
import com.jjveleber.gallery.slideshow.tapZone
import com.jjveleber.gallery.ui.GalleryViewModel
import com.jjveleber.gallery.ui.settings.SettingsSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableImage
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState

@Composable
fun ViewerScreen(
    vm: GalleryViewModel,
    startIndex: Int,
    onBrowseFolder: (FolderLocation?) -> Unit,
    onExit: () -> Unit,
) {
    val items by vm.items.collectAsState()
    val settings by vm.settings.collectAsState()
    val openWith by vm.openWith.collectAsState()
    val close = { if (vm.closeViewer()) onExit() }
    BackHandler(onBack = close)
    val n = items.size
    if (n == 0) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = LoopPaging.startPage(startIndex.coerceIn(0, n - 1), n)) { LoopPaging.pageCount(n) }
    val current = LoopPaging.indexOf(pager.currentPage, n)
    var overlay by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var fadingFrom by remember { mutableStateOf<ImageBitmap?>(null) }
    // The decoded image of each page near the current one, so a crossfade can show the outgoing image as it
    // was instead of requesting it again (animated results are never memory cached).
    val loaded = remember { HashMap<String, CoilImage>() }
    val fade = remember { Animatable(0f) }

    val advance by rememberUpdatedState<suspend () -> Unit>(newValue = {
        if (n > 1) {
            if (settings.transition == Transition.Crossfade) {
                // Draw a snapshot of the outgoing image on top and fade it out over the incoming page.
                val snapshot = loaded[items[LoopPaging.indexOf(pager.currentPage, n)].uri]?.snapshot()
                fadingFrom = snapshot
                fade.snapTo(1f)
                pager.scrollToPage(pager.currentPage + 1)
                if (snapshot != null) fade.animateTo(0f, tween(400))
                fadingFrom = null
            } else {
                pager.scrollToPage(pager.currentPage + 1)
            }
        }
    })
    // Advance in its own coroutine: the page change resets the timer, which must not cancel the fade.
    val timer = remember { AutoAdvanceTimer(scope) { scope.launch { advance() } } }

    LaunchedEffect(settings.autoAdvance, settings.intervalSec) {
        timer.setInterval(settings.intervalSec * 1000L)
        timer.setEnabled(settings.autoAdvance)
    }
    LaunchedEffect(current) {
        val near = listOf(current - 1, current, current + 1).map { items[Math.floorMod(it, n)].uri }.toSet()
        loaded.keys.retainAll(near)
        vm.viewerShowing(current)
        zoomed = false
        timer.reset()
    }
    LaunchedEffect(zoomed) { if (zoomed) timer.pause(PauseReason.Zoomed) else timer.resume(PauseReason.Zoomed) }
    LaunchedEffect(showSettings) { if (showSettings) timer.pause(PauseReason.Sheet) else timer.resume(PauseReason.Sheet) }
    LaunchedEffect(overlay, current) {
        if (overlay) {
            delay(3_000)
            overlay = false
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> timer.pause(PauseReason.Background)
                Lifecycle.Event.ON_START -> timer.resume(PauseReason.Background)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            timer.setEnabled(false)
        }
    }
    ImmersiveWindow(keepScreenOn = settings.autoAdvance)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed && n > 1,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ViewerPage(
                item = items[LoopPaging.indexOf(page, n)],
                // Only the settled page animates: its neighbours are composed but off screen, so they show the still.
                animate = settings.animateFullScreen && page == pager.settledPage,
                onLoaded = { loaded[it.first] = it.second },
                onZoomChange = { if (page == pager.currentPage) zoomed = it },
                onTap = { x, width ->
                    // While zoomed, every tap toggles the overlay; navigation waits until zoomed out.
                    val zone = if (zoomed) TapZone.Middle else tapZone(x, width)
                    if (zone == TapZone.Middle) {
                        overlay = !overlay
                    } else if (n > 1) {
                        val step = if (zone == TapZone.Next) 1 else -1
                        scope.launch { pager.animateScrollToPage(pager.currentPage + step) }
                    }
                },
            )
        }
        fadingFrom?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
            )
        }
        AnimatedVisibility(
            visible = overlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerOverlay(
                name = items[current].name,
                position = "${current + 1} / $n",
                playing = settings.autoAdvance,
                intervalSec = settings.intervalSec,
                showBrowse = openWith?.single == true,
                onBack = close,
                onPlayPause = { vm.updateSettings { it.copy(autoAdvance = !it.autoAdvance) } },
                onInterval = { vm.updateSettings { it.copy(intervalSec = nextInterval(it.intervalSec)) } },
                onSettings = { showSettings = true },
                onBrowse = { onBrowseFolder(openWith?.folderHint) },
            )
        }
    }
    if (showSettings) SettingsSheet(settings, vm::updateSettings) { showSettings = false }
}

@Composable
private fun ViewerPage(
    item: ImageItem,
    animate: Boolean,
    onLoaded: (Pair<String, CoilImage>) -> Unit,
    onZoomChange: (Boolean) -> Unit,
    onTap: (x: Float, width: Float) -> Unit,
) {
    val context = LocalContext.current
    var failed by remember(item.uri) { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(1f) }
    val tap by rememberUpdatedState(onTap)
    val onLoadedNow by rememberUpdatedState(onLoaded)
    val state = rememberZoomableImageState()
    // userZoom is 1 at the fitted size. zoomFraction is no use here: it reads 1 when the zoom range is empty,
    // as for images smaller than the screen, which would lock the pager.
    val zoomed = state.zoomableState.contentTransformation.scaleMetadata.userZoom > 1.01f
    LaunchedEffect(zoomed) { onZoomChange(zoomed) }
    val request = remember(item.uri, animate) {
        imageRequest(context, item.uri, animate, object : ImageRequest.Listener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                onLoadedNow(item.uri to result.image)
            }

            override fun onError(request: ImageRequest, result: ErrorResult) {
                failed = true
            }
        })
    }
    Box(Modifier.fillMaxSize().onSizeChanged { width = it.width.toFloat() }) {
        if (failed) {
            Text("Can't display ${item.name}", color = Color.White, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.fillMaxSize().pointerInput(item.uri) { detectTapGestures { tap(it.x, width) } })
        } else {
            if (subSamplingUnsupported(item)) {
                ZoomableImage(
                    image = rememberDecodedImageSource(request),
                    contentDescription = item.name,
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { tap(it.x, width) },
                )
            } else {
                ZoomableAsyncImage(
                    model = request,
                    contentDescription = item.name,
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { tap(it.x, width) },
                )
            }
        }
    }
}

/**
 * A bitmap of the image as it looks right now (the current frame of an animation), at most 2048 px on a side.
 * A drawable that is on screen is drawn at its current bounds: resizing an animated drawable restarts its decoder
 * and blanks the frame.
 */
private fun CoilImage.snapshot(): ImageBitmap? = runCatching {
    val drawable = (this as? DrawableImage)?.drawable
    val bounds = drawable?.bounds
    if (drawable != null && bounds != null && !bounds.isEmpty) {
        val scale = minOf(1f, 2048f / maxOf(bounds.width(), bounds.height()))
        val bitmap = Bitmap.createBitmap(
            (bounds.width() * scale).toInt().coerceAtLeast(1),
            (bounds.height() * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    } else if (width > 0 && height > 0) {
        val scale = minOf(1f, 2048f / maxOf(width, height))
        toBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1)).asImageBitmap()
    } else {
        null
    }
}.getOrNull()

/**
 * Telephoto tiles images with BitmapRegionDecoder, which cannot read BMP: the tiles stay black. Its only
 * opt-outs are SVG and GIF, so BMP is shown from the fully decoded bitmap instead (still zoomable).
 */
private fun subSamplingUnsupported(item: ImageItem): Boolean =
    item.mime.endsWith("bmp") || item.name.substringAfterLast('.', "").equals("bmp", ignoreCase = true)

@Composable
private fun rememberDecodedImageSource(request: ImageRequest): ZoomableImageSource {
    val context = LocalContext.current
    val painter by produceState<Painter?>(null, request) {
        val result = SingletonImageLoader.get(context).execute(request)
        value = (result as? SuccessResult)?.image?.asPainter(context)
    }
    return remember(painter) {
        object : ZoomableImageSource {
            @Composable
            override fun resolve(canvasSize: Flow<Size>) = ZoomableImageSource.ResolveResult(
                delegate = painter?.let { ZoomableImageSource.PainterDelegate(it) },
            )
        }
    }
}

@Composable
private fun ViewerOverlay(
    name: String,
    position: String,
    playing: Boolean,
    intervalSec: Int,
    showBrowse: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onInterval: () -> Unit,
    onSettings: () -> Unit,
    onBrowse: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(position, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
        if (showBrowse) AssistChip(onClick = onBrowse, label = { Text("Browse this folder") })
        TextButton(onClick = onPlayPause) { Text(if (playing) "Pause" else "Play") }
        TextButton(onClick = onInterval) { Text(intervalLabel(intervalSec)) }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White) }
    }
}

/** Hides the system bars while the viewer is shown, and keeps the screen on while auto advance runs. */
@Composable
private fun ImmersiveWindow(keepScreenOn: Boolean) {
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
