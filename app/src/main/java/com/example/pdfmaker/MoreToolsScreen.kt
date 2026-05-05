package com.example.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Tool definitions ──────────────────────────────────────────────────────────

private data class ToolDef(
    val key   : String,
    val label : String,
    val icon  : ImageVector,
    val tint  : Color,
    val bg    : Color
)

private val popularTools = listOf(
    ToolDef("smart_scan",  "Smart Scan",    Icons.Default.DocumentScanner, Color(0xFF4F8EF7), Color(0xFF1A2340)),
    ToolDef("scan_id",     "Scan ID Card",  Icons.Default.Badge,           Color(0xFF26C6A0), Color(0xFF0F2420)),
    ToolDef("import_pdf",  "Import PDF",    Icons.Default.FolderOpen,      Color(0xFFFFA726), Color(0xFF2A1E0A)),
    ToolDef("ocr",         "OCR / Extract", Icons.Default.DocumentScanner, Color(0xFF26C6A0), Color(0xFF0F2420)),
    ToolDef("print_pdf",   "Print PDF",     Icons.Default.Print,           Color(0xFF4F8EF7), Color(0xFF1A2340)),
)

private val editTools = listOf(
    ToolDef("merge_pdf",    "Merge PDF",     Icons.Default.MergeType,            Color(0xFFFFA726), Color(0xFF2A1E0A)),
    ToolDef("split_pdf",    "Split PDF",     Icons.Default.CallSplit,            Color(0xFFEF5350), Color(0xFF2A1010)),
    ToolDef("page_manager", "Manage Pages",  Icons.Default.Pages,                Color(0xFF4F8EF7), Color(0xFF1A2340)),
    ToolDef("compress",     "Compress",      Icons.Default.Compress,             Color(0xFFEF5350), Color(0xFF2A1010)),
    ToolDef("doodle",       "Doodle",        Icons.Default.Edit,                 Color(0xFF9C6DFF), Color(0xFF1E1530)),
    ToolDef("add_text",     "Add Text",      Icons.Default.TextFields,           Color(0xFF26C6A0), Color(0xFF0F2420)),
    ToolDef("signature",    "Signature",     Icons.Default.Draw,                 Color(0xFF9C6DFF), Color(0xFF1E1530)),
    ToolDef("lock_pdf",     "Lock PDF",      Icons.Default.Lock,                 Color(0xFF4F8EF7), Color(0xFF1A2340)),
    ToolDef("unlock_pdf",   "Unlock PDF",    Icons.Default.LockOpen,             Color(0xFF4F8EF7), Color(0xFF1A2340)),
)

private val convertTools = listOf(
    ToolDef("image_to_pdf","Image to PDF",  Icons.Default.Image,                Color(0xFFEF5350), Color(0xFF2A1010)),
    ToolDef("pdf_to_jpg",  "PDF to JPG",    Icons.Default.PhotoLibrary,         Color(0xFFFFA726), Color(0xFF2A1E0A)),
    ToolDef("docx_to_pdf", "Docx to PDF",   Icons.Default.Description,          Color(0xFF4F8EF7), Color(0xFF1A2340)),
)

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun MoreToolsScreen(
    onBack    : () -> Unit,
    onToolClick: (String) -> Unit
) {
    val bgDark  = Color(0xFF0D0D16)
    val barBg   = Color(0xFF1A1A2A)
    val textPri = Color.White
    val textSec = Color(0xFF9999BB)

    Column(
        Modifier
            .fillMaxSize()
            .background(bgDark)
            .statusBarsPadding()
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(barBg)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = textPri)
            }
            Text(
                "Tools", color = textPri,
                fontSize = 19.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        // ── Scrollable content ────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            ToolSection("Popular",  popularTools,  textPri, textSec, onToolClick)
            ToolSection("Edit",     editTools,     textPri, textSec, onToolClick)
            ToolSection("Convert",  convertTools,  textPri, textSec, onToolClick)
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Section ───────────────────────────────────────────────────────────────────

@Composable
private fun ToolSection(
    title      : String,
    tools      : List<ToolDef>,
    textPri    : Color,
    textSec    : Color,
    onToolClick: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = textPri, fontSize = 17.sp, fontWeight = FontWeight.Bold)

        // 3-column grid
        val rows = tools.chunked(3)
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { tool ->
                    ToolCard(tool, textSec, modifier = Modifier.weight(1f), onClick = { onToolClick(tool.key) })
                }
                // Fill empty cells in last row
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ── Tool card ─────────────────────────────────────────────────────────────────

@Composable
private fun ToolCard(
    tool    : ToolDef,
    textSec : Color,
    modifier: Modifier = Modifier,
    onClick : () -> Unit
) {
    Column(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF14141F))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tool.bg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                tool.icon, null,
                tint     = tool.tint,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            tool.label,
            color      = Color.White,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines   = 2,
            textAlign  = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 15.sp
        )
    }
}
