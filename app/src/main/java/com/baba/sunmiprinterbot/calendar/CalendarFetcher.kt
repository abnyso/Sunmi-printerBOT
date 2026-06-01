package com.baba.sunmiprinterbot.calendar

import android.accounts.Account
import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import java.text.SimpleDateFormat
import java.util.*
import java.util.TimeZone

class CalendarFetcher(private val context: Context) {

    private val TAG = "CalendarFetcher"
    private var calendarService: Calendar? = null

    fun setup(accountName: String) {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(CalendarScopes.CALENDAR_READONLY)
            )
            // Set the account explicitly; selectedAccountName can be lost on a background thread.
            credential.selectedAccount = Account(accountName, "com.google")
            calendarService = Calendar.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("SunmiPrinterBot").build()
            Log.d(TAG, "Calendar setup OK for " + accountName)
        } catch (e: Exception) {
            Log.e(TAG, "Setup error: " + e.message, e)
        }
    }

    fun isReady(): Boolean = calendarService != null

    fun getEventsForDay(dateStr: String? = null): Pair<String, List<String>> {
        val svc = calendarService ?: return Pair("Error", listOf("Calendar not configured"))

        val cal = java.util.Calendar.getInstance()
        if (dateStr != null) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                cal.time = sdf.parse(dateStr) ?: cal.time
            } catch (e: Exception) {
                Log.e(TAG, "Date parse error: " + e.message)
            }
        }

        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val dayStart = DateTime(cal.timeInMillis)

        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        val dayEnd = DateTime(cal.timeInMillis)

        val titleDate = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.ENGLISH).format(cal.time)

        return try {
            Log.d(TAG, "Fetching events from " + dayStart + " to " + dayEnd)
            // Query every calendar, not just "primary".
            val calList = try {
                svc.calendarList().list().execute().items ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "CalendarList error: " + e.message)
                listOf(com.google.api.services.calendar.model.CalendarListEntry().setId("primary"))
            }
            Log.d(TAG, "Found " + calList.size + " calendars")

            val targetDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            Log.d(TAG, "Target date: " + targetDate)
            val allEvents = mutableListOf<com.google.api.services.calendar.model.Event>()
            for (calEntry in calList) {
                try {
                    val evts = svc.events().list(calEntry.id)
                        .setTimeMin(dayStart)
                        .setTimeMax(dayEnd)
                        .setOrderBy("startTime")
                        .setSingleEvents(true)
                        .setMaxResults(50)
                        .execute()
                        .items ?: emptyList()
                    for (evt in evts) {
                        // All-day events use a date (no timezone); compare the date
                        // string to avoid pulling in the adjacent day's events.
                        if (evt.start?.date != null) {
                            val evtDate = evt.start.date.toString().substring(0, 10)
                            if (evtDate == targetDate) {
                                allEvents.add(evt)
                            } else {
                                Log.d(TAG, "Skipping all-day event: " + evt.summary + " date=" + evtDate)
                            }
                        } else {
                            allEvents.add(evt)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching cal " + calEntry.id + ": " + e.message)
                }
            }
            val timedEvents = allEvents.filter { it.start?.dateTime != null }
                .sortedBy { it.start.dateTime.value }
            val allDayEvents = allEvents.filter { it.start?.dateTime == null }
            val events = allDayEvents + timedEvents

            Log.d(TAG, "Got " + events.size + " events total")

            if (events.isEmpty()) {
                Pair(titleDate, listOf("  No events"))
            } else {
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                // Pin the timezone; relying on the device default caused off-by-one-hour times.
                timeFmt.timeZone = TimeZone.getTimeZone("Europe/Rome")
                val lines = events.map { event ->
                    val start = event.start
                    val summary = event.summary ?: "(no title)"
                    val location = if (event.location != null) " @ " + event.location else ""
                    if (start?.dateTime != null) {
                        timeFmt.format(Date(start.dateTime.value)) + "  " + summary + location
                    } else {
                        summary + location
                    }
                }
                Pair(titleDate, lines)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Calendar error: " + e.message, e)
            Pair(titleDate, listOf("  Error: " + e.message))
        }
    }
}
