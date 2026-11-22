package com.example.myaiagent.core

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.content.Context
import com.example.myaiagent.data.ReminderDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.MessageDigest

object AgentPreferences {
    private const val FILE = "agent_preferences"
    fun botName(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("bot_name", "").orEmpty()
    fun setBotName(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("bot_name", value.trim()).apply()
    }
    fun seen(context: Context, key: String): Boolean {
        val set = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet("seen_messages", emptySet()).orEmpty()
        return key in set
    }
    fun markSeen(context: Context, key: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val set = prefs.getStringSet("seen_messages", emptySet()).orEmpty().toMutableSet()
        set.add(key)
        while (set.size > 250) set.remove(set.first())
        prefs.edit().putStringSet("seen_messages", set).apply()
    }
}

class TelegramNotificationListener : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in setOf("org.telegram.messenger", "org.thunderdog.challegram")) return
        val configuredBot = AgentPreferences.botName(this)
        if (configuredBot.isBlank()) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (!title.contains(configuredBot, ignoreCase = true)) return

        val parts = mutableListOf<String>()
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf(String::isNotBlank)?.let(parts::add)
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf(String::isNotBlank)?.let(parts::add)
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
            line?.toString()?.takeIf(String::isNotBlank)?.let(parts::add)
        }
        val uniqueParts = parts.distinct()
        if (uniqueParts.isEmpty()) return

        uniqueParts.forEach { message ->
            val fingerprint = sha256("${sbn.key}|$title|$message")
            if (AgentPreferences.seen(this, fingerprint)) return@forEach
            AgentPreferences.markSeen(this, fingerprint)
            when (val parsed = ReminderParser.parse(message)) {
                is ParseResult.Success -> serviceScope.launch {
                    when (val result = ReminderScheduler.create(this@TelegramNotificationListener, parsed.reminder, "Telegram: $title")) {
                        is ScheduleResult.Created -> AgentNotifications.status(this@TelegramNotificationListener, result.message)
                        is ScheduleResult.Failed -> AgentNotifications.status(this@TelegramNotificationListener, result.message)
                    }
                }
                is ParseResult.Failure -> {
                    // Non-reminder Telegram messages are intentionally ignored.
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
