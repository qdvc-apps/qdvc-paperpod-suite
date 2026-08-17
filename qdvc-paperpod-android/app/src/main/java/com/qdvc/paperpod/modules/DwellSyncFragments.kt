package com.qdvc.paperpod.modules

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import com.qdvc.paperpod.PayloadPickerActivity
import com.qdvc.paperpod.data.DwellCard
import com.qdvc.paperpod.text.FontRegistry
import com.qdvc.paperpod.ui.Eink
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dwell: one card at a time, chosen for today.
 *
 * This is the deliberate opposite of a feed. There is no scroll, no "load more"
 * and no infinite tail — Studio schedules a small number of cards per date, and
 * when you have seen them the screen says so. The constraint is the feature: the
 * thing that makes a photo of your family land is that it is not item nine of two
 * hundred.
 */
class DeckFragment : ModuleFragment() {

    private lateinit var body: LinearLayout
    private var cards: List<DwellCard> = emptyList()
    private var index = 0

    override fun buildView(): View {
        val deck = repo.dwellDeck(spec.source ?: "dwell/deck.json")
        cards = deck?.forDate(LocalDate.now().toString()).orEmpty()
        val (root, b) = page(spec.label, meta = if (cards.size > 1) "1 of ${cards.size}" else null)
        body = b
        render()
        return root
    }

    private fun render() {
        val ctx = requireContext()
        body.removeAllViews()
        if (cards.isEmpty()) {
            body.addView(
                emptyState(
                    "Nothing set aside for today.",
                    "Add photos, quotes and ideas in Studio under Dwell, then build and sync."
                )
            )
            return
        }
        val current = cards[index.coerceIn(cards.indices)]
        body.addView(cardView(current), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = Eink.dp(ctx, 12f) })

        if (cards.size > 1) {
            val nav = Eink.row(ctx).apply { setPadding(0, Eink.dp(ctx, 10f), 0, 0) }
            nav.addView(button("\u2190") { index = (index - 1 + cards.size) % cards.size; render() })
            nav.addView(
                Eink.body(ctx, "${index + 1} of ${cards.size}", sizeSp = 13f, family = family()).apply {
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            nav.addView(button("\u2192") { index = (index + 1) % cards.size; render() })
            body.addView(nav)
        }
    }

    private fun cardView(c: DwellCard): View {
        val ctx = requireContext()
        val frame = card(0f)
        val inner = Eink.column(ctx, 14f)

        val imageFile = c.image?.let { repo.file(it) }
        if (imageFile != null) {
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = false
                setImageBitmap(decodeScaled(imageFile))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            frame.addView(iv)
            frame.addView(Eink.rule(ctx, Eink.OUTLINE_DP))
        }

        if (c.title.isNotBlank()) {
            inner.addView(Eink.body(ctx, c.title, sizeSp = 20f, bold = true, family = family()))
            inner.addView(Eink.spacer(ctx, 6f))
        }
        if (c.body.isNotBlank()) {
            val isQuote = c.kind == "quote"
            inner.addView(
                Eink.body(
                    ctx, c.body,
                    sizeSp = if (isQuote) 20f else 17f,
                    italic = isQuote,
                    family = family()
                ).apply { setLineSpacing(0f, 1.4f) }
            )
        }
        val foot = listOf(c.attribution, prettyDate(c.date)).filter { it.isNotBlank() }
        if (foot.isNotEmpty()) {
            inner.addView(Eink.spacer(ctx, 10f))
            inner.addView(Eink.rule(ctx, Eink.HAIRLINE_DP))
            inner.addView(Eink.spacer(ctx, 6f))
            inner.addView(Eink.body(ctx, foot.joinToString(" \u00b7 "), sizeSp = 13f, family = family()))
        }
        frame.addView(inner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return frame
    }

    private fun decodeScaled(file: File): android.graphics.Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val target = resources.displayMetrics.widthPixels
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (e: Exception) {
        null
    }

    private fun prettyDate(iso: String): String = try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK))
    } catch (e: Exception) {
        iso
    }
}

/**
 * Sync: the honest status page.
 *
 * The app does not sync anything itself — a separate helper mirrors the payload
 * over SMB in the background. So this screen does two things: it re-reads what is
 * on disk, and it tells you plainly what it found. When something is wrong, the
 * answer is nearly always visible here rather than requiring a laptop.
 */
class SyncFragment : ModuleFragment() {

    private lateinit var body: LinearLayout

