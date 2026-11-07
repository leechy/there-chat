package com.therechat.app.feature.chatinfo

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import com.therechat.app.LocalNavigator
import com.therechat.app.core.ApiClient
import com.therechat.app.core.Chat
import com.therechat.app.core.ChatKind
import com.therechat.app.core.Member
import com.therechat.app.feature.newchat.SettingSwitch
import com.therechat.app.ui.components.EmojiPickerSheet
import com.therechat.app.ui.components.QrCode
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class Filter(val label: String) { ALL("All"), PENDING("Pending"), ACTIVE("Active") }

private data class Confirm(val title: String, val text: String, val run: suspend () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoScreen(chatId: String) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val api = ApiClient.get(context)
    val scope = rememberCoroutineScope()

    var chat by remember { mutableStateOf<Chat?>(null) }
    var members by remember { mutableStateOf(emptyList<Member>()) }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var showSettings by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        runCatching {
            chat = api.chat(chatId)
            members = api.members(chatId)
        }.onFailure { error = it.message }
    }
    fun run(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { error = it.message }
        load()
    }
    LaunchedEffect(chatId) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chat?.title.orEmpty()) },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (chat?.isAdmin == true) {
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Tune, "Chat settings") }
                    }
                },
            )
        },
    ) { padding ->
        val c = chat ?: return@Scaffold
        val shown = when (filter) {
            Filter.ALL -> members
            Filter.PENDING -> members.filter { it.status == Member.Status.PENDING }
            Filter.ACTIVE -> members.filter { it.status == Member.Status.ACTIVE }
        }
        LazyColumn(Modifier.padding(padding)) {
            item { Header(c) { showQr = true } }
            item {
                SettingSwitch("Notifications", "Get a notification for every new message in this chat.", c.me?.notify ?: false) { on ->
                    run { chat = api.updateMe(c.id, ApiClient.MeUpdate(notify = on)) }
                }
            }
            item {
                Text("Members", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Filter.entries.forEachIndexed { i, f ->
                        val pending = members.count { it.status == Member.Status.PENDING }
                        SegmentedButton(
                            selected = filter == f,
                            onClick = { filter = f },
                            shape = SegmentedButtonDefaults.itemShape(i, Filter.entries.size),
                        ) { Text(if (f == Filter.PENDING && pending > 0) "${f.label} ($pending)" else f.label) }
                    }
                }
            }
            items(shown, key = { it.id }) { m ->
                MemberRow(m, c, onAction = { action -> run { api.member(c.id, m.id, action) } }) { title, text, block ->
                    confirm = Confirm(title, text, block)
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    val c = chat
    if (showSettings && c != null) {
        SettingsSheet(c, onDismiss = { showSettings = false }, save = { settings ->
            run { chat = api.updateChat(c.id, settings) }
        }, delete = {
            confirm = Confirm("Delete ${c.title}?", "All messages will be erased for everyone. This can't be undone.") {
                api.deleteChat(c.id)
                nav.home()
            }
        })
    }
    if (showQr && c?.inviteUrl != null) {
        ModalBottomSheet(onDismissRequest = { showQr = false }) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Link to chat", style = MaterialTheme.typography.titleLarge)
                Text("This link lets anyone join the chat without finding it on the map — even if it's hidden.", textAlign = TextAlign.Center)
                QrCode(c.inviteUrl!!, Modifier.size(240.dp))
                Text(c.inviteUrl!!, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.inviteUrl), null))
                }) { Text("Share link") }
            }
        }
    }
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(action.title) },
            text = { Text(action.text) },
            confirmButton = {
                TextButton(onClick = { confirm = null; run(action.run) }) { Text("Confirm", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    error?.let {
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Something went wrong") }, text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
    }
}

@Composable
private fun Header(c: Chat, showQr: () -> Unit) {
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(c.lat, c.lng), 16f) }
    Box(Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 16.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().height(180.dp)) {
            GoogleMap(
                cameraPositionState = camera,
                uiSettings = MapUiSettings(
                    scrollGesturesEnabled = false, zoomGesturesEnabled = false, rotationGesturesEnabled = false,
                    tiltGesturesEnabled = false, zoomControlsEnabled = false, mapToolbarEnabled = false,
                ),
            )
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 4.dp,
            modifier = Modifier.align(Alignment.Center)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(c.emoji, fontSize = 28.sp)
                Text(c.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        if (c.inviteUrl != null) {
            FilledTonalIconButton(onClick = showQr, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                Icon(Icons.Outlined.QrCode2, "Link and QR code")
            }
        }
    }
}

@Composable
private fun MemberRow(
    m: Member,
    c: Chat,
    onAction: (ApiClient.MemberAction) -> Unit,
    confirm: (String, String, suspend () -> Unit) -> Unit,
) {
    val api = ApiClient.get(LocalContext.current)
    val isMe = m.id == c.me?.id
    val canModerate = c.isAdmin && !isMe && m.role != Member.Role.OWNER &&
        (m.role != Member.Role.ADMIN || c.me?.role == Member.Role.OWNER) && m.status != Member.Status.LEFT
    var menu by remember { mutableStateOf(false) }
    val since = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.createdAt * 1000))
    ListItem(
        headlineContent = { Text(m.name + if (isMe) " (you)" else "", fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Text(when {
                m.status == Member.Status.PENDING -> "pending since $since"
                m.role == Member.Role.OWNER -> "created on $since"
                else -> "joined on $since"
            })
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (m.status) {
                        Member.Status.ACTIVE -> m.role.name.lowercase()
                        else -> m.status.name.lowercase()
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (m.status == Member.Status.PENDING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (canModerate) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Actions for ${m.name}") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            when (m.status) {
                                Member.Status.PENDING -> {
                                    DropdownMenuItem(text = { Text("Approve") }, leadingIcon = { Icon(Icons.Outlined.Check, null) },
                                        onClick = { menu = false; onAction(ApiClient.MemberAction.APPROVE) })
                                    DropdownMenuItem(text = { Text("Reject", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Outlined.Close, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = false; onAction(ApiClient.MemberAction.REJECT) })
                                }
                                Member.Status.ACTIVE -> {
                                    if (m.role == Member.Role.MEMBER) {
                                        DropdownMenuItem(text = { Text("Promote to admin") }, leadingIcon = { Icon(Icons.Outlined.Star, null) },
                                            onClick = { menu = false; onAction(ApiClient.MemberAction.PROMOTE) })
                                    } else {
                                        DropdownMenuItem(text = { Text("Remove admin rights") }, leadingIcon = { Icon(Icons.Outlined.StarOutline, null) },
                                            onClick = { menu = false; onAction(ApiClient.MemberAction.DEMOTE) })
                                    }
                                    DropdownMenuItem(text = { Text("Delete all member messages", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menu = false
                                            confirm("Delete all messages", "Every message ${m.name} wrote in this chat will be deleted.") {
                                                api.deleteMessagesOf(m.id, c.id)
                                            }
                                        })
                                    DropdownMenuItem(text = { Text("Block this member", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Outlined.Block, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menu = false
                                            confirm("Block ${m.name}", "They won't be able to read or join this chat from this device.") {
                                                api.member(c.id, m.id, ApiClient.MemberAction.BLOCK)
                                            }
                                        })
                                }
                                Member.Status.BLOCKED ->
                                    DropdownMenuItem(text = { Text("Unblock") }, leadingIcon = { Icon(Icons.Outlined.Block, null) },
                                        onClick = { menu = false; onAction(ApiClient.MemberAction.UNBLOCK) })
                                Member.Status.LEFT -> Unit
                            }
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(c: Chat, onDismiss: () -> Unit, save: (ApiClient.ChatSettings) -> Unit, delete: () -> Unit) {
    var title by remember { mutableStateOf(c.title) }
    var emoji by remember { mutableStateOf(c.emoji) }
    var freeJoin by remember { mutableStateOf(c.freeJoin) }
    var premoderated by remember { mutableStateOf(c.premoderated) }
    var visible by remember { mutableStateOf(c.visible) }
    var pickEmoji by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chat settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = {
                    save(ApiClient.ChatSettings(title.trim(), emoji, freeJoin, premoderated, visible))
                    onDismiss()
                }, enabled = title.isNotBlank() && emoji.isNotEmpty()) { Text("Update") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                        .clickable { pickEmoji = true },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 28.sp) }
                OutlinedTextField(value = title, onValueChange = { title = it.take(32) }, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            SettingSwitch("Free to join", "Anyone who can see the chat or has a link can join and read all messages.", freeJoin) { freeJoin = it }
            if (c.kind == ChatKind.CHAT) {
                SettingSwitch("Premoderated", "New messages are hidden until an admin publishes them.", premoderated) { premoderated = it }
            }
            SettingSwitch("Visible to everyone", "Shown on the map when zoomed in to building level.", visible) { visible = it }
            if (c.me?.role == Member.Role.OWNER) {
                Button(
                    onClick = { onDismiss(); delete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).height(52.dp),
                ) { Text("Delete this chat") }
            }
        }
    }
    if (pickEmoji) EmojiPickerSheet(onDismiss = { pickEmoji = false }) { emoji = it }
}
