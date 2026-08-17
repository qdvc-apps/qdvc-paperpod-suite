package com.qdvc.paperpod.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.qdvc.paperpod.md.Block
import com.qdvc.paperpod.text.FontRegistry
import java.io.File

/**
 * Renders a parsed document as discrete pages.
 *
 * This exists because panning is the wrong interaction for a slow panel: a drag
 * asks the display for a continuous stream of updates it cannot deliver, so it
 * smears and lags. A page turn asks for exactly one refresh, which is the one
 * thing e-paper does well. So there is no scrolling anywhere in the reader —
 * tapping the right edge advances, the left edge goes back.
 *
 * Pagination is block-based with line-level splitting: a paragraph too tall for
 * the remaining space is broken between its own lines rather than pushed whole to
 * the next page, so pages fill properly instead of ending in half-empty columns.
 */
class PagedDocumentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** One drawable unit on a page: a slice of a text layout, or an image. */
    private class Piece(
        val layout: StaticLayout? = null,
        val firstLine: Int = 0,
        val lastLine: Int = 0,
        val bitmap: Bitmap? = null,
        val indent: Float = 0f,
        val marker: String? = null,
        val markerPaint: TextPaint? = null,
        val quoteBar: Boolean = false,
        val rule: Boolean = false,
        val gapAbove: Float = 0f,
        val gapBelow: Float = 0f,
        val keepWithNext: Boolean = false,
    ) {
        val height: Float
            get() = when {
                layout != null -> (layout.getLineBottom(lastLine) - layout.getLineTop(firstLine)).toFloat()
                bitmap != null -> bitmap.height.toFloat()
                rule -> 1f
                else -> 0f
            }
    }

    private var blocks: List<Block> = emptyList()
    private var assetBase: File? = null
    private val pages = mutableListOf<MutableList<Piece>>()
    private val pageOffsets = mutableListOf<MutableList<Float>>()

    private var bodySizeSp = 19f
    private var lineSpacing = 1.35f
    private var familyName: String? = null
    private var marginPx = 0f
    private var turnsSinceFullRefresh = 0

    var fullRefreshEvery: Int = 6
    var currentPage: Int = 0
        private set

    var onPageChanged: ((page: Int, total: Int) -> Unit)? = null
    var onFigureTapped: ((path: String, caption: String) -> Unit)? = null
    var onCentreTapped: (() -> Unit)? = null

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val italicPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rulePaint = Paint().apply { style = Paint.Style.FILL }
    private val figureRects = mutableListOf<Triple<android.graphics.RectF, String, String>>()

    init {
        setBackgroundColor(Eink.paper(context))
        isClickable = true
        isFocusable = true
    }

    fun configure(
        familyName: String?,
        bodySizeSp: Float,
        lineSpacing: Float,
        marginDp: Int,
    ) {
        this.familyName = familyName
        this.bodySizeSp = bodySizeSp
        this.lineSpacing = lineSpacing
        this.marginPx = Eink.dp(context, marginDp.toFloat()).toFloat()
        applyPaints()
        repaginate()
    }

    private fun applyPaints() {
        val size = Eink.sp(context, bodySizeSp)
        val ink = Eink.ink(context)
        bodyPaint.apply {
            textSize = size; color = ink
            typeface = FontRegistry.typeface(familyName, bold = false, italic = false)
        }
        boldPaint.apply {
            textSize = size; color = ink
            typeface = FontRegistry.typeface(familyName, bold = true, italic = false)
        }
        italicPaint.apply {
            textSize = size; color = ink
            typeface = FontRegistry.typeface(familyName, bold = false, italic = true)
        }
        monoPaint.apply {
            textSize = size * 0.86f; color = ink
            typeface = Typeface.MONOSPACE
        }
        rulePaint.color = ink
    }

    fun setDocument(blocks: List<Block>, assetBase: File?, startPage: Int = 0) {
        this.blocks = blocks
        this.assetBase = assetBase
        this.currentPage = startPage
        repaginate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        repaginate()
    }

    val pageCount: Int get() = pages.size.coerceAtLeast(1)

    private fun contentWidth(): Int = (width - marginPx * 2).toInt()
    private fun contentHeight(): Float = height - marginPx * 2 - Eink.dp(context, 22f)

    // -------------------------------------------------------------- pagination

    private fun repaginate() {
        pages.clear()
        pageOffsets.clear()
        if (width == 0 || height == 0 || blocks.isEmpty()) { invalidate(); return }

        val w = contentWidth()
        val maxH = contentHeight()
        if (w <= 0 || maxH <= 0) return

        var page = mutableListOf<Piece>()
        var offsets = mutableListOf<Float>()
        var y = 0f

        fun newPage() {
            pages += page
            pageOffsets += offsets
            page = mutableListOf()
            offsets = mutableListOf()
            y = 0f
        }

        fun place(piece: Piece) {
            val gap = if (page.isEmpty()) 0f else piece.gapAbove
            if (y + gap + piece.height > maxH && page.isNotEmpty()) newPage()
            val topGap = if (page.isEmpty()) 0f else piece.gapAbove
            y += topGap
            page += piece
            offsets += y
            y += piece.height + piece.gapBelow
        }

        /** Splits an over-tall text layout across pages at line boundaries. */
        fun placeText(
            layout: StaticLayout,
            indent: Float,
            gapAbove: Float,
            gapBelow: Float,
            marker: String?,
            markerPaint: TextPaint?,
            quoteBar: Boolean,
            keepWithNext: Boolean,
        ) {
            var line = 0
            var first = true
            while (line < layout.lineCount) {
                val available = maxH - y - (if (page.isEmpty()) 0f else if (first) gapAbove else 0f)
                val top = layout.getLineTop(line)
                var last = line
                var fits = 0
                while (last < layout.lineCount &&
                    layout.getLineBottom(last) - top <= available
                ) { fits++; last++ }

                // A single orphaned line at the foot of a page reads worse than a
                // slightly short page, so push the whole thing forward instead.
                val wouldOrphan = fits in 1..1 && layout.lineCount > 2 && first
                if (fits == 0 || wouldOrphan) {
                    if (page.isEmpty()) { fits = 1; last = line + 1 } else { newPage(); first = true; continue }
                }

                val piece = Piece(
                    layout = layout,
                    firstLine = line,
                    lastLine = last - 1,
                    indent = indent,
                    marker = if (first) marker else null,
                    markerPaint = markerPaint,
                    quoteBar = quoteBar,
                    gapAbove = if (first) gapAbove else 0f,
                    gapBelow = if (last >= layout.lineCount) gapBelow else 0f,
                    keepWithNext = keepWithNext && last >= layout.lineCount,
                )
                place(piece)
                line = last
                first = false
                if (line < layout.lineCount) newPage()
            }
        }

        val em = bodyPaint.textSize
        blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Heading -> {
                    val scale = when (block.level) {
                        1 -> 1.55f; 2 -> 1.32f; 3 -> 1.16f; else -> 1.04f
                    }
                    val paint = TextPaint(boldPaint).apply { textSize = bodyPaint.textSize * scale }
                    val layout = layout(block.text, paint, w, 1.12f)
                    // A heading stranded at the foot of a page is a broken promise;
                    // if its first body line won't follow it, start a new page.
                    val needed = layout.height + em * 2.2f
                    if (y > 0f && y + needed > maxH) newPage()
                    placeText(layout, 0f, em * 1.1f, em * 0.45f, null, null, false, true)
                }

                is Block.Paragraph -> placeText(
                    layout(block.text, bodyPaint, w, lineSpacing), 0f,
                    em * 0.7f, 0f, null, null, false, false
                )

                is Block.Quote -> {
                    val indent = em * 0.9f
                    placeText(
                        layout(block.text, italicPaint, (w - indent).toInt(), lineSpacing),
                        indent, em * 0.8f, em * 0.2f, null, null, true, false
                    )
                }

                is Block.Bullet -> {
                    val indent = em * 1.4f
                    placeText(
                        layout(block.text, bodyPaint, (w - indent).toInt(), lineSpacing),
                        indent, em * 0.35f, 0f, block.marker, bodyPaint, false, false
                    )
                }

                is Block.Code -> placeText(
                    layout(block.text, monoPaint, w, 1.15f), em * 0.5f,
                    em * 0.7f, em * 0.3f, null, null, false, false
                )

                is Block.Rule -> place(
                    Piece(rule = true, gapAbove = em * 0.8f, gapBelow = em * 0.8f)
                )

                is Block.Figure -> {
                    val bmp = loadFigure(block.path, w)
                    if (bmp != null) {
                        // Figures are placed inline at column width and open full
                        // screen on tap, so looking closely is a decision, not an
                        // accident of panning.
                        place(Piece(bitmap = bmp, gapAbove = em * 0.9f, gapBelow = em * 0.3f))
                        figureIndex[bmp] = block.path to block.caption
                    }
                    if (block.caption.isNotBlank()) {
                        val cp = TextPaint(italicPaint).apply { textSize = bodyPaint.textSize * 0.84f }
                        placeText(layout(block.caption, cp, w, 1.15f), 0f, em * 0.15f, em * 0.6f, null, null, false, false)
                    }
                }
            }
            if (index == blocks.lastIndex && page.isNotEmpty()) newPage()
        }
        if (page.isNotEmpty()) newPage()

        currentPage = currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        onPageChanged?.invoke(currentPage, pageCount)
        invalidate()
    }

    private val figureIndex = HashMap<Bitmap, Pair<String, String>>()

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, spacing: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    private fun loadFigure(path: String, targetWidth: Int): Bitmap? {
        val base = assetBase ?: return null
        val f = File(base, path).takeIf { it.isFile }
            ?: File(base.parentFile ?: base, path).takeIf { it.isFile }
            ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
            val cap = (contentHeight() * 0.62f).toInt().coerceAtLeast(80)
            var w = targetWidth
            var h = (raw.height * (w.toFloat() / raw.width)).toInt()
            if (h > cap) { h = cap; w = (raw.width * (h.toFloat() / raw.height)).toInt() }
            Bitmap.createScaledBitmap(raw, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        figureRects.clear()
        if (pages.isEmpty()) return
        val page = pages.getOrNull(currentPage) ?: return
        val offsets = pageOffsets.getOrNull(currentPage) ?: return

        page.forEachIndexed { i, piece ->
            val top = marginPx + offsets[i]
            val left = marginPx + piece.indent
            when {
                piece.rule -> canvas.drawRect(
                    marginPx, top, width - marginPx, top + Eink.dp(context, 1f), rulePaint
                )

                piece.bitmap != null -> {
                    val x = marginPx + (contentWidth() - piece.bitmap.width) / 2f
                    canvas.drawBitmap(piece.bitmap, x, top, null)
                    figureIndex[piece.bitmap]?.let { (path, caption) ->
                        figureRects += Triple(
                            android.graphics.RectF(
                                x, top, x + piece.bitmap.width, top + piece.bitmap.height
                            ), path, caption
                        )
                    }
                    // A hairline frame tells you the figure is tappable without a
                    // shadow or a tint, neither of which this panel can render.
                    canvas.drawRect(
                        x - 1f, top - 1f,
                        x + piece.bitmap.width + 1f, top + piece.bitmap.height + 1f,
                        Paint().apply {
                            style = Paint.Style.STROKE
                            strokeWidth = Eink.dp(context, 1f).toFloat()
                            color = Eink.ink(context)
                        }
                    )
                }

                piece.layout != null -> {
                    if (piece.quoteBar) {
                        canvas.drawRect(
                            marginPx, top,
                            marginPx + Eink.dp(context, 3f), top + piece.height, rulePaint
                        )
                    }
                    piece.marker?.let { m ->
                        canvas.drawText(
                            m, marginPx,
                            top + piece.layout.getLineBaseline(piece.firstLine) -
                                piece.layout.getLineTop(piece.firstLine),
                            piece.markerPaint ?: bodyPaint
                        )
                    }
                    canvas.save()
                    canvas.clipRect(left, top, width - marginPx, top + piece.height)
                    canvas.translate(left, top - piece.layout.getLineTop(piece.firstLine))
                    piece.layout.draw(canvas)
                    canvas.restore()
                }
            }
        }

        // Page indicator: the only chrome, and it earns its space by telling you
        // how much is left, which is the question you actually have.
        val label = "${currentPage + 1} / $pageCount"
        val p = TextPaint(bodyPaint).apply { textSize = Eink.sp(context, 12f) }
        canvas.drawText(
            label,
            width - marginPx - p.measureText(label),
            height - marginPx * 0.35f,
            p
        )
    }

    // ------------------------------------------------------------------- input

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; return true }
            MotionEvent.ACTION_UP -> {
                if (Math.abs(event.x - downX) > 40 || Math.abs(event.y - downY) > 40) return true
                val hit = figureRects.firstOrNull { it.first.contains(event.x, event.y) }
                if (hit != null) { onFigureTapped?.invoke(hit.second, hit.third); return true }
                when {
                    event.x < width * 0.30f -> previousPage()
                    event.x > width * 0.70f -> nextPage()
                    else -> onCentreTapped?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun nextPage() = goTo(currentPage + 1)
    fun previousPage() = goTo(currentPage - 1)

    fun goTo(page: Int) {
        val target = page.coerceIn(0, pageCount - 1)
        if (target == currentPage) return
        currentPage = target
        turnsSinceFullRefresh++
        if (fullRefreshEvery > 0 && turnsSinceFullRefresh >= fullRefreshEvery) {
            turnsSinceFullRefresh = 0
            Eink.flashClear(this) { invalidate() }
        } else {
            invalidate()
        }
        onPageChanged?.invoke(currentPage, pageCount)
    }
}
