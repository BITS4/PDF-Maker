package com.example.pdfmaker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
internal fun FilesScreenCacheEffect(activity: MainActivity) {
    LaunchedEffect(FileCache.version) {
        FileCache.load(activity)
    }
}
