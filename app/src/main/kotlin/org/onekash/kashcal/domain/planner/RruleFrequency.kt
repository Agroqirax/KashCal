package org.onekash.kashcal.domain.planner

import java.util.Locale

/**
 * Returns the FREQ value of [rrule] in upper case ("WEEKLY"), or null when there is no FREQ part.
 *
 * Reads only the FREQ part, case-insensitively and with or without an "RRULE:" prefix, so a
 * malformed UNTIL, BYDAY or COUNT elsewhere in the rule doesn't hide the frequency.
 */
fun rruleFrequency(rrule: String?): String? {
    if (rrule.isNullOrBlank()) return null
    return rrule.trim().removePrefix("RRULE:").removePrefix("rrule:")
        .split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("FREQ=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.uppercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
}

/** Returns true when [rrule] repeats daily or weekly, at any interval. */
fun isDailyOrWeekly(rrule: String?): Boolean = rruleFrequency(rrule).let { it == "DAILY" || it == "WEEKLY" }
