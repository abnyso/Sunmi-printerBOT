package com.baba.sunmiprinterbot.service

import android.app.*
import android.content.Context
import android.content.Intent
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
import java.util.Locale
import java.util.TimeZone

class TelegramPollingService : Service() {

    private val TAG = "PrinterBotService"
    private val CHANNEL_ID = "printer_bot_channel"
    private val NOTIF_ID = 1
    private val ALERT_NOTIF_ID = 2
    private val PRINTER_WIDTH_PX = 384
    private val DEFAULT_PDF_PAGES = 5

    private lateinit var telegram: TelegramApi
    private lateinit var printer: SunmiPrinter
    private lateinit var calendar: CalendarFetcher
    private lateinit var db: AppDatabase
    private lateinit var prefs: android.content.SharedPreferences

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private var queueJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val token = getString(R.string.telegram_bot_token)
        val chatId = getString(R.string.telegram_chat_id)
        prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        telegram = TelegramApi(token, chatId, prefs)
        printer = SunmiPrinter(this)
        calendar = CalendarFetcher(this)
        db = AppDatabase.get(this)
        printer.bind()
        printer.textSize = prefs.getFloat("text_size", 24f)
        calendar.zone = zone()
        prefs.getString("install_date", null) ?: prefs.edit()
            .putString("install_date", isoDate()).apply()
        prefs.getString("google_account", null)?.let { calendar.setup(it) }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Bot running"))
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
            var backoff = 3000L
            while (isActive) {
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
                                Log.e(TAG, "processUpdate error: " + e.message)
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
                        Log.e(TAG, "Fatal Telegram error " + res.code + ": " + res.description)
                        alert("Telegram error " + res.code,
                            if (res.code == 401) "Invalid bot token — check secrets.xml"
                            else "Bot blocked or forbidden (403)")
                        updateNotification("Stopped: Telegram error " + res.code)
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
            }
            text.startsWith("/qr") -> {
                val payload = text.removePrefix("/qr").trim()
                if (payload.isEmpty()) telegram.sendMessage("Usage: /qr <text or url>")
                else { enqueue(PrintJob(type = "qr", content = payload)); telegram.sendMessage("QR queued") }
            }
            text == "/status" -> {
                val pending = db.printJobDao().getPending().size
                val failed = db.printJobDao().getFailed().size
                telegram.sendMessage(
                    "Printer: " + printerStateLabel() +
                    "\nCalendar: " + (if (calendar.isReady()) "OK" else "NO") +
                    "\nQueue: " + pending + "\nFailed: " + failed +
                    "\nDaily agenda: " + dailyAgendaStatus() +
                    "\nTimezone: " + zone().id
                )
            }
            text == "/stats" -> telegram.sendMessage(statsText())
            text == "/cut" -> {
                telegram.sendMessage(if (printer.cut()) "Paper cut" else "Cut not supported on this model")
            }
            text == "/retry" -> {
                val n = db.printJobDao().retryFailed()
                telegram.sendMessage("Requeued " + n + " failed job(s)")
            }
            text == "/clearqueue" -> {
                val n = db.printJobDao().clearUnprinted()
                telegram.sendMessage("Cleared " + n + " queued job(s)")
            }
            text.startsWith("/daily") -> handleDailyCommand(text)
            text.startsWith("/tz") -> handleTzCommand(text)
            text.startsWith("/pdfpages") -> handlePdfPagesCommand(text)
            text.startsWith("/size") -> {
                val size = text.split(" ", limit = 2).getOrNull(1)?.toFloatOrNull()
                if (size != null && size in 16f..48f) {
                    printer.textSize = size
                    prefs.edit().putFloat("text_size", size).apply()
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
            }
        }
    }

    private suspend fun handleDocument(doc: TelegramApi.TgDocument) {
        val mime = doc.mimeType ?: ""
        val name = doc.fileName ?: ""
        val isPdf = mime == "application/pdf" || name.endsWith(".pdf", true)
        val isImage = mime.startsWith("image/")
        if (!isPdf && !isImage) {
            telegram.sendMessage("Unsupported file type: " + mime)
            return
        }
        val file = telegram.downloadFile(doc.fileId, imagesDir())
        if (file == null) {
            telegram.sendMessage("Download error")
            return
        }
        if (isPdf) {
            val maxPages = prefs.getInt("pdf_max_pages", DEFAULT_PDF_PAGES)
            val pages = renderPdfPages(file, maxPages)
            file.delete()
            if (pages.first.isEmpty()) {
                telegram.sendMessage("PDF render error")
            } else {
                for (png in pages.first) enqueue(PrintJob(type = "image", content = png.absolutePath))
                val truncated = if (pages.second) " (truncated to " + pages.first.size + " of more)" else ""
                telegram.sendMessage("PDF queued: " + pages.first.size + " page(s)" + truncated)
            }
        } else {
            enqueue(PrintJob(type = "image", content = file.absolutePath))
            telegram.sendMessage("Image queued")
        }
    }

    // Renders up to maxPages (0 = all) to white-background PNGs at printer width.
    // Returns the page files plus a flag telling whether more pages were skipped.
    private fun renderPdfPages(pdf: File, maxPages: Int): Pair<List<File>, Boolean> {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val out = mutableListOf<File>()
        var truncated = false
        try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val total = renderer.pageCount
            val limit = if (maxPages <= 0) total else minOf(maxPages, total)
            truncated = limit < total
            for (i in 0 until limit) {
                val page = renderer.openPage(i)
                val scale = PRINTER_WIDTH_PX.toFloat() / page.width
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(PRINTER_WIDTH_PX, h, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()
                val png = File(imagesDir(), "pdf_" + System.currentTimeMillis() + "_" + i + ".png")
                png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                out.add(png)
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF render error: " + e.message)
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
        return Pair(out, truncated)
    }

    private fun handleDailyCommand(text: String) {
        val arg = text.split(" ", limit = 2).getOrNull(1)?.trim()
        when {
            arg == null -> telegram.sendMessage("Daily agenda: " + dailyAgendaStatus() + "\nUsage: /daily 7  or  /daily off")
            arg.equals("off", true) -> {
                prefs.edit().putInt("daily_agenda_hour", -1).apply()
                telegram.sendMessage("Daily agenda disabled")
            }
            else -> {
                val hour = arg.toIntOrNull()
                if (hour != null && hour in 0..23) {
                    prefs.edit().putInt("daily_agenda_hour", hour).apply()
                    telegram.sendMessage("Daily agenda set to " + hour + ":00")
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
                prefs.edit().remove("timezone").apply()
                calendar.zone = zone()
                telegram.sendMessage("Timezone reset to device default: " + zone().id)
            }
            else -> {
                val tz = TimeZone.getTimeZone(arg)
                // getTimeZone falls back to GMT for unknown IDs; reject that unless asked.
                if (tz.id == "GMT" && !arg.equals("GMT", true) && !arg.equals("UTC", true)) {
                    telegram.sendMessage("Unknown timezone: " + arg + "\nUse an IANA id like Europe/Rome")
                } else {
                    prefs.edit().putString("timezone", tz.id).apply()
                    calendar.zone = tz
                    telegram.sendMessage("Timezone set to " + tz.id)
                }
            }
        }
    }

    private fun handlePdfPagesCommand(text: String) {
        val arg = text.split(" ", limit = 2).getOrNull(1)?.trim()
        val n = arg?.toIntOrNull()
        if (n == null || n < 0) {
            telegram.sendMessage("PDF page limit: " + prefs.getInt("pdf_max_pages", DEFAULT_PDF_PAGES) +
                    " (0 = all)\nUsage: /pdfpages 5")
        } else {
            prefs.edit().putInt("pdf_max_pages", n).apply()
            telegram.sendMessage("PDF page limit set to " + (if (n == 0) "all" else n.toString()))
        }
    }

    private fun dailyAgendaStatus(): String {
        val h = prefs.getInt("daily_agenda_hour", -1)
        return if (h < 0) "off" else h.toString() + ":00"
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
                                    telegram.sendMessage("Failed after " + MAX_RETRIES +
                                            " tries: " + jobLabel(job) + "\nUse /retry to try again")
                                }
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

    // Returns true if the printer is in a state that can print. When the head
    // reports paper out / overheat / cover open we DON'T touch the queue (no
    // retries are burned); we just notify once and wait for it to recover.
    private fun printerReadyToPrint(): Boolean {
        val st = printer.paperStatus()
        // -1 = state query unsupported on this model; assume we can print.
        if (st == -1 || st == 1) {
            if (prefs.getBoolean("printer_alerted", false)) {
                prefs.edit().putBoolean("printer_alerted", false).apply()
                telegram.sendMessage("Printer back to normal")
            }
            return true
        }
        if (!prefs.getBoolean("printer_alerted", false)) {
            prefs.edit().putBoolean("printer_alerted", true).apply()
            telegram.sendMessage("Printing paused: " + statusReason(st))
        }
        return false
    }

    private fun statusReason(code: Int): String = when (code) {
        2 -> "printer preparing"
        4 -> "OUT OF PAPER"
        5 -> "overheated"
        6 -> "cover open"
        else -> "abnormal state (" + code + ")"
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
                "agenda" -> {
                    if (!calendar.isReady()) false
                    else {
                        val (title, lines) = calendar.getAgenda(job.content)
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

    private fun cleanupJobFile(job: PrintJob) {
        if (job.type == "image") {
            try { File(job.content).delete() } catch (_: Exception) {}
        }
    }

    private fun recordStat(job: PrintJob) {
        prefs.edit()
            .putInt("stat_total", prefs.getInt("stat_total", 0) + 1)
            .putInt("stat_" + job.type, prefs.getInt("stat_" + job.type, 0) + 1)
            .apply()
    }

    private fun statsText(): String {
        val since = prefs.getString("install_date", "?")
        return "Printed since " + since + ":\n" +
                "Total: " + prefs.getInt("stat_total", 0) +
                "\nText: " + prefs.getInt("stat_text", 0) +
                "\nImages: " + prefs.getInt("stat_image", 0) +
                "\nQR: " + prefs.getInt("stat_qr", 0) +
                "\nAgenda: " + prefs.getInt("stat_agenda", 0)
    }

    private fun printedConfirmation(job: PrintJob): String = "Printed: " + jobLabel(job)

    private fun jobLabel(job: PrintJob): String = when (job.type) {
        "text" -> "text (" + job.content.take(20) + ")"
        "image" -> "image"
        "qr" -> "QR"
        "agenda" -> "agenda"
        else -> job.type
    }

    // Enqueues today's agenda once per day after the configured hour.
    private suspend fun maybeEnqueueDailyAgenda() {
        val hour = prefs.getInt("daily_agenda_hour", -1)
        if (hour < 0) return
        val cal = java.util.Calendar.getInstance(zone())
        val today = isoDate()
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) < hour) return
        if (prefs.getString("last_agenda_date", null) == today) return
        prefs.edit().putString("last_agenda_date", today).apply()
        enqueue(PrintJob(type = "agenda", content = "today"))
        telegram.sendMessage("Daily agenda queued")
    }

    private fun helpText(): String =
        "Send text, a photo, or a PDF to print.\n" +
        "/agenda [today|tomorrow|week|YYYY-MM-DD]\n" +
        "/qr <text|url>\n" +
        "/size 16-48\n" +
        "/daily <0-23|off>\n" +
        "/tz <IANA|default>\n" +
        "/pdfpages <n|0=all>\n" +
        "/status  /stats  /retry  /clearqueue  /cut"

    private fun zone(): TimeZone {
        val id = prefs.getString("timezone", null)
        return if (id != null) TimeZone.getTimeZone(id) else TimeZone.getDefault()
    }

    private fun isoDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone() }
            .format(java.util.Calendar.getInstance(zone()).time)

    private fun imagesDir(): File = File(filesDir, "images").apply { mkdirs() }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Printer Bot", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    // Local Android notification, used when Telegram itself is unreachable.
    @Suppress("DEPRECATION")
    private fun alert(title: String, text: String) {
        val n = Notification.Builder(this)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIF_ID, n)
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
