package com.example.pdfmaker

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PdfToJpgPreviewHeader(
    name: String,
    sizeKb: Long,
    pageCount: Int,
    previews: List<Bitmap>,
    primaryText: Color,
    secondaryText: Color,
    cardBackground: Color,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cardBackground).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E1E30)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = Color(0xFFE53935),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$name.pdf",
                color = primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("$pageCount pages · ${jpgFormatSize(sizeKb)}", color = secondaryText, fontSize = 12.sp)
        }
    }

    if (previews.isEmpty()) return
    Text("Preview", color = secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(previews) { index, bitmap ->
            Box(
                Modifier.size(width = 72.dp, height = 96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E2E)),
            ) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = "Page ${index + 1} preview",
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Fit,
                )
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text("${index + 1}", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (pageCount > previews.size) {
            item {
                Box(
                    Modifier.size(width = 72.dp, height = 96.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E2E)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+${pageCount - previews.size} more",
                        color = secondaryText,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
