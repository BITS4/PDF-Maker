package com.example.pdfmaker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
fun PdfViewerScreen(
    file: PdfFile,
    onBack: () -> Unit,
    onShare: () -> Unit = {},
) {
    val context = LocalContext.current
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val kind = remember(file.filePath, file.name) { detectViewerFileKind(file.filePath, file.name) }
    val listState = rememberLazyListState()

    var pages by remember { mutableStateOf<List<ViewerPageArtifact>>(emptyList()) }
    var pageStore by remember(file.filePath) { mutableStateOf<ViewerPageArtifactStore?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadedCount by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showBars by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isLocked by remember(file.filePath) { mutableStateOf(isLockedPdf(file.filePath)) }
    var decryptedPath by remember(file.filePath) { mutableStateOf<String?>(null) }

    val viewFile =
        remember(decryptedPath, file) {
            decryptedPath?.let { file.copy(filePath = it) } ?: file
        }
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    val activePageStore = pageStore
    DisposableEffect(activePageStore) {
        onDispose { activePageStore?.close() }
    }
    DisposableEffect(decryptedPath) {
        val temporaryPath = decryptedPath
        onDispose { temporaryPath?.let { deleteViewerTemporaryFile(context, it) } }
    }

    if (isLocked) {
        ViewerUnlockGate(
            file = file,
            onBack = onBack,
            onUnlocked = { temporaryFile ->
                decryptedPath = temporaryFile.absolutePath
                isLocked = false
            },
        )
        return
    }

    LaunchedEffect(file.filePath, kind) {
        if (shouldOpenExternally(kind)) openWithExternalApp(context, file)
    }

    LaunchedEffect(viewFile.filePath, kind, screenWidth) {
        pageStore?.close()
        pageStore = null
        pages = emptyList()
        listState.scrollToItem(0)
        scale = 1f
        offset = Offset.Zero
        loadedCount = 0
        errorMessage = null
        isLoading = canRenderInApp(kind)

        if (!canRenderInApp(kind)) return@LaunchedEffect

        val nextStore = ViewerPageArtifactStore.create(context)
        pageStore = nextStore
        try {
            val targetWidth =
                viewerTargetWidth(
                    screenWidthDp = screenWidth,
                    density = context.resources.displayMetrics.density,
                )
            cacheViewerPageArtifacts(viewFile, kind, targetWidth, nextStore) { artifact ->
                pages = pages + artifact
                loadedCount = pages.size
            }
            if (loadedCount == 0) {
                errorMessage = "This document could not be rendered."
                pages = emptyList()
                nextStore.close()
                if (pageStore === nextStore) pageStore = null
            }
        } catch (cancellation: CancellationException) {
            pages = emptyList()
            nextStore.close()
            if (pageStore === nextStore) pageStore = null
            throw cancellation
        } catch (ignoredError: Exception) {
            pages = emptyList()
            nextStore.close()
            if (pageStore === nextStore) pageStore = null
            errorMessage = viewerErrorMessage(ignoredError)
        } finally {
            isLoading = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
        when {
            shouldOpenExternally(kind) ->
                ViewerExternalOpenView(
                    file = file,
                    kind = kind,
                    modifier = Modifier.align(Alignment.Center),
                )
            kind == ViewerFileKind.UNSUPPORTED ->
                ViewerUnsupportedView(
                    file = file,
                    modifier = Modifier.align(Alignment.Center),
                )
            errorMessage != null ->
                ViewerErrorView(
                    message = errorMessage.orEmpty(),
                    onBack = onBack,
                    modifier = Modifier.align(Alignment.Center),
                )
            else ->
                ViewerPageList(
                    pages = pages,
                    isLoading = isLoading,
                    listState = listState,
                    scale = scale,
                    offset = offset,
                    onTransform = { pan, zoom ->
                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    },
                    onToggleBars = { showBars = !showBars },
                )
        }

        ViewerTopBar(
            visible = showBars || isLoading || errorMessage != null,
            fileName = file.name,
            kind = kind,
            loadedCount = loadedCount,
            isLoading = isLoading,
            onBack = onBack,
            onShare = onShare,
        )
        ViewerBottomBar(
            visible = showBars && pages.isNotEmpty(),
            pages = pages,
            currentPage = currentPage,
            totalPages = loadedCount,
            isLoading = isLoading,
            listState = listState,
        )

        AnimatedVisibility(
            visible = scale > 1.1f,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 80.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                },
                containerColor = BgToolIcon,
                contentColor = Color.White,
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
            ) {
                Icon(Icons.Default.ZoomOut, null, modifier = Modifier.size(20.dp))
            }
        }
    }
}
