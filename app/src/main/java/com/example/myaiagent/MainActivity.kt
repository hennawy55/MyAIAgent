package com.example.myaiagent

import android.Manifest
import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private lateinit var bot: EditText
    private lateinit var sample: EditText
    private lateinit var result: TextView
    private lateinit var items: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24,24,24,24)
            setBackgroundColor(0xFF0B1020.toInt())
        }
        scroll.addView(root)
        root.addView(label("MY AI AGENT",26,0xFFFFFFFF.toInt(),true))
        root.addView(label("مساعد تذكيرات محلي — بدون سيرفر أو API",14,0xFFABB8CF.toInt()))
        root.addView(label("اسم البوت كما يظهر في إشعار Telegram",16,0xFF78A9FF.toInt(),true))
        bot = EditText(this).apply { hint="مثال: Reminder Bot"; setTextColor(-1); setHintTextColor(0xFF8B98AE.toInt()); setSingleLine(true) }
        bot.setText(Store.bot(this)); root.addView(bot)
        root.addView(btn("حفظ اسم البوت") { Store.setBot(this,bot.text.toString()); result.text="تم حفظ اسم البوت." })
        root.addView(btn("فتح صلاحية الوصول للإشعارات") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        root.addView(btn("السماح بالمنبّهات الدقيقة") { exactPermission() })
        root.addView(btn("إعدادات إشعارات التطبيق") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
        })
        root.addView(label("اختبار فهم رسالة",16,0xFF78A9FF.toInt(),true))
        sample = EditText(this).apply {
            hint="فكرني بكرة الساعة 8 مساء"; minLines=2; gravity=Gravity.TOP
            setTextColor(-1); setHintTextColor(0xFF8B98AE.toInt())
        }
        root.addView(sample)
        root.addView(btn("حلّل الرسالة وسجّل المنبّه") {
            val parsed = ReminderParser.parse(sample.text.toString())
            if (parsed == null) result.text="لم أتعرف على موعد واضح. اكتب فعل تذكير + تاريخ + وقت. حدّد صباحًا أو مساءً عند استخدام 1–12."
            else {
                result.text="${parsed.whenAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}\n${Scheduler.schedule(this,parsed)}"
                refresh()
            }
        })
        result = label("الحالة: جاهز للإعداد",14,0xFFB9C7DC.toInt())
        root.addView(result)
        root.addView(label("المواعيد المستقبلية داخل التطبيق",16,0xFF78A9FF.toInt(),true))
        items = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(items)
        root.addView(label("مواعيد اليوم تُرسل إلى تطبيق الساعة. المواعيد بعد اليوم تعمل عبر AlarmManager. تأكد من صلاحيات الإشعارات والمنبّهات الدقيقة.",12,0xFF8B98AE.toInt()))
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),44)
        refresh()
    }

    private fun exactPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms())
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName")))
            else result.text="صلاحية المنبّهات الدقيقة مفعّلة."
        } else result.text="لا تحتاج إلى صلاحية خاصة على هذا الإصدار."
    }
    private fun refresh() {
        if (!::items.isInitialized) return
        items.removeAllViews()
        val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        val rows = Store.reminders(this).filter { it.optLong("at") > System.currentTimeMillis() }.sortedBy { it.optLong("at") }
        if (rows.isEmpty()) { items.addView(label("لا توجد مواعيد مستقبلية.",13,0xFFABB8CF.toInt())); return }
        rows.forEach { o ->
            val id=o.optInt("id")
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(8,8,8,8) }
            row.addView(label(Instant.ofEpochMilli(o.optLong("at")).atZone(ZoneId.systemDefault()).format(fmt),14,-1),LinearLayout.LayoutParams(0,-2,1f))
            row.addView(btn("إلغاء") { Scheduler.cancel(this@MainActivity,id); refresh() })
            items.addView(row)
        }
    }
    private fun label(s:String,size:Int,color:Int,bold:Boolean=false)=TextView(this).apply {
        text=s; textSize=size.toFloat(); setTextColor(color); setPadding(0,10,0,10)
        if(bold) setTypeface(typeface,android.graphics.Typeface.BOLD)
    }
    private fun btn(s:String, action:()->Unit)=Button(this).apply {
        text=s; isAllCaps=false; setOnClickListener { action() }
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=4; bottomMargin=4 }
    }
}
