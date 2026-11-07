package com.therechat.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.therechat.app.core.Chat

sealed interface Screen {
    data object Map : Screen
    data class ChatScreen(val chat: Chat, val invite: String? = null) : Screen
    data class ChatInfo(val chatId: String) : Screen
    data object MyChats : Screen
    data object Notifications : Screen
    data object NewChat : Screen
    data object Settings : Screen
}

/** A tiny back stack; the map always stays at the bottom. */
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Map)
    val top: Screen get() = stack.last()

    fun open(screen: Screen) { stack.add(screen) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replaceTop(screen: Screen) { back(); open(screen) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }
