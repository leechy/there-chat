package com.therechat.app.feature.newchat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import com.therechat.app.LocalNavigator
import com.therechat.app.Screen
import com.therechat.app.core.ApiClient
import com.therechat.app.core.ChatKind
import com.therechat.app.feature.map.TriangleShape
import com.therechat.app.ui.components.EmojiPickerSheet
import kotlinx.coroutines.launch

/** Drag the map to place the chat, fill in the sheet, create. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(start: LatLng = LatLng(48.8530, 2.3499)) {
    val nav = LocalNavigator.current
    val api = ApiClient.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(start, 17f) }
    val density = LocalDensity.current

    var emoji by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ChatKind.CHAT) }
    var freeJoin by remember { mutableStateOf(true) }
    var premoderated by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var pickEmoji by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pinPx by remember { mutableStateOf(android.graphics.Point(0, 0)) }

    LaunchedEffect(Unit) {
        if (name.isBlank()) name = runCatching { api.suggestedNames(1).first() }.getOrDefault("")
    }

    val valid = emoji.isNotEmpty() && title.isNotBlank() && name.isNotBlank()

    fun create() {
        val point = camera.projection?.fromScreenLocation(pinPx) ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val chat = api.createChat(ApiClient.NewChat(
                    kind = kind, title = title.trim(), emoji = emoji, lat = point.latitude, lng = point.longitude,
                    freeJoin = freeJoin, premoderated = premoderated && kind == ChatKind.CHAT, visible = visible, name = name.trim(),
                ))
                nav.replaceTop(Screen.ChatScreen(chat))
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    BottomSheetScaffold(
        sheetPeekHeight = 360.dp,
        sheetContent = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (kind == ChatKind.CHAT) "New chat" else "New channel", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(onClick = ::create, enabled = valid && !busy) { Text("Create") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                                .clickable { pickEmoji = true },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji.ifEmpty { "❓" }, fontSize = 28.sp) }
                        Text("Icon", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(32) },
                        label = { Text("Name") },
                        placeholder = { Text("Guess who?") },
                        supportingText = { Text("Keep it short · ${title.length}/32") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ChatKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = kind == k,
                            onClick = { kind = k },
                            shape = SegmentedButtonDefaults.itemShape(i, ChatKind.entries.size),
                        ) { Text(if (k == ChatKind.CHAT) "Chat" else "Channel") }
                    }
                }
                Text(
                    if (kind == ChatKind.CHAT) "All members of the chat can post new messages."
                    else "Only admins post. Everyone else reads and reacts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch("Free to join", "Anyone who can see the chat or has a link can join and read all messages.", freeJoin) { freeJoin = it }
                if (kind == ChatKind.CHAT) {
                    SettingSwitch("Premoderated", "New messages are hidden until an admin publishes them.", premoderated) { premoderated = it }
                }
                SettingSwitch("Visible to everyone", "Shown on the map when zoomed in to building level. Hidden chats are reachable only by link.", visible) { visible = it }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text("Your name in this chat") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { scope.launch { name = runCatching { api.suggestedNames(1).first() }.getOrDefault(name) } }) {
                            Icon(Icons.Outlined.Shuffle, "Random name")
                        }
                    },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pinX = constraints.maxWidth / 2
            val pinY = (constraints.maxHeight * 0.27f).toInt()
            pinPx = android.graphics.Point(pinX, pinY)
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, compassEnabled = false),
            )
            // The pin's tail points at (pinX, pinY).
            Column(
                Modifier.offset(
                    x = with(density) { pinX.toDp() } - 20.dp,
                    y = with(density) { pinY.toDp() } - 47.dp,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(emoji.ifEmpty { "❓" }, fontSize = 20.sp) }
                Box(Modifier.size(10.dp, 7.dp).background(MaterialTheme.colorScheme.primary, TriangleShape))
            }
            Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = nav::back,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) { Icon(Icons.Outlined.Close, "Close") }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 3.dp,
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text("Drag the map to place the chat", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }

    if (pickEmoji) EmojiPickerSheet(onDismiss = { pickEmoji = false }) { emoji = it }
}

@Composable
fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // A plain row instead of ListItem, so the text lines up with the other fields in the sheet.
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
