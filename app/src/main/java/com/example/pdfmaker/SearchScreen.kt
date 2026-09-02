package com.example.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SearchScreen(
    allFiles: List<PdfFile>,
    onFileClick: (PdfFile) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val results =
        remember(query, allFiles) {
            if (query.isBlank()) {
                emptyList()
            } else {
                allFiles.filter { it.name.contains(query, ignoreCase = true) }
            }
        }

    Column(Modifier.fillMaxSize().background(currentBg)) {
        // Search bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(currentCard)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = currentText)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(currentBg)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text("Search files…", color = currentTextSecond, fontSize = 14.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = currentText, fontSize = 14.sp),
                    cursorBrush = SolidColor(AccentBlue),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner -> inner() },
                )
            }
            Icon(
                Icons.Default.Search,
                null,
                tint = currentTextSecond,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
            )
        }

        // Results
        when {
            query.isBlank() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Type to search files…", color = currentTextSecond, fontSize = 15.sp)
                }
            }

            results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(kind = EmptyKind.SEARCH, query = query)
                }
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.filePath }) { file ->
                        FileItemWithThumb(
                            file = file,
                            onItemClick = { onFileClick(file) },
                            onShareClick = {},
                            onMoreClick = {},
                        )
                    }
                }
            }
        }
    }
}
