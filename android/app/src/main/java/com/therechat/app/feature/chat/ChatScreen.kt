package com.therechat.app.feature.chat

import android.app.Application
import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.therechat.app.LocalNavigator
import com.therechat.app.Screen
import com.therechat.app.core.Chat
import com.therechat.app.core.Push
import com.therechat.app.core.ChatKind
import com.therechat.app.core.Member
import com.therechat.app.core.Message
import com.therechat.app.ui.components.NameSheet
import com.therechat.app.ui.components.ReportDialog
import com.therechat.app.ui.theme.ThereColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class NamePurpose { JOIN, RENAME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chat: Chat, invite: String?) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ChatViewModel = viewModel(key = "chat-${chat.id}") { ChatViewModel(app, chat, invite) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val context = LocalContext.current

    var draft by rememberSaveable { mutableStateOf("") }
    var namePurpose by remember { mutableStateOf<NamePurpose?>(null) }
    var reportTarget by remember { mutableStateOf<Pair<Boolean, Message?>?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(chat.id) {
        vm.start()
        Push.openChatId = chat.id
        Push.clear(context, chat.id)
        onDispose {
            vm.stop()
            if (Push.openChatId == chat.id) Push.openChatId = null
        }
    }

    val c = state.chat
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.clickable(enabled = c.isMember) { nav.open(Screen.ChatInfo(c.id)) },
                    ) {
                        Text(c.emoji, fontSize = 22.sp)
                        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (c.kind == ChatKind.CHANNEL) {
                            Icon(Icons.Outlined.Campaign, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (c.isMember) {
                                DropdownMenuItem(
                                    text = { Text("Chat info") },
                                    leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                    onClick = { menuOpen = false; nav.open(Screen.ChatInfo(c.id)) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Change my name") },
                                    leadingIcon = { Icon(Icons.Outlined.Badge, null) },
                                    onClick = { menuOpen = false; namePurpose = NamePurpose.RENAME },
                                )
                            }
                            c.inviteUrl?.let { url ->
                                DropdownMenuItem(
                                    text = { Text("Share link") },
                                    leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                    onClick = {
                                        menuOpen = false
                                        context.startActivity(Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Report chat") },
                                leadingIcon = { Icon(Icons.Outlined.Report, null) },
                                onClick = { menuOpen = false; reportTarget = true to null },
                            )
                            if (c.isMember && c.me?.role != Member.Role.OWNER) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Leave chat", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; confirmLeave = true },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().imePadding()) {
                BottomBar(state, draft, onDraft = { draft = it },
                    send = { vm.send(draft); draft = "" },
                    rename = { namePurpose = NamePurpose.RENAME },
                    join = { namePurpose = NamePurpose.JOIN },
                    withdraw = { vm.leave() })
            }
        },
    ) { padding ->
        if (state.canRead) {
            Messages(state, vm, Modifier.padding(padding)) { msg -> reportTarget = true to msg }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(c.emoji, fontSize = 56.sp)
                Spacer(Modifier.size(12.dp))
                Text(
                    if (state.me?.status == Member.Status.PENDING) "Your request is waiting for the admins."
                    else "This chat is closed for the public.\nTo join the conversation, you have to request access from the admins first.",
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (state.offline) OfflineBanner(Modifier.padding(padding))
    }

    namePurpose?.let { purpose ->
        NameSheet(
            title = if (purpose == NamePurpose.JOIN) "Join ${c.title}" else "Change your name",
            explanation = when {
                purpose == NamePurpose.RENAME -> "Your old messages keep the old name. Everyone will see that you changed it."
                c.freeJoin -> "Nobody here knows who you are. Pick any name — it's only used in this chat."
                else -> "The admins will see this name in your request. It's only used in this chat."
            },
            action = when {
                purpose == NamePurpose.RENAME -> "Save"
                c.freeJoin -> "Join"
                else -> "Send request"
            },
            initial = if (purpose == NamePurpose.RENAME) c.me?.name.orEmpty() else "",
            suggest = vm::suggestedNames,
            onDismiss = { namePurpose = null },
            submit = { name -> if (purpose == NamePurpose.JOIN) vm.join(name) else vm.rename(name) },
        )
    }

    reportTarget?.let { (_, message) ->
        ReportDialog(onDismiss = { reportTarget = null }) { reason -> vm.report(message, reason) }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave ${c.title}?") },
            text = { Text("You can come back later, but you'll need to pick a name again.") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.leave { nav.back() } }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("Something went wrong") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { vm.clearError(); if (state.gone) nav.back() }) { Text("OK") }
            },
        )
    }
}

// --- messages ---

private sealed interface Item {
    val key: String
    data class Day(val date: LocalDate) : Item { override val key = "day-$date" }
    data class Msg(val message: Message, val showAuthor: Boolean) : Item { override val key = message.id }
}

private fun timeline(messages: List<Message>): List<Item> {
    val zone = ZoneId.systemDefault()
    val out = mutableListOf<Item>()
    var lastDay: LocalDate? = null
    var lastAuthor: String? = null
    for (m in messages) {
        val day = Instant.ofEpochSecond(m.createdAt).atZone(zone).toLocalDate()
        if (day != lastDay) {
            out += Item.Day(day)
            lastDay = day
            lastAuthor = null
        }
        val author = if (m.kind == Message.Kind.TEXT) "${m.memberId}|${m.authorName}" else null
        out += Item.Msg(m, author != lastAuthor)
        lastAuthor = author
    }
    return out
}

@Composable
private fun Messages(state: ChatUiState, vm: ChatViewModel, modifier: Modifier, report: (Message) -> Unit) {
    // Newest first with reverseLayout: the list stays anchored to the bottom when the
    // keyboard opens or a message grows (reactions), like a native chat.
    val items = remember(state.messages, state.outbox) { timeline(state.messages + state.outbox).asReversed() }
    val list = rememberLazyListState()

    // Show new messages if the reader was already at the bottom.
    LaunchedEffect(items.firstOrNull()?.key) {
        if (list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0)
    }

    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (state.canPost) "No messages yet. Say hi!" else "It's quiet here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is Item.Day -> Text(
                    item.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                )
                is Item.Msg -> if (item.message.kind == Message.Kind.SYSTEM) {
                    SystemEvent(item.message)
                } else {
                    MessageBubble(item.message, item.showAuthor, state, vm, report)
                }
            }
        }
        // Last in a reversed list = shown at the top.
        if (state.hasOlder && state.messages.isNotEmpty()) {
            item(key = "older") {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                LaunchedEffect(state.offline) { vm.loadOlder() } // retries when back online
            }
        }
    }
}

