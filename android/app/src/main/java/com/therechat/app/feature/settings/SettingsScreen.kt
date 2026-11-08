package com.therechat.app.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NoAccounts
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.therechat.app.core.ApiClient
import com.therechat.app.core.ServerStatus
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.therechat.app.core.Realtime
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.therechat.app.BuildConfig
import com.therechat.app.LocalNavigator

/** App settings and the privacy story in plain words. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmErase by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var eraseError by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf<ServerStatus?>(null) }
    val api = remember { ApiClient.get(context) }
    var serverUrl by remember { mutableStateOf(api.baseUrl) }

    fun checkServer() {
        server = null
        scope.launch { server = api.serverStatus() }
    }
    LaunchedEffect(Unit) { checkServer() }

    /** Debug builds only: talk to another server with the same device key. */
    fun switchServer(url: String) {
        serverUrl = url
        scope.launch {
            api.use(url)
            // Reconnect to the new server; the socket's resync reloads the map.
            Realtime.get(context).run { stop(); start() }
            checkServer()
        }
    }

    fun erase() {
        erasing = true
        scope.launch {
            try {
                ApiClient.get(context).eraseDevice()
                // Reconnect as the new device; the socket's resync reloads the map.
                Realtime.get(context).run { stop(); start() }
                nav.home()
            } catch (e: Exception) {
                eraseError = e.message ?: "Something went wrong"
            } finally {
                erasing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { Header("Privacy") }
            item { Row(Icons.Outlined.NoAccounts, "No account, no phone number, no e-mail.") }
            item { Row(Icons.Outlined.TheaterComedy, "Your name is chosen separately in every chat. Nobody can tell that two names are the same person.") }
            item { Row(Icons.Outlined.LocationOn, "Your location is only used to show the map around you and is never stored.") }
            item { Header("This device") }
            item { Row(Icons.Outlined.Key, "Your chats are tied to a secret key on this phone. It is backed up with your Google account, so it survives reinstalling and moving to a new phone.") }
            item {
                ListItem(
                    headlineContent = { Text("Erase my data", color = MaterialTheme.colorScheme.error) },
                    supportingContent = {
                        Text("Deletes your messages, reactions and names in every chat, removes you from your chats and deletes the chats you created. This can't be undone.")
                    },
                    trailingContent = { if (erasing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                    modifier = Modifier.clickable(enabled = !erasing) { confirmErase = true },
                )
            }
            if (ApiClient.servers.isNotEmpty()) {
                item { Header("Connect to") }
                ApiClient.servers.forEach { (name, url) ->
                    item {
                        ListItem(
                            leadingContent = { RadioButton(selected = serverUrl == url, onClick = null) },
                            headlineContent = { Text(name) },
                            supportingContent = { Text(url.substringAfter("://")) },
                            modifier = Modifier.selectable(selected = serverUrl == url, role = Role.RadioButton) {
                                if (serverUrl != url) switchServer(url)
                            },
                        )
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Server") },
                    supportingContent = { Text("${api.host} · tap to check again") },
                    trailingContent = { ServerStatusText(server) },
                    modifier = Modifier.clickable(enabled = server != null) { checkServer() },
                )
            }
            item { ListItem(headlineContent = { Text("Version") }, trailingContent = { Text(BuildConfig.VERSION_NAME) }) }
        }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase all your data?") },
            text = { Text("Your messages and names will be deleted from every chat, and chats you created will be deleted for everyone. You will start over as a new anonymous user.") },
            confirmButton = {
                TextButton(onClick = { confirmErase = false; erase() }) {
                    Text("Erase everything", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } },
        )
    }
    eraseError?.let { message ->
        AlertDialog(
            onDismissRequest = { eraseError = null },
            title = { Text("Couldn't erase your data") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { eraseError = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))
}

@Composable
private fun Row(icon: ImageVector, text: String) {
    ListItem(leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text(text) })
}

@Composable
private fun ServerStatusText(status: ServerStatus?) {
    val (color, text) = when (status) {
        null -> {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            return
        }
        is ServerStatus.Online -> Color(0xFF2E9E4F) to "Online · ${status.ms} ms"
        is ServerStatus.Failing -> Color(0xFFE08A00) to "Error ${status.status}"
        is ServerStatus.Unreachable -> MaterialTheme.colorScheme.error to "Unreachable"
    }
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text)
    }
}
