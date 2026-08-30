package com.baba.sunmiprinterbot.service

import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import com.baba.sunmiprinterbot.MainActivity
import com.baba.sunmiprinterbot.R
import com.baba.sunmiprinterbot.calendar.CalendarFetcher
import com.baba.sunmiprinterbot.printer.SunmiPrinter
import com.baba.sunmiprinterbot.queue.AppDatabase
import com.baba.sunmiprinterbot.queue.MAX_RETRIES
import com.baba.sunmiprinterbot.queue.PrintJob
import com.baba.sunmiprinterbot.telegram.TelegramApi
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class TelegramPollingService : Service() {

    private val tag = "PrinterBotService"
    private val channelId = "printer_bot_channel"
    private val notifId = 1
    private val alertNotifId = 2
    private val printerWidthPx = 384
    private val defaultPdfPages = 5

    private lateinit var telegram: TelegramApi
    private lateinit var printer: SunmiPrinter
    private lateinit var calendar: CalendarFetcher
    private lateinit var db: AppDatabase
    private lateinit var prefs: SharedPreferences

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var pollingJob: Job? = null
    private var queueJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SunmiPrinterBot:WakeLock")
        // Acquire the WakeLock to maintain background polling and queue processing.
        // The timeout helps avoid permanent drain if the service is killed unexpectedly.
        wakeLock?.acquire(10 * 60 * 1000L)

        val token = getString(R.string.telegram_bot_token)
        val chatId = getString(R.string.telegram_chat_id)
        prefs = getSharedPreferences("bot_prefs", MODE_PRIVATE)
        telegram = TelegramApi(token, chatId, prefs)
        printer = SunmiPrinter(this)
        calendar = CalendarFetcher(this)
        db = AppDatabase.get(this)
        printer.bind()
        printer.textSize = prefs.getFloat("text_size", 24f)
        calendar.zone = zone()
        if (prefs.getString("install_date", null) == null) {
            prefs.edit { putString("install_date", isoDate()) }
        }
        prefs.getString("google_account", null)?.let { calendar.setup(it) }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(notifId, buildNotification("Bot running"))
        startPolling()
        startQueueProcessor()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        printer.unbind()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            Log.d(tag, "Polling loop started")
            var backoff = 3000L
            while (isActive) {
                // Refresh wakeLock to ensure we don't time out while active
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(10 * 60 * 1000L)
                }

                if (!isOnline()) {
                    delay(5000)
                    continue
                }
                when (val res = telegram.getUpdates()) {
                    is TelegramApi.PollResult.Ok -> {
                        for (update in res.updates) {
                            try {
                                if (telegram.isAllowed(update)) processUpdate(update)
                            } catch (e: Exception) {
                                Log.e(tag, "processUpdate error: ${e.message}")
                            }
                            // Acknowledge after handling (even foreign updates) so a
                            // crash mid-processing re-delivers instead of dropping.
                            telegram.confirmOffset(update.updateId)
                        }
                        backoff = 3000L
                    }
                    is TelegramApi.PollResult.Fatal -> {
                        // Bad token or bot blocked: Telegram is unusable, so we
                        // can't notify over it. Raise a local Android alert and stop.
                        Log.e(tag, "Fatal Telegram error ${res.code}: ${res.description}")
                        alert("Telegram error ${res.code}",
                            if (res.code == 401) "Invalid bot token — check secrets.xml"
                            else "Bot blocked or forbidden (403)")
                        updateNotification("Stopped: Telegram error ${res.code}")
                        break
                    }
                    is TelegramApi.PollResult.Transient -> {
                        delay(backoff)
                        backoff = minOf(backoff * 2, 60000L)
                    }
                }
            }
        }
    }

    private suspend fun processUpdate(update: TelegramApi.TgUpdate) {
        val msg = update.message ?: return

        msg.photo?.let { photos ->
            val largest = photos.maxByOrNull { it.width * it.height } ?: return@let
            val file = telegram.downloadFile(largest.fileId, imagesDir())
            if (file != null) {
                enqueue(PrintJob(type = "image", content = file.absolutePath))
                telegram.sendMessage("Image queued")
            } else {
                telegram.sendMessage("Image download error")
            }
            return
        }

        msg.document?.let { doc ->
            handleDocument(doc)
            return
        }

        val text = msg.text ?: return
        when {
            text.startsWith("/agenda") -> {
                val spec = text.split(" ", limit = 2).getOrNull(1)?.trim()
                enqueue(PrintJob(type = "agenda", content = spec ?: "today"))
                telegram.sendMessage("Agenda queued")
                saveLastCommand(text)
            }
            text.startsWith("/qr") -> {
                val payload = text.removePrefix("/qr").trim()
                if (payload.isEmpty()) telegram.sendMessage("Usage: /qr <text or url>")
                else {
                    enqueue(PrintJob(type = "qr", content = payload))
                    telegram.sendMessage("QR queued")
                    saveLastCommand(text)
                }
            }
            text == "/status" -> {
                val pending = db.printJobDao().getPending().size
                val failed = db.printJobDao().getFailed().size
                telegram.sendMessage(
                    "Printer: ${printerStateLabel()}" +
                    "\nCalendar: ${if (calendar.isReady()) "OK" else "NO"}" +
                    "\nQueue: $pending\nFailed: $failed" +
                    "\nDaily agenda: ${dailyAgendaStatus()}" +
                    "\nTimezone: ${zone().id}"
                )
            }
            text == "/repeat" -> {
                val last = prefs.getString("last_command", null)
                if (last == null) telegram.sendMessage("No command to repeat")
                else {
                    telegram.sendMessage("Repeating: $last")
                    processUpdate(update.copy(message = msg.copy(text = last)))
                }
            }
            text == "/stats" -> telegram.sendMessage(statsText())
            text == "/test" -> {
                enqueue(PrintJob(type = "test", content = ""))
                telegram.sendMessage("Calibration page queued")
            }
            text.startsWith("/feed") -> {
                val arg = text.split(" ", limit = 2).getOrNull(1)?.trim()
                val mm = arg?.toIntOrNull() ?: 70
                if (mm in 1..500) {
                    enqueue(PrintJob(type = "feed", content = mm.toString()))
                    telegram.sendMessage("Feed of $mm mm queued")
                } else {
                    telegram.sendMessage("Usage: /feed [mm] (1-500). Default: 70mm")
                }
            }
            text == "/cut" -> {
                telegram.sendMessage(if (printer.cut()) "Paper cut" else "Cut not supported on this model")
            }
            text == "/retry" -> {
                val n = db.printJobDao().retryFailed()
                telegram.sendMessage("Requeued $n failed job(s)")
            }
            text == "/clearqueue" -> {
                val n = db.printJobDao().clearUnprinted()
                telegram.sendMessage("Cleared $n queued job(s)")
            }
            text.startsWith("/daily") -> handleDailyCommand(text)
            text.startsWith("/tz") -> handleTzCommand(text)
            text.startsWith("/pdfpages") -> handlePdfPagesCommand(text)
            text.startsWith("/size") -> {
                val size = text.split(" ", limit = 2).getOrNull(1)?.toFloatOrNull()
                if (size != null && size in 16f..48f) {
                    printer.textSize = size
                    prefs.edit { putFloat("text_size", size) }
                    telegram.sendMessage("Text size: " + size.toInt())
                } else {
                    telegram.sendMessage("Usage: /size 16-48\nCurrent: " + printer.textSize.toInt() +
                            "\nSmall=16 Normal=24 Large=32 XL=48")
                }
            }
            text.startsWith("/") -> telegram.sendMessage(helpText())
            else -> {
                enqueue(PrintJob(type = "text", content = text))
                telegram.sendMessage("Text queued")
                saveLastCommand(text)
            }
        }
    }

    private fun saveLastCommand(cmd: String) {
        if (!cmd.startsWith("/repeat") && !cmd.startsWith("/status") && !cmd.startsWith("/stats")) {
            prefs.edit { putString("last_command", cmd) }
        }
    }

    private suspend fun handleDocument(doc: TelegramApi.TgDocument) {
        val mime = doc.mimeType ?: ""
        val name = doc.fileName ?: ""
        val isPdf = mime == "application/pdf" || name.endsWith(".pdf", true)
        val isImage = mime.startsWith("image/")
        if (!isPdf && !isImage) {
            telegram.sendMessage("Unsupported file type: $mime")
            return
        }
        val file = telegram.downloadFile(doc.fileId, imagesDir())
        if (file == null) {
            telegram.sendMessage("Download error")
            return
        }
        if (isPdf) {
            val maxPages = prefs.getInt("pdf_max_pages", defaultPdfPages)
            val res = renderPdfPages(file, maxPages)
            file.delete()
            val pages = res.first
            val total = res.second
            if (pages.isEmpty()) {
                telegram.sendMessage("PDF render error")
            } else {
                for (png in pages) enqueue(PrintJob(type = "image", content = png.absolutePath))
                val truncated = if (total > pages.size) " (truncated: printing " + pages.size + " of " + total + " pages)" else ""
                telegram.sendMessage("PDF queued: " + pages.size + " page(s)" + truncated)
            }
        } else {
            enqueue(PrintJob(type = "image", content = file.absolutePath))
            telegram.sendMessage("Image queued")
        }
    }

    // Renders up to maxPages (0 = all) to white-background PNGs at printer width.
    // Returns the page files plus the total page count found in the PDF.
    private fun renderPdfPages(pdf: File, maxPages: Int): Pair<List<File>, Int> {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val out = mutableListOf<File>()
        var total = 0
        try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            total = renderer.pageCount
            val limit = if (maxPages <= 0) total else minOf(maxPages, total)
            for (i in 0 until limit) {
                val page = renderer.openPage(i)
                val scale = printerWidthPx.toFloat() / page.width
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = createBitmap(printerWidthPx, h, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()
                val png = File(imagesDir(), "pdf_" + System.currentTimeMillis() + "_" + i + ".png")
                png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                out.add(png)
            }
        } catch (e: Exception) {
            Log.e(tag, "PDF render error: ${e.message}")
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
        return Pair(out, total)
    }

    private fun handleDailyCommand(text: String) {
        val arg = text.split(" ", limit = 2).getOrNull(1)?.trim()
        when {
            arg == null -> telegram.sendMessage("Daily agenda: " + dailyAgendaStatus() + "\nUsage: /daily 7  or  /daily off")
            arg.equals("off", true) -> {
                prefs.edit { putInt("daily_agenda_hour", -1) }
                telegram.sendMessage("Daily agenda disabled")
            }
            else -> {
                val hour = arg.toIntOrNull()
                if (hour != null && hour in 0..23) {
                    prefs.edit { putInt("daily_agenda_hour", hour) }
                    telegram.sendMessage("Daily agenda set to $hour:00")
                } else {
                    telegram.sendMessage("Usage: /daily 0-23  or  /daily off")
                }
            }
        }
    }

    private fun handleTzCommand(text: String) {
        val arg = text.split(" ", limit = 2).getOrNull(1)?.trim()
        when {
            arg == null -> telegram.sendMessage("Timezone: " + zone().id +
                    "\nUsage: /tz Europe/Rome  or  /tz default")
            arg.equals("default", true) -> {
                prefs.edit { remove("timezone") }
                calendar.zone = zone()
                telegram.sendMessage("Timezone reset to device default: " + zone().id)
            }
            else -> {
                val tz = TimeZone.getTimeZone(arg)
                // getTimeZone falls back to GMT for unknown IDs; reject that unless asked.
                if (tz.id == "GMT" && !arg.equals("GMT", true) && !arg.equals("UTC", true)) {
                    telegram.sendMessage("Unknown timezone: $arg\nUse an IANA id like Europe/Rome")
                } else {
                    prefs.edit { putString("timezone", tz.id) }
                    calendar.zone = tz
                    telegram.sendMessage("Timezone set to ${tz.id}")
                }
            }
        }
    }

    private fun handlePdfPagesCommand(text: String) {
        val arg = text.split(" ", limit = 2).getOrNull(1)?.trim()
        val n = arg?.toIntOrNull()
        if (n == null || n < 0) {
            telegram.sendMessage("PDF page limit: ${prefs.getInt("pdf_max_pages", defaultPdfPages)} (0 = all)\nUsage: /pdfpages 5")
        } else {
            prefs.edit { putInt("pdf_max_pages", n) }
            telegram.sendMessage("PDF page limit set to ${if (n == 0) "all" else n.toString()}")
        }
    }

    private fun dailyAgendaStatus(): String {
        val h = prefs.getInt("daily_agenda_hour", -1)
        return if (h < 0) "off" else "$h:00"
    }

    private suspend fun enqueue(job: PrintJob) {
        db.printJobDao().insert(job)
    }

    private fun startQueueProcessor() {
        queueJob?.cancel()
        queueJob = scope.launch {
            while (isActive) {
                try {
                    maybeEnqueueDailyAgenda()
                    if (printer.isReady() && printerReadyToPrint()) {
                        for (job in db.printJobDao().getPending()) {
                            if (executeJob(job)) {
                                db.printJobDao().markPrinted(job.id)
                                cleanupJobFile(job)
                                recordStat(job)
                                telegram.sendMessage(printedConfirmation(job))
                            } else {
                                db.printJobDao().incrementRetry(job.id)
                                if (job.retryCount + 1 >= MAX_RETRIES) {
                                    db.printJobDao().markFailed(job.id)
                                    cleanupJobFile(job)
                                    telegram.sendMessage("Failed after $MAX_RETRIES tries: ${jobLabel(job)}\nUse /retry to try again")
                                }
                            }
                        }
                    }
                    val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
                    db.printJobDao().cleanOld(weekAgo)
                } catch (e: Exception) {
                    Log.e(tag, "Queue error: ${e.message}")
                }
                delay(2000)
            }
        }
    }

    // Returns true if the printer is in a state that can print. When the head
    // reports paper out / overheat / cover open we DON'T touch the queue (no
    // retries are burned); we just notify once and wait for it to recover.
    private fun printerReadyToPrint(): Boolean {
        val st = printer.paperStatus()
        // -1 = state query unsupported on this model; assume we can print.
        if (st == -1 || st == 1) {
            if (prefs.getBoolean("printer_alerted", false)) {
                prefs.edit { putBoolean("printer_alerted", false) }
                telegram.sendMessage("Printer back to normal")
            }
            return true
        }
        if (!prefs.getBoolean("printer_alerted", false)) {
            prefs.edit { putBoolean("printer_alerted", true) }
            telegram.sendMessage("Printing paused: " + statusReason(st))
        }
        return false
    }

    private fun statusReason(code: Int): String = when (code) {
        2 -> "printer preparing"
        4 -> "OUT OF PAPER"
        5 -> "overheated"
        6 -> "cover open"
        else -> "abnormal state ($code)"
    }

    private fun printerStateLabel(): String {
        if (!printer.isReady()) return "NO"
        val st = printer.paperStatus()
        return if (st == -1 || st == 1) "OK" else statusReason(st)
    }

    private fun executeJob(job: PrintJob): Boolean {
        return try {
            when (job.type) {
                "text" -> { printer.printText(job.content); true }
                "image" -> { printer.printImage(job.content); true }
                "qr" -> { printer.printQRCode(job.content); true }
                "test" -> { printer.printTestPage(); true }
                "feed" -> { printer.feedPaper(job.content.toIntOrNull() ?: 70); true }
                "agenda" -> {
                    if (!calendar.isReady()) false
                    else {
                        val (title, lines) = calendar.getAgenda(job.content)
                        if (title == "Error") false
                        else {
                            printer.printFormatted(title, lines)
                            true
                        }
                    }
                }
                else -> true
            }
        } catch (e: Exception) {
            Log.e(tag, "Print error: ${e.message}")
            false
        }
    }

    private fun cleanupJobFile(job: PrintJob) {
        if (job.type == "image") {
            try { File(job.content).delete() } catch (_: Exception) {}
        }
    }

    private fun recordStat(job: PrintJob) {
        prefs.edit {
            putInt("stat_total", prefs.getInt("stat_total", 0) + 1)
            putInt("stat_${job.type}", prefs.getInt("stat_${job.type}", 0) + 1)
        }
    }

    private fun statsText(): String {
        val since = prefs.getString("install_date", "?")
        return "Printed since $since:\n" +
                "Total: ${prefs.getInt("stat_total", 0)}" +
                "\nText: ${prefs.getInt("stat_text", 0)}" +
                "\nImages: ${prefs.getInt("stat_image", 0)}" +
                "\nQR: ${prefs.getInt("stat_qr", 0)}" +
                "\nAgenda: ${prefs.getInt("stat_agenda", 0)}"
    }

    private fun printedConfirmation(job: PrintJob): String = "Printed: ${jobLabel(job)}"

    private fun jobLabel(job: PrintJob): String = when (job.type) {
        "text" -> "text (${job.content.take(20)})"
        "image" -> "image"
        "qr" -> "QR"
        "test" -> "calibration"
        "feed" -> "paper feed (${job.content}mm)"
        "agenda" -> "agenda"
        else -> job.type
    }

    // Enqueues today's agenda once per day after the configured hour.
    private suspend fun maybeEnqueueDailyAgenda() {
        val hour = prefs.getInt("daily_agenda_hour", -1)
        if (hour < 0) return
        val cal = Calendar.getInstance(zone())
        val today = isoDate()
        if (cal.get(Calendar.HOUR_OF_DAY) < hour) return
        if (prefs.getString("last_agenda_date", null) == today) return
        prefs.edit { putString("last_agenda_date", today) }
        enqueue(PrintJob(type = "agenda", content = "today"))
        telegram.sendMessage("Daily agenda queued")
    }

    private fun helpText(): String =
        "Send text, a photo, or a PDF to print.\n" +
        "/agenda [today|tomorrow|week|YYYY-MM-DD]\n" +
        "/qr <text|url>\n" +
        "/repeat\n" +
        "/size 16-48\n" +
        "/daily <0-23|off>\n" +
        "/tz <IANA|default>\n" +
        "/pdfpages <n|0=all>\n" +
        "/status  /stats  /test  /feed [mm]  /retry  /clearqueue  /cut"

    private fun zone(): TimeZone {
        val id = prefs.getString("timezone", null)
        return if (id != null) TimeZone.getTimeZone(id) else TimeZone.getDefault()
    }

    private fun isoDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone() }
            .format(Calendar.getInstance(zone()).time)

    private fun imagesDir(): File = File(filesDir, "images").apply { mkdirs() }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Printer Bot", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(notifId, buildNotification(text))
    }

    // Local Android notification, used when Telegram itself is unreachable.
    @Suppress("DEPRECATION")
    private fun alert(title: String, text: String) {
        val n = Notification.Builder(this)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .build()
        getSystemService(NotificationManager::class.java).notify(alertNotifId, n)
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
