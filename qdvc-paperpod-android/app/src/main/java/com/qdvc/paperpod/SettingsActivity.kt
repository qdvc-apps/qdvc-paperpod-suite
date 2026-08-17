package com.qdvc.paperpod

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.qdvc.paperpod.prefs.Prefs
import com.qdvc.paperpod.text.FontRegistry
import com.qdvc.paperpod.ui.Eink

/**
 * Settings, built in code rather than with the preference framework, which would
 * pull in a dependency, a theme fight, and a pile of ripples and greys we would
 * then have to undo.
 *
 * Font families are not a fixed list: they are whatever the payload's fonts/
 * directory contains, discovered at load time. Adding a face is a sync, not a
 * release.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val repo = PaperpodApp.repository(this)
        FontRegistry.load(repo.root)

        container = Eink.column(this, 16f)
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Eink.paper(this@SettingsActivity))
            addView(container, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(scroll)
        render()
    }

    private fun render() {
        container.removeAllViews()

        container.addView(title("Settings"))

        // ------------------------------------------------------------ typography
        container.addView(section("Reading typeface"))
        val current = prefs.fontFamily.ifBlank {
            PaperpodApp.repository(this).manifest?.typography?.defaultFamily ?: FontRegistry.SYSTEM
        }
        FontRegistry.names().forEach { name ->
            container.addView(choice(name, subtitle = FontRegistry.describe(name), selected = name == current) {
                prefs.fontFamily = name
                render()
            })
        }
        container.addView(note(
            "Families come from the payload's fonts/ directory. Style is read from " +
                "the filename, so a family with regular, bold, italic and bold-italic " +
                "files gets all four faces without any configuration."
        ))

        // ------------------------------------------------------------- text size
        container.addView(section("Body size"))
        val size = if (prefs.bodySizeSp > 0) prefs.bodySizeSp
        else PaperpodApp.repository(this).manifest?.typography?.defaultBodySizeSp ?: 19
        container.addView(stepper("$size sp", onMinus = {
            prefs.bodySizeSp = (size - 1).coerceAtLeast(13); render()
        }, onPlus = {
            prefs.bodySizeSp = (size + 1).coerceAtMost(30); render()
        }))

        container.addView(section("Line spacing"))
        val spacing = if (prefs.lineSpacing > 0.5f) prefs.lineSpacing
        else PaperpodApp.repository(this).manifest?.typography?.defaultLineSpacing ?: 1.35f
        container.addView(stepper(String.format("%.2f", spacing), onMinus = {
            prefs.lineSpacing = (spacing - 0.05f).coerceAtLeast(1.0f); render()
        }, onPlus = {
            prefs.lineSpacing = (spacing + 0.05f).coerceAtMost(2.0f); render()
        }))

        container.addView(section("Page margin"))
        container.addView(stepper("${prefs.readerMargin} dp", onMinus = {
            prefs.readerMargin = (prefs.readerMargin - 2).coerceAtLeast(8); render()
        }, onPlus = {
            prefs.readerMargin = (prefs.readerMargin + 2).coerceAtMost(48); render()
        }))

        // ------------------------------------------------------------------ rail
        container.addView(section("Icon bar side"))
        container.addView(choice("Left", selected = !prefs.railOnRight) {
            prefs.railOnRight = false; render()
        })
        container.addView(choice("Right", selected = prefs.railOnRight) {
            prefs.railOnRight = true; render()
        })
        container.addView(note(
            "On a 7\" slate the rail is a thumb reach, and which side is comfortable " +
                "depends on the hand holding it."
        ))

        // -------------------------------------------------------------- refresh
        container.addView(section("Full refresh interval"))
        container.addView(stepper(
            if (prefs.fullRefreshEvery <= 0) "off" else "every ${prefs.fullRefreshEvery} page turns",
            onMinus = { prefs.fullRefreshEvery = (prefs.fullRefreshEvery - 1).coerceAtLeast(0); render() },
            onPlus = { prefs.fullRefreshEvery = (prefs.fullRefreshEvery + 1).coerceAtMost(20); render() }
        ))
        container.addView(note(
            "Partial refreshes leave faint traces of previous pages. Blanking the " +
                "panel periodically clears them; more often is cleaner but flashes more."
        ))

        // ---------------------------------------------------------- payload path
        container.addView(section("Payload location"))
        val repo = PaperpodApp.repository(this)
        val field = EditText(this).apply {
            setText(prefs.payloadPath ?: repo.root?.absolutePath ?: "")
            hint = "/storage/emulated/0/QDVC-Paperpod"
            setTextColor(Eink.ink(this@SettingsActivity))
            setHintTextColor(Eink.ink(this@SettingsActivity))
            textSize = 14f
            background = Eink.outline(this@SettingsActivity)
            val p = Eink.dp(this@SettingsActivity, 10f)
            setPadding(p, p, p, p)
            isSingleLine = true
        }
        container.addView(field, wide(6f))
        container.addView(rowOf(
            button("Save and reload") {
                prefs.payloadPath = field.text.toString().trim().ifBlank { null }
                repo.load()
                FontRegistry.load(repo.root, force = true)
                render()
            },
            button("Clear") {
                prefs.payloadPath = null
                repo.load()
                FontRegistry.load(repo.root, force = true)
                render()
            }
        ))
        container.addView(note(
            "Leave blank to search the usual locations: " +
                repo.candidateRoots().drop(1).joinToString(", ") { it.name }
        ))

        // ---------------------------------------------------------- start module
        container.addView(section("Open on launch"))
        val modules = repo.manifest?.modules.orEmpty()
        container.addView(choice("Last used", selected = prefs.startModuleId.isBlank()) {
            prefs.startModuleId = ""; render()
        })
        modules.forEach { m ->
            container.addView(choice(m.label, selected = prefs.startModuleId == m.id) {
                prefs.startModuleId = m.id; render()
            })
        }

        container.addView(Eink.spacer(this, 24f))
        container.addView(note("Paperpod ${versionName()} \u00b7 payload schema 1"))
        container.addView(Eink.spacer(this, 24f))
    }

    // ------------------------------------------------------------------ widgets

    private fun title(text: String): View {
        val col = Eink.column(this)
        col.addView(Eink.body(this, text, sizeSp = 24f, bold = true))
        col.addView(Eink.rule(this, Eink.HEAVY_DP, marginTopDp = 6f))
        return col
    }

    private fun section(text: String): View {
        val col = Eink.column(this)
        col.addView(Eink.spacer(this, 20f))
        col.addView(Eink.body(this, text.uppercase(), sizeSp = 12f, bold = true).apply {
            letterSpacing = 0.14f
        })
        col.addView(Eink.rule(this, Eink.HAIRLINE_DP, marginTopDp = 3f))
        col.addView(Eink.spacer(this, 8f))
        return col
    }

    /**
     * A radio row drawn as a filled or empty square. Inverting the whole row when
     * selected would be louder, but a list of these needs the eye to scan quickly,
     * so the marker carries the state and the text stays black on white.
     */
    private fun choice(
        label: String,
        subtitle: String? = null,
        selected: Boolean,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Eink.outline(this@SettingsActivity, if (selected) Eink.HEAVY_DP else Eink.HAIRLINE_DP)
            val p = Eink.dp(this@SettingsActivity, 11f)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = wide(6f)
        }
        row.addView(View(this).apply {
            background = if (selected) Eink.invertedFill(this@SettingsActivity)
            else Eink.outline(this@SettingsActivity, Eink.OUTLINE_DP)
            layoutParams = LinearLayout.LayoutParams(
                Eink.dp(this@SettingsActivity, 16f), Eink.dp(this@SettingsActivity, 16f)
            ).apply {
                rightMargin = Eink.dp(this@SettingsActivity, 12f)
                topMargin = Eink.dp(this@SettingsActivity, 3f)
            }
        })
        val col = Eink.column(this)
        col.addView(Eink.body(this, label, sizeSp = 16f, bold = selected, family = familyFor(label)))
        if (subtitle != null) {
            col.addView(Eink.body(this, subtitle, sizeSp = 12f))
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** Font rows are set in their own face, so the list is its own specimen sheet. */
    private fun familyFor(label: String): String? =
        if (FontRegistry.family(label) != null) label else null

    private fun stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = wide(0f)
        }
        row.addView(button("\u2212", onMinus))
        row.addView(
            Eink.body(this, value, sizeSp = 16f, bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(button("+", onPlus))
        return row
    }

    private fun rowOf(vararg views: View): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wide(6f)
        }
        views.forEachIndexed { i, v ->
            row.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) leftMargin = Eink.dp(this@SettingsActivity, 8f) })
        }
        return row
    }

    private fun button(label: String, onClick: () -> Unit): View =
        Eink.body(this, label, sizeSp = 15f, bold = true).apply {
            background = Eink.outline(this@SettingsActivity, Eink.OUTLINE_DP)
            val h = Eink.dp(this@SettingsActivity, 16f)
            val v = Eink.dp(this@SettingsActivity, 9f)
            setPadding(h, v, h, v)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun note(text: String): View =
        Eink.body(this, text, sizeSp = 12f).apply {
            layoutParams = wide(8f)
            setLineSpacing(0f, 1.3f)
        }

    private fun wide(topDp: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Eink.dp(this@SettingsActivity, topDp) }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
