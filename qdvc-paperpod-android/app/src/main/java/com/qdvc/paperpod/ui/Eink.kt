package com.qdvc.paperpod.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.qdvc.paperpod.R
import com.qdvc.paperpod.text.FontRegistry

/**
 * The house style, in one place.
 *
 * Three rules drive everything below. Structure is drawn with outlines and rules,
 * never shadows or greys, because the panel has no tonal range to spare. Nothing
 * animates, because an animation is a queue of wasted refreshes. And text is pure
 * black at a generous size, because Kaleido's colour filter already costs contrast.
 */
object Eink {

    const val HAIRLINE_DP = 1f
    const val OUTLINE_DP = 1.6f
    const val HEAVY_DP = 2.4f

    fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    ).toInt()

    fun sp(context: Context, value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics
    )

    fun ink(context: Context): Int = ContextCompat.getColor(context, R.color.ink)
    fun paper(context: Context): Int = ContextCompat.getColor(context, R.color.paper)
    fun urgent(context: Context): Int = ContextCompat.getColor(context, R.color.urgent)

    /** A card: white fill, hard black outline, square corners. */
    fun outline(
        context: Context,
        widthDp: Float = OUTLINE_DP,
        radiusDp: Float = 0f,
        fill: Int = paper(context),
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(context, widthDp), ink(context))
        cornerRadius = dp(context, radiusDp).toFloat()
    }

    fun invertedFill(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(ink(context))
    }

    /** Body text, set in the reader's chosen family. */
    fun body(
        context: Context,
        text: CharSequence = "",
        sizeSp: Float = 17f,
        bold: Boolean = false,
        italic: Boolean = false,
        family: String? = null,
    ): TextView = TextView(context).apply {
        this.text = text
        setTextColor(ink(context))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = FontRegistry.typeface(family, bold, italic)
        includeFontPadding = false
        setLineSpacing(0f, 1.25f)
    }

    /** A horizontal rule. Structure, not decoration: it separates real things. */
    fun rule(context: Context, heightDp: Float = HAIRLINE_DP, marginTopDp: Float = 0f): View =
        View(context).apply {
            setBackgroundColor(ink(context))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)
            ).apply { topMargin = dp(context, marginTopDp) }
        }

    fun spacer(context: Context, heightDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)
        )
    }

    fun column(context: Context, paddingDp: Float = 0f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(context, paddingDp)
            setPadding(p, p, p, p)
            setBackgroundColor(paper(context))
        }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    /**
     * Forces a full panel refresh by flashing solid black for one frame. Without a
     * vendor SDK this is the only lever available, and it is the difference between
     * a page that is legible after forty turns and one that is a grey smear.
     */
    fun flashClear(view: View, onDone: () -> Unit = {}) {
        val original = view.background
        view.setBackgroundColor(Color.BLACK)
        view.invalidate()
        view.postDelayed({
            view.background = original
            view.setBackgroundColor(paper(view.context))
            view.invalidate()
            onDone()
        }, 60L)
    }
}
