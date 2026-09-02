package com.example.pdfmaker

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun OcrTopBar(
    showActions: Boolean,
    onBack: () -> Unit,
    onCopyAll: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(currentCard)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = currentText)
        }
        Text(
            "OCR – Extract Text",
            color = currentText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (showActions) {
            IconButton(onClick = onCopyAll) {
                Icon(Icons.Default.ContentCopy, null, tint = AccentBlue)
            }
            IconButton(onClick = onSave) {
                Icon(Icons.Default.Save, null, tint = AccentBlue)
            }
        }
    }
}

internal fun CoroutineScope.saveOcrText(
    context: Context,
    pickedName: String,
    text: String,
) {
    launch(Dispatchers.IO) {
        val result =
            runCatching {
                OutputStore.writeUnique(
                    getPdfMakerDir(context),
                    "${SafeFileName.baseName(pickedName)}_ocr",
                    "txt",
                ) { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
        withContext(Dispatchers.Main) {
            result.fold(
                onSuccess = { file ->
                    FileCache.prependFile(
                        PdfFile(
                            file.nameWithoutExtension,
                            file.absolutePath,
                            FileRepository.formatSize(file.length()),
                            FileRepository.formatDate(file.lastModified()),
                            0,
                            file.lastModified(),
                        ),
                    )
                    Toast.makeText(context, "Text saved", Toast.LENGTH_SHORT).show()
                },
                onFailure = {
                    Toast.makeText(context, "Could not save text", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}
