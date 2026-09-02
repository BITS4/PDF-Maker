package com.example.pdfmaker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun FilesScreenDialogs(
    activity: MainActivity,
    state: FilesScreenState,
    callbacks: FilesScreenCallbacks,
) {
    FilesSortDialog(state)
    FilesDeleteDialog(activity, state)
    FilesMoreDialog(state, callbacks)
    FilesRenameDialog(activity, state)
}

@Composable
private fun FilesSortDialog(state: FilesScreenState) {
    if (!state.showSortDialog) return
    AlertDialog(
        onDismissRequest = { state.showSortDialog = false },
        containerColor = currentCard,
        title = { Text("Sort by", color = currentText, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                SortOrder.entries.forEach { order ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    state.sortOrder = order
                                    state.showSortDialog = false
                                }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.sortOrder == order,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = AccentBlue),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(order.label, color = currentText, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun FilesDeleteDialog(
    activity: MainActivity,
    state: FilesScreenState,
) {
    val file = state.deleteCandidate ?: return
    AlertDialog(
        onDismissRequest = { state.deleteCandidate = null },
        containerColor = currentCard,
        title = { Text("Delete File", color = currentText) },
        text = {
            Text(
                text = "Delete \"${file.name}\"? This cannot be undone.",
                color = currentTextSecond,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (FilesScreenOperations.delete(activity, file)) {
                        state.fileDeleted(file)
                    } else {
                        state.deleteCandidate = null
                    }
                },
            ) {
                Text("Delete", color = BadgeRed)
            }
        },
        dismissButton = {
            TextButton(onClick = { state.deleteCandidate = null }) {
                Text("Cancel", color = currentTextSecond)
            }
        },
    )
}

@Composable
private fun FilesMoreDialog(
    state: FilesScreenState,
    callbacks: FilesScreenCallbacks,
) {
    val file = state.menuCandidate ?: return
    val isFavorite = file.filePath in state.favoritePaths
    AlertDialog(
        onDismissRequest = { state.menuCandidate = null },
        containerColor = currentCard,
        title = {
            Text(
                text = file.name,
                color = currentText,
                fontSize = 13.sp,
                maxLines = 2,
            )
        },
        text = {
            Column {
                MoreMenuItem(Icons.Default.Visibility, "Open") {
                    state.menuCandidate = null
                    callbacks.onFileClick(file)
                }
                MoreMenuItem(Icons.Default.DriveFileRenameOutline, "Rename") {
                    state.requestRename(file)
                }
                MoreMenuItem(Icons.Default.Share, "Share") {
                    state.menuCandidate = null
                    callbacks.onShareFile(file)
                }
                MoreMenuItem(
                    icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                ) {
                    state.toggleFavorite(file)
                    state.menuCandidate = null
                }
                MoreMenuItem(Icons.Default.Delete, "Delete", tint = BadgeRed) {
                    state.requestDelete(file)
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun FilesRenameDialog(
    activity: MainActivity,
    state: FilesScreenState,
) {
    val file = state.renameCandidate ?: return
    AlertDialog(
        onDismissRequest = { state.renameCandidate = null },
        containerColor = currentCard,
        title = { Text("Rename", color = currentText, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = state.renameDraft,
                onValueChange = { state.renameDraft = it },
                label = { Text("File name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = currentTextSecond,
                        focusedTextColor = currentText,
                        unfocusedTextColor = currentText,
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = currentTextSecond,
                    ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    FilesScreenOperations.rename(activity, file, state.renameDraft)
                    state.renameCandidate = null
                },
            ) {
                Text("Rename", color = AccentBlue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { state.renameCandidate = null }) {
                Text("Cancel", color = currentTextSecond)
            }
        },
    )
}
