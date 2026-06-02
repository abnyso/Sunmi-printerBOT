package com.baba.sunmiprinterbot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.baba.sunmiprinterbot.service.TelegramPollingService
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.calendar.CalendarScopes

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnGoogle: Button
    private lateinit var btnToggle: Button

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.result
            val email = account.email ?: return@registerForActivityResult
            // Salva account
            getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
                .edit().putString("google_account", email).apply()
            updateStatus()
            // Riavvia service con nuovo account
            if (isServiceRunning()) {
                stopBotService()
                startBotService()
            }
        } catch (e: Exception) {
            statusText.text = "Errore Google Sign-In: ${e.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layout programmatico (niente XML extra)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "🖨 SunmiPrinterBot"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }

        statusText = TextView(this).apply {
            text = "..."
            textSize = 14f
            setPadding(0, 0, 0, 32)
        }

        btnGoogle = Button(this).apply {
            text = "Accedi con Google"
            setOnClickListener { signInGoogle() }
        }

        btnToggle = Button(this).apply {
            text = "Avvia Bot"
            setOnClickListener { toggleService() }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(btnGoogle)
        layout.addView(btnToggle)
        setContentView(layout)

        requestNotificationPermission()
        updateStatus()
    }

    private fun signInGoogle() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(CalendarScopes.CALENDAR_READONLY))
            .requestServerAuthCode(getString(R.string.google_client_id))
            .build()
        val client = GoogleSignIn.getClient(this, gso)
        googleSignInLauncher.launch(client.signInIntent)
    }

    private fun toggleService() {
        if (isServiceRunning()) {
            stopBotService()
        } else {
            startBotService()
        }
        updateStatus()
    }

    private fun startBotService() {
        val intent = Intent(this, TelegramPollingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_enabled", true).apply()
    }

    private fun stopBotService() {
        stopService(Intent(this, TelegramPollingService::class.java))
        getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_enabled", false).apply()
    }

    private fun isServiceRunning(): Boolean {
        return getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
            .getBoolean("service_enabled", false)
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        val account = prefs.getString("google_account", null)
        val running = prefs.getBoolean("service_enabled", false)

        val tokenOk = getString(R.string.telegram_bot_token) != "YOUR_BOT_TOKEN_HERE"

        statusText.text = buildString {
            appendLine("Telegram Bot: ${if (tokenOk) "✅ configurato" else "❌ token mancante"}")
            appendLine("Google: ${account ?: "❌ non connesso"}")
            appendLine("Servizio: ${if (running) "🟢 attivo" else "⚫ fermo"}")
        }

        btnToggle.text = if (running) "Ferma Bot" else "Avvia Bot"
        btnGoogle.text = if (account != null) "Cambia account ($account)" else "Accedi con Google"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
}
