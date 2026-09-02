package com.example.pdfmaker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertPageBreak
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DocxReadyContent(
    input: DocxInputMetadata?,
    onConvert: () -> Unit,
) {
    if (input == null) return
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DocxCard)
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DocxDocumentBadge(icon = Icons.Default.Description, compact = true)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    text = input.fileName,
                    color = DocxPrimaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(input.formattedSize, color = DocxSecondaryText, fontSize = 12.sp)
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DocxCard)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "What gets converted",
                color = DocxPrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            FeatureRow(Icons.AutoMirrored.Filled.FormatAlignLeft, "Paragraphs and text", DocxAccent)
            FeatureRow(Icons.Default.FormatBold, "Bold and italic formatting", DocxAccent)
            FeatureRow(Icons.Default.Title, "Headings", DocxAccent)
            FeatureRow(Icons.Default.Image, "Embedded images", DocxAccent)
            FeatureRow(Icons.Default.InsertPageBreak, "Page breaks", DocxAccent)
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onConvert,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DocxAccent),
        ) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = "Convert to PDF",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
internal fun DocxConvertingContent(
    progress: Int,
    progressText: String,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DocxSpinner(progress = progress, color = DocxAccent)
        Text(
            text = "Converting…",
            color = DocxPrimaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            text = progressText,
            color = DocxSecondaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "$progress%",
            color = DocxAccent,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp),
        )
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            color = DocxAccent,
            trackColor = Color(0xFF2A2A40),
        )
    }
}

@Composable
internal fun DocxDoneContent(
    result: DocxSavedResult?,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onConvertAnother: () -> Unit,
) {
    if (result == null) return
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF1A3020)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(52.dp),
            )
        }
        Text(
            text = "Conversion complete",
            color = DocxPrimaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = result.catalogEntry.name,
            color = DocxSecondaryText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = DocxToPdfPolicy.formatSize(result.sizeBytes),
            color = Color(0xFF4CAF50),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DocxResultButton(
                label = "Open",
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                color = AccentBlue,
                onClick = onOpen,
                modifier = Modifier.weight(1f),
            )
            DocxResultButton(
                label = "Share",
                icon = Icons.Default.Share,
                color = DocxAccent,
                onClick = onShare,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(
            onClick = onConvertAnother,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, DocxSecondaryText.copy(alpha = 0.4f)),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = DocxSecondaryText)
            Text(
                text = "Convert another",
                color = DocxSecondaryText,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
internal fun DocxDocumentBadge(
    icon: ImageVector,
    compact: Boolean = false,
) {
    val size = if (compact) 54.dp else 100.dp
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(if (compact) RoundedCornerShape(12.dp) else CircleShape)
                .background(Color(0xFF0D1A2E))
                .border(if (compact) 0.dp else 2.dp, DocxAccent.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = DocxAccent,
            modifier = Modifier.size(if (compact) 30.dp else 46.dp),
        )
    }
}

@Composable
private fun DocxResultButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
