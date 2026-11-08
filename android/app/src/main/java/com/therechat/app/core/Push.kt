package com.therechat.app.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.therechat.app.MainActivity
import com.therechat.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Push notifications: asks for permission when the user first joins or
 * creates a chat, sends the FCM token to the server, and shows the
 * notifications itself (the server sends data-only messages) so they group
 * by chat and stay quiet for the chat on screen.
 */
object Push {
    private const val CHANNEL = "messages"
    const val EXTRA_CHAT_ID = "chat_id"
    private const val TAG = "Push"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The chat on screen and whether the app is in the foreground. */
    @Volatile var openChatId: String? = null
    @Volatile var foreground = false

    /** Set by MainActivity: shows the system permission prompt. */
    var requestPermission: (() -> Unit)? = null

    private fun prefs(context: Context) = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    /** Firebase is set up only when the build had google-services.json. */
    fun available(context: Context) = FirebaseApp.getApps(context).isNotEmpty()

    fun enabled(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun preview(context: Context) = prefs(context).getBoolean("preview", true)

    fun setPreview(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean("preview", on) }
        sync(context)
    }

    private fun granted(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Asks once, at the moment it makes sense: right after joining or creating a chat. */
    fun requestIfNeeded(context: Context) {
        if (granted(context)) {
            sync(context)
            return
        }
        if (prefs(context).getBoolean("asked", false)) return
        prefs(context).edit { putBoolean("asked", true) }
        requestPermission?.invoke()
    }

    /**
     * Sends the current token to the current server: on start, when the token
     * changes, and after switching servers or erasing data.
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (!available(app) || !granted(app)) return
        scope.launch {
            runCatching {
                val token = FirebaseMessaging.getInstance().token.await()
                ApiClient.get(app).setPush("fcm", token, preview(app))
            }.onFailure { Log.w(TAG, "token upload failed", it) }
        }
    }

    @SuppressLint("MissingPermission") // checked by granted()
    fun show(context: Context, chatId: String, title: String, body: String) {
        if (foreground && openChatId == chatId) return
        if (!granted(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages in your chats"
            },
        )
        val open = PendingIntent.getActivity(
            context, chatId.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // One notification per chat, collecting its recent lines.
        val lines = (prefs(context).getStringSet("lines-$chatId", emptySet()).orEmpty().sorted() + "${System.currentTimeMillis()}|$body")
            .takeLast(6)
        prefs(context).edit { putStringSet("lines-$chatId", lines.toSet()) }
        val texts = lines.map { it.substringAfter('|') }
        val style = NotificationCompat.InboxStyle().also { s -> texts.forEach(s::addLine) }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(if (texts.size > 1) style else NotificationCompat.BigTextStyle().bigText(body))
            .setNumber(texts.size)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(chatId, 0, notification)
    }

    /** Removes a chat's notification once it is open. */
    fun clear(context: Context, chatId: String) {
        NotificationManagerCompat.from(context).cancel(chatId, 0)
        prefs(context).edit { remove("lines-$chatId") }
    }
}

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Push.sync(applicationContext)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val chatId = data["chat_id"] ?: return
        Push.show(applicationContext, chatId, data["title"].orEmpty(), data["body"].orEmpty())
    }
}
