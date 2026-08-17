package com.qdvc.paperpod.modules

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.qdvc.paperpod.PaperpodApp
import com.qdvc.paperpod.data.ModuleSpec
import com.qdvc.paperpod.data.PayloadRepository
import com.qdvc.paperpod.prefs.Prefs
import com.qdvc.paperpod.ui.Eink

/**
 * Base for every module screen.
 *
 * Modules are selected by the `primitive` field in the manifest rather than
 * hard-wired to ids, so a new screen is usually a manifest entry plus a Studio
 * page — no Kotlin, no rebuild, no sideload. Iterating on a 2fps device is slow
 * enough that keeping the device dumb is the point.
 */
abstract class ModuleFragment : Fragment() {

    protected lateinit var spec: ModuleSpec
    protected val repo: PayloadRepository get() = PaperpodApp.repository(requireContext())
    protected val prefs: Prefs by lazy { Prefs(requireContext()) }

    /** The reader family, resolved from prefs then the manifest default. */
    protected fun family(): String? {
        val chosen = prefs.fontFamily
        if (chosen.isNotBlank()) {
            return if (chosen == com.qdvc.paperpod.text.FontRegistry.SYSTEM) null else chosen
        }
        return repo.manifest?.typography?.defaultFamily
    }

    protected fun bodySize(): Float {
        val chosen = prefs.bodySizeSp
        if (chosen > 0) return chosen.toFloat()
        return (repo.manifest?.typography?.defaultBodySizeSp ?: 19).toFloat()
    }

    protected fun lineSpacing(): Float {
        val chosen = prefs.lineSpacing
        if (chosen > 0.5f) return chosen
        return repo.manifest?.typography?.defaultLineSpacing ?: 1.35f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spec = ModuleSpec(
            id = arguments?.getString(ARG_ID) ?: "",
            label = arguments?.getString(ARG_LABEL) ?: "",
            icon = arguments?.getString(ARG_ICON) ?: "",
            primitive = arguments?.getString(ARG_PRIMITIVE) ?: "",
            source = arguments?.getString(ARG_SOURCE),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = buildView()

    protected abstract fun buildView(): View

    // -------------------------------------------------------------- scaffolding

    /**
     * A screen header: heavy rule under a title, optional right-aligned meta.
     * Every module uses the same one so you always know where you are without
     * reading.
     */
    protected fun header(title: String, meta: String? = null, action: View? = null): View {
        val ctx = requireContext()
        val wrap = Eink.column(ctx)
        // Stated explicitly. Without this the header is only full width because the
        // rule inside it happens to stretch a wrap_content parent, which is the same
        // accident that once made the rail eat the whole screen.
        wrap.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val row = Eink.row(ctx).apply {
            setPadding(0, 0, 0, Eink.dp(ctx, 6f))
        }
        row.addView(
            Eink.body(ctx, title, sizeSp = 22f, bold = true, family = family()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (meta != null) {
            row.addView(
                Eink.body(ctx, meta, sizeSp = 14f, family = family()).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                }
            )
        }
        action?.let { row.addView(it) }
        wrap.addView(row)
        wrap.addView(Eink.rule(ctx, Eink.HEAVY_DP))
        return wrap
    }

    /** A bordered card. Structure through outlines, since shades are unavailable. */
    protected fun card(paddingDp: Float = 12f, inverted: Boolean = false): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = if (inverted) Eink.invertedFill(ctx) else Eink.outline(ctx)
            val p = Eink.dp(ctx, paddingDp)
            setPadding(p, p, p, p)
        }
    }

    protected fun scroller(content: View): ScrollView {
        val ctx = requireContext()
        return ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(
                content,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    /** A page frame: padding, header slot, body slot. */
    protected fun page(title: String, meta: String? = null, action: View? = null): Pair<LinearLayout, LinearLayout> {
        val ctx = requireContext()
        val root = Eink.column(ctx, 14f)
        root.addView(header(title, meta, action))
        val body = Eink.column(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(body)
        return root to body
    }

    /**
     * Empty states name the missing thing and where it comes from, because on this
     * device an empty screen is almost always a sync question, not a data question.
     */
    protected fun emptyState(message: String, hint: String? = null): View {
        val ctx = requireContext()
        val col = Eink.column(ctx, 4f).apply {
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        col.addView(Eink.body(ctx, message, sizeSp = 17f, bold = true, family = family()).apply {
            gravity = android.view.Gravity.CENTER
        })
        if (hint != null) {
            col.addView(Eink.spacer(ctx, 6f))
            col.addView(Eink.body(ctx, hint, sizeSp = 14f, family = family()).apply {
                gravity = android.view.Gravity.CENTER
            })
        }
        return col
    }

    protected fun button(label: String, onClick: () -> Unit): TextView {
        val ctx = requireContext()
        return Eink.body(ctx, label, sizeSp = 15f, bold = true, family = family()).apply {
            background = Eink.outline(ctx, Eink.OUTLINE_DP)
            val h = Eink.dp(ctx, 14f)
            val v = Eink.dp(ctx, 9f)
            setPadding(h, v, h, v)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    companion object {
        const val ARG_ID = "id"
        const val ARG_LABEL = "label"
        const val ARG_ICON = "icon"
        const val ARG_PRIMITIVE = "primitive"
        const val ARG_SOURCE = "source"

        fun argsFor(spec: ModuleSpec) = Bundle().apply {
            putString(ARG_ID, spec.id)
            putString(ARG_LABEL, spec.label)
            putString(ARG_ICON, spec.icon)
            putString(ARG_PRIMITIVE, spec.primitive)
            putString(ARG_SOURCE, spec.source)
        }
    }
}

/**
 * Maps manifest primitives to renderers. Everything the app can display is listed
 * here; anything else falls back to a screen that says so plainly rather than
 * crashing on a payload built by a newer Studio.
 */
object ModuleRegistry {

    fun create(spec: ModuleSpec): Fragment {
        val fragment: ModuleFragment = when (spec.primitive.lowercase()) {
            "agenda" -> AgendaFragment()
            "week" -> WeekFragment()
            "library" -> LibraryFragment()
            "clock" -> ClockFragment()
            "countdown" -> CountdownFragment()
            "deck" -> DeckFragment()
            "sync" -> SyncFragment()
            else -> UnknownPrimitiveFragment()
        }
        fragment.arguments = ModuleFragment.argsFor(spec)
        return fragment
    }

    fun knownPrimitives() = listOf("agenda", "week", "library", "clock", "countdown", "deck", "sync")
}

class UnknownPrimitiveFragment : ModuleFragment() {
    override fun buildView(): View {
        val (root, body) = page(spec.label)
        body.addView(
            emptyState(
                "This module needs a newer app.",
                "The payload asks for the \"${spec.primitive}\" primitive, which this " +
                    "build does not render. Known primitives: " +
                    ModuleRegistry.knownPrimitives().joinToString(", ") + "."
            )
        )
        return root
    }
}
