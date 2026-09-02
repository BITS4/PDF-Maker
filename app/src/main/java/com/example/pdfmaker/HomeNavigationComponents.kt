package com.example.pdfmaker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Home tool grid and bottom navigation components.

@Composable
fun ToolsGrid(onToolClick: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        toolKeys.chunked(4).forEach { rowKeys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                rowKeys.forEach { key ->
                    Column(
                        modifier = Modifier
                            .width(80.dp)
                            .clickable { onToolClick(key) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(toolIconBg(key)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(toolIcon(key), null, tint = toolIconTint(key),
                                modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            toolName(key), color = currentText, fontSize = 11.sp,
                            textAlign = TextAlign.Center, maxLines = 2, lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}


// ── Speed dial FAB ────────────────────────────────────────────────────────────

private data class FabAction(val key: String, val label: String, val icon: ImageVector, val tint: Color, val bg: Color)

private val fabActions = listOf(
    FabAction("image_to_pdf", "Image to PDF",  Icons.Default.Image,           Color(0xFFEF5350), Color(0xFF2A1010)),
    FabAction("smart_scan",   "Smart Scan",    Icons.Default.DocumentScanner, Color(0xFF4F8EF7), Color(0xFF1A2340)),
    FabAction("import_pdf",   "Import PDF",    Icons.Default.FolderOpen,      Color(0xFFFFA726), Color(0xFF2A1E0A)),
    FabAction("merge_pdf",    "Merge PDF",     Icons.Default.MergeType,       Color(0xFFFFA726), Color(0xFF2A1E0A)),
    FabAction("docx_to_pdf",  "Docx to PDF",   Icons.Default.Description,     Color(0xFF4F8EF7), Color(0xFF1A2340)),
)

@Composable
fun SpeedDialFab(
    expanded  : Boolean,
    onToggle  : () -> Unit,
    onAction  : (String) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier            = Modifier.padding(bottom = 6.dp)
    ) {
        // Mini action buttons (shown when expanded)
        fabActions.forEach { action ->
            AnimatedVisibility(
                visible = expanded,
                enter   = fadeIn() + scaleIn(),
                exit    = fadeOut() + scaleOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Label pill
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1E1E2E))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(action.label, color = Color.White, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium)
                    }
                    // Mini FAB
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(action.bg)
                            .clickable { onAction(action.key); onToggle() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(action.icon, null, tint = action.tint,
                            modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        // Main FAB
        val rotation by animateFloatAsState(
            targetValue = if (expanded) 45f else 0f, label = "fabRot"
        )
        FloatingActionButton(
            onClick        = onToggle,
            containerColor = AccentBlue,
            contentColor   = Color.White,
            shape          = CircleShape,
            modifier       = Modifier.size(56.dp)
        ) {
            Icon(Icons.Default.Add, null,
                modifier = Modifier.size(28.dp).rotate(rotation))
        }
    }
}


@Composable
fun BottomNavBar(
    selected    : Int,
    onHomeClick : () -> Unit,
    onFilesClick: () -> Unit,
    onFabClick  : () -> Unit,
    showFab     : Boolean = true,
    fabExpanded : Boolean = false,
    onAction    : (String) -> Unit = {}
) {
    // Wrap in a Box so speed-dial items can float above the bar
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {

        // ── Speed dial items (above bar, only on Home) ────────────────────────
        if (showFab) {
            AnimatedVisibility(
                visible = fabExpanded,
                enter   = fadeIn() + slideInVertically { it / 2 },
                exit    = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)   // sits just above the bar
            ) {
                Surface(
                    shape         = RoundedCornerShape(16.dp),
                    color         = Color(0xFF1A1A2E),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier      = Modifier.padding(horizontal = 24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalArrangement   = Arrangement.spacedBy(4.dp),
                        horizontalAlignment   = Alignment.End
                    ) {
                        fabActions.forEach { action ->
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onAction(action.key); onFabClick() }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Box(
                                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                        .background(action.bg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(action.icon, null, tint = action.tint,
                                        modifier = Modifier.size(20.dp))
                                }
                                Text(action.label, color = Color.White, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // ── Navigation bar ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(currentCard)
                .navigationBarsPadding()
                .height(65.dp)
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { onHomeClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Home, null,
                        tint = if (selected == 0) AccentBlue else currentTextSecond,
                        modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.nav_home),
                        color = if (selected == 0) AccentBlue else currentTextSecond,
                        fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { onFilesClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Description, null,
                        tint = if (selected == 1) AccentBlue else currentTextSecond,
                        modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.nav_files),
                        color = if (selected == 1) AccentBlue else currentTextSecond,
                        fontSize = 11.sp)
                }
            }

            // FAB — centered, lifted above the bar
            if (showFab) {
                val rotation by animateFloatAsState(
                    targetValue = if (fabExpanded) 45f else 0f, label = "fabRot"
                )
                FloatingActionButton(
                    onClick        = onFabClick,
                    modifier       = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-10).dp)
                        .size(56.dp),
                    containerColor = AccentBlue,
                    contentColor   = Color.White,
                    shape          = CircleShape,
                    elevation      = FloatingActionButtonDefaults.elevation(
                        defaultElevation  = 4.dp,
                        pressedElevation  = 8.dp
                    )
                ) {
                    Icon(Icons.Default.Add, null,
                        modifier = Modifier.size(28.dp).rotate(rotation))
                }
            }
        }
    }
}

