package com.example.pdfmaker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal data class ViewerPresentationState(
    val file: PdfFile,
    val kind: ViewerFileKind,
    val pages: List<ViewerPageArtifact>,
    val isLoading: Boolean,
    val errorMessage: String?,
    val showBars: Boolean,
    val scale: Float,
    val offset: Offset,
    val loadedCount: Int,
    val currentPage: Int,
)

internal data class ViewerPresentationActions(
    val onBack: () -> Unit,
    val onShare: () -> Unit,
    val onTransform: (pan: Offset, zoom: Float) -> Unit,
    val onToggleBars: () -> Unit,
    val onResetZoom: () -> Unit,
)

@Composable
internal fun ViewerPresentation(
    state: ViewerPresentationState,
    listState: LazyListState,
    actions: ViewerPresentationActions,
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
        ViewerDocumentBody(state, listState, actions)
        ViewerTopBar(
            visible = state.showBars || state.isLoading || state.errorMessage != null,
            fileName = state.file.name,
            kind = state.kind,
            loadedCount = state.loadedCount,
            isLoading = state.isLoading,
            onBack = actions.onBack,
            onShare = actions.onShare,
        )
        ViewerBottomBar(
            visible = state.showBars && state.pages.isNotEmpty(),
            pages = state.pages,
            currentPage = state.currentPage,
            totalPages = state.loadedCount,
            isLoading = state.isLoading,
            listState = listState,
        )
        ViewerZoomReset(
            visible = state.scale > 1.1f,
            onClick = actions.onResetZoom,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 80.dp),
        )
    }
}

@Composable
private fun ViewerDocumentBody(
    state: ViewerPresentationState,
    listState: LazyListState,
    actions: ViewerPresentationActions,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            shouldOpenExternally(state.kind) -> {
                ViewerExternalOpenView(
                    file = state.file,
                    kind = state.kind,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            state.kind == ViewerFileKind.UNSUPPORTED -> {
                ViewerUnsupportedView(
                    file = state.file,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            state.errorMessage != null -> {
                ViewerErrorView(
                    message = state.errorMessage,
                    onBack = actions.onBack,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            else -> {
                ViewerPageList(
                    pages = state.pages,
                    isLoading = state.isLoading,
                    listState = listState,
                    scale = state.scale,
                    offset = state.offset,
                    onTransform = actions.onTransform,
                    onToggleBars = actions.onToggleBars,
                )
            }
        }
    }
}

@Composable
private fun ViewerZoomReset(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = BgToolIcon,
            contentColor = Color.White,
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
        ) {
            Icon(
                imageVector = Icons.Default.ZoomOut,
                contentDescription = "Reset zoom",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
