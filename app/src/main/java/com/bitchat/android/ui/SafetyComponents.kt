package com.bitchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

val SosRed = Color(0xFFD32F2F)

enum class SosStatus(val label: String) {
    INJURED("Injured"),
    MEDIC("Need medic"),
    DETAINED("Detained"),
    LOST("Lost / separated"),
    DANGER("In danger")
}

object SosFormat {
    const val PREFIX = "SOS!"

    fun build(status: SosStatus, latitude: Double?, longitude: Double?, accuracyMeters: Float?): String {
        val location = if (latitude != null && longitude != null) {
            val acc = accuracyMeters?.let { " ±${it.toInt()}m" }.orEmpty()
            String.format(Locale.US, "%.5f,%.5f", latitude, longitude) + acc +
                String.format(Locale.US, " geo:%.5f,%.5f", latitude, longitude)
        } else {
            "unknown"
        }
        return "$PREFIX ${status.label.uppercase(Locale.US)} - need help. Location: $location"
    }

    fun isSos(content: String): Boolean = content.trimStart().startsWith(PREFIX)
}

@Composable
fun SosDialog(onSend: (SosStatus) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send SOS", color = SosRed, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Alerts everyone nearby on the mesh with your GPS location.", fontSize = 13.sp)
                SosStatus.entries.forEach { status ->
                    Button(
                        onClick = { onSend(status) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White)
                    ) { Text(status.label, fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun GroupDialog(onJoin: (name: String, password: String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val cleanName = name.trim().removePrefix("#").replace(Regex("\\s+"), "-")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create or join group", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Same name + same password = same group. Only people with the password can read it.", fontSize = 13.sp)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (min ${ChatViewModel.GROUP_MIN_PASSWORD})") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(enabled = cleanName.isNotEmpty() && password.length >= ChatViewModel.GROUP_MIN_PASSWORD, onClick = { onJoin(cleanName, password) }) { Text("Open group") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        modifier = Modifier.padding(0.dp)
    )
}
