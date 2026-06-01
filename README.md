# SunmiPrinterBot

App Android per Sunmi V2 Pro che trasforma il POS in una stampante smart controllata via Telegram.

## Funzionalità
- **Stampa testo** → invia un messaggio al bot Telegram, viene stampato
- **Stampa immagini** → invia una foto, viene ridimensionata a 384px e stampata
- **Stampa agenda** → comando `/agenda` o `/agenda 2026-04-10` per stampare gli eventi del giorno
- **Coda offline** → se il POS è offline, i messaggi vengono accodati e stampati alla riconnessione
- **Reversibile al 100%** → disinstalla l'APK e il POS torna originale

## Prerequisiti
- Android Studio (Hedgehog o successivo)
- Sunmi V2 Pro con Google Play Services
- Account Google con Calendar
- Token Telegram Bot (da @BotFather)

## Setup

### 1. Crea il Telegram Bot
1. Apri Telegram → cerca `@BotFather`
2. `/newbot` → scegli nome e username
3. Copia il **token** (es. `123456:ABC-DEF...`)
4. Invia un messaggio al bot dal tuo account
5. Vai su `https://api.telegram.org/bot<TOKEN>/getUpdates` → copia il tuo `chat_id`

### 2. Configura Google Calendar API
1. Vai su [Google Cloud Console](https://console.cloud.google.com/)
2. Crea un nuovo progetto
3. Abilita **Google Calendar API**
4. Crea credenziali → **OAuth 2.0 Client ID** → tipo **Android**
5. Package name: `com.baba.sunmiprinterbot`
6. SHA-1: ottienilo con:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
   ```
7. Scarica `credentials.json` (non serve nel progetto, l'OAuth è on-device via Google Sign-In)

### 3. Build & Install
1. Apri il progetto in Android Studio
2. Inserisci il token Telegram e il tuo chat_id in `app/src/main/res/values/secrets.xml`
3. Build → genera APK
4. Installa sul Sunmi V2 Pro via ADB o file manager

### 4. Primo avvio
1. Apri l'app → premi "Accedi con Google" → autorizza Calendar read-only
2. L'app mostra "Bot attivo" → il service gira in background
3. Testa: invia un messaggio al bot su Telegram

## Comandi Telegram
| Comando | Azione |
|---|---|
| qualsiasi testo | Stampa il testo |
| foto/immagine | Stampa l'immagine (auto-resize 384px) |
| `/agenda` | Stampa eventi di oggi |
| `/agenda 2026-04-10` | Stampa eventi della data specificata |
| `/status` | Risponde con stato connessione e coda |
| `/cut` | Avanza e taglia la carta |

## Struttura progetto
```
app/src/main/java/com/baba/sunmiprinterbot/
├── MainActivity.kt              # UI minimale + Google Sign-In
├── service/
│   ├── TelegramPollingService.kt # Long polling Telegram
│   └── PrintQueueService.kt     # Gestione coda offline
├── printer/
│   └── SunmiPrinter.kt          # Wrapper Sunmi SDK
├── calendar/
│   └── CalendarFetcher.kt       # Google Calendar API
├── queue/
│   ├── PrintJob.kt              # Entity Room
│   ├── PrintJobDao.kt           # DAO
│   └── AppDatabase.kt           # Room database
└── telegram/
    └── TelegramApi.kt           # Client API Telegram
```
