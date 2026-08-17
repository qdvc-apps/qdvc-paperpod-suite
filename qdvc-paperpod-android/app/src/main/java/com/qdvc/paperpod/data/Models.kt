package com.qdvc.paperpod.data

/**
 * Plain models for the payload described in PAYLOAD-SPEC.md.
 *
 * Everything here is read-only. The device never writes into the payload, so
 * there is no mutation, no conflict resolution and no dirty state to track.
 */

data class Typography(
    val defaultFamily: String?,
    val defaultBodySizeSp: Int,
    val defaultLineSpacing: Float,
)

data class ModuleSpec(
    val id: String,
    val label: String,
    val icon: String,
    val primitive: String,
    val source: String?,
)

data class Manifest(
    val schema: Int,
    val bundleId: String,
    val generatedAt: String,
    val title: String,
    val typography: Typography,
    val modules: List<ModuleSpec>,
)

data class BuildInfo(
    val buildId: String,
    val generatedAt: String,
    val studioVersion: String,
    val counts: Map<String, Int>,
    val files: Map<String, String>,
)

data class DayEvent(
    val start: String?,
    val end: String?,
    val allDay: Boolean,
    val title: String,
    val location: String,
    val calendar: String,
    val note: String,
) {
    /** "09:30–10:00", "09:30" or "All day" — formatted once, here, not per view. */
    fun timeLabel(): String = when {
        allDay -> "All day"
        start != null && end != null -> "$start\u2013$end"
        start != null -> start
        else -> ""
    }
}

data class DayTask(
    val title: String,
    val project: String,
    val priority: String,
    val due: String?,
    val overdue: Boolean,
)

data class Sun(val rise: String?, val set: String?)

data class Day(
    val date: String,
    val weekday: String,
    val dayNote: String,
    val sun: Sun?,
    val moon: String,
    val events: List<DayEvent>,
    val tasks: List<DayTask>,
) {
    val isEmpty: Boolean get() = events.isEmpty() && tasks.isEmpty() && dayNote.isBlank()
}

data class WeekDay(
    val date: String,
    val weekday: String,
    val taskCount: Int,
    val events: List<DayEvent>,
)

data class Week(
    val isoWeek: String,
    val start: String,
    val end: String,
    val days: List<WeekDay>,
)

data class DocumentRef(
    val id: String,
    val title: String,
    val authors: List<String>,
    val year: Int?,
    val venue: String,
    val kind: String,
    val tags: List<String>,
    val words: Int,
    val readingMinutes: Int,
    val addedAt: String,
    val sourceUrl: String,
    val path: String,
) {
    fun authorLabel(): String = when {
        authors.isEmpty() -> ""
        authors.size == 1 -> authors[0]
        authors.size == 2 -> "${authors[0]} & ${authors[1]}"
        else -> "${authors[0]} et al."
    }
}

data class DocumentDetail(
    val id: String,
    val title: String,
    val authors: List<String>,
    val abstract: String,
    val textPath: String,
    val method: String,
)

data class DwellCard(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val image: String?,
    val attribution: String,
    val date: String,
)

data class DwellDeck(
    val schedule: Map<String, List<String>>,
    val cards: List<DwellCard>,
) {
    private val byId: Map<String, DwellCard> = cards.associateBy { it.id }

    /**
     * Today's cards. Studio schedules months ahead, but if the schedule has run
     * dry the date seeds a deterministic pick so the module is never blank —
     * a stale card is better than an empty screen you stopped opening.
     */
    fun forDate(isoDate: String): List<DwellCard> {
        val scheduled = schedule[isoDate]?.mapNotNull { byId[it] }.orEmpty()
        if (scheduled.isNotEmpty()) return scheduled
        if (cards.isEmpty()) return emptyList()
        val seed = isoDate.filter { it.isDigit() }.toLongOrNull() ?: 0L
        return listOf(cards[(seed % cards.size).toInt()])
    }
}

data class Countdown(
    val id: String,
    val title: String,
    val date: String,
    val kind: String,
    val annual: Boolean,
    val note: String,
)

data class TimeZoneEntry(
    val label: String,
    val tz: String,
    val primary: Boolean,
)
