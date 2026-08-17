package com.qdvc.paperpod.modules

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.qdvc.paperpod.data.DocumentRef
import com.qdvc.paperpod.md.Markdown
import com.qdvc.paperpod.ui.Eink
import com.qdvc.paperpod.ui.PagedDocumentView
import java.io.File

/**
 * Read: the reading list.
 *
 * Filtering is a single text box rather than folders or tags-as-navigation. Deep
 * hierarchies assume cheap back-and-forth, and every level of a tree costs a
 * refresh here — so one flat list with a filter beats a browsable filesystem.
 */
class LibraryFragment : ModuleFragment() {

    private lateinit var listCol: LinearLayout
    private var query: String = ""
    private var docs: List<DocumentRef> = emptyList()

    override fun buildView(): View {
        val ctx = requireContext()
        docs = repo.library(spec.source ?: "library/index.json")
        val (root, body) = page(spec.label, meta = "${docs.size} item${if (docs.size == 1) "" else "s"}")

        val filter = EditText(ctx).apply {
            hint = "Filter by title, author or tag"
            setTextColor(Eink.ink(ctx))
            setHintTextColor(Eink.ink(ctx))
            textSize = 15f
            typeface = com.qdvc.paperpod.text.FontRegistry.typeface(family())
            background = Eink.outline(ctx)
            val p = Eink.dp(ctx, 10f)
            setPadding(p, p, p, p)
            isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    renderList()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        body.addView(filter, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Eink.dp(ctx, 10f) })

        listCol = Eink.column(ctx)
        body.addView(scroller(listCol), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        renderList()
        return root
    }

    private fun renderList() {
        val ctx = requireContext()
        listCol.removeAllViews()
        val q = query.lowercase()
        val shown = if (q.isBlank()) docs else docs.filter { d ->
            d.title.lowercase().contains(q) ||
                d.authors.any { it.lowercase().contains(q) } ||
                d.tags.any { it.lowercase().contains(q) } ||
                d.venue.lowercase().contains(q)
        }
        if (shown.isEmpty()) {
            listCol.addView(
                if (docs.isEmpty()) {
                    emptyState(
                        "Nothing to read yet.",
                        "Add papers in Studio, build, then sync."
                    )
                } else {
                    emptyState("No matches for \u201c$query\u201d.")
                }
            )
            return
        }
        shown.forEach { d -> listCol.addView(docRow(d), rowParams()) }
    }

    private fun docRow(d: DocumentRef): View {
        val ctx = requireContext()
        val row = card(11f).apply {
            isClickable = true
            setOnClickListener { openDocument(d) }
        }
        row.addView(Eink.body(ctx, d.title, sizeSp = 17f, bold = true, family = family()))
        val meta = buildList {
            d.authorLabel().takeIf { it.isNotBlank() }?.let { add(it) }
            d.year?.let { add(it.toString()) }
            d.venue.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (meta.isNotEmpty()) {
            row.addView(Eink.body(ctx, meta.joinToString(" \u00b7 "), sizeSp = 13f, family = family()).apply {
                setPadding(0, Eink.dp(ctx, 3f), 0, 0)
            })
        }
        val tail = buildList {
            if (d.readingMinutes > 0) add("${d.readingMinutes} min")
            else if (d.words > 0) add("${d.words} words")
            val saved = prefs.readerPage(d.id)
            if (saved > 0) add("resume p${saved + 1}")
            d.tags.take(3).forEach { add("#$it") }
        }
        if (tail.isNotEmpty()) {
            row.addView(Eink.body(ctx, tail.joinToString("   "), sizeSp = 12f, family = family()).apply {
                setPadding(0, Eink.dp(ctx, 5f), 0, 0)
            })
        }
        return row
    }

    private fun openDocument(d: DocumentRef) {
        parentFragmentManager.beginTransaction()
            .setReorderingAllowed(false)
            .replace(com.qdvc.paperpod.R.id.content, ReaderFragment.newInstance(d.id, d.path, d.title))
            .addToBackStack("reader")
            .commit()
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Eink.dp(requireContext(), 7f) }
}

/**
 * The reader.
 *
 * This is the answer to the original complaint: an A4 two-column PDF on a 7" 4:3
 * panel is unreadable because it forces continuous panning, which is the one
 * gesture the hardware handles worst. Studio reflows the paper to Markdown against
 * its source (LaTeX, JATS, then PDF extraction as a last resort), and this view
 * paginates it, so reading is a sequence of single refreshes.
 */
class ReaderFragment : ModuleFragment() {

    private lateinit var pager: PagedDocumentView
    private lateinit var chrome: LinearLayout
    private var chromeVisible = true
    private var docId = ""

    override fun buildView(): View {
        val ctx = requireContext()
        docId = arguments?.getString(A_ID).orEmpty()
        val path = arguments?.getString(A_PATH).orEmpty()
        val title = arguments?.getString(A_TITLE).orEmpty()

        val root = Eink.column(ctx)

        chrome = Eink.row(ctx).apply {
            val p = Eink.dp(ctx, 8f)
            setPadding(p, p, p, p)
            gravity = Gravity.CENTER_VERTICAL
        }
        chrome.addView(ImageView(ctx).apply {
            setImageResource(com.qdvc.paperpod.R.drawable.ic_back)
            layoutParams = LinearLayout.LayoutParams(Eink.dp(ctx, 30f), Eink.dp(ctx, 30f))
            isClickable = true
            contentDescription = "Back to the reading list"
            setOnClickListener { parentFragmentManager.popBackStack() }
        })
        chrome.addView(
            Eink.body(ctx, title, sizeSp = 14f, bold = true, family = family()).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Eink.dp(ctx, 8f), 0, Eink.dp(ctx, 8f), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        chrome.addView(button("A\u2212") { adjustSize(-1) })
        chrome.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(Eink.dp(ctx, 6f), 1)
        })
        chrome.addView(button("A+") { adjustSize(+1) })
        root.addView(chrome)
        root.addView(Eink.rule(ctx, Eink.HAIRLINE_DP))

        pager = PagedDocumentView(ctx).apply {
            fullRefreshEvery = prefs.fullRefreshEvery
            onCentreTapped = { toggleChrome() }
            onFigureTapped = { p, c -> openFigure(path, p, c) }
            onPageChanged = { page, _ -> prefs.setReaderPage(docId, page) }
        }
        root.addView(pager, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val detail = repo.documentDetail(
            DocumentRef(
                docId, title, emptyList(), null, "", "paper", emptyList(),
                0, 0, "", "", path
            )
        )
        val md = detail?.let { repo.readText(it.textPath) }
            ?: repo.readText("$path/text.md")

        pager.configure(family(), bodySize(), lineSpacing(), prefs.readerMargin)
        if (md == null) {
            pager.setDocument(
                listOf(
                    com.qdvc.paperpod.md.Block.Heading(2, "Text missing"),
                    com.qdvc.paperpod.md.Block.Paragraph(
                        "No text.md was found under $path. Re-run the conversion in Studio, " +
                            "then build and sync."
                    )
                ),
                null
            )
        } else {
            val blocks = buildBlocks(detail, md)
            pager.setDocument(blocks, repo.file(path), prefs.readerPage(docId))
        }
        return root
    }

    private fun buildBlocks(
        detail: com.qdvc.paperpod.data.DocumentDetail?,
        md: String,
    ): List<com.qdvc.paperpod.md.Block> {
        val head = mutableListOf<com.qdvc.paperpod.md.Block>()
        if (detail != null) {
            head += com.qdvc.paperpod.md.Block.Heading(1, detail.title)
            if (detail.authors.isNotEmpty()) {
                head += com.qdvc.paperpod.md.Block.Paragraph(
                    Markdown.inline("*" + detail.authors.joinToString(", ") + "*")
                )
            }
            if (detail.abstract.isNotBlank()) {
                head += com.qdvc.paperpod.md.Block.Quote(Markdown.inline(detail.abstract))
            }
            head += com.qdvc.paperpod.md.Block.Rule
        }
        return head + Markdown.parse(md)
    }

    private fun adjustSize(delta: Int) {
        val next = (bodySize() + delta).coerceIn(13f, 30f)
        prefs.bodySizeSp = next.toInt()
        pager.configure(family(), next, lineSpacing(), prefs.readerMargin)
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        chrome.visibility = if (chromeVisible) View.VISIBLE else View.GONE
    }

    private fun openFigure(docPath: String, assetPath: String, caption: String) {
        parentFragmentManager.beginTransaction()
            .replace(
                com.qdvc.paperpod.R.id.content,
                FigureFragment.newInstance(docPath, assetPath, caption)
            )
            .addToBackStack("figure")
            .commit()
    }

    companion object {
        private const val A_ID = "docId"
        private const val A_PATH = "docPath"
        private const val A_TITLE = "docTitle"

        fun newInstance(id: String, path: String, title: String) = ReaderFragment().apply {
            arguments = Bundle().apply {
                putString(A_ID, id)
                putString(A_PATH, path)
                putString(A_TITLE, title)
            }
        }
    }
}

/**
 * A figure, full screen. Deliberate rather than incidental: you tapped it, so it
 * gets the whole panel and one clean refresh.
 */
class FigureFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val docPath = arguments?.getString("docPath").orEmpty()
        val assetPath = arguments?.getString("assetPath").orEmpty()
        val caption = arguments?.getString("caption").orEmpty()

        val root = Eink.column(ctx, 10f).apply {
            isClickable = true
            setOnClickListener { parentFragmentManager.popBackStack() }
        }
        val repo = com.qdvc.paperpod.PaperpodApp.repository(ctx)
        val base = repo.file(docPath)
        val file = base?.let { File(it, assetPath) }?.takeIf { it.isFile }

        val image = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (file != null) setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
        }
        root.addView(image, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        if (caption.isNotBlank()) {
            root.addView(Eink.body(ctx, caption, sizeSp = 13f, italic = true).apply {
                setPadding(0, Eink.dp(ctx, 8f), 0, 0)
            })
        }
        root.addView(Eink.body(ctx, "Tap anywhere to go back", sizeSp = 12f).apply {
            gravity = Gravity.CENTER
            setPadding(0, Eink.dp(ctx, 6f), 0, 0)
        })
        return root
    }

    companion object {
        fun newInstance(docPath: String, assetPath: String, caption: String) =
            FigureFragment().apply {
                arguments = Bundle().apply {
                    putString("docPath", docPath)
                    putString("assetPath", assetPath)
                    putString("caption", caption)
                }
            }
    }
}