private fun time(epoch: Long): String =
    Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

@Composable
private fun SystemEvent(m: Message) {
    val text = buildAnnotatedString {
        fun name(n: String) = withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(n) }
        when {
            m.body == "joined" -> { name(m.authorName); append(" joined the conversation") }
            m.body == "requested" -> { name(m.authorName); append(" requested to join the conversation") }
            m.body == "left" -> { name(m.authorName); append(" has left the conversation") }
            m.body == "blocked" -> { name(m.authorName); append(" was blocked by the admins") }
            m.body == "promoted" -> { name(m.authorName); append(" is now an admin") }
            m.body.startsWith("renamed:") -> {
                name(m.body.removePrefix("renamed:")); append(" is now known as "); name(m.authorName)
            }
            else -> { name(m.authorName); append(": ${m.body}") }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(time(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

private val QuickReactions = listOf("👍", "❤️", "🤣", "😮", "😢", "🔥", "👏")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(m: Message, showAuthor: Boolean, state: ChatUiState, vm: ChatViewModel, report: (Message) -> Unit) {
    val mine = m.memberId != null && m.memberId == state.me?.id
    var actions by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (showAuthor) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.authorName) }
                    m.authorRole?.takeIf { it != Member.Role.MEMBER }?.let { append(" (${it.name.lowercase()})") }
                },
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
            )
        }
        Box {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (m.status == Message.Status.DELETED) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface,
                border = if (m.status == Message.Status.DELETED) androidx.compose.foundation.BorderStroke(1.dp, secondary.copy(alpha = 0.4f)) else null,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(
                        enabled = !m.isLocal && m.status != Message.Status.DELETED,
                        onClick = { actions = true },
                        onLongClick = { actions = true },
                    ),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    when {
                        m.status == Message.Status.DELETED ->
                            Text("Message deleted", fontStyle = FontStyle.Italic, color = secondary)
                        m.status == Message.Status.REJECTED && m.body.isEmpty() ->
                            Text("Message rejected by the admins", fontStyle = FontStyle.Italic, color = secondary)
                        m.status == Message.Status.PENDING && m.body.isEmpty() ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.HourglassEmpty, null, Modifier.size(16.dp), tint = secondary)
                                Text("Waiting for moderation", fontStyle = FontStyle.Italic, color = secondary)
                            }
                        else -> Text(
                            m.body,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (m.status == Message.Status.PUBLISHED) MaterialTheme.colorScheme.onSurface else secondary,
                        )
                    }
                    Row(
                        Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val status = when {
                            m.isLocal -> "sending…"
                            m.status == Message.Status.PENDING -> "pending"
                            m.status == Message.Status.REJECTED -> "rejected"
                            else -> null
                        }
                        status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = secondary) }
                        if (!m.isLocal) {
                            Text(time(m.deletedAt ?: m.createdAt), style = MaterialTheme.typography.labelSmall, color = secondary)
                        }
                    }
                }
            }
            DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                if (state.chat.isMember && m.status == Message.Status.PUBLISHED) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        QuickReactions.forEach { emoji ->
                            val chosen = m.reactions.any { it.emoji == emoji && it.mine }
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                                    .clickable { actions = false; vm.toggle(emoji, m) },
                                contentAlignment = Alignment.Center,
                            ) { Text(emoji, fontSize = 22.sp) }
                        }
                    }
                    HorizontalDivider()
                }
                if (m.body.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = {
                            actions = false
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", m.body))) }
                        },
                    )
                }
                if (state.chat.isAdmin && m.status == Message.Status.PENDING) {
                    DropdownMenuItem(
                        text = { Text("Publish") },
                        leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) },
                        onClick = { actions = false; vm.moderate(m, approve = true) },
                    )
                    DropdownMenuItem(
                        text = { Text("Reject", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Cancel, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { actions = false; vm.moderate(m, approve = false) },
                    )
                }
                if (state.canDelete(m)) {
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { actions = false; vm.delete(m) },
                    )
                }
                if (!mine) {
                    DropdownMenuItem(
                        text = { Text("Report", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Report, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { actions = false; report(m) },
                    )
                }
            }
        }
        if (m.reactions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                m.reactions.forEach { r ->
                    Row(
                        Modifier
                            .background(ThereColors.Reaction, RoundedCornerShape(6.dp))
                            .border(1.dp, if (r.mine) MaterialTheme.colorScheme.primary else ThereColors.ReactionBorder, RoundedCornerShape(6.dp))
                            .clickable(enabled = state.chat.isMember) { vm.toggle(r.emoji, m) }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(r.emoji)
                        Text("${r.count}", style = MaterialTheme.typography.labelMedium, color = ThereColors.Ink)
                    }
                }
            }
        }
    }
}

