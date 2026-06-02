# SunmiPrinterBot

Turn a **Sunmi V2 Pro** POS terminal into a Telegram-controlled thermal printer.

Send a message, photo, or calendar command to your private Telegram bot, and the Sunmi prints it. No server, no VPS, no domain required — the app polls Telegram directly from the device.

## Features

- **Text printing** — send any text message to print it, with adjustable font size
- **Image printing** — send a photo; it is auto-resized to 384px and dithered (Floyd–Steinberg) for clean thermal output
- **Document / PDF printing** — send a PDF (first page is rendered) or an image file
- **QR codes** — `/qr <text|url>` prints a scannable QR code
- **Calendar agenda** — print events from *all* your Google calendars for today, tomorrow, a specific date, or the next 7 days
- **Daily agenda** — optionally auto-print today's agenda every morning at a chosen hour
- **Offline queue** — messages received while the printer is busy or offline are queued (Room/SQLite) and printed when ready, with a retry cap and failure reporting
- **Print confirmations** — the bot replies when a job is actually printed, queued, or has failed
- **Word wrapping** — long text wraps cleanly, accounting for the selected font size
- **Boot persistence** — the bot restarts automatically after a device reboot
- **Fully reversible** — uninstall the APK and the POS returns to stock; no hardware modification

## Telegram commands

| Command | Action |
|---|---|
| *(any text)* | Print the text |
| *(photo / image / PDF)* | Print it (auto-resized + dithered; PDF = first page) |
| `/agenda` | Print today's events |
| `/agenda tomorrow` | Print tomorrow's events |
| `/agenda week` | Print the next 7 days |
| `/agenda 2026-04-15` | Print events for a specific date |
| `/qr <text\|url>` | Print a QR code |
| `/size` / `/size 32` | Show / set font size (range 16–48) |
| `/daily 7` / `/daily off` | Auto-print today's agenda each morning at HH:00, or disable |
| `/status` | Show system status (printer, calendar, queue, failed) |
| `/retry` | Requeue all permanently failed jobs |
| `/clearqueue` | Drop all queued (not yet printed) jobs |
| `/cut` | Feed and cut paper (if supported by the device) |

## Requirements

- Sunmi V2 Pro (with Google Play Services)
- Android Studio (Hedgehog or newer)
- A Google account with Calendar
- A Telegram bot token (from [@BotFather](https://t.me/BotFather))

## Setup

### 1. Create a Telegram bot

1. Open Telegram, talk to **@BotFather**, send `/newbot`
2. Choose a name and a username ending in `bot`
3. Copy the **token** (e.g. `123456789:ABC-DEF...`)
4. Send any message to your new bot
5. Open `https://api.telegram.org/bot<TOKEN>/getUpdates` and find your `chat.id`

### 2. Configure Google Calendar API

1. Go to the [Google Cloud Console](https://console.cloud.google.com/) and create a project
2. Enable the **Google Calendar API**
3. Configure the **OAuth consent screen** (External), add the `calendar.readonly` scope, and add your Google address as a **test user**
4. Create an **OAuth Client ID** of type **Android**:
   - Package name: `com.baba.sunmiprinterbot`
   - SHA-1: get it with
     ```
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
     ```
5. Also create an **OAuth Client ID** of type **Web application** (required for Google Sign-In on Android; you don't need to configure it further)

### 3. Configure secrets

Copy the template and fill in your values:

```bash
cp secrets.xml.template app/src/main/res/values/secrets.xml
```

> `google_client_id` must be the **Web application** client ID — it's used as the Google Sign-In server auth code. It is read from this resource at build time, never hardcoded.

Edit `secrets.xml`:

```xml
<resources>
    <string name="telegram_bot_token">YOUR_BOT_TOKEN</string>
    <string name="telegram_chat_id">YOUR_CHAT_ID</string>
    <string name="google_client_id">YOUR_WEB_CLIENT_ID.apps.googleusercontent.com</string>
</resources>
```

> `secrets.xml` is git-ignored and will never be committed.

### 4. Build and install

Open the project in Android Studio and build, or use ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 5. First run

1. Open the app on the Sunmi
2. Tap **Sign in with Google** and authorize Calendar read-only access
3. Tap **Start Bot**
4. Send a test message to your bot

## Architecture

```
Telegram (long polling)  ──►  TelegramPollingService  ──►  Room queue  ──►  SunmiPrinter
                                       │
                                       └──►  CalendarFetcher (Google Calendar API)
```

- **TelegramPollingService** — foreground service; long-polls Telegram, routes commands, drives the print queue
- **TelegramApi** — thin Telegram Bot API client (OkHttp + Gson); persists the update offset
- **SunmiPrinter** — wraps the Sunmi Printer SDK (AIDL); handles text, formatted agenda, and dithered images
- **CalendarFetcher** — Google Calendar API client; aggregates events across all calendars
- **PrintQueue** — Room/SQLite offline queue with retry

## Project structure

```
app/src/main/java/com/baba/sunmiprinterbot/
├── MainActivity.kt
├── App.kt                      # Application + BootReceiver
├── service/
│   └── TelegramPollingService.kt
├── printer/
│   └── SunmiPrinter.kt
├── calendar/
│   └── CalendarFetcher.kt
├── queue/
│   └── PrintQueue.kt           # Room entity, DAO, database
└── telegram/
    └── TelegramApi.kt
```

## Device-specific notes

See [DEVICE_NOTES.md](DEVICE_NOTES.md) for the quirks discovered on the Sunmi V2 Pro (unsupported `cutPaper`, timezone handling, Android 7 notification API, etc.).

## License

MIT — see [LICENSE](LICENSE).
