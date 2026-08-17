package com.qdvc.paperpod.md

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * A deliberately small Markdown reader for the dialect in PAYLOAD-SPEC.md.
 *
 * Studio owns the hard parts: it converts LaTeX or JATS sources, rasterises maths
 * and tables to images, and extracts figures as assets. So the device never needs
 * a maths renderer, a table layout engine, or an HTML parser — it needs headings,
 * paragraphs, lists, quotes, code and images, and nothing else.
 */
sealed class Block {
    data class Heading(val level: Int, val text: CharSequence) : Block()
    data class Paragraph(val text: CharSequence) : Block()
    data class Quote(val text: CharSequence) : Block()
    data class Code(val text: CharSequence) : Block()
    data class Bullet(val text: CharSequence, val marker: String) : Block()
    data class Figure(val path: String, val caption: String) : Block()
    object Rule : Block()
}

object Markdown {

    private val IMAGE = Regex("""^!\[(.*?)]\((.+?)\)\s*$""")
    private val ATX = Regex("""^(#{1,6})\s+(.*)$""")
    private val UL = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
    private val OL = Regex("""^\s{0,3}(\d+)[.)]\s+(.*)$""")

    fun parse(source: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        val para = StringBuilder()

        fun flushParagraph() {
            val t = para.toString().trim()
            if (t.isNotEmpty()) blocks += Block.Paragraph(inline(t))
            para.setLength(0)
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()

            when {
                line.isBlank() -> {
                    flushParagraph()
                    i++
                }

                line.trimStart().startsWith("```") -> {
                    flushParagraph()
                    i++
                    val code = StringBuilder()
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.appendLine(lines[i])
                        i++
                    }
                    if (i < lines.size) i++
                    blocks += Block.Code(code.toString().trimEnd())
                }

                line.trim().let { it == "---" || it == "***" || it == "___" } -> {
                    flushParagraph()
                    blocks += Block.Rule
                    i++
                }

                IMAGE.matches(line.trim()) -> {
                    flushParagraph()
                    val m = IMAGE.find(line.trim())!!
                    blocks += Block.Figure(m.groupValues[2].trim(), m.groupValues[1].trim())
                    i++
                }

                ATX.matches(line) -> {
                    flushParagraph()
                    val m = ATX.find(line)!!
                    blocks += Block.Heading(
                        m.groupValues[1].length,
                        inline(m.groupValues[2].trim().trimEnd('#').trim())
                    )
                    i++
                }

                line.trimStart().startsWith(">") -> {
                    flushParagraph()
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quote.append(lines[i].trimStart().removePrefix(">").trim()).append(' ')
                        i++
                    }
                    blocks += Block.Quote(inline(quote.toString().trim()))
                }

                UL.matches(line) -> {
                    flushParagraph()
                    blocks += Block.Bullet(inline(UL.find(line)!!.groupValues[1].trim()), "\u2022")
                    i++
                }

                OL.matches(line) -> {
                    flushParagraph()
                    val m = OL.find(line)!!
                    blocks += Block.Bullet(inline(m.groupValues[2].trim()), m.groupValues[1] + ".")
                    i++
                }

                else -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(line.trim())
                    i++
                }
            }
        }
        flushParagraph()
        return blocks
    }

    /**
     * Inline emphasis, code and links. Link URLs are dropped rather than styled:
     * there is no browser worth opening on this device, so a blue underline would
     * be a promise the hardware cannot keep.
     */
    fun inline(text: String): CharSequence {
        val out = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> { out.append(text[i + 1]); i += 2 }

                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end == -1) { out.append(c); i++ } else {
                        val start = out.length
                        out.append(text, i + 1, end)
                        out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
                        i = end + 1
                    }
                }

                c == '[' -> {
                    val close = text.indexOf(']', i)
                    if (close > 0 && close + 1 < text.length && text[close + 1] == '(') {
                        val paren = text.indexOf(')', close)
                        if (paren > 0) {
                            out.append(inline(text.substring(i + 1, close)))
                            i = paren + 1
                        } else { out.append(c); i++ }
                    } else { out.append(c); i++ }
                }

                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val token = text.substring(i, i + 2)
                    val end = text.indexOf(token, i + 2)
                    if (end == -1) { out.append(token); i += 2 } else {
                        val start = out.length
                        out.append(inline(text.substring(i + 2, end)))
                        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                        i = end + 2
                    }
                }

                c == '*' || c == '_' -> {
                    val end = text.indexOf(c, i + 1)
                    if (end == -1) { out.append(c); i++ } else {
                        val start = out.length
                        out.append(inline(text.substring(i + 1, end)))
                        out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, SPAN)
                        i = end + 1
                    }
                }

                else -> { out.append(c); i++ }
            }
        }
        return out
    }

    private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
}
