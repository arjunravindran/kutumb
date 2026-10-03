package com.vibethroughcode.ftree.kutumb.messaging

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.R

/** When a message earns a note. The one rule, so the notifier and its tests cannot disagree. */
object MessageNotificationRules {
    /**
     * Only for what somebody else wrote, only when the reader has asked for notes and Android will
     * show them, and not for the conversation they are looking at: that message is already on screen.
     */
    fun shouldPost(switchOn: Boolean, allowed: Boolean, openThread: String?, message: Message): Boolean =
        switchOn && allowed && message.isFromThem && message.threadPersonId != openThread
}

/** Which conversation is on screen right now, if any. Set while its screen is resumed. */
object OpenThread {
    @Volatile var personId: String? = null
}

/**
 * Notes about new messages (off until switched on in Settings). Read straight from the preferences
 * file each time, because the container that receives a message in the background is not the one the
 * switch was turned on in.
 */
class MessageNotificationPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("message-notification-preferences", Context.MODE_PRIVATE)

    private val _enabled = kotlinx.coroutines.flow.MutableStateFlow(isEnabled())
    val enabled: kotlinx.coroutines.flow.StateFlow<Boolean> = _enabled

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}

/**
 * Posts one note per conversation, replaced as more arrive. On a locked phone it says only that
 * there is a new message: what somebody wrote to you is nobody else's business, and a lock screen is
 * read by whoever picks the phone up. Tapping it opens the conversation, in the tree it belongs to.
 */
class MessageNotifier(
    private val context: Context,
    private val treeId: String,
    private val preferences: MessageNotificationPreferences,
    /** The name of the person in this tree, if it has one. */
    private val nameOf: suspend (personId: String) -> String?,
) {
    fun canNotify(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission") // canNotify() is checked first.
    suspend fun onReceived(message: Message) {
        if (!MessageNotificationRules.shouldPost(preferences.isEnabled(), canNotify(), OpenThread.personId, message)) return
        channel()
        val name = nameOf(message.threadPersonId)?.trim()?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.message_notification_someone)
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.message_public_title))
            .build()
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open(message.threadPersonId))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(tag(message.threadPersonId), NOTIFICATION, note)
    }

    /** The note for a conversation is out of date the moment it is opened. */
    fun cancel(personId: String) = NotificationManagerCompat.from(context).cancel(tag(personId), NOTIFICATION)

    private fun tag(personId: String) = "$treeId/$personId"

    private fun open(personId: String): PendingIntent = PendingIntent.getActivity(
        context,
        tag(personId).hashCode(),
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_THREAD)
            .putExtra(EXTRA_PERSON, personId)
            .putExtra(EXTRA_TREE, treeId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun channel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.message_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.message_channel_description) },
        )
    }

    companion object {
        const val CHANNEL = "messages"
        const val NOTIFICATION = 418
        const val ACTION_OPEN_THREAD = "com.vibethroughcode.ftree.action.OPEN_THREAD"
        const val EXTRA_PERSON = "person"
        const val EXTRA_TREE = "tree"
    }
}
