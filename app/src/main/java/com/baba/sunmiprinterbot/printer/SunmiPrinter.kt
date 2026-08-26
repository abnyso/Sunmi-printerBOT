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
    private val tailLines = 4
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
        svc.printText(wrapped + "\n", null)
        svc.lineWrap(tailLines, null)
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
        svc.printerInit(null)
        svc.setAlignment(1, null)
        svc.sendRAWData(byteArrayOf(0x1B, 0x45, 0x01), null)
        svc.printText(title + "\n", null)
        svc.sendRAWData(byteArrayOf(0x1B, 0x45, 0x00), null)
        svc.printText("--------------------------------\n", null)
        svc.setAlignment(0, null)
        for (line in lines) {
            svc.printText("- " + line + "\n", null)
        }
        svc.printText("\n", null)
        svc.setAlignment(1, null)
        svc.printText("--------------------------------\n", null)
        svc.printText("NOTES\n", null)
        svc.lineWrap(6, null)
        svc.setAlignment(1, null)
        svc.printText("--------------------------------\n", null)
        svc.lineWrap(tailLines, null)
    }

    fun printImage(imagePath: String) {
        val svc = printerService ?: return
        val original = decodeSampled(imagePath) ?: return
        val resized = resizeToPrinterWidth(original)
        val dithered = floydSteinbergDither(resized)
        svc.printerInit(null)
        svc.setAlignment(1, null)
        svc.printBitmap(dithered, null)
        svc.lineWrap(tailLines, null)
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
        svc.lineWrap(tailLines, null)
    }

    fun printTestPage() {
        val svc = printerService ?: return
        val h = 800
        val bmp = createBitmap(printerWidthPx, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        val paint = Paint()

        // Background white
        canvas.drawColor(Color.WHITE)
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f

        // 1. Four corner targets (rectangles)
        val targetSize = 20f
        canvas.drawRect(0f, 0f, targetSize, targetSize, paint) // Top-left
        canvas.drawRect(printerWidthPx - targetSize, 0f, printerWidthPx.toFloat(), targetSize, paint) // Top-right
        canvas.drawRect(0f, h - targetSize, targetSize, h.toFloat(), paint) // Bottom-left
        canvas.drawRect(printerWidthPx - targetSize, h - targetSize, printerWidthPx.toFloat(), h.toFloat(), paint) // Bottom-right

        // Crosshairs in corners
        canvas.drawLine(targetSize/2, 0f, targetSize/2, targetSize, paint)
        canvas.drawLine(0f, targetSize/2, targetSize, targetSize/2, paint)
        canvas.drawLine(printerWidthPx - targetSize/2, 0f, printerWidthPx - targetSize/2, targetSize, paint)
        canvas.drawLine(printerWidthPx - targetSize, targetSize/2, printerWidthPx.toFloat(), targetSize/2, paint)

        // 2. Variable thickness lines (Horizontal)
        var currentY = 50f
        paint.style = Paint.Style.FILL
        val thicknesses = listOf(1f, 2f, 4f, 8f, 12f)
        for (t in thicknesses) {
            paint.strokeWidth = t
            canvas.drawLine(targetSize, currentY, printerWidthPx - targetSize, currentY, paint)
            currentY += 30f
        }

        // 3. Grayscale gradient (10 steps)
        currentY += 20f
        val steps = 10
        val stepHeight = 40f
        for (i in 0 until steps) {
            val gray = (255 * i / (steps - 1))
            paint.color = Color.rgb(gray, gray, gray)
            canvas.drawRect(targetSize, currentY, printerWidthPx - targetSize, currentY + stepHeight, paint)
            currentY += stepHeight
        }

        // 4. Variable thickness lines (Vertical)
        currentY += 40f
        paint.color = Color.BLACK
        var currentX = targetSize + 20f
        for (t in thicknesses) {
            paint.strokeWidth = t
            canvas.drawLine(currentX, currentY, currentX, currentY + 100f, paint)
            currentX += 40f
        }

        // Label
        paint.strokeWidth = 1f
        paint.textSize = 20f
        canvas.drawText("SUNMI V2 PRO CALIBRATION", 50f, currentY + 140f, paint)

        // Dither and print
        val dithered = floydSteinbergDither(bmp)
        svc.printerInit(null)
        svc.setAlignment(1, null)
        svc.printBitmap(dithered, null)
        svc.lineWrap(tailLines, null)

        bmp.recycle()
        dithered.recycle()
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
    // so photos print with the illusion of shading on a black/white head.
    private fun floydSteinbergDither(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val oldVal = gray[idx]
                val newVal = if (oldVal > 128f) 255f else 0f
                gray[idx] = newVal
                val err = oldVal - newVal

                if (x + 1 < w)           gray[idx + 1]     += err * 7f / 16f
                if (y + 1 < h) {
                    if (x - 1 >= 0) {
                        gray[idx + w - 1] += err * 3f / 16f
                    }
                    gray[idx + w] += err * 5f / 16f
                    if (x + 1 < w) {
                        gray[idx + w + 1] += err * 1f / 16f
                    }
                }
            }
        }

        val result = IntArray(w * h)
        for (i in gray.indices) {
            val v = if (gray[i] > 128f) 255 else 0
            result[i] = Color.rgb(v, v, v)
        }

        val output = createBitmap(w, h, Bitmap.Config.RGB_565)
        output.setPixels(result, 0, w, 0, 0, w, h)
        return output
    }
}
