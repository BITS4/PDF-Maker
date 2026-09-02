package com.example.pdfmaker

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun ViewerPageList(
    pages: List<ViewerPageArtifact>,
    isLoading: Boolean,
    listState: LazyListState,
    scale: Float,
    offset: Offset,
    onTransform: (pan: Offset, zoom: Float) -> Unit,
    onToggleBars: () -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(top = 64.dp, bottom = 64.dp)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ -> onTransform(pan, zoom) }
                },
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(
            items = pages,
            key = { _, artifact -> artifact.file.absolutePath },
        ) { index, artifact ->
            ViewerPageArtifactImage(
                artifact = artifact,
                page = index + 1,
                scale = scale,
                offset = offset,
                onToggleBars = onToggleBars,
            )
        }
        if (isLoading) item { PageLoadingPlaceholder() }
    }
}

@Composable
private fun ViewerPageArtifactImage(
    artifact: ViewerPageArtifact,
    page: Int,
    scale: Float,
    offset: Offset,
    onToggleBars: () -> Unit,
) {
    val bitmap = rememberViewerArtifactBitmap(artifact, maxDimension = 2_048)
    val modifier =
        Modifier
            .fillMaxWidth()
            .aspectRatio(artifact.aspectRatio)
            .background(Color.White)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = if (scale > 1f) offset.x else 0f,
                translationY = if (scale > 1f) offset.y else 0f,
            ).clickable(onClick = onToggleBars)

    if (bitmap == null) {
        Box(modifier)
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Page $page",
            contentScale = ContentScale.FillBounds,
            modifier = modifier,
        )
    }
}

@Composable
internal fun rememberViewerArtifactBitmap(
    artifact: ViewerPageArtifact,
    maxDimension: Int,
): Bitmap? {
    val path = artifact.file.absolutePath
    var bitmap by remember(path, maxDimension) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(path, maxDimension) {
        var pending: Bitmap? = null
        try {
            pending = try {
                withContext(Dispatchers.IO) {
                    if (
                        !ViewerPageArtifactPolicy.acceptsArtifact(
                            artifact.width,
                            artifact.height,
                            artifact.file.length(),
                        )
                    ) {
                        null
                    } else {
                        artifact.file.inputStream().use { input ->
                            ThumbnailInput.decodeImage(input, maxDimension.coerceIn(1, 2_048))
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignoredError: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            bitmap = pending
            pending = null
        } finally {
            pending?.let { decoded -> if (!decoded.isRecycled) decoded.recycle() }
        }
    }

    DisposableEffect(path, maxDimension) {
        onDispose { bitmap?.let { decoded -> if (!decoded.isRecycled) decoded.recycle() } }
    }
    return bitmap
}

@Composable
private fun PageLoadingPlaceholder() {
    val transition = rememberInfiniteTransition(label = "viewerShimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "viewerShimmerAlpha",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.77f)
            .padding(horizontal = 2.dp)
            .background(Color.White.copy(alpha = alpha), RoundedCornerShape(4.dp)),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(6) { index ->
                Box(
                    Modifier
                        .fillMaxWidth(if (index % 3 == 2) 0.6f else 1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = alpha * 1.5f)),
                )
            }
        }
    }
}
