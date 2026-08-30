package com.baba.sunmiprinterbot.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.sunmi.peripheral.printer.*

class SunmiPrinter(private val context: Context) {

    private val tag = "SunmiPrinter"
    private var printerService: SunmiPrinterService? = null
    private var isConnected = false
    private val printerWidthPx = 384
    // Read from the queue thread, written from the polling thread.
    @Volatile var textSize: Float = 24f

    private val connectCallback = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiPrinterService?) {
            printerService = service
            isConnected = true
            Log.d(tag, "Printer connected")
        }
        override fun onDisconnected() {
            printerService = null
            isConnected = false
        }
    }

    fun bind() {
        try {
            InnerPrinterManager.getInstance().bindService(context, connectCallback)
        } catch (e: Exception) {
            Log.e(tag, "Bind error: " + e.message)
        }
    }

    fun unbind() {
        try {
            InnerPrinterManager.getInstance().unBindService(context, connectCallback)
        } catch (_: Exception) {}
    }

    fun isReady(): Boolean = isConnected && printerService != null

    // Sunmi updatePrinterState() codes: 1 normal, 4 out of paper, 5 overheated,
    // 6 cover open. Returns -1 if the call is unavailable.
    fun paperStatus(): Int = try {
        printerService?.updatePrinterState() ?: -1
    } catch (_: Exception) {
        -1
    }

    fun printText(text: String) {
        val svc = printerService ?: return
        svc.printerInit(null)
        svc.setAlignment(0, null)
        svc.setFontSize(textSize, null)
        val wrapped = wordWrap(text, (768 / textSize).toInt())
        svc.printText("$wrapped\n", null)
        // 130 dots + preceding \n (~30 dots) = 160 dots (~20mm) -> 1cm extra after tear bar
        feedTail(svc, 130)
    }

    fun feedPaper(mm: Int) {
        val svc = printerService ?: return
        svc.printerInit(null)
        var totalDots = mm * 8 // Sunmi V2 Pro is ~8 dots/mm (203 DPI)
        while (totalDots > 0) {
            val feed = minOf(totalDots, 255)
            svc.sendRAWData(byteArrayOf(0x1B, 0x4A, feed.toByte()), null)
            totalDots -= feed
        }
    }

    private fun feedTail(svc: SunmiPrinterService, dots: Int = 120) {
        // Advance specified dots to clear the tear bar (~10mm / 80 dots).
        // Default 120 dots (~15mm) ensures approx 5mm extra paper margin for a safe cut.
        svc.sendRAWData(byteArrayOf(0x1B, 0x4A, dots.coerceIn(0, 255).toByte()), null)
    }

    // Wraps text to fit the paper width for the current font size.
    // Words longer than a line are hyphenated.
    private fun wordWrap(text: String, maxChars: Int): String {
        val result = StringBuilder()
        for (paragraph in text.split("\n")) {
            val words = paragraph.split(" ")
            var lineLen = 0
            for (word in words) {
                if (word.length > maxChars) {
                    if (lineLen > 0) {
                        result.append("\n")
                        lineLen = 0
                    }
                    var i = 0
                    while (i < word.length) {
                        val end = minOf(i + maxChars - 1, word.length)
                        if (end < word.length && i + maxChars - 1 < word.length) {
                            result.append(word.substring(i, end) + "-")
                            result.append("\n")
                        } else {
                            result.append(word.substring(i, end))
                            lineLen = end - i
                        }
                        i = end
                    }
                } else if (lineLen == 0) {
                    result.append(word)
                    lineLen = word.length
                } else if (lineLen + 1 + word.length <= maxChars) {
                    result.append(" " + word)
                    lineLen += 1 + word.length
                } else {
                    result.append("\n" + word)
                    lineLen = word.length
                }
            }
            result.append("\n")
        }
        return result.toString().trimEnd()
    }

    fun printFormatted(title: String, lines: List<String>) {
        val svc = printerService ?: return
        
        val headerBmp = createHeaderBitmap()
        val ditheredHeader = floydSteinbergDither(headerBmp)
        
        svc.printerInit(null)
        // Set fixed line spacing for symmetry (30 dots)
        svc.sendRAWData(byteArrayOf(0x1B, 0x33, 0x1E), null)
        svc.setAlignment(1, null)
        svc.printBitmap(ditheredHeader, null)

        // 1. Dash line after logo
        svc.printText("--------------------------------\n", null)

        // 2. Title (date)
        svc.sendRAWData(byteArrayOf(0x1B, 0x45, 0x01), null)
        svc.printText("$title\n", null)
        svc.sendRAWData(byteArrayOf(0x1B, 0x45, 0x00), null)

        // 3. Dash line before content
        svc.printText("--------------------------------\n", null)
        
        svc.setAlignment(0, null)
        // 4. Uniform spacing for events
        lines.filter { it.isNotBlank() }.forEach { line ->
            val content = when {
                line.startsWith("  ") -> line
                line.startsWith("[") && line.endsWith("]") -> line
                else -> "- $line"
            }
            svc.printText(content + "\n", null)
        }
        
        svc.setAlignment(1, null)
        // Ensure no empty line before the closing divider
        svc.printText("--------------------------------\n", null)
        svc.printText("NOTES\n", null)
        svc.lineWrap(6, null)
        svc.setAlignment(1, null)
        svc.printText("--------------------------------\n", null)
        
        // Reset line spacing to default (approx 30-32 dots depending on font)
        svc.sendRAWData(byteArrayOf(0x1B, 0x32), null)
        // Keep agenda unchanged as requested (90 dots feed + preceding \n = ~120 dots total)
        feedTail(svc, 90)

        headerBmp.recycle()
        ditheredHeader.recycle()
    }

    fun printImage(imagePath: String) {
        val svc = printerService ?: return
        val original = decodeSampled(imagePath) ?: return
        val resized = resizeToPrinterWidth(original)
        val dithered = floydSteinbergDither(resized)
        svc.printerInit(null)
        svc.setAlignment(1, null)
        svc.printBitmap(dithered, null)
        // 160 dots (~20mm) -> 10mm (1cm) extra after tear bar
        feedTail(svc, 160)
        if (resized != original) resized.recycle()
        if (dithered != resized) dithered.recycle()
        original.recycle()
    }

    // Returns true only if the cut actually happened. The V2 Pro and other
    // models throw "this model does not support this method!"; report that
    // honestly instead of pretending the paper was cut.
    fun cut(): Boolean {
        val svc = printerService ?: return false
        return try {
            svc.cutPaper(null)
            true
        } catch (_: Exception) {
            Log.w(tag, "Cut not supported")
            false
        }
    }

    // Prints a QR code centered on the paper. moduleSize 1-16, the SDK clamps it.
    fun printQRCode(content: String, moduleSize: Int = 8) {
        val svc = printerService ?: return
        svc.printerInit(null)
        svc.setAlignment(1, null)
        svc.printQRCode(content, moduleSize, 3, null)
        // 120 dots (~15mm) -> 5mm extra after tear bar
        feedTail(svc)
    }

    fun printTestPage() {
        val svc = printerService ?: return
        
        val headerBmp = createHeaderBitmap()
        val ditheredHeader = floydSteinbergDither(headerBmp)

        svc.printerInit(null)
        svc.setAlignment(1, null)
        svc.printBitmap(ditheredHeader, null)
        svc.printText("--------------------------------\n", null)

        // Expanded test page with calibration markers and larger elements
        val contentH = 950
        val contentBmp = createBitmap(printerWidthPx, contentH, Bitmap.Config.RGB_565)
        val canvas = Canvas(contentBmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawColor(Color.WHITE)
        paint.color = Color.BLACK
        
        // --- 1. Calibration Corner Markers ---
        // Distance: H=40mm (320px), V=100mm (800px)
        val mRadius = 20f
        val mX1 = 32f
        val mX2 = 352f
        val mY1 = 50f
        val mY2 = 850f

        fun drawMarker(x: Float, y: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawCircle(x, y, mRadius, paint)
            canvas.drawLine(x - mRadius, y, x + mRadius, y, paint)
            canvas.drawLine(x, y - mRadius, x, y + mRadius, paint)
            paint.style = Paint.Style.FILL
            canvas.drawArc(x - mRadius, y - mRadius, x + mRadius, y + mRadius, 180f, 90f, true, paint)
            canvas.drawArc(x - mRadius, y - mRadius, x + mRadius, y + mRadius, 0f, 90f, true, paint)
        }
        drawMarker(mX1, mY1)
        drawMarker(mX2, mY1)
        drawMarker(mX1, mY2)
        drawMarker(mX2, mY2)
        
        paint.style = Paint.Style.FILL
        paint.textSize = 26f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("H: 40mm / V: 100mm", printerWidthPx / 2f, mY1 - 15, paint)

        // --- 2. Millimeter Scale Cross ---
        val cx = printerWidthPx / 2f
        val cy = 250f
        val halfLen = 180f // 22.5mm * 8 px/mm = 180px (Total 45mm cross)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawLine(cx - halfLen, cy, cx + halfLen, cy, paint)
        canvas.drawLine(cx, cy - halfLen, cx, cy + halfLen, paint)

        // Arrows
        val ar = 12f
        canvas.drawLine(cx + halfLen, cy, cx + halfLen - ar, cy - ar, paint)
        canvas.drawLine(cx + halfLen, cy, cx + halfLen - ar, cy + ar, paint)
        canvas.drawLine(cx - halfLen, cy, cx - halfLen + ar, cy - ar, paint)
        canvas.drawLine(cx - halfLen, cy, cx - halfLen + ar, cy + ar, paint)
        canvas.drawLine(cx, cy - halfLen, cx - ar, cy - halfLen + ar, paint)
        canvas.drawLine(cx, cy - halfLen, cx + ar, cy - halfLen + ar, paint)
        canvas.drawLine(cx, cy + halfLen, cx - ar, cy + halfLen - ar, paint)
        canvas.drawLine(cx, cy + halfLen, cx + ar, cy + halfLen - ar, paint)

        // Ticks and numbers every 5mm
        paint.textSize = 22f
        paint.style = Paint.Style.FILL
        for (i in -4..4) {
            val p = i * 40f
            if (i == 0) {
                canvas.drawText("0", cx + 10, cy - 10, paint)
                continue
            }
            paint.strokeWidth = 2f
            paint.style = Paint.Style.STROKE
            canvas.drawLine(cx + p, cy - 8, cx + p, cy + 8, paint)
            paint.style = Paint.Style.FILL
            canvas.drawText("${Math.abs(i * 5)}", cx + p, cy + 25, paint)
            paint.style = Paint.Style.STROKE
            canvas.drawLine(cx - 8, cy + p, cx + 8, cy + p, paint)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${Math.abs(i * 5)}", cx - 15, cy + p + 5, paint)
            paint.textAlign = Paint.Align.CENTER
        }

        // --- 3. Larger Grayscale Matrix ---
        var yPos = cy + halfLen + 60f
        val mCols = 8
        val mRows = 4
        val cellSize = 36f
        val startX = (printerWidthPx - (mCols * cellSize)) / 2f
        for (r in 0 until mRows) {
            for (c in 0 until mCols) {
                val v = (r * mCols + c) * 255 / (mCols * mRows - 1)
                paint.color = Color.rgb(v, v, v)
                paint.style = Paint.Style.FILL
                canvas.drawRect(startX + c * cellSize, yPos + r * cellSize, 
                               startX + (c + 1) * cellSize, yPos + (r + 1) * cellSize, paint)
            }
        }
        
        // --- 4. Variable Thickness Lines ---
        yPos += mRows * cellSize + 40f
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.LEFT
        for (t in listOf(1f, 2f, 4f, 8f, 12f, 20f)) {
            paint.strokeWidth = t
            paint.style = Paint.Style.STROKE
            canvas.drawLine(40f, yPos, printerWidthPx - 40f, yPos, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 20f
            canvas.drawText("${t.toInt()}px", printerWidthPx - 35f, yPos + 4, paint)
            yPos += maxOf(t + 15f, 25f)
        }

        val ditheredContent = floydSteinbergDither(contentBmp)
        svc.printBitmap(ditheredContent, null)
        // 40 dots (~5mm): Reduced tail for /test only, as requested (-10mm from standard 15mm)
        feedTail(svc, 40)

        headerBmp.recycle()
        contentBmp.recycle()
        ditheredHeader.recycle()
        ditheredContent.recycle()
    }

    private fun createHeaderBitmap(): Bitmap {
        // Tight height (60px) to minimize extra vertical space
        val h = 60
        val bmp = createBitmap(printerWidthPx, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawColor(Color.WHITE)

        val logoSize = 50f
        val text = "Abnyso"
        paint.textSize = 34f
        val textWidth = paint.measureText(text)
        val spacing = 15f
        val totalWidth = logoSize + spacing + textWidth
        
        val startX = (printerWidthPx - totalWidth) / 2f
        val logoY = 5f // Minimal top margin
        
        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL
        canvas.drawRect(startX, logoY, startX + logoSize, logoY + logoSize, paint)

        paint.color = Color.WHITE
        paint.textSize = 38f
        paint.textAlign = Paint.Align.CENTER
        val fontMetrics = paint.fontMetrics
        val textBaselineY = logoY + (logoSize / 2f) - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText("A", startX + (logoSize / 2f), textBaselineY, paint)

        paint.color = Color.BLACK
        paint.textSize = 34f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, startX + logoSize + spacing, textBaselineY, paint)

        return bmp
    }

    // Decodes only large enough to cover the printer width, halving on each
    // step, so a 12 MP photo never inflates to a full-resolution bitmap (OOM).
    private fun decodeSampled(imagePath: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= printerWidthPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(imagePath, opts)
    }

    private fun resizeToPrinterWidth(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= printerWidthPx) return bitmap
        val ratio = printerWidthPx.toFloat() / bitmap.width
        val newHeight = (bitmap.height * ratio).toInt()
        return bitmap.scale(printerWidthPx, newHeight, true)
    }

    // Floyd-Steinberg dithering: converts grayscale to 1-bit
    // Optimized with integer math for better performance on embedded hardware.
    private fun floydSteinbergDither(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // Use an IntArray for gray values to avoid float overhead, scaled by 256
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            // Luma calculation: 0.299R + 0.587G + 0.114B approximated with integers
            gray[i] = (r * 77 + g * 150 + b * 29)
        }

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val idx = rowOffset + x
                val oldVal = gray[idx]
                val newVal = if (oldVal > 32768) 65280 else 0 // 128 * 256 and 255 * 256
                gray[idx] = newVal
                val err = oldVal - newVal

                if (x + 1 < w) gray[idx + 1] += (err * 7) shr 4
                if (y + 1 < h) {
                    val nextRowOffset = rowOffset + w
                    if (x - 1 >= 0) gray[nextRowOffset + x - 1] += (err * 3) shr 4
                    gray[nextRowOffset + x] += (err * 5) shr 4
                    if (x + 1 < w) gray[nextRowOffset + x + 1] += (err * 1) shr 4
                }
            }
        }

        val result = IntArray(w * h)
        for (i in gray.indices) {
            val v = if (gray[i] > 32768) 255 else 0
            result[i] = -0x1000000 or (v shl 16) or (v shl 8) or v
        }

        val output = createBitmap(w, h, Bitmap.Config.RGB_565)
        output.setPixels(result, 0, w, 0, 0, w, h)
        return output
    }
}
