package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorder
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleError
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGold
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurface
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextMuted

/** Question box. Answers come from computed statistics; the log rows themselves are never shown. */
@Composable
fun AskComposer(
    analyzedEvents: Long,
    isAnswering: Boolean,
    error: String?,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    val hasData = analyzedEvents > 0
    val canSend = hasData && !isAnswering && text.isNotBlank()
    val send = {
        if (canSend) {
            onAsk(text)
            text = ""
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ChronicleSurface,
        border = BorderStroke(1.dp, ChronicleBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask about your logs, e.g. who took the most damage?") },
                    singleLine = true,
                    enabled = hasData,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Button(
                    onClick = { send() },
                    enabled = canSend,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ChronicleGold,
                        contentColor = Color(0xFF1E1602),
                    ),
                ) {
                    Text(if (isAnswering) "Thinking…" else "Ask")
                }
            }
            Text(
                text = when {
                    error != null -> error
                    hasData -> "Answers use statistics computed from $analyzedEvents events. Log lines are not shown."
                    else -> "Process some combat events first, then ask questions about them."
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) ChronicleError else ChronicleTextMuted,
            )
        }
    }
}
