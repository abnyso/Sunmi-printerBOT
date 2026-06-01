package com.baba.sunmiprinterbot.telegram

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class TelegramApi(private val token: String, private val allowedChatId: String, private val prefs: SharedPreferences) {

    private val TAG = "TelegramApi"
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private var lastUpdateId: Long = prefs.getLong("last_update_id", 0)

    private fun baseUrl(): String = "https://api.telegram.org/bot" + token

    private fun saveOffset() {
        prefs.edit().putLong("last_update_id", lastUpdateId).apply()
    }

    data class TgResponse(val ok: Boolean, val result: List<TgUpdate>?)
    data class TgUpdate(
        @SerializedName("update_id") val updateId: Long,
        val message: TgMessage?
    )
    data class TgMessage(
        @SerializedName("message_id") val messageId: Long,
        val chat: TgChat,
        val text: String?,
        val photo: List<TgPhotoSize>?
    )
    data class TgChat(val id: Long)
    data class TgPhotoSize(
        @SerializedName("file_id") val fileId: String,
        val width: Int,
        val height: Int
    )
    data class TgFileResponse(val ok: Boolean, val result: TgFile?)
    data class TgFile(
        @SerializedName("file_path") val filePath: String?
    )

    fun getUpdates(): List<TgUpdate> {
        return try {
            val offset = lastUpdateId + 1
            val url = baseUrl() + "/getUpdates?offset=" + offset + "&timeout=30"
            Log.d(TAG, "Polling offset=" + offset)
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            Log.d(TAG, "Response len=" + body.length)
            val parsed = gson.fromJson(body, TgResponse::class.java)

            val updates = parsed.result
                ?.filter { it.message?.chat?.id.toString() == allowedChatId }
                ?: emptyList()

            parsed.result?.maxByOrNull { it.updateId }?.let {
                lastUpdateId = it.updateId
                saveOffset()
            }

            updates
        } catch (e: Exception) {
            Log.e(TAG, "Polling error: " + e.message)
            emptyList()
        }
    }

    fun downloadPhoto(fileId: String, destDir: File): File? {
        return try {
            val fileUrl = baseUrl() + "/getFile?file_id=" + fileId
            val fileReq = Request.Builder().url(fileUrl).build()
            val fileResp = client.newCall(fileReq).execute()
            val fileBody = fileResp.body?.string() ?: return null
            val tgFile = gson.fromJson(fileBody, TgFileResponse::class.java)
            val filePath = tgFile.result?.filePath ?: return null

            val downloadUrl = "https://api.telegram.org/file/bot" + token + "/" + filePath
            val dlReq = Request.Builder().url(downloadUrl).build()
            val dlResp = client.newCall(dlReq).execute()
            val bytes = dlResp.body?.bytes() ?: return null

            val ext = filePath.substringAfterLast('.', "jpg")
            val dest = File(destDir, "tg_" + System.currentTimeMillis() + "." + ext)
            dest.writeBytes(bytes)
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Download photo error: " + e.message)
            null
        }
    }

    fun sendMessage(text: String) {
        try {
            val url = baseUrl() + "/sendMessage?chat_id=" + allowedChatId +
                "&text=" + java.net.URLEncoder.encode(text, "UTF-8")
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.e(TAG, "Send error: " + e.message)
        }
    }
}
