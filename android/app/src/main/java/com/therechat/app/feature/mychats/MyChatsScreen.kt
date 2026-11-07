package com.therechat.app.feature.mychats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therechat.app.LocalNavigator
import com.therechat.app.Screen
import com.therechat.app.core.ApiClient
import com.therechat.app.core.ChatKind
import com.therechat.app.core.Member
import com.therechat.app.core.MyChat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyChatsScreen(title: String = "My Chats") {
    val nav = LocalNavigator.current
    val api = ApiClient.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<MyChat>?>(null) }
    var kind by remember { mutableStateOf(ChatKind.CHAT) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    suspend fun load() {
        runCatching { api.myChats() }
            .onSuccess { chats = it; error = null }
            .onFailure { error = it.message; if (chats == null) chats = emptyList() }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ChatKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(i, ChatKind.entries.size),
                    ) { Text(if (k == ChatKind.CHAT) "Chats" else "Channels") }
                }
            }
            val shown = chats?.filter { it.kind == kind }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (shown != null && shown.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (kind == ChatKind.CHAT) "No chats yet" else "No channels yet", style = MaterialTheme.typography.titleMedium)
                        Text(error ?: "Join one on the map or start your own.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { nav.open(Screen.NewChat) }, modifier = Modifier.padding(top = 16.dp)) { Text("New chat") }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown.orEmpty(), key = { it.id }) { item ->
                            ListItem(
                                leadingContent = { Text(item.emoji, fontSize = 28.sp) },
                                headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Group, null, Modifier.size(14.dp))
                                        Text("${item.memberCount}", style = MaterialTheme.typography.labelSmall)
                                        Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(14.dp))
                                        Text("${item.lastSeq}", style = MaterialTheme.typography.labelSmall)
                                        if (item.me?.status == Member.Status.PENDING) {
                                            Text("request pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                },
                                trailingContent = {
                                    if (item.unread > 0) {
                                        Badge(
                                            containerColor = if (item.me?.notify == false) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                        ) { Text("${item.unread}", modifier = Modifier.padding(horizontal = 4.dp)) }
                                    }
                                },
                                modifier = Modifier.clickable { nav.open(Screen.ChatScreen(item.chat)) },
                            )
                            HorizontalDivider(Modifier.padding(start = 72.dp))
                        }
                    }
                }
            }
        }
    }
}
