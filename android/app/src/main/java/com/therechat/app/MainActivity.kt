package com.therechat.app

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.therechat.app.core.ApiClient
import com.therechat.app.core.Push
import com.therechat.app.core.Realtime
import com.therechat.app.feature.chat.ChatScreen
import com.therechat.app.feature.chatinfo.ChatInfoScreen
import com.therechat.app.feature.map.MapHomeScreen
import com.therechat.app.feature.mychats.MyChatsScreen
import com.therechat.app.feature.newchat.NewChatScreen
import com.therechat.app.feature.settings.SettingsScreen
import com.therechat.app.ui.theme.ThereTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val navigator = Navigator()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) Push.sync(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Push.requestPermission = {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Push.sync(this) // tokens can change; send the current one on every launch
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            ThereTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    BackHandler(enabled = navigator.stack.size > 1) { navigator.back() }
                    Box(Modifier.fillMaxSize()) {
                        // The map stays composed underneath so it keeps its camera.
                        MapHomeScreen()
                        AnimatedContent(
                            targetState = navigator.top,
                            transitionSpec = {
                                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it / 3 } + fadeOut())
                            },
                            label = "screens",
                        ) { screen ->
                            if (screen != Screen.Map) {
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                    when (screen) {
                                        is Screen.ChatScreen -> ChatScreen(screen.chat, screen.invite)
                                        is Screen.ChatInfo -> ChatInfoScreen(screen.chatId)
                                        Screen.MyChats -> MyChatsScreen()
                                        Screen.Notifications -> MyChatsScreen(title = "Notifications")
                                        Screen.NewChat -> NewChatScreen()
                                        Screen.Settings -> SettingsScreen()
                                        Screen.Map -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // The realtime socket lives only while the app is in the foreground.
    override fun onStart() {
        super.onStart()
        Push.foreground = true
        Realtime.get(this).start()
    }

    override fun onStop() {
        super.onStop()
        Push.foreground = false
        Realtime.get(this).stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        Push.requestPermission = null // the next activity sets its own in onCreate
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /**
     * Opens a chat from a tapped notification, or an invite link:
     * https://there-chat.com/<code> and there://<code>.
     */
    private fun handleLink(intent: Intent?) {
        intent?.getStringExtra(Push.EXTRA_CHAT_ID)?.let { chatId ->
            intent.removeExtra(Push.EXTRA_CHAT_ID)
            lifecycleScope.launch {
                runCatching { ApiClient.get(this@MainActivity).chat(chatId) }.onSuccess { chat ->
                    navigator.home()
                    navigator.open(Screen.ChatScreen(chat))
                }
            }
            return
        }
        val uri = intent?.data ?: return
        val code = (if (uri.scheme == "there") uri.host else uri.lastPathSegment)?.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch {
            runCatching { ApiClient.get(this@MainActivity).chatByInvite(code) }
                .onSuccess { chat ->
                    navigator.home()
                    navigator.open(Screen.ChatScreen(chat, invite = code))
                }
                .onFailure { Toast.makeText(this@MainActivity, it.message ?: "Can't open the link", Toast.LENGTH_LONG).show() }
        }
    }
}
