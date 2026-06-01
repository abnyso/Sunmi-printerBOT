# SunmiPrinterBot

Turn a **Sunmi V2 Pro** POS terminal into a Telegram-controlled thermal printer.

Send a message, photo, or calendar command to your private Telegram bot, and the Sunmi prints it. No server, no VPS, no domain required — the app polls Telegram directly from the device.

## Features

- **Text printing** — send any text message to print it, with adjustable font size
- **Image printing** — send a photo; it is auto-resized to 384px and dithered (Floyd–Steinberg) for clean thermal output
- **Calendar agenda** — print the day's events from *all* your Google calendars
- **Date selection** — print the agenda for any specific day
- **Offline queue** — messages received while the printer is busy or offline are queued (Room/SQLite) and printed when ready
- **Word wrapping** — long text wraps cleanly, accounting for the selected font size
- **Boot persistence** — the bot restarts automatically after a device reboot
- **Fully reversible** — uninstall the APK and the POS returns to stock; no hardware modification

## Telegram commands

| Command | Action |
|---|---|
| *(any text)* | Print the text |
| *(photo)* | Print the image (auto-resized + dithered) |
| `/agenda` | Print today's events |
| `/agenda 2026-04-15` | Print events for a specific date |
| `/size` | Show current font size and options |
| `/size 32` | Set font size (range 16–48) |
| `/status` | Show system status (printer, calendar, queue) |
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
cp app/src/main/res/values/secrets.xml.template app/src/main/res/values/secrets.xml
```

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
