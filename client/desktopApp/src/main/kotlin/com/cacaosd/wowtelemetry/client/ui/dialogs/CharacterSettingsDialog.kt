package com.cacaosd.wowtelemetry.client.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.*
import com.cacaosd.wowtelemetry.domain.CharacterProfile

@Composable
fun CharacterSettingsDialog(
    profile: CharacterProfile,
    onDismiss: () -> Unit,
    onSave: (CharacterProfile) -> Unit,
) {
    var characterName by remember(profile) { mutableStateOf(profile.characterName) }
    var characterClass by remember(profile) { mutableStateOf(profile.characterClass) }
    var specialization by remember(profile) { mutableStateOf(profile.specialization) }
    var realm by remember(profile) { mutableStateOf(profile.realm) }
    var region by remember(profile) { mutableStateOf(profile.region) }

    val currentClassColor = wowClassColor(characterClass)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(currentClassColor)
                )
                Text("Character Profile", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Personalize the narrator by specifying who you play in battle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ChronicleTextMuted,
                )

                OutlinedTextField(
                    value = characterName,
                    onValueChange = { characterName = it },
                    label = { Text("Character Name") },
                    placeholder = { Text("e.g. Thrall") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = characterClass,
                        onValueChange = { characterClass = it },
                        label = { Text("Class") },
                        placeholder = { Text("Shaman") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = specialization,
                        onValueChange = { specialization = it },
                        label = { Text("Specialization") },
                        placeholder = { Text("Enhancement") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = realm,
                        onValueChange = { realm = it },
                        label = { Text("Realm") },
                        placeholder = { Text("Tarren Mill") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = region,
                        onValueChange = { region = it },
                        label = { Text("Region") },
                        placeholder = { Text("EU") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChronicleGold,
                    contentColor = Color(0xFF1E1602)
                ),
                onClick = {
                    onSave(
                        CharacterProfile(
                            characterName = characterName,
                            characterClass = characterClass,
                            specialization = specialization,
                            realm = realm,
                            region = region,
                        )
                    )
                },
            ) {
                Text("Save Profile", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