// --- bottom bar ---

@Composable
private fun BottomBar(
    state: ChatUiState,
    draft: String,
    onDraft: (String) -> Unit,
    send: () -> Unit,
    rename: () -> Unit,
    join: () -> Unit,
    withdraw: () -> Unit,
) {
    val c = state.chat
    val me = state.me
    when {
        state.canPost && me != null -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.clickable(onClick = rename).padding(start = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(me.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (me.role != Member.Role.MEMBER) {
                    Text("(${me.role.name.lowercase()})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.Edit, "Change name", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text("Message") },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(onClick = send, enabled = draft.isNotBlank(), modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
        me?.status == Member.Status.PENDING -> OutlinedButton(
            onClick = withdraw,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("Withdraw request", color = MaterialTheme.colorScheme.error) }
        !c.isMember && me?.status != Member.Status.BLOCKED && !state.gone -> Button(
            onClick = join,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) {
            Text(
                when {
                    !c.freeJoin -> "Send a request to join"
                    c.kind == ChatKind.CHANNEL -> "Follow this channel"
                    else -> "Join this chat"
                },
            )
        }
    }
}

/** Non-blocking notice while the server can't be reached; the loaded messages stay readable. */
@Composable
private fun OfflineBanner(modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 2.dp) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Text("Can't reach the server. Retrying…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
