package com.example.myaiagent.core

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myaiagent.MainActivity
import com.example.myaiagent.data.ReminderDatabase
import com.example.myaiagent.data.ReminderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

sealed class ScheduleResult {
    data class Created(val entity: ReminderEntity, val message: String) : ScheduleResult()
    data class Failed(val message: String) : ScheduleResult()
}

object ReminderScheduler {
    suspend fun create(context: Context, parsed: ParsedReminder, source: String): ScheduleResult =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val at = parsed.dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (at <= System.currentTimeMillis()) return@withContext ScheduleResult.Failed("هذا الموعد أصبح في الماضي.")
            val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val today = parsed.dateTime.toLocalDate() == LocalDate.now()
            if (today) {
                val clockIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, parsed.dateTime.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, parsed.dateTime.minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "تذكير: ${parsed.originalText.take(90)}")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val canOpenClock = clockIntent.resolveActivity(app.packageManager) != null
                if (canOpenClock) {
                    try {
                        app.startActivity(clockIntent)
                        val item = ReminderEntity(
                            fireAtMillis = at,
                            message = parsed.originalText,
                            source = source,
                            deliveryMode = "CLOCK"
                        )
                        val id = ReminderDatabase.get(app).reminders().insert(item)
                        return@withContext ScheduleResult.Created(item.copy(id = id), "تم إرسال المنبّه إلى تطبيق الساعة. راجع تطبيق الساعة للتأكد من ظهوره.")
                    } catch (_: Exception) {
                        // Some Android builds block background clock launches. Fall back to an in-app alarm.
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                return@withContext ScheduleResult.Failed("فعّل صلاحية المنبّهات الدقيقة من شاشة الإعدادات أولًا.")
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext ScheduleResult.Failed("اسمح بإشعارات التطبيق حتى يصلك تنبيه الموعد.")
            }

            val item = ReminderEntity(
                fireAtMillis = at,
                message = parsed.originalText,
                source = source,
                deliveryMode = "INTERNAL"
            )
            val id = ReminderDatabase.get(app).reminders().insert(item)
            val saved = item.copy(id = id)
            try {
                scheduleInternal(app, saved)
                ScheduleResult.Created(saved, "تم تسجيل التذكير داخل التطبيق.")
            } catch (e: SecurityException) {
                ReminderDatabase.get(app).reminders().delete(id)
                ScheduleResult.Failed("لم يسمح Android بإنشاء المنبّه. راجع صلاحية المنبّهات الدقيقة.")
            } catch (_: Exception) {
                ReminderDatabase.get(app).reminders().delete(id)
                ScheduleResult.Failed("تعذر إنشاء المنبّه. حاول مرة أخرى.")
            }
        }

    private fun scheduleInternal(context: Context, item: ReminderEntity) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = alarmPendingIntent(context, item.id, item.message)
        val open = PendingIntent.getActivity(
            context, item.id.toInt(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(item.fireAtMillis, open), operation)
    }

    suspend fun restore(context: Context) = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) return@withContext
        ReminderDatabase.get(context).reminders().pending()
            .filter { it.deliveryMode == "INTERNAL" && it.fireAtMillis > System.currentTimeMillis() }
            .forEach { runCatching { scheduleInternal(context, it) } }
    }

    private fun alarmPendingIntent(context: Context, id: Long, message: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            data = Uri.parse("myaiagent://reminder/$id")
            putExtra("reminder_id", id)
            putExtra("message", message)
        }
        return PendingIntent.getBroadcast(
            context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun kotlinx.coroutines.CoroutineScope.launchSafe(block: suspend () -> Unit) {
        launch { runCatching { block() } }
    }
}

object AgentNotifications {
    private const val CHANNEL_STATUS = "agent_status_v2"
    private const val CHANNEL_REMINDERS = "agent_reminders_v2"

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "حالة المساعد", NotificationManager.IMPORTANCE_DEFAULT)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_REMINDERS, "التذكيرات", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    setSound(
                        android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
                        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
                    )
                }
            )
        }
    }

    fun status(context: Context, text: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("مساعد التذكيرات")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(7101, notification) }
    }

    fun fire(context: Context, id: Long, message: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("حان موعد التذكير")
            .setContentText(message.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id.toInt(), notification) }
    }
}
