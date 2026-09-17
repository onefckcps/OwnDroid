package com.bintianqi.owndroid.feature.time_blocker

import kotlinx.serialization.Serializable

@Serializable
data class BlockRule(
    val id: Int = 0,
    val packageName: String,
    val dailyLimitMinutes: Int = 0,        // 0 = no daily limit
    val blockedWindows: List<TimeWindow> = emptyList(),
    val allowedWindows: List<TimeWindow> = emptyList(),
    val enabled: Boolean = true
)

@Serializable
data class TimeWindow(
    val startMinutes: Int,   // minutes since 00:00
    val endMinutes: Int,     // minutes since 00:00, can be < startMinutes (wraps midnight)
    val daysOfWeek: Set<Int> = (1..7).toSet()  // 1=Mon .. 7=Sun
) {
    /**
     * Whether [minuteOfDay] on [dayOfWeek] (1=Mon..7=Sun) falls inside this window.
     * Handles midnight wrap: when startMinutes > endMinutes, the post-midnight
     * portion belongs to the previous day.
     */
    fun contains(minuteOfDay: Int, dayOfWeek: Int): Boolean {
        val effectiveDay = if (startMinutes > endMinutes && minuteOfDay < endMinutes) {
            if (dayOfWeek == 1) 7 else dayOfWeek - 1
        } else {
            dayOfWeek
        }
        if (effectiveDay !in daysOfWeek) return false
        return if (startMinutes <= endMinutes) {
            minuteOfDay in startMinutes until endMinutes
        } else {
            minuteOfDay >= startMinutes || minuteOfDay < endMinutes
        }
    }
}
