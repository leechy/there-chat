package com.therechat.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

/** Picks the name to use in one chat, with random film character suggestions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NameSheet(
    title: String,
    explanation: String,
    action: String,
    initial: String,
    suggest: suspend () -> List<String>,
    onDismiss: () -> Unit,
    submit: suspend (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    suspend fun reload() {
        suggestions = suggest()
        if (name.isBlank()) name = suggestions.firstOrNull().orEmpty()
    }
    LaunchedEffect(Unit) { reload() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(32); error = null },
                label = { Text("Your name in this chat") },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: "${name.length}/32") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Or be someone famous:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Outlined.Shuffle, "Shuffle") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s -> SuggestionChip(onClick = { name = s }, label = { Text(s) }) }
            }
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            submit(name.trim())
                            sheet.hide()
                            onDismiss()
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = name.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(action) }
        }
    }
}

private val ReportReasons = listOf("Child safety", "Spam", "Harassment or hate", "Violence or threats", "Sexual content", "Illegal activity")

/** Abuse report for a message or a whole chat; goes to the operators. */
@Composable
fun ReportDialog(onDismiss: () -> Unit, send: (String) -> Unit) {
    var reason by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report") },
        text = {
            Column {
                ReportReasons.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = reason == r, role = Role.RadioButton) { reason = r },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = null)
                        Spacer(Modifier.padding(start = 8.dp))
                        Text(r, Modifier.padding(vertical = 10.dp))
                    }
                }
                Text(
                    "Reports go to the There team, not to the chat admins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { reason?.let(send); onDismiss() }, enabled = reason != null) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The system emoji picker in a bottom sheet; returns one emoji. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(onDismiss: () -> Unit, pick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AndroidView(
            factory = { ctx ->
                EmojiPickerView(ctx).apply {
                    emojiGridColumns = 8
                    setOnEmojiPickedListener { pick(it.emoji); onDismiss() }
                }
            },
            modifier = Modifier.fillMaxWidth().height(420.dp),
        )
    }
}

/** A QR code for text, drawn as a crisp bitmap. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.MARGIN to 1))
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).apply {
            for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
                setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
    Image(bitmap.asImageBitmap(), contentDescription = "QR code", modifier = modifier)
}
