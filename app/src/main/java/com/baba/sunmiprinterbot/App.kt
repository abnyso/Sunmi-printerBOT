package com.baba.sunmiprinterbot

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.baba.sunmiprinterbot.service.TelegramPollingService

class App : Application()

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("service_enabled", false)) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TelegramPollingService::class.java)
                )
            }
        }
    }
}
