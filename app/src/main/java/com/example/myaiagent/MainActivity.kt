package com.example.myaiagent

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.AlarmClock
import android.provider.Settings.Secure
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.myaiagent.core.AgentPreferences
import com.example.myaiagent.core.ParseResult
import com.example.myaiagent.core.ReminderParser
import com.example.myaiagent.core.ReminderScheduler
import com.example.myaiagent.core.ScheduleResult
import com.example.myaiagent.data.ReminderDatabase
import com.example.myaiagent.data.ReminderEntity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Page = Color(0xFF0B1020)
private val Panel = Color(0xFF151D32)
private val PanelSoft = Color(0xFF1B2740)
private val Accent = Color(0xFF9B8CFF)
private val AccentSoft = Color(0xFF29264D)
private val TextMain = Color(0xFFF4F5FC)
private val TextMuted = Color(0xFFAAB4CC)
private val Good = Color(0xFF55D6A1)
private val Warning = Color(0xFFFFC66D)
private val Danger = Color(0xFFFF7B85)

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private var permissionRefresh by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        permissionRefresh++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AgentScreen(
            permissionRefresh = permissionRefresh,
            onOpenListenerSettings = { openListenerSettings() },
            onOpenExactAlarmSettings = { openExactAlarmSettings() },
            onRequestNotificationPermission = { requestNotificationPermission() },
            onOpenAppNotificationSettings = { openAppNotificationSettings() },
            notificationEnabled = { hasNotificationPermission() },
            exactAlarmEnabled = { hasExactAlarmPermission() },
            listenerEnabled = { isListenerEnabled() }
        ) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun hasExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    private fun isListenerEnabled(): Boolean {
        val enabled = Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(packageName)
    }

    private fun openListenerSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun openAppNotificationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentScreen(
    permissionRefresh: Int,
    onOpenListenerSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    notificationEnabled: () -> Boolean,
    exactAlarmEnabled: () -> Boolean,
    listenerEnabled: () -> Boolean
) {
    val context = LocalContext.current
    val dao = remember { ReminderDatabase.get(context).reminders() }
    val reminders by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var botName by remember { mutableStateOf(AgentPreferences.botName(context)) }
    var botSaved by remember { mutableStateOf(AgentPreferences.botName(context).isNotBlank()) }
    var sample by remember { mutableStateOf("فكرني بكرة الساعة 8 مساءً") }
    var parseResult by remember { mutableStateOf<ParseResult?>(null) }
    var statusMessage by remember { mutableStateOf("جاهز. ابدأ بإعداد اسم البوت والصلاحيات.") }
    var statusIsError by remember { mutableStateOf(false) }
    val notifOk = remember(permissionRefresh) { notificationEnabled() }
    val exactOk = remember(permissionRefresh) { exactAlarmEnabled() }
    val listenerOk = remember(permissionRefresh) { listenerEnabled() }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            containerColor = Page,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("مساعد التذكيرات", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text("Telegram • يعمل على جهازك", color = TextMuted, fontSize = 12.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Page)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HeroCard(
                    ready = botSaved && listenerOk && exactOk,
                    listenerOk = listenerOk,
                    exactOk = exactOk
                )

                SectionTitle("إعداد بوت Telegram", "اكتب الاسم كما يظهر في عنوان إشعار البوت")
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = botName,
                            onValueChange = { botName = it; botSaved = false },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("اسم البوت") },
                            placeholder = { Text("مثال: My Reminder Bot") },
                            colors = fieldColors()
                        )
                        Button(
                            onClick = {
                                AgentPreferences.setBotName(context, botName)
                                botSaved = botName.isNotBlank()
                                statusIsError = !botSaved
                                statusMessage = if (botSaved) "تم حفظ اسم البوت." else "اكتب اسم البوت أولًا."
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = botName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)
                        ) { Text("حفظ اسم البوت") }
                        Text("المساعد يعالج إشعارات البوت المطابق فقط، ولا يرسل بياناتك إلى أي خادم.", color = TextMuted, fontSize = 12.sp)
                    }
                }

                SectionTitle("حالة الصلاحيات", "فعّل العناصر الثلاثة حتى يعمل الاستقبال والتنبيه")
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PermissionRow("الوصول إلى إشعارات Telegram", listenerOk, "فتح إعدادات الوصول") {
                            onOpenListenerSettings()
                        }
                        HorizontalDivider(color = PanelSoft)
                        PermissionRow("المنبّهات الدقيقة", exactOk, "تفعيل المنبّهات") {
                            onOpenExactAlarmSettings()
                        }
                        HorizontalDivider(color = PanelSoft)
                        PermissionRow("إشعارات التطبيق", notifOk, "السماح بالإشعارات") {
                            if (!notifOk) onRequestNotificationPermission() else onOpenAppNotificationSettings()
                        }
                        Text("بعد الرجوع من إعدادات Android، افتح التطبيق مرة أخرى لتحديث حالة الصلاحيات.", color = TextMuted, fontSize = 11.sp)
                    }
                }

                SectionTitle("اختبار فهم الرسالة", "الاختبار لا يضبط منبّهًا إلا بعد الضغط على تسجيل")
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = sample,
                            onValueChange = { sample = it; parseResult = null },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            label = { Text("اكتب رسالة التذكير") },
                            placeholder = { Text("مثال: فكرني بكرة الساعة 8:30 مساءً") },
                            colors = fieldColors()
                        )
                        OutlinedButton(
                            onClick = { parseResult = ReminderParser.parse(sample) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                        ) { Text("حلّل الرسالة") }

                        when (val result = parseResult) {
                            is ParseResult.Success -> {
                                val formatted = result.reminder.dateTime.format(
                                    DateTimeFormatter.ofPattern("EEEE، d MMMM yyyy - hh:mm a", Locale("ar"))
                                )
                                Surface(color = AccentSoft, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("تم فهم الموعد", color = Good, fontWeight = FontWeight.Bold)
                                        Text(formatted, color = TextMain, fontWeight = FontWeight.SemiBold)
                                        Text("النص: ${result.reminder.originalText}", color = TextMuted, fontSize = 12.sp)
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    when (val scheduled = ReminderScheduler.create(context, result.reminder, "اختبار يدوي")) {
                                                        is ScheduleResult.Created -> {
                                                            statusMessage = scheduled.message
                                                            statusIsError = false
                                                            parseResult = null
                                                        }
                                                        is ScheduleResult.Failed -> {
                                                            statusMessage = scheduled.message
                                                            statusIsError = true
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)
                                        ) { Text("تسجيل المنبّه") }
                                    }
                                }
                            }
                            is ParseResult.Failure -> {
                                Surface(color = Color(0xFF35232D), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                    Text(result.reason, color = Warning, modifier = Modifier.padding(13.dp), fontSize = 13.sp)
                                }
                            }
                            null -> Text("جرّب: «فكرني بكرة الساعة 8 مساءً» أو «اجتماع يوم 25/12/2026 الساعة 18:30».", color = TextMuted, fontSize = 12.sp)
                        }
                    }
                }

                if (statusMessage.isNotBlank()) {
                    Surface(
                        color = if (statusIsError) Color(0xFF35232D) else PanelSoft,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(statusMessage, color = if (statusIsError) Warning else TextMain, modifier = Modifier.padding(14.dp), fontSize = 13.sp)
                    }
                }

                SectionTitle("سجل التذكيرات", "${reminders.size} تذكير محفوظ")
                if (reminders.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Text("لا توجد تذكيرات حتى الآن.", color = TextMuted, modifier = Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center)
                    }
                } else {
                    reminders.sortedByDescending { it.fireAtMillis }.take(30).forEach { item ->
                        ReminderRow(
                            item = item,
                            onDelete = {
                                scope.launch {
                                    if (item.deliveryMode == "INTERNAL") {
                                        val manager = context.getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager
                                        val intent = Intent(context, com.example.myaiagent.core.AlarmReceiver::class.java).apply {
                                            data = Uri.parse("myaiagent://reminder/${item.id}")
                                        }
                                        val pi = android.app.PendingIntent.getBroadcast(
                                            context, item.id.toInt(), intent,
                                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                                        )
                                        manager.cancel(pi)
                                    }
                                    dao.delete(item.id)
                                }
                            }
                        )
                    }
                }

                Text(
                    "ملاحظة: مواعيد اليوم تُرسل إلى تطبيق الساعة إذا سمح Android بذلك، وإذا تعذر ذلك يستخدم التطبيق منبّهًا داخليًا. المواعيد المستقبلية تُدار بواسطة AlarmManager.",
                    color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun HeroCard(ready: Boolean, listenerOk: Boolean, exactOk: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = PanelSoft), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = AccentSoft, shape = RoundedCornerShape(15.dp)) {
                    Text("AI", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("مساعدك جاهز للتذكير", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("قراءة محلية • بدون حسابات • بدون API", color = TextMuted, fontSize = 12.sp)
                }
                Surface(color = if (ready) Color(0xFF173C35) else Color(0xFF493821), shape = RoundedCornerShape(50)) {
                    Text(if (ready) "جاهز" else "إعداد مطلوب", color = if (ready) Good else Warning, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Text(
                "يراقب إشعارات البوت الذي تحدده، يستخرج الموعد، ثم ينشئ منبّهًا على هاتفك.",
                color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TinyStatus("Telegram", listenerOk)
                TinyStatus("المنبّهات", exactOk)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun TinyStatus(label: String, ok: Boolean) {
    Surface(color = if (ok) Color(0xFF173C35) else Color(0xFF493821), shape = RoundedCornerShape(50)) {
        Text("$label • ${if (ok) "مفعّل" else "غير مفعّل"}", color = if (ok) Good else Warning, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun PermissionRow(title: String, enabled: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(if (enabled) "مفعّل" else "مطلوب", color = if (enabled) Good else Warning, fontSize = 11.sp)
        }
        TextButton(onClick = onClick) { Text(action, color = Accent, fontSize = 11.sp) }
    }
}

@Composable
private fun ReminderRow(item: ReminderEntity, onDelete: () -> Unit) {
    val date = Instant.ofEpochMilli(item.fireAtMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE d MMM • hh:mm a", Locale("ar")))
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(17.dp)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(date, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(item.message, color = TextMuted, fontSize = 11.sp, maxLines = 2)
                Text(
                    when {
                        item.status == "DONE" -> "اكتمل"
                        item.deliveryMode == "CLOCK" && item.fireAtMillis <= System.currentTimeMillis() -> "موعد سابق • راجع تطبيق الساعة"
                        item.deliveryMode == "CLOCK" -> "مُرسل إلى تطبيق الساعة"
                        else -> "منبّه داخلي"
                    },
                    color = if (item.status == "DONE") Good else Accent,
                    fontSize = 10.sp
                )
            }
            if (item.deliveryMode == "INTERNAL") {
                TextButton(onClick = onDelete) { Text("حذف", color = Danger, fontSize = 12.sp) }
            } else {
                Text("الساعة", color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextMain,
    unfocusedTextColor = TextMain,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Color(0xFF39445E),
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextMuted,
    cursorColor = Accent,
    focusedPlaceholderColor = TextMuted,
    unfocusedPlaceholderColor = TextMuted
)
