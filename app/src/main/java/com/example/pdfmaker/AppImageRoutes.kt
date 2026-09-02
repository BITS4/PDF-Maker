package com.example.pdfmaker

import androidx.compose.runtime.Composable

@Composable
internal fun AppImageRoute(
    navigation: AppNavigationState,
    screen: Screen,
) {
    when (screen) {
        Screen.SMART_SCAN -> smartScanRoute(navigation)
        Screen.IMAGE_SELECTION -> imageSelectionRoute(navigation)
        Screen.IMAGE_EDIT -> imageEditRoute(navigation)
        Screen.IMAGE_CROP -> imageCropRoute(navigation)
        Screen.IMAGE_REVIEW -> imageReviewRoute(navigation)
        Screen.CONVERT_RESULT -> conversionResultRoute(navigation)
        Screen.ID_CARD_RESULT -> navigation.navigate(Screen.HOME)
        else -> error("Screen $screen is not an image route")
    }
}

@Composable
private fun smartScanRoute(navigation: AppNavigationState) {
    SmartScanScreen(
        onBack = navigation::navigateBackToOrigin,
        onDocsDone = { uris ->
            prepareScanImages(uris, isIdCard = false)
            navigation.addingMoreImages = false
            navigation.fromSmartScan = true
            navigation.navigate(Screen.IMAGE_CROP)
        },
        onIdCardDone = { frontUri, backUri ->
            prepareScanImages(listOfNotNull(frontUri, backUri), isIdCard = true)
            navigation.addingMoreImages = false
            navigation.fromSmartScan = true
            navigation.navigate(Screen.IMAGE_CROP)
        },
    )
}

private fun prepareScanImages(
    uris: List<android.net.Uri>,
    isIdCard: Boolean,
) {
    ImageToPdfState.clear()
    ImageToPdfState.isIdCardScan = isIdCard
    ImageToPdfState.addUris(uris)
    ImageToPdfState.currentEditIndex = 0
}

@Composable
private fun imageSelectionRoute(navigation: AppNavigationState) {
    ImageSelectionScreen(
        preSelected =
            if (navigation.addingMoreImages) {
                ImageToPdfState.editStates.map { it.uri }
            } else {
                emptyList()
            },
        onImport = { uris ->
            val previousCount = ImageToPdfState.editStates.size
            ImageToPdfState.addUris(uris)
            ImageToPdfState.currentEditIndex =
                if (navigation.addingMoreImages) {
                    previousCount.coerceAtMost(
                        (ImageToPdfState.editStates.size - 1).coerceAtLeast(0),
                    )
                } else {
                    0
                }
            navigation.navigate(Screen.IMAGE_EDIT)
        },
        onBack = {
            if (navigation.addingMoreImages) {
                navigation.navigate(Screen.IMAGE_REVIEW)
            } else {
                ImageToPdfState.clear()
                navigation.fromSmartScan = false
                navigation.navigateBackToOrigin()
            }
        },
    )
}

@Composable
private fun imageEditRoute(navigation: AppNavigationState) {
    val states = ImageToPdfState.editStates.toList()
    if (states.isEmpty()) {
        navigation.navigate(Screen.HOME)
        return
    }
    ImageEditScreen(
        editStates = states,
        initialIndex = ImageToPdfState.currentEditIndex,
        onCrop = { index ->
            ImageToPdfState.currentEditIndex = index
            navigation.navigate(Screen.IMAGE_CROP)
        },
        onDone = { navigation.navigate(Screen.IMAGE_REVIEW) },
        onBack = {
            val destination =
                when {
                    navigation.addingMoreImages -> Screen.IMAGE_REVIEW
                    navigation.fromSmartScan -> Screen.SMART_SCAN
                    else -> Screen.IMAGE_SELECTION
                }
            navigation.navigate(destination)
        },
        onDelete = { index ->
            ImageToPdfState.removeAt(index)
            if (ImageToPdfState.editStates.isEmpty()) {
                navigation.navigate(Screen.IMAGE_SELECTION)
            }
        },
    )
}

@Composable
private fun imageCropRoute(navigation: AppNavigationState) {
    val states = ImageToPdfState.editStates.toList()
    val index = ImageToPdfState.currentEditIndex
    val editState = states.getOrNull(index)
    if (editState == null) {
        navigation.navigate(
            if (navigation.fromSmartScan) Screen.SMART_SCAN else Screen.IMAGE_EDIT,
        )
        return
    }
    ImageCropScreen(
        editState = editState,
        pageIndex = index,
        totalPages = states.size,
        isIdCard = ImageToPdfState.isIdCardScan,
        onNext = {
            val nextIndex = index + 1
            if (nextIndex < states.size) {
                ImageToPdfState.currentEditIndex = nextIndex
                navigation.navigate(Screen.IMAGE_CROP)
            } else {
                ImageToPdfState.currentEditIndex = 0
                navigation.navigate(Screen.IMAGE_EDIT)
            }
        },
        onBack = {
            if (index > 0) {
                ImageToPdfState.currentEditIndex = index - 1
                navigation.navigate(Screen.IMAGE_CROP)
            } else {
                navigation.navigate(
                    if (navigation.fromSmartScan) Screen.SMART_SCAN else Screen.IMAGE_EDIT,
                )
            }
        },
        onRetake =
            if (navigation.fromSmartScan) {
                {
                    navigation.fromSmartScan = false
                    navigation.navigate(Screen.SMART_SCAN)
                }
            } else {
                null
            },
    )
}

@Composable
private fun imageReviewRoute(navigation: AppNavigationState) {
    val states = ImageToPdfState.editStates.toList()
    if (states.isEmpty()) {
        navigation.navigate(Screen.HOME)
        return
    }
    ImageReviewScreen(
        editStates = states,
        onAddMore = {
            navigation.addingMoreImages = true
            navigation.navigate(Screen.IMAGE_SELECTION)
        },
        onBack = { navigation.navigate(Screen.IMAGE_EDIT) },
        onConvertDone = { path, name ->
            navigation.resultFilePath = path
            navigation.resultFileName = name
            navigation.navigate(Screen.CONVERT_RESULT)
        },
    )
}

@Composable
private fun conversionResultRoute(navigation: AppNavigationState) {
    ConvertResultScreen(
        filePath = navigation.resultFilePath,
        fileName = navigation.resultFileName,
        onDone = {
            ImageToPdfState.clear()
            navigation.navigate(Screen.HOME)
        },
        onOpenFile = { file ->
            ImageToPdfState.clear()
            navigation.openFile(file)
        },
    )
}
