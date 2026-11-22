package com.example.myaiagent

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Reminder(val whenAt: LocalDateTime, val text: String)

object ReminderParser {
    private val triggers = listOf("فكرني", "ذكرني", "تذكير", "منبه", "منبّه", "موعد", "ميعاد", "اجتماع", "remind me", "reminder")
    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): Reminder? {
        val digits = "٠١٢٣٤٥٦٧٨٩"
        var s = raw.map { c -> val i = digits.indexOf(c); if (i >= 0) ('0'.code+i).toChar() else c }.joinToString("")
        s = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('،',' ').replace('٫',':')
        if (triggers.none { s.contains(it) }) return null
        val clock = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)""").find(s)
        val marked = Regex("""(?<!\d)(1[0-2]|0?[1-9])\s*(صباحا|الصبح|صباح|مساء|بالليل|ليل|am|pm)""").find(s)
        val explicit = Regex("""(?:الساعة|الساعه|ساعة)\s*([01]?\d|2[0-3])(?:[:.]([0-5]\d))?""").find(s)
        val match = clock ?: marked ?: explicit ?: return null
        var h = match.groupValues[1].toInt()
        val m = if (clock != null || explicit != null) match.groupValues[2].ifBlank { "0" }.toInt() else 0
        val mer = if (marked != null && match === marked) match.groupValues[2] else if (clock == null && explicit == null) match.groupValues[3] else ""
        if (mer in listOf("صباحا","الصبح","صباح","am")) { if (h == 12) h = 0 }
        else if (mer in listOf("مساء","بالليل","ليل","pm")) { if (h in 1..11) h += 12 }
        else if (h in 1..12) return null

        val today = now.toLocalDate()
        val date = when {
            listOf("بعد بكرة","بعد غد","بعد الغد").any { s.contains(it) } -> today.plusDays(2)
            listOf("بكرة","بكره","غدا","غداً","غدًا").any { s.contains(it) } -> today.plusDays(1)
            listOf("اليوم","النهارده","انهارده").any { s.contains(it) } -> today
            else -> parseDate(s, today) ?: if (h == 0 || h >= 13) today else return null
        }
        val dt = LocalDateTime.of(date, LocalTime.of(h,m))
        if (!dt.isAfter(now)) return null
        return Reminder(dt, raw)
    }
    private fun parseDate(s: String, today: LocalDate): LocalDate? {
        Regex("""\b(20\d{2})[-/](\d{1,2})[-/](\d{1,2})\b""").find(s)?.let {
            return runCatching { LocalDate.of(it.groupValues[1].toInt(),it.groupValues[2].toInt(),it.groupValues[3].toInt()) }.getOrNull()
        }
        Regex("""(?<!\d)(\d{1,2})[/-](\d{1,2})(?:[/-](20\d{2}))?(?!\d)""").find(s)?.let {
            val y = it.groupValues[3].ifBlank { today.year.toString() }.toInt()
            return runCatching { LocalDate.of(y,it.groupValues[2].toInt(),it.groupValues[1].toInt()) }.getOrNull()
        }
        return null
    }
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("agent_store", Context.MODE_PRIVATE)
    fun bot(c: Context) = p(c).getString("bot_name","") ?: ""
    fun setBot(c: Context, v: String) { p(c).edit().putString("bot_name",v.trim()).apply() }
    fun reminders(c: Context): List<JSONObject> = runCatching {
        val a = JSONArray(p(c).getString("items","[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())
    fun save(c: Context, id: Int, at: Long, title: String, source: String) {
        val a = JSONArray()
        reminders(c).filter { it.optInt("id") != id }.forEach { a.put(it) }
        a.put(JSONObject().put("id",id).put("at",at).put("title",title).put("source",source))
        p(c).edit().putString("items",a.toString()).apply()
    }
    fun remove(c: Context, id: Int) {
        val a = JSONArray()
        reminders(c).filter { it.optInt("id") != id }.forEach { a.put(it) }
        p(c).edit().putString("items",a.toString()).apply()
    }
    fun seen(c: Context, key: String): Boolean {
        val set = p(c).getStringSet("seen", emptySet()) ?: emptySet()
        return key in set
    }
    fun mark(c: Context, key: String) {
        val set = (p(c).getStringSet("seen", emptySet()) ?: emptySet()).toMutableSet()
        set.add(key); while (set.size > 100) set.remove(set.first())
        p(c).edit().putStringSet("seen",set).apply()
    }
}

object Notices {
    private const val STATUS = "agent_status"
    private const val ALARM = "agent_alarm"
    private fun channels(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(STATUS,"حالة المساعد",NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(ALARM,"منبّهات المساعد",NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setSound(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
            })
        }
    }
    fun status(c: Context, message: String) {
        channels(c)
        val n = NotificationCompat.Builder(c,STATUS).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("My AI Agent").setContentText(message).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(c).notify(9001,n) }
    }
    fun alarm(c: Context, id: Int, title: String) {
        channels(c)
        val open = PendingIntent.getActivity(c,id,Intent(c,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c,ALARM).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("حان موعد التذكير").setContentText(title).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(c).notify(id,n) }
    }
}

object Scheduler {
    fun schedule(c: Context, reminder: Reminder): String {
        val at = reminder.whenAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at <= System.currentTimeMillis()) return "الموعد أصبح في الماضي."
        if (reminder.whenAt.toLocalDate() == LocalDate.now()) {
            return try {
                val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR,reminder.whenAt.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES,reminder.whenAt.minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE,"تذكير من My AI Agent")
                    putExtra(AlarmClock.EXTRA_SKIP_UI,true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (i.resolveActivity(c.packageManager) == null) internal(c,reminder,at)
                else { c.startActivity(i); "أُرسل طلب المنبّه إلى تطبيق الساعة. تحقق من ظهوره في الساعة." }
            } catch (_: Exception) { internal(c,reminder,at) }
        }
        return internal(c,reminder,at)
    }
    private fun internal(c: Context, r: Reminder, at: Long): String {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) return "فعّل صلاحية Alarms & reminders أولًا."
        val id = (at xor r.text.hashCode().toLong()).toInt()
        val op = alarmPending(c,id,r.text)
        val show = PendingIntent.getActivity(c,id,Intent(c,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at,show),op)
            Store.save(c,id,at,"تذكير من Telegram",r.text)
            "تم تسجيل الموعد داخل My AI Agent."
        } catch (_: SecurityException) { "لم تُمنح صلاحية المنبّهات الدقيقة." }
    }
    private fun alarmPending(c: Context,id: Int,title: String): PendingIntent {
        val i = Intent(c,AlarmReceiver::class.java).apply {
            data = Uri.parse("myaiagent://alarm/$id"); putExtra("id",id); putExtra("title",title)
        }
        return PendingIntent.getBroadcast(c,id,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun cancel(c: Context,id: Int) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmPending(c,id,""))
        Store.remove(c,id)
    }
    fun restore(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) return
        Store.reminders(c).forEach { o ->
            val at = o.optLong("at")
            if (at > System.currentTimeMillis()) {
                val id = o.optInt("id")
                val show = PendingIntent.getActivity(c,id,Intent(c,MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at,show),alarmPending(c,id,o.optString("source")))
            }
        }
    }
}

class TelegramNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "org.telegram.messenger") return
        val allowed = Store.bot(this)
        if (allowed.isBlank()) return
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (!title.equals(allowed,true)) return
        val body = listOf(
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        ).filter { it.isNotBlank() }.distinct().joinToString(" ")
        if (body.isBlank()) return
        val key = MessageDigest.getInstance("SHA-256").digest("$title|$body".toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (Store.seen(this,key)) return
        Store.mark(this,key)
        val parsed = ReminderParser.parse(body) ?: return
        Notices.status(this,Scheduler.schedule(this,parsed))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id",0)
        Notices.alarm(c,id,i.getStringExtra("title") ?: "موعدك الآن")
        Store.remove(c,id)
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Scheduler.restore(c) }
}
