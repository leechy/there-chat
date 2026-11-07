package com.therechat.app.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NoAccounts
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
            item { ListItem(headlineContent = { Text("Version") }, trailingContent = { Text(BuildConfig.VERSION_NAME) }) }
        }
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
