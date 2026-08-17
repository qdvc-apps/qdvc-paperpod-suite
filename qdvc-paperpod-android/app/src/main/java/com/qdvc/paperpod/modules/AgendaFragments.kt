package com.qdvc.paperpod.modules

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.qdvc.paperpod.data.Day
import com.qdvc.paperpod.data.DayEvent
import com.qdvc.paperpod.data.DayTask
import com.qdvc.paperpod.data.Week
import com.qdvc.paperpod.ui.Eink
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Day: a single pre-resolved day file, rendered.
 *
 * The device does no recurrence expansion, no timezone arithmetic and no ICS
 * parsing — Studio has already done all of it and written one flat file per date.
 * The upshot is that this screen is correct even if the tablet has not synced for
 * a fortnight, and that a broken RRULE is a desktop bug rather than a bug you
 * discover while standing in a corridor.
 */
class AgendaFragment : ModuleFragment() {

    private var offset = 0L
    private lateinit var body: LinearLayout
    private lateinit var root: LinearLayout

    override fun buildView(): View {
        val pair = page(spec.label)
        root = pair.first
        body = pair.second
        render()
        return root
    }

    private fun render() {
        val ctx = requireContext()
        val date = LocalDate.now().plusDays(offset)
        val day = repo.day(date, spec.source ?: "days")

        // Rebuild the header so the date is always the headline, not a subtitle.
        root.removeViewAt(0)
        val meta = day?.sun?.let { s ->
            listOfNotNull(
                s.rise?.let { "\u2191 $it" },
                s.set?.let { "\u2193 $it" }
            ).joinToString("   ")
        }.orEmpty()
        root.addView(
            header(
                title = titleFor(date, day),
                meta = meta.ifBlank { null },
                action = null
            ),
            0
        )

        body.removeAllViews()
        if (day == null) {
            body.addView(
                emptyState(
                    "No day file for ${date.format(HUMAN)}.",
                    "Studio writes a rolling window of days. Build and sync to extend it."
                )
            )
        } else if (day.isEmpty) {
            body.addView(emptyState("Nothing scheduled.", "A clear day."))
        } else {
            val content = Eink.column(ctx)
            if (day.dayNote.isNotBlank()) {
                content.addView(card(12f).apply {
                    addView(Eink.body(ctx, day.dayNote, sizeSp = 16f, italic = true, family = family()))
                }, matchWrap(topDp = 10f))
            }
            if (day.events.isNotEmpty()) {
                content.addView(sectionLabel("Events"))
                day.events.forEach { content.addView(eventRow(it), matchWrap(topDp = 6f)) }
            }
            if (day.tasks.isNotEmpty()) {
                content.addView(sectionLabel("Tasks"))
                day.tasks.forEach { content.addView(taskRow(it), matchWrap(topDp = 6f)) }
            }
            if (day.moon.isNotBlank()) {
                content.addView(Eink.spacer(ctx, 10f))
                content.addView(Eink.body(ctx, day.moon, sizeSp = 13f, family = family()))
            }
            body.addView(scroller(content), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }

        body.addView(dayNav())
    }

    private fun titleFor(date: LocalDate, day: Day?): String {
        val weekday = day?.weekday?.ifBlank { null }
            ?: date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.UK)
        return "$weekday ${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.UK)}"
    }

