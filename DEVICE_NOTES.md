# Device-specific notes — Sunmi V2 Pro

These are quirks discovered while building and running this app on a real Sunmi V2 Pro. They may apply to other Sunmi devices or older Android builds.

## Android version

The V2 Pro tested here runs **Android 7.1 (API 25)**. This has several consequences:

- **Notification channels** (`Notification.Builder(context, channelId)`) do **not** work — the constructor is missing even though `SDK_INT` may report a higher value on some custom ROMs. Use the deprecated `Notification.Builder(context)` constructor instead.
- Some newer AndroidX/Play Services APIs are unavailable; the code sticks to broadly compatible calls.

## Printer SDK

- **`cutPaper()` is not supported** on this model. Calling it throws `this model does not support this method!`. `SunmiPrinter.cut()` catches this and returns `false`, so `/cut` reports honestly instead of pretending success. The cut is invoked on demand only (never as a queued job), so it can't poison the retry loop.
- **Queue retry cap.** Print jobs are retried at most `MAX_RETRIES` (5) times; after that they are marked `failed` and the user is notified on Telegram (`/retry` requeues them). Without this cap a permanently failing job — e.g. an agenda requested before Google Sign-In, or a corrupt image — would be retried every 2 seconds forever.
- **Printer state.** `updatePrinterState()` returns codes (1 normal, 4 out of paper, 5 overheated, 6 cover open). The queue checks it before printing: on an abnormal state it pauses *without* consuming retries and notifies once, then resumes when normal. Some models return nothing useful, so `paperStatus()` returns `-1` in that case and printing proceeds as before.
- **Connectivity.** `ConnectivityManager.activeNetworkInfo` is deprecated; the app uses `activeNetwork` + `getNetworkCapabilities` (available since API 21/23, fine on the V2 Pro's Android 7.1).
- The print head is **384px wide** (58mm at ~8 dots/mm). Images are resized to this width.
- The thermal printer is **1-bit** (pure black/white). Grayscale images look flat without dithering, so a **Floyd–Steinberg** dither is applied before printing.

## Google Sign-In / OAuth

- Google Sign-In on Android requires **both** an Android OAuth client *and* a Web application OAuth client in the same Google Cloud project. Without the Web client, sign-in fails with `ApiException: 10` (DEVELOPER_ERROR).
- The Android OAuth client's **package name** and **SHA-1** must match the installed APK exactly. A mismatch also produces error 10.
- When fetching the token on a background thread, `GoogleAccountCredential.selectedAccountName` can be lost, producing `IllegalArgumentException: the name must not be empty`. Setting the account explicitly with `credential.selectedAccount = Account(accountName, "com.google")` fixes this.

## Calendar

- Querying only the `primary` calendar misses events that live on other calendars (work, shared, holidays). The app lists all calendars via `calendarList().list()` and queries each one.
- **All-day events** use a `date` field (no time, no timezone) rather than `dateTime`. The Google API's time-window filter can include all-day events from the adjacent day. The app additionally filters all-day events by comparing the date string to the target day.
- The time formatter must have its timezone pinned (`TimeZone.getTimeZone("Europe/Rome")` in this build). Relying on the device default caused times to be off by one hour when the device timezone was misconfigured.

## Device timezone

- The test device defaulted to **Europe/London**. With the wrong timezone, "today" is computed incorrectly and the agenda prints the wrong day. Set the correct timezone in **Settings → Date & time**, or via ADB, and reboot.

## Build environment

- **JDK 21** requires **AGP 8.5.0** and **Gradle 8.7**. Older AGP versions fail with a `jlink` / `core-for-system-modules.jar` transform error.
- A corrupt Gradle transform cache can cause the same `jlink` error; clearing `~/.gradle/caches/transforms-3/` resolves it.
- Duplicate `META-INF/DEPENDENCIES` from the Google API client libraries must be excluded via a `packaging { resources { excludes += [...] } }` block.

## Linux / ADB

- The Sunmi enumerates over USB with Qualcomm vendor ID `05c6`. On Linux, add a udev rule so ADB can access it without root:
  ```
  SUBSYSTEM=="usb", ATTR{idVendor}=="05c6", MODE="0666", GROUP="plugdev"
  ```
  then reload udev and restart the ADB server.
