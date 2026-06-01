package com.baba.sunmiprinterbot.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import com.sunmi.peripheral.printer.*

class SunmiPrinter(private val context: Context) {

    private val TAG = "SunmiPrinter"
    private var printerService: SunmiPrinterService? = null
    private var isConnected = false
    private val PRINTER_WIDTH_PX = 384
    private val TAIL_LINES = 4
    var textSize: Float = 24f

    private val connectCallback = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiPrinterService?) {
            printerService = service
            isConnected = true
            Log.d(TAG, "Printer connected")
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
            Log.e(TAG, "Bind error: " + e.message)
        }
    }

    fun unbind() {
        try {
            InnerPrinterManager.getInstance().unBindService(context, connectCallback)
        } catch (e: Exception) {}
    }

    fun isReady(): Boolean = isConnected && printerService != null

    fun printText(text: String) {
        val svc = printerService ?: return
        svc.printerInit(null)
        svc.setAlignment(0, null)
        svc.setFontSize(textSize, null)
        val wrapped = wordWrap(text, (768 / textSize).toInt())
        svc.printText(wrapped + "\n", null)
        svc.lineWrap(TAIL_LINES, null)
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
        svc.lineWrap(TAIL_LINES, null)
    }

    fun printImage(imagePath: String) {
        val svc = printerService ?: return
        val original = BitmapFactory.decodeFile(imagePath) ?: return
        val resized = resizeToPrinterWidth(original)
        val dithered = floydSteinbergDither(resized)
        svc.printerInit(null)
        svc.setAlignment(1, null)
        svc.printBitmap(dithered, null)
        svc.lineWrap(TAIL_LINES, null)
        if (resized != original) resized.recycle()
        if (dithered != resized) dithered.recycle()
        original.recycle()
    }

    fun printBitmap(bitmap: Bitmap) {
        val svc = printerService ?: return
        val resized = resizeToPrinterWidth(bitmap)
        val dithered = floydSteinbergDither(resized)
        svc.printerInit(null)
        svc.setAlignment(1, null)
        svc.printBitmap(dithered, null)
        svc.lineWrap(TAIL_LINES, null)
        if (resized != bitmap) resized.recycle()
        if (dithered != resized) dithered.recycle()
    }

    fun cut() {
        // Not supported on all models (e.g. V2 Pro); fail silently.
        try {
            printerService?.cutPaper(null)
        } catch (e: Exception) {
            Log.w(TAG, "Cut not supported")
        }
    }

    private fun resizeToPrinterWidth(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= PRINTER_WIDTH_PX) return bitmap
        val ratio = PRINTER_WIDTH_PX.toFloat() / bitmap.width
        val newHeight = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, PRINTER_WIDTH_PX, newHeight, true)
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
                    if (x - 1 >= 0)      gray[idx + w - 1] += err * 3f / 16f
                                         gray[idx + w]     += err * 5f / 16f
                    if (x + 1 < w)       gray[idx + w + 1] += err * 1f / 16f
                }
            }
        }

        val result = IntArray(w * h)
        for (i in gray.indices) {
            val v = if (gray[i] > 128f) 255 else 0
            result[i] = Color.rgb(v, v, v)
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        output.setPixels(result, 0, w, 0, 0, w, h)
        return output
    }
}
