package com.example.pdfmaker

import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

@Composable
fun PdfViewerScreen(
    file: PdfFile,
    onBack: () -> Unit,
    onShare: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenWidthPx = LocalWindowInfo.current.containerSize.width
    val screenWidth = (screenWidthPx / density.density).roundToInt()
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
                    density = density.density,
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

    ViewerPresentation(
        state =
            ViewerPresentationState(
                file = file,
                kind = kind,
                pages = pages,
                isLoading = isLoading,
                errorMessage = errorMessage,
                showBars = showBars,
                scale = scale,
                offset = offset,
                loadedCount = loadedCount,
                currentPage = currentPage,
            ),
        listState = listState,
        actions =
            ViewerPresentationActions(
                onBack = onBack,
                onShare = onShare,
                onTransform = { pan, zoom ->
                    val nextScale = (scale * zoom).coerceIn(0.5f, 4f)
                    scale = nextScale
                    offset = if (nextScale > 1f) offset + pan else Offset.Zero
                },
                onToggleBars = { showBars = !showBars },
                onResetZoom = {
                    scale = 1f
                    offset = Offset.Zero
                },
            ),
    )
}
