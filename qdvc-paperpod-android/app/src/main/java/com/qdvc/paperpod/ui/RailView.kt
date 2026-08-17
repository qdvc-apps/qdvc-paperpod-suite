package com.qdvc.paperpod.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.qdvc.paperpod.R
import com.qdvc.paperpod.data.ModuleSpec

/**
 * The whole navigation model: every module is one tap away, always visible, and
 * nothing is hidden behind a drawer or a gesture. On a panel this slow, a visible
 * destination beats a discoverable one every time.
 *
 * The selected item is drawn inverted (black fill, white glyph) rather than tinted,
 * because a wash of grey is exactly what this display renders worst.
 */
class RailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var onSelect: ((ModuleSpec) -> Unit)? = null
    private var onSettings: (() -> Unit)? = null
    private val itemViews = mutableMapOf<String, View>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Eink.paper(context))
        setPadding(Eink.dp(context, 4f), Eink.dp(context, 6f), Eink.dp(context, 4f), Eink.dp(context, 6f))
        // Width comes from R.dimen.rail_width at the layout, not from here.
    }

    fun setOnSelect(block: (ModuleSpec) -> Unit) { onSelect = block }
    fun setOnSettings(block: () -> Unit) { onSettings = block }

    fun bind(modules: List<ModuleSpec>, selected: String?) {
        removeAllViews()
        itemViews.clear()
        val inflater = LayoutInflater.from(context)

        modules.forEach { spec ->
            val item = inflater.inflate(R.layout.view_rail_item, this, false)
            item.findViewById<ImageView>(R.id.icon).setImageResource(iconFor(spec.icon))
            item.findViewById<TextView>(R.id.label).text = spec.label
            item.contentDescription = spec.label
            item.setOnClickListener { onSelect?.invoke(spec) }
            addView(item)
            itemViews[spec.id] = item
        }

        // Push settings to the far end: it is not a module, it is maintenance.
        //
        // The width here must be an explicit 0, not MATCH_PARENT or WRAP_CONTENT.
        // A bare View returns the full spec size from getDefaultSize() for both
        // AT_MOST and EXACTLY, so either of those makes this spacer measure as wide
        // as the screen — which drags the rail's width with it and leaves the
        // content pane nothing. Only a non-negative dimension yields EXACTLY(0).
        addView(View(context).apply {
            layoutParams = LayoutParams(0, 0, 1f)
        })

        val settings = inflater.inflate(R.layout.view_rail_item, this, false)
        settings.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_settings)
        settings.findViewById<TextView>(R.id.label).text = "Set"
        settings.contentDescription = "Settings"
        settings.setOnClickListener { onSettings?.invoke() }
        addView(settings)

        setSelectedModule(selected)
    }

    fun setSelectedModule(id: String?) {
        itemViews.forEach { (moduleId, view) ->
            val active = moduleId == id
            val icon = view.findViewById<ImageView>(R.id.icon)
            val label = view.findViewById<TextView>(R.id.label)
            if (active) {
                view.background = Eink.invertedFill(context)
                icon.setColorFilter(Eink.paper(context))
                label.setTextColor(Eink.paper(context))
            } else {
                view.background = null
                icon.clearColorFilter()
                label.setTextColor(Eink.ink(context))
            }
        }
    }

    /**
     * Draws the rule that separates rail from content on whichever edge the rail
     * currently sits against.
     */
    fun setEdge(onRight: Boolean) {
        val w = Eink.dp(context, Eink.OUTLINE_DP)
        setPadding(
            if (onRight) w else 0, paddingTop,
            if (onRight) 0 else w, paddingBottom
        )
        background = android.graphics.drawable.LayerDrawable(
            arrayOf(
                android.graphics.drawable.ColorDrawable(Eink.ink(context)),
                android.graphics.drawable.InsetDrawable(
                    android.graphics.drawable.ColorDrawable(Eink.paper(context)),
                    if (onRight) w else 0, 0, if (onRight) 0 else w, 0
                )
            )
        )
    }

    private fun iconFor(name: String): Int = when (name.lowercase()) {
        "day", "today" -> R.drawable.ic_day
        "week" -> R.drawable.ic_week
        "read", "library", "shelf" -> R.drawable.ic_read
        "time", "clock" -> R.drawable.ic_time
        "soon", "countdown", "until" -> R.drawable.ic_soon
        "dwell", "mems", "keep" -> R.drawable.ic_dwell
        "sync", "reload", "refresh" -> R.drawable.ic_sync
        else -> R.drawable.ic_module_default
    }
}
