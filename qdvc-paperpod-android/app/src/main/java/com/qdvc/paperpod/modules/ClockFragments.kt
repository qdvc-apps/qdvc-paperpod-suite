package com.qdvc.paperpod.modules

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.qdvc.paperpod.data.Countdown
import com.qdvc.paperpod.data.TimeZoneEntry
import com.qdvc.paperpod.ui.Eink
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Time: a world clock.
 *
 * Redrawn on the minute rather than the second. A seconds display would demand
 * sixty panel refreshes a minute for information nobody reads, and would leave
 * ghosting across the rest of the screen.
 */
class ClockFragment : ModuleFragment() {

    private lateinit var body: LinearLayout
    private var zones: List<TimeZoneEntry> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var lastMinute = -1

    private val tick = object : Runnable {
        override fun run() {
            val minute = LocalDateTime.now().minute
            if (minute != lastMinute) { lastMinute = minute; render() }
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun buildView(): View {
        zones = repo.zones(spec.source ?: "time/zones.json")
        val (root, b) = page(spec.label)
        body = b
        render()
        return root
    }

    override fun onResume() {
        super.onResume()
        lastMinute = -1
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun render() {
        val ctx = requireContext()
        body.removeAllViews()
        if (zones.isEmpty()) {
            body.addView(emptyState("No zones configured.", "Add them in Studio under Time."))
            return
        }
        val primary = zones.firstOrNull { it.primary } ?: zones.first()
        val primaryZone = safeZone(primary.tz)
        val now = ZonedDateTime.now(primaryZone)

        val hero = card(16f)
        hero.addView(Eink.body(ctx, now.format(BIG), sizeSp = 54f, bold = true, family = family()).apply {
            gravity = Gravity.CENTER
        })
        hero.addView(Eink.body(ctx, primary.label, sizeSp = 16f, bold = true, family = family()).apply {
            gravity = Gravity.CENTER
        })
        hero.addView(Eink.body(ctx, now.format(DATE), sizeSp = 14f, family = family()).apply {
            gravity = Gravity.CENTER
        })
        body.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Eink.dp(ctx, 12f) })

        val others = zones.filter { it !== primary }
        if (others.isNotEmpty()) {
            val col = Eink.column(ctx)
            others.forEach { z -> col.addView(zoneRow(z, primaryZone), rowParams()) }
            body.addView(scroller(col), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
    }

    private fun zoneRow(z: TimeZoneEntry, reference: ZoneId): View {
        val ctx = requireContext()
        val zone = safeZone(z.tz)
        val there = ZonedDateTime.now(zone)
        val here = ZonedDateTime.now(reference)
        val hours = (there.offset.totalSeconds - here.offset.totalSeconds) / 3600.0

        val row = card(11f).apply { orientation = LinearLayout.HORIZONTAL }
        val col = Eink.column(ctx)
        col.addView(Eink.body(ctx, z.label, sizeSp = 17f, bold = true, family = family()))
        val delta = when {
            hours == 0.0 -> "same time"
            hours > 0 -> "+${trim(hours)} h"
            else -> "${trim(hours)} h"
        }
        val dayNote = when {
            there.toLocalDate().isAfter(here.toLocalDate()) -> "next day"
            there.toLocalDate().isBefore(here.toLocalDate()) -> "previous day"
            else -> ""
        }
        col.addView(Eink.body(
            ctx,
            listOf(delta, dayNote).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
            sizeSp = 13f, family = family()
        ))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Eink.body(ctx, there.format(BIG), sizeSp = 26f, bold = true, family = family()).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        return row
    }

    private fun trim(h: Double): String =
        if (h == h.toLong().toDouble()) h.toLong().toString() else String.format(Locale.UK, "%.1f", h)

    private fun safeZone(id: String): ZoneId =
        try { ZoneId.of(id) } catch (e: Exception) { ZoneId.systemDefault() }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Eink.dp(requireContext(), 7f) }

    private companion object {
        val BIG: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)
    }
}

/**
 * Soon: countdowns, sorted by what is nearest.
 *
 * The day count is the headline because that is the number you came for. Annual
 * entries are rolled forward by Studio, but this screen rolls them again as a
 * safety net so a stale payload still shows a birthday in the future rather than
 * one that has apparently passed.
 */
class CountdownFragment : ModuleFragment() {

    override fun buildView(): View {
        val ctx = requireContext()
        val items = repo.countdowns(spec.source ?: "soon/countdowns.json")
        val today = LocalDate.now()
        val resolved = items.mapNotNull { c ->
            val d = nextOccurrence(c, today) ?: return@mapNotNull null
            c to d
        }.sortedBy { it.second }

        val (root, body) = page(spec.label, meta = "${resolved.size} tracked")
        if (resolved.isEmpty()) {
            body.addView(emptyState("Nothing on the horizon.", "Add dates in Studio under Soon."))
            return root
        }
        val col = Eink.column(ctx)
        resolved.forEach { (c, date) -> col.addView(row(c, date, today), rowParams()) }
        body.addView(scroller(col), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return root
    }

    private fun nextOccurrence(c: Countdown, today: LocalDate): LocalDate? {
        val parsed = try { LocalDate.parse(c.date) } catch (e: Exception) { return null }
        if (!c.annual) return parsed
        var candidate = parsed.withYear(today.year)
        if (candidate.isBefore(today)) candidate = candidate.plusYears(1)
        return candidate
    }

    private fun row(c: Countdown, date: LocalDate, today: LocalDate): View {
        val ctx = requireContext()
        val days = Duration.between(today.atStartOfDay(), date.atStartOfDay()).toDays()
        val imminent = days in 0..7

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Eink.outline(ctx, if (imminent) Eink.HEAVY_DP else Eink.OUTLINE_DP)
            val p = Eink.dp(ctx, 11f)
            setPadding(p, p, p, p)
        }

        // Inverted block for the count: the one emphasis that survives on e-paper.
        val stamp = Eink.column(ctx, 8f).apply {
            background = if (imminent) Eink.invertedFill(ctx) else Eink.outline(ctx, Eink.HAIRLINE_DP)
            layoutParams = LinearLayout.LayoutParams(
                Eink.dp(ctx, 74f), ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = Eink.dp(ctx, 12f) }
        }
        val fg = if (imminent) Eink.paper(ctx) else Eink.ink(ctx)
        val bigLabel = when {
            days == 0L -> "today"
            days == 1L -> "1"
            days < 0L -> "past"
            else -> days.toString()
        }
        stamp.addView(Eink.body(ctx, bigLabel, sizeSp = if (days in 0..1) 20f else 28f, bold = true, family = family()).apply {
            setTextColor(fg); gravity = Gravity.CENTER
        })
        if (days > 0) {
            stamp.addView(Eink.body(ctx, if (days == 1L) "day" else "days", sizeSp = 12f, family = family()).apply {
                setTextColor(fg); gravity = Gravity.CENTER
            })
        }
        row.addView(stamp)

        val col = Eink.column(ctx)
        col.addView(Eink.body(ctx, c.title, sizeSp = 17f, bold = true, family = family()))
        val sub = buildList {
            add(date.format(DATE))
            if (c.kind.isNotBlank() && c.kind != "event") add(c.kind)
            if (c.annual) add("annual")
        }
        col.addView(Eink.body(ctx, sub.joinToString(" \u00b7 "), sizeSp = 13f, family = family()))
        if (c.note.isNotBlank()) {
            col.addView(Eink.body(ctx, c.note, sizeSp = 13f, italic = true, family = family()))
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Eink.dp(requireContext(), 7f) }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK)
    }
}
