package com.ryudev.adskip

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val SKIP_STATS_PREFS = "skip_stats_prefs"
private const val KEY_SKIP_EVENTS = "skip_events_json"
private const val RETENTION_DAYS = 56
private const val MAX_NOTES_PER_DAY = 3

private data class SkipEventRecord(
    val timestampMillis: Long,
    val matchedText: String,
    val note: String?
)

data class SkipDaySummary(
    val dayStartMillis: Long,
    val dayLabel: String,
    val skipCount: Int,
    val notes: List<String>
)

data class SkipWeekSummary(
    val weekStartMillis: Long,
    val weekEndMillis: Long,
    val weekLabel: String,
    val totalSkips: Int,
    val days: List<SkipDaySummary>
)

object SkipStatsStore {
    private val lock = Any()

    fun recordSkip(context: Context, matchedText: String, note: String? = null) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(SKIP_STATS_PREFS, Context.MODE_PRIVATE)
            val events = loadEvents(prefs).toMutableList()
            events += SkipEventRecord(
                timestampMillis = System.currentTimeMillis(),
                matchedText = matchedText,
                note = note
            )
            saveEvents(prefs, pruneOldEvents(events))
        }
    }

    fun getTodaySummary(context: Context): SkipDaySummary {
        val todayStart = startOfDayMillis(System.currentTimeMillis())
        return buildDaySummaries(loadEvents(context), todayStart, todayStart)
            .firstOrNull()
            ?: emptyDaySummary(todayStart)
    }

    fun getWeeklySummaries(context: Context, weeksToShow: Int = 4): List<SkipWeekSummary> {
        val now = System.currentTimeMillis()
        val currentWeekStart = startOfWeekMillis(now)
        val events = loadEvents(context)

        return (0 until weeksToShow).map { weekOffset ->
            val weekStart = addDays(currentWeekStart, -(weekOffset * 7))
            val weekEnd = addDays(weekStart, 7)
            val days = buildDaySummaries(events, weekStart, weekEnd)
            val totalSkips = days.sumOf { it.skipCount }
            SkipWeekSummary(
                weekStartMillis = weekStart,
                weekEndMillis = weekEnd,
                weekLabel = formatWeekLabel(context, weekStart, weekEnd, weekOffset == 0),
                totalSkips = totalSkips,
                days = days
            )
        }
    }

    private fun loadEvents(context: Context): List<SkipEventRecord> {
        val prefs = context.getSharedPreferences(SKIP_STATS_PREFS, Context.MODE_PRIVATE)
        return synchronized(lock) { loadEvents(prefs) }
    }

    private fun loadEvents(prefs: android.content.SharedPreferences): List<SkipEventRecord> {
        val raw = prefs.getString(KEY_SKIP_EVENTS, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }

        val events = mutableListOf<SkipEventRecord>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val timestamp = item.optLong("timestampMillis", -1L)
            val matchedText = item.optString("matchedText", "").trim()
            if (timestamp <= 0L || matchedText.isEmpty()) continue
            val note = item.opt("note")?.toString()?.takeIf { it.isNotBlank() }
            events += SkipEventRecord(
                timestampMillis = timestamp,
                matchedText = matchedText,
                note = note
            )
        }
        return pruneOldEvents(events)
    }

    private fun saveEvents(prefs: android.content.SharedPreferences, events: List<SkipEventRecord>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject().apply {
                    put("timestampMillis", event.timestampMillis)
                    put("matchedText", event.matchedText)
                    put("note", event.note ?: JSONObject.NULL)
                }
            )
        }
        prefs.edit { putString(KEY_SKIP_EVENTS, array.toString()) }
    }

    private fun pruneOldEvents(events: List<SkipEventRecord>): List<SkipEventRecord> {
        val retentionStart = startOfDayMillis(System.currentTimeMillis()) - (RETENTION_DAYS - 1L) * DAY_IN_MILLIS
        return events
            .filter { it.timestampMillis >= retentionStart }
            .sortedBy { it.timestampMillis }
    }

    private fun buildDaySummaries(
        events: List<SkipEventRecord>,
        rangeStartMillis: Long,
        rangeEndMillis: Long
    ): List<SkipDaySummary> {
        val dayStarts = linkedSetOf<Long>()
        var cursor = rangeStartMillis
        while (cursor < rangeEndMillis) {
            dayStarts += cursor
            cursor = addDays(cursor, 1)
        }

        return dayStarts.map { dayStart ->
            val dayEnd = addDays(dayStart, 1)
            val dayEvents = events.filter { it.timestampMillis in dayStart until dayEnd }
            SkipDaySummary(
                dayStartMillis = dayStart,
                dayLabel = formatDayLabel(dayStart),
                skipCount = dayEvents.size,
                notes = dayEvents
                    .map { it.note?.takeIf { note -> note.isNotBlank() } ?: it.matchedText }
                    .distinct()
                    .take(MAX_NOTES_PER_DAY)
            )
        }
    }

    private fun emptyDaySummary(dayStartMillis: Long): SkipDaySummary {
        return SkipDaySummary(
            dayStartMillis = dayStartMillis,
            dayLabel = formatDayLabel(dayStartMillis),
            skipCount = 0,
            notes = emptyList()
        )
    }

    private fun formatWeekLabel(
        context: Context,
        weekStartMillis: Long,
        weekEndMillis: Long,
        isCurrentWeek: Boolean
    ): String {
        if (isCurrentWeek) return context.getString(R.string.skip_stats_this_week)
        val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
        return "${formatter.format(Date(weekStartMillis))} - ${formatter.format(Date(weekEndMillis - 1))}"
    }

    private fun formatDayLabel(dayStartMillis: Long): String {
        return SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(dayStartMillis))
    }

    private fun startOfDayMillis(timestamp: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun startOfWeekMillis(timestamp: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.MONDAY
        calendar.timeInMillis = startOfDayMillis(timestamp)
        while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }
        return calendar.timeInMillis
    }

    private fun addDays(timestamp: Long, days: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.add(Calendar.DAY_OF_MONTH, days)
        return calendar.timeInMillis
    }

    private const val DAY_IN_MILLIS = 24L * 60L * 60L * 1000L
}