    private fun dayNav(): View {
        val ctx = requireContext()
        val row = Eink.row(ctx).apply {
            setPadding(0, Eink.dp(ctx, 10f), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(button("\u2190 Prev") { offset -= 1; render() })
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        if (offset != 0L) {
            row.addView(button("Today") { offset = 0; render() })
            row.addView(Eink.spacer(ctx, 0f).apply {
                layoutParams = LinearLayout.LayoutParams(Eink.dp(ctx, 8f), 1)
            })
        }
        row.addView(button("Next \u2192") { offset += 1; render() })
        return row
    }

    private fun sectionLabel(text: String): View {
        val ctx = requireContext()
        val col = Eink.column(ctx)
        col.addView(Eink.spacer(ctx, 14f))
        col.addView(Eink.body(ctx, text.uppercase(Locale.UK), sizeSp = 12f, bold = true, family = family()).apply {
            letterSpacing = 0.14f
        })
        col.addView(Eink.rule(ctx, Eink.HAIRLINE_DP, marginTopDp = 3f))
        return col
    }

    private fun eventRow(e: DayEvent): View {
        val ctx = requireContext()
        val row = card(10f).apply { orientation = LinearLayout.HORIZONTAL }
        val time = Eink.body(ctx, e.timeLabel(), sizeSp = 15f, bold = true, family = family()).apply {
            width = Eink.dp(ctx, 92f)
        }
        row.addView(time)
        val col = Eink.column(ctx)
        col.addView(Eink.body(ctx, e.title, sizeSp = 17f, bold = true, family = family()))
        val sub = listOf(e.location, e.calendar).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
        if (sub.isNotBlank()) {
            col.addView(Eink.body(ctx, sub, sizeSp = 13f, family = family()))
        }
        if (e.note.isNotBlank()) {
            col.addView(Eink.body(ctx, e.note, sizeSp = 13f, italic = true, family = family()))
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun taskRow(t: DayTask): View {
        val ctx = requireContext()
        val row = card(10f).apply { orientation = LinearLayout.HORIZONTAL }
        // An empty outlined square, not a checkbox: sync is one-way, so the device
        // must not imply it can record that you did the thing.
        row.addView(View(ctx).apply {
            background = Eink.outline(ctx, Eink.OUTLINE_DP)
            layoutParams = LinearLayout.LayoutParams(Eink.dp(ctx, 18f), Eink.dp(ctx, 18f)).apply {
                topMargin = Eink.dp(ctx, 3f)
                rightMargin = Eink.dp(ctx, 12f)
            }
        })
        val col = Eink.column(ctx)
        col.addView(Eink.body(ctx, t.title, sizeSp = 16f, bold = t.overdue, family = family()))
        val bits = buildList {
            if (t.priority.isNotBlank()) add("(${t.priority})")
            if (t.project.isNotBlank()) add("+${t.project}")
            if (t.overdue) add("overdue")
            else if (t.due != null) add("due ${t.due}")
        }
        if (bits.isNotEmpty()) {
            col.addView(Eink.body(ctx, bits.joinToString(" \u00b7 "), sizeSp = 13f, family = family()))
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (t.overdue) {
            row.addView(View(ctx).apply {
                setBackgroundColor(Eink.urgent(ctx))
                layoutParams = LinearLayout.LayoutParams(Eink.dp(ctx, 5f), ViewGroup.LayoutParams.MATCH_PARENT)
            })
        }
        return row
    }

    private fun matchWrap(topDp: Float = 0f) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Eink.dp(requireContext(), topDp) }

    companion object {
        val HUMAN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    }
}

/**
 * Week: seven rows, one refresh, no horizontal scrolling.
 *
 * A 4:3 panel at 7" cannot hold a real grid calendar legibly, so this is a list of
 * days rather than a matrix — the question it answers is "what is coming", not
 * "what does the month look like".
 */
class WeekFragment : ModuleFragment() {

    private var weekOffset = 0L
    private lateinit var body: LinearLayout
    private lateinit var root: LinearLayout

    override fun buildView(): View {
        val pair = page(spec.label)
        root = pair.first
        body = pair.second
        render()
        return root
    }

    private fun render() {
        val ctx = requireContext()
        val anchor = LocalDate.now().plusWeeks(weekOffset)
        val week = repo.week(anchor, spec.source ?: "weeks")

        root.removeViewAt(0)
        root.addView(header(titleFor(week, anchor), meta = week?.isoWeek), 0)

        body.removeAllViews()
        if (week == null) {
            body.addView(
                emptyState(
                    "No week file for ${repo.weekIdFor(anchor)}.",
                    "Build and sync in Studio to extend the window."
                )
            )
        } else {
            val content = Eink.column(ctx)
            val today = LocalDate.now().toString()
            week.days.forEach { d -> content.addView(dayRow(d, isToday = d.date == today), rowParams()) }
            body.addView(scroller(content), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        body.addView(weekNav())
    }

    private fun titleFor(week: Week?, anchor: LocalDate): String {
        if (week == null) return spec.label
        return try {
            val s = LocalDate.parse(week.start)
            val e = LocalDate.parse(week.end)
            "${s.dayOfMonth} ${s.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.UK)} " +
                "\u2013 ${e.dayOfMonth} ${e.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.UK)}"
        } catch (e: Exception) {
            week.isoWeek
        }
    }

    private fun dayRow(d: com.qdvc.paperpod.data.WeekDay, isToday: Boolean): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Eink.outline(ctx, if (isToday) Eink.HEAVY_DP else Eink.HAIRLINE_DP)
            val p = Eink.dp(ctx, 9f)
            setPadding(p, p, p, p)
        }
        // Today is marked by inverting its date cell — a solid black block is the
        // one emphasis this panel renders unambiguously.
        val stamp = Eink.column(ctx, 4f).apply {
            background = if (isToday) Eink.invertedFill(ctx) else null
            layoutParams = LinearLayout.LayoutParams(Eink.dp(ctx, 52f), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = Eink.dp(ctx, 10f) }
        }
        val fg = if (isToday) Eink.paper(ctx) else Eink.ink(ctx)
        stamp.addView(Eink.body(ctx, d.weekday, sizeSp = 13f, bold = true, family = family()).apply {
            setTextColor(fg); gravity = Gravity.CENTER
        })
        stamp.addView(Eink.body(ctx, dayNumber(d.date), sizeSp = 20f, bold = true, family = family()).apply {
            setTextColor(fg); gravity = Gravity.CENTER
        })
        row.addView(stamp)

        val col = Eink.column(ctx)
        if (d.events.isEmpty()) {
            col.addView(Eink.body(ctx, "\u2014", sizeSp = 15f, family = family()))
        } else {
            d.events.forEach { e ->
                val line = Eink.row(ctx)
                line.addView(Eink.body(ctx, e.timeLabel().ifBlank { "\u00b7" }, sizeSp = 14f, bold = true, family = family()).apply {
                    width = Eink.dp(ctx, 62f)
                })
                line.addView(
                    Eink.body(ctx, e.title, sizeSp = 15f, family = family()).apply { maxLines = 2 },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                col.addView(line, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Eink.dp(ctx, 2f) })
            }
        }
        if (d.taskCount > 0) {
            col.addView(Eink.body(ctx, "${d.taskCount} task${if (d.taskCount == 1) "" else "s"}", sizeSp = 12f, family = family()))
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun dayNumber(iso: String): String =
        try { LocalDate.parse(iso).dayOfMonth.toString() } catch (e: Exception) { "?" }

    private fun weekNav(): View {
        val ctx = requireContext()
        val row = Eink.row(ctx).apply {
            setPadding(0, Eink.dp(ctx, 10f), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(button("\u2190 Prev") { weekOffset -= 1; render() })
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        if (weekOffset != 0L) {
            row.addView(button("This week") { weekOffset = 0; render() })
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(Eink.dp(ctx, 8f), 1)
            })
        }
        row.addView(button("Next \u2192") { weekOffset += 1; render() })
        return row
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Eink.dp(requireContext(), 6f) }
}
