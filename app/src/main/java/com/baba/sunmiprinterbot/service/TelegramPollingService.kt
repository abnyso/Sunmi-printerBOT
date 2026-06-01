package com.baba.sunmiprinterbot.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.baba.sunmiprinterbot.MainActivity
import com.baba.sunmiprinterbot.R
import com.baba.sunmiprinterbot.calendar.CalendarFetcher
import com.baba.sunmiprinterbot.printer.SunmiPrinter
import com.baba.sunmiprinterbot.queue.AppDatabase
import com.baba.sunmiprinterbot.queue.PrintJob
import com.baba.sunmiprinterbot.telegram.TelegramApi
import kotlinx.coroutines.*
import java.io.File

class TelegramPollingService : Service() {

    private val TAG = "PrinterBotService"
    private val CHANNEL_ID = "printer_bot_channel"
    private val NOTIF_ID = 1

    private lateinit var telegram: TelegramApi
    private lateinit var printer: SunmiPrinter
    private lateinit var calendar: CalendarFetcher
    private lateinit var db: AppDatabase

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private var queueJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val token = getString(R.string.telegram_bot_token)
        val chatId = getString(R.string.telegram_chat_id)
        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        telegram = TelegramApi(token, chatId, prefs)
        printer = SunmiPrinter(this)
        calendar = CalendarFetcher(this)
        db = AppDatabase.get(this)
        printer.bind()
        prefs.getString("google_account", null)?.let { calendar.setup(it) }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Bot running")
        startForeground(NOTIF_ID, notification)
        startPolling()
        startQueueProcessor()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        printer.unbind()
        super.onDestroy()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            Log.d(TAG, "Polling loop started")
            while (isActive) {
                try {
                    if (isOnline()) {
                        val updates = telegram.getUpdates()
                        Log.d(TAG, "Got " + updates.size + " updates")
                        for (update in updates) {
                            processUpdate(update)
                        }
                    } else {
                        delay(5000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Polling loop error: " + e.message)
                    delay(3000)
                }
            }
        }
    }

    private suspend fun processUpdate(update: TelegramApi.TgUpdate) {
        val msg = update.message ?: return
        msg.photo?.let { photos ->
            val largest = photos.maxByOrNull { it.width * it.height } ?: return@let
            val imageDir = File(filesDir, "images").apply { mkdirs() }
            val file = telegram.downloadPhoto(largest.fileId, imageDir)
            if (file != null) {
                enqueueJob(PrintJob(type = "image", content = file.absolutePath))
                telegram.sendMessage("Image queued")
            } else {
                telegram.sendMessage("Image download error")
            }
            return
        }
        val text = msg.text ?: return
        when {
            text.startsWith("/agenda") -> {
                val parts = text.split(" ", limit = 2)
                val dateArg = parts.getOrNull(1)
                enqueueJob(PrintJob(type = "agenda", content = dateArg ?: "today"))
                telegram.sendMessage("Agenda queued")
            }
            text == "/status" -> {
                val pending = db.printJobDao().getPending().size
                val printerOk = if (printer.isReady()) "OK" else "NO"
                val calOk = if (calendar.isReady()) "OK" else "NO"
                telegram.sendMessage("Printer: " + printerOk + "\nCalendar: " + calOk + "\nQueue: " + pending)
            }
            text == "/cut" -> {
                printer.cut()
                telegram.sendMessage("Paper cut")
            }
            text.startsWith("/size") -> {
                val parts = text.split(" ", limit = 2)
                val size = parts.getOrNull(1)?.toFloatOrNull()
                if (size != null && size in 16f..48f) {
                    printer.textSize = size
                    telegram.sendMessage("Text size: " + size.toInt())
                } else {
                    telegram.sendMessage("Usage: /size 16-48" +
                            "\nCurrent: " + printer.textSize.toInt() + "\nSmall=16 Normal=24 Large=32 XL=48")
                }
            }
            text.startsWith("/") -> {
                telegram.sendMessage("Commands: /agenda [date] /status /cut /size\nOr send text or a photo to print")
            }
            else -> {
                enqueueJob(PrintJob(type = "text", content = text))
                telegram.sendMessage("Text queued")
            }
        }
    }

    private suspend fun enqueueJob(job: PrintJob) {
        db.printJobDao().insert(job)
    }

    private fun startQueueProcessor() {
        queueJob?.cancel()
        queueJob = scope.launch {
            while (isActive) {
                try {
                    if (printer.isReady()) {
                        val pending = db.printJobDao().getPending()
                        for (job in pending) {
                            val success = executeJob(job)
                            if (success) {
                                db.printJobDao().markPrinted(job.id)
                            } else {
                                db.printJobDao().incrementRetry(job.id)
                            }
                        }
                    }
                    val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
                    db.printJobDao().cleanOld(weekAgo)
                } catch (e: Exception) {
                    Log.e(TAG, "Queue error: " + e.message)
                }
                delay(2000)
            }
        }
    }

    private fun executeJob(job: PrintJob): Boolean {
        return try {
            when (job.type) {
                "text" -> { printer.printText(job.content); true }
                "image" -> { printer.printImage(job.content); true }
                "agenda" -> {
                    if (!calendar.isReady()) { false }
                    else {
                        val dateStr = if (job.content == "today") null else job.content
                        val (title, lines) = calendar.getEventsForDay(dateStr)
                        printer.printFormatted("AGENDA - " + title, lines)
                        true
                    }
                }
                else -> true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Print error: " + e.message)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val ni = cm.activeNetworkInfo
        return ni != null && ni.isConnected
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Printer Bot", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // Android 7 / Sunmi ROM: the channel-based Notification.Builder constructor
    // is unavailable, so use the deprecated single-arg constructor.
    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this)
            .setContentTitle("SunmiPrinterBot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