    /**
     * Registered as a property so it is in place before the fragment starts, which
     * is what the Activity Result API requires.
     */
    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = result.data?.getStringExtra(PayloadPickerActivity.EXTRA_RESULT)
        if (result.resultCode == android.app.Activity.RESULT_OK && !path.isNullOrBlank()) {
            prefs.payloadPath = path
            repo.load()
            FontRegistry.load(repo.root, force = true)
            repo.buildInfo?.buildId?.let { prefs.lastSeenBuildId = it }
            (activity as? com.qdvc.paperpod.MainActivity)?.rebuildFromManifest()
            render()
        }
    }

    override fun buildView(): View {
        val (root, b) = page(spec.label)
        body = b
        render()
        return root
    }

    private fun render() {
        val ctx = requireContext()
        body.removeAllViews()
        val col = Eink.column(ctx)

        val actions = Eink.row(ctx)
        actions.addView(button("Reload payload") {
            repo.load()
            FontRegistry.load(repo.root, force = true)
            repo.buildInfo?.buildId?.let { prefs.lastSeenBuildId = it }
            (activity as? com.qdvc.paperpod.MainActivity)?.rebuildFromManifest()
            render()
        })
        actions.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(Eink.dp(ctx, 8f), 1)
        })
        actions.addView(button(if (repo.root == null) "Find payload\u2026" else "Change folder\u2026") {
            pickerLauncher.launch(PayloadPickerActivity.intent(ctx, repo.root))
        })
        col.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Eink.dp(ctx, 12f) })

        if (!hasAllFilesAccess()) {
            val warn = card(12f)
            warn.addView(Eink.body(
                ctx,
                "This app cannot read the payload directory yet.",
                sizeSp = 16f, bold = true, family = family()
            ))
            warn.addView(Eink.spacer(ctx, 6f))
            warn.addView(Eink.body(
                ctx,
                "The payload is a plain folder maintained by your sync helper, which " +
                    "scoped storage hides from other apps. Grant all-files access, then reload.",
                sizeSp = 14f, family = family()
            ))
            warn.addView(Eink.spacer(ctx, 10f))
            warn.addView(button("Grant file access") { requestAllFilesAccess() })
            col.addView(warn, marginTop(12f))
        }

        val root = repo.root
        val info = card(12f)
        info.addView(sectionTitle("Payload"))
        info.addView(kv("Location", root?.absolutePath ?: "not found"))
        repo.manifest?.let { m ->
            info.addView(kv("Bundle", m.bundleId.ifBlank { "\u2014" }))
            info.addView(kv("Built", m.generatedAt.ifBlank { "\u2014" }))
            info.addView(kv("Modules", m.modules.size.toString()))
        }
        repo.buildInfo?.let { b ->
            info.addView(kv("Studio", b.studioVersion.ifBlank { "\u2014" }))
            if (b.counts.isNotEmpty()) {
                info.addView(kv("Contents", b.counts.entries
                    .sortedBy { it.key }
                    .joinToString(", ") { "${it.value} ${it.key}" }))
            }
            info.addView(kv("Files", b.files.size.toString()))
        }
        col.addView(info, marginTop(12f))

        val fonts = card(12f)
        fonts.addView(sectionTitle("Fonts in payload"))
        val names = FontRegistry.names().filter { it != FontRegistry.SYSTEM }
        if (names.isEmpty()) {
            fonts.addView(Eink.body(
                ctx,
                "None found. Drop families into fonts/<Family>/ in the payload " +
                    "and reload; they appear in Settings automatically.",
                sizeSp = 14f, family = family()
            ))
        } else {
            names.forEach { n -> fonts.addView(kv(n, FontRegistry.describe(n))) }
        }
        col.addView(fonts, marginTop(12f))

        repo.lastError?.let { err ->
            val problem = card(12f)
            problem.addView(sectionTitle("Problem"))
            problem.addView(Eink.body(ctx, err, sizeSp = 13f, family = family()))
            col.addView(problem, marginTop(12f))
        }

        body.addView(scroller(col), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
    }

    private fun sectionTitle(text: String): View {
        val ctx = requireContext()
        val col = Eink.column(ctx)
        col.addView(Eink.body(ctx, text.uppercase(Locale.UK), sizeSp = 12f, bold = true, family = family()).apply {
            letterSpacing = 0.14f
        })
        col.addView(Eink.rule(ctx, Eink.HAIRLINE_DP, marginTopDp = 3f))
        col.addView(Eink.spacer(ctx, 6f))
        return col
    }

    private fun kv(key: String, value: String): View {
        val ctx = requireContext()
        val row = Eink.row(ctx).apply {
            setPadding(0, Eink.dp(ctx, 3f), 0, Eink.dp(ctx, 3f))
        }
        row.addView(Eink.body(ctx, key, sizeSp = 14f, bold = true, family = family()).apply {
            width = Eink.dp(ctx, 96f)
        })
        row.addView(
            Eink.body(ctx, value, sizeSp = 14f, family = family()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        return row
    }

    private fun marginTop(dp: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Eink.dp(requireContext(), dp) }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}
