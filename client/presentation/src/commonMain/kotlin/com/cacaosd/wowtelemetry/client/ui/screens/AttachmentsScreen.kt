package com.cacaosd.wowtelemetry.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cacaosd.wowtelemetry.client.ui.components.AttachmentCard
import com.cacaosd.wowtelemetry.domain.LogAttachment
import kotlinx.coroutines.launch

@Composable
fun AttachmentsScreen(
    attachments: List<LogAttachment>,
    onToggleEnabled: (id: String, isEnabled: Boolean) -> Unit,
    onDescriptionChange: (id: String, description: String) -> Unit,
    onRemoveAttachment: (id: String) -> Unit,
    onAddAttachment: (pathOrUri: String, displayName: String?, description: String) -> Unit,
    modifier: Modifier = Modifier,
    pickLogFile: (suspend () -> String?)? = null,
) {
    var isAddDialogVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompact = maxWidth < 600.dp
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val header = @Composable {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Attached Combat Logs",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "External logs and telemetry files available for AI inspection and reference.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val attachButton = @Composable {
                    Button(
                        onClick = { isAddDialogVisible = true },
                        modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(text = "+ Attach Log")
                    }
                }

                if (isCompact) {
                    header()
                    attachButton()
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(modifier = Modifier.weight(1f)) { header() }
                        attachButton()
                    }
                }
            }
        }

        // Attachments List or Empty State
        if (attachments.isEmpty()) {
            EmptyAttachmentsState(onAttachClick = { isAddDialogVisible = true })
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(attachments, key = { it.id }) { attachment ->
                    AttachmentCard(
                        attachment = attachment,
                        onToggleEnabled = { isEnabled -> onToggleEnabled(attachment.id, isEnabled) },
                        onDescriptionChange = { desc -> onDescriptionChange(attachment.id, desc) },
                        onRemove = { onRemoveAttachment(attachment.id) },
                    )
                }
            }
        }
    }

    if (isAddDialogVisible) {
        AddAttachmentDialog(
            onDismiss = { isAddDialogVisible = false },
            onConfirm = { path, name, desc ->
                isAddDialogVisible = false
                onAddAttachment(path, name, desc)
            },
            pickLogFile = pickLogFile,
        )
    }
}

@Composable
private fun EmptyAttachmentsState(
    onAttachClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "📎", fontSize = 42.sp)
            Text(
                text = "No Log Files Attached",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Attach World of Warcraft combat logs (.txt, .log, .wowlog) to give the Chronicle narrator background context and enable deep line-by-line inspection tools.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(0.75f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Button(
                onClick = onAttachClick,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(text = "Attach Log File")
            }
        }
    }
}

@Composable
private fun AddAttachmentDialog(
    onDismiss: () -> Unit,
    onConfirm: (path: String, displayName: String?, description: String) -> Unit,
    pickLogFile: (suspend () -> String?)?,
) {
    var filePath by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Attach Combat Log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                // Path input & Browse
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = filePath,
                        onValueChange = { filePath = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Log File Path") },
                        placeholder = { Text("/path/to/WoWCombatLog.txt") },
                        singleLine = true,
                    )

                    if (pickLogFile != null) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val picked = pickLogFile()
                                    if (picked != null) {
                                        filePath = picked
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Browse...")
                        }
                    }
                }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display Name (Optional)") },
                    placeholder = { Text("e.g. Mythic Ansurek Progress") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Narrator Context (Optional)") },
                    placeholder = { Text("e.g. 5-minute fight with early tank wipe on add phase") },
                    maxLines = 3,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            if (filePath.isNotBlank()) {
                                onConfirm(
                                    filePath.trim(),
                                    displayName.trim().takeIf { it.isNotEmpty() },
                                    description.trim()
                                )
                            }
                        },
                        enabled = filePath.isNotBlank(),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("Add Attachment")
                    }
                }
            }
        }
    }
}
