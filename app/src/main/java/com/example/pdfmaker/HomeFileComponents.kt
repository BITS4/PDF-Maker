package com.example.pdfmaker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── File item with real PDF thumbnail ─────────────────────────────────────────

@Composable
fun FileItemWithThumb(
    file: PdfFile,
    onItemClick: () -> Unit,
    onShareClick: () -> Unit,
    onMoreClick: () -> Unit,
) {
    val thumb = rememberPdfThumbnail(file.filePath, 200)
    val ext = file.filePath.substringAfterLast('.').uppercase()

    Surface(modifier = Modifier.fillMaxWidth(), color = currentCard) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick() }
                        .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isLocked = remember(file.filePath) { isLockedFile(file.filePath) }
                Box(
                    modifier =
                        Modifier
                            .size(width = 72.dp, height = 80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(currentThumbnail),
                ) {
                    if (isLocked) {
                        // Show lock icon for encrypted files
                        Box(
                            Modifier.fillMaxSize().background(Color(0xFF1A2340)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                null,
                                tint = AccentBlue,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    } else if (thumb != null) {
                        Image(
                            thumb.asImageBitmap(),
                            null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            fileTypeIcon(file.filePath.substringAfterLast('.', "").lowercase()),
                            null,
                            tint = fileTypeTint(file.filePath.substringAfterLast('.', "").lowercase()),
                            modifier = Modifier.size(36.dp).align(Alignment.Center),
                        )
                    }
                    Text(
                        ext,
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .background(
                                    fileTypeTint(file.filePath.substringAfterLast('.', "").lowercase()),
                                    RoundedCornerShape(bottomStart = 4.dp),
                                ).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name,
                        color = currentText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AccentBlue),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                file.pageCount.toString(),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(file.date, color = currentTextSecond, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(file.size, color = currentTextSecond, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Icon(
                            Icons.Default.Share,
                            null,
                            tint = currentTextSecond,
                            modifier = Modifier.size(22.dp).clickable { onShareClick() },
                        )
                        Spacer(Modifier.width(20.dp))
                        Icon(
                            Icons.Default.MoreVert,
                            null,
                            tint = currentTextSecond,
                            modifier = Modifier.size(22.dp).clickable { onMoreClick() },
                        )
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                thickness = 0.5.dp,
                color = currentDivider,
            )
        }
    }
}

// ── Shared UI pieces ──────────────────────────────────────────────────────────

@Composable
fun IconButtonRound(
    icon: ImageVector,
    contentDesc: String,
    onClick: () -> Unit = {},
) {
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(currentToolIcon)
                .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDesc, tint = currentText, modifier = Modifier.size(20.dp))
    }
}

// ── File type icon / tint helpers ─────────────────────────────────────────────

fun fileTypeIcon(ext: String): ImageVector =
    when (ext) {
        "pdf" -> Icons.Default.PictureAsPdf

        "doc", "docx" -> Icons.Default.Description

        "xls", "xlsx" -> Icons.Default.TableChart

        "ppt", "pptx" -> Icons.Default.Slideshow

        "csv", "tsv" -> Icons.Default.TableRows

        "txt", "md", "log" -> Icons.AutoMirrored.Filled.TextSnippet

        "jpg", "jpeg",
        "png", "webp",
        "bmp", "gif",
        -> Icons.Default.Image

        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

fun fileTypeTint(ext: String): Color =
    when (ext) {
        "pdf" -> Color(0xFFEF5350)

        "doc", "docx" -> Color(0xFF4F8EF7)

        "xls", "xlsx" -> Color(0xFF26C6A0)

        "ppt", "pptx" -> Color(0xFFFFA726)

        "csv", "tsv" -> Color(0xFF9C6DFF)

        "txt", "md", "log" -> Color(0xFF8888AA)

        "jpg", "jpeg",
        "png", "webp",
        "bmp", "gif",
        -> Color(0xFF26C6A0)

        else -> Color(0xFF8888AA)
    }

// Legacy alias keeps FilesScreen and SearchScreen compiling
@Composable
fun FileItem(
    file: PdfFile,
    onItemClick: () -> Unit,
    onShareClick: () -> Unit,
    onMoreClick: () -> Unit,
) = FileItemWithThumb(file, onItemClick, onShareClick, onMoreClick)
