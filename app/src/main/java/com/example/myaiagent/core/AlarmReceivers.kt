package com.example.myaiagent.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myaiagent.data.ReminderDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val id = intent.getLongExtra("reminder_id", -1L)
        val message = intent.getStringExtra("message") ?: "موعدك الآن"
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AgentNotifications.fire(context, id, message)
                if (id > 0) ReminderDatabase.get(context).reminders().markDone(id)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { ReminderScheduler.restore(context) }
            finally { pending.finish() }
        }
    }
}
