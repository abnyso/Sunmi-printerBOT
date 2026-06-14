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
import com.google.api.services.calendar.model.Event
import java.text.SimpleDateFormat
import java.util.*
import java.util.TimeZone

class CalendarFetcher(private val context: Context) {

    private val tag = "CalendarFetcher"
    // Defaults to the device timezone; the service can override it from prefs
    // (the original off-by-one bug came from a *misconfigured device* TZ, so a
    // blind getDefault() isn't enough — an explicit override must be possible).
    var zone: TimeZone = TimeZone.getDefault()
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
            Log.d(tag, "Calendar setup OK for $accountName")
        } catch (e: Exception) {
            Log.e(tag, "Setup error: ${e.message}", e)
        }
    }

    fun isReady(): Boolean = calendarService != null

    // Dispatches the agenda spec coming from Telegram:
    // "today" (or null), "tomorrow", "week", or an ISO yyyy-MM-dd date.
    fun getAgenda(spec: String?): Pair<String, List<String>> {
        return when (spec?.lowercase()?.trim()) {
            null, "", "today", "oggi" -> getEventsForDay(null)
            "tomorrow", "domani" -> {
                val cal = java.util.Calendar.getInstance(zone)
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                getEventsForDay(isoDate(cal))
            }
            "week", "settimana" -> getEventsForWeek()
            else -> getEventsForDay(spec)
        }
    }

    fun getEventsForDay(dateStr: String? = null): Pair<String, List<String>> {
        if (calendarService == null) return Pair("Error", listOf("Calendar not configured"))
        val cal = java.util.Calendar.getInstance(zone)
        if (dateStr != null) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = zone }
                cal.time = sdf.parse(dateStr) ?: cal.time
            } catch (e: Exception) {
                Log.e(tag, "Date parse error: ${e.message}")
            }
        }
        val title = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.ENGLISH).apply { timeZone = zone }.format(cal.time)
        val lines = eventLinesForDay(cal)
        return Pair(title, if (lines.isEmpty()) listOf("  No events") else lines)
    }

    // Aggregates the next 7 days (today included) under per-day headers.
    private fun getEventsForWeek(): Pair<String, List<String>> {
        if (calendarService == null) return Pair("Error", listOf("Calendar not configured"))
        val header = SimpleDateFormat("EEE dd MMM", Locale.ENGLISH).apply { timeZone = zone }
        val out = mutableListOf<String>()
        val cal = java.util.Calendar.getInstance(zone)
        for (i in 0 until 7) {
            val day = cal.clone() as java.util.Calendar
            day.add(java.util.Calendar.DAY_OF_YEAR, i)
            val lines = eventLinesForDay(day)
            if (lines.isNotEmpty()) {
                out.add("[${header.format(day.time)}]")
                out.addAll(lines)
            }
        }
        if (out.isEmpty()) out.add("  No events")
        return Pair("NEXT 7 DAYS", out)
    }

    // Returns formatted lines for the given day (empty if no events).
    private fun eventLinesForDay(day: java.util.Calendar): List<String> {
        val svc = calendarService ?: return emptyList()
        val cal = day.clone() as java.util.Calendar
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val dayStart = DateTime(cal.timeInMillis)
        val targetDate = isoDate(cal)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        val dayEnd = DateTime(cal.timeInMillis)

        val calList = try {
            svc.calendarList().list().execute().items ?: emptyList()
        } catch (e: Exception) {
            Log.e(tag, "CalendarList error: ${e.message}")
            listOf(com.google.api.services.calendar.model.CalendarListEntry().setId("primary"))
        }

        val allEvents = mutableListOf<Event>()
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
                        if (evtDate == targetDate) allEvents.add(evt)
                    } else {
                        allEvents.add(evt)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error fetching cal ${calEntry.id}: ${e.message}")
            }
        }

        val timedEvents = allEvents.filter { it.start?.dateTime != null }.sortedBy { it.start.dateTime.value }
        val allDayEvents = allEvents.filter { it.start?.dateTime == null }
        val events = allDayEvents + timedEvents
        if (events.isEmpty()) return emptyList()

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = zone }
        return events.map { event ->
            val summary = event.summary ?: "(no title)"
            val location = if (event.location != null) " @ ${event.location}" else ""
            if (event.start?.dateTime != null) {
                timeFmt.format(Date(event.start.dateTime.value)) + "  $summary$location"
            } else {
                summary + location
            }
        }
    }

    private fun isoDate(cal: java.util.Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = zone }.format(cal.time)
}
