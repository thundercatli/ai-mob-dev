package com.devhc.aidevmob.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * Renders a practical subset of Markdown for the preview pane.
 *
 * Line-based on purpose: headings, fences, lists and quotes are line constructs, and resolving them
 * first means the inline pass never has to care whether it is inside a code fence - the fence's body
 * is emitted verbatim and skipped.
 *
 * Not CommonMark. Reference links, nested blockquotes, tables and HTML blocks are left as their
 * source text rather than half-rendered, because a wrong rendering hides content that raw text at
 * least still shows.
 */
object MarkdownRenderer {

    data class Palette(
        val heading: Int,
        val code: Int,
        val codeBackground: Int,
        val quote: Int,
        val link: Int,
        val rule: Int
    )

    fun render(source: String, palette: Palette): CharSequence {
        val out = SpannableStringBuilder()
        val lines = source.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code: emitted verbatim, so nothing inside it is mistaken for markup.
            val fence = line.trimStart().takeIf { it.startsWith("```") || it.startsWith("~~~") }
            if (fence != null) {
                val marker = fence.take(3)
                val start = out.length
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                    out.append(lines[i]).append('\n')
                    i++
                }
                i++ // closing fence
                if (out.length > start) {
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
                    out.setSpan(ForegroundColorSpan(palette.code), start, out.length, SPAN)
                    out.setSpan(BackgroundColorSpan(palette.codeBackground), start, out.length, SPAN)
                }
                out.append('\n')
                continue
            }

            when {
                line.isBlank() -> out.append('\n')

                // Horizontal rule, drawn rather than described.
                line.trim().let { it.length >= 3 && (it.all { c -> c == '-' } || it.all { c -> c == '*' }) } -> {
                    val start = out.length
                    out.append("────────────────────\n")
                    out.setSpan(ForegroundColorSpan(palette.rule), start, out.length, SPAN)
                }

                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length.coerceAtMost(6)
                    val start = out.length
                    appendInline(out, line.drop(level).trim(), palette)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                    // 1.5x down to 1.0x, so the hierarchy is visible without shouting.
                    out.setSpan(RelativeSizeSpan(1f + (0.5f - (level - 1) * 0.1f).coerceAtLeast(0f)), start, out.length, SPAN)
                    out.setSpan(ForegroundColorSpan(palette.heading), start, out.length, SPAN)
                    out.append("\n\n")
                }

                line.trimStart().startsWith("> ") -> {
                    val start = out.length
                    appendInline(out, line.trimStart().removePrefix("> "), palette)
                    out.setSpan(ForegroundColorSpan(palette.quote), start, out.length, SPAN)
                    out.setSpan(LeadingMarginSpan.Standard(QUOTE_INDENT), start, out.length, SPAN)
                    out.append('\n')
                }

                BULLET.matches(line) -> {
                    val start = out.length
                    out.append("•  ")
                    appendInline(out, line.trimStart().drop(2), palette)
                    out.setSpan(LeadingMarginSpan.Standard(0, LIST_INDENT), start, out.length, SPAN)
                    out.append('\n')
                }

                NUMBERED.matches(line) -> {
                    val start = out.length
                    val trimmed = line.trimStart()
                    val marker = trimmed.substringBefore(' ')
                    out.append(marker).append("  ")
                    appendInline(out, trimmed.substringAfter(' '), palette)
                    out.setSpan(LeadingMarginSpan.Standard(0, LIST_INDENT), start, out.length, SPAN)
                    out.append('\n')
                }

                else -> {
                    appendInline(out, line, palette)
                    out.append('\n')
                }
            }
            i++
        }
        return out
    }

    /** Inline pass: code, bold, italic, strikethrough, links. */
    private fun appendInline(out: SpannableStringBuilder, text: String, palette: Palette) {
        var i = 0
        while (i < text.length) {
            // Inline code wins over emphasis: `**not bold**` inside backticks is literal.
            if (text[i] == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > i) {
                    val start = out.length
                    out.append(text, i + 1, close)
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
                    out.setSpan(ForegroundColorSpan(palette.code), start, out.length, SPAN)
                    out.setSpan(BackgroundColorSpan(palette.codeBackground), start, out.length, SPAN)
                    i = close + 1
                    continue
                }
            }

            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                val marker = text.substring(i, i + 2)
                val close = text.indexOf(marker, i + 2)
                if (close > i) {
                    val start = out.length
                    appendInline(out, text.substring(i + 2, close), palette)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                    i = close + 2
                    continue
                }
            }

            if (text.startsWith("~~", i)) {
                val close = text.indexOf("~~", i + 2)
                if (close > i) {
                    val start = out.length
                    appendInline(out, text.substring(i + 2, close), palette)
                    out.setSpan(StrikethroughSpan(), start, out.length, SPAN)
                    i = close + 2
                    continue
                }
            }

            if ((text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != text[i]) {
                val close = text.indexOf(text[i], i + 1)
                if (close > i) {
                    val start = out.length
                    appendInline(out, text.substring(i + 1, close), palette)
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, SPAN)
                    i = close + 1
                    continue
                }
            }

            // [label](target): the label is shown, the target is dropped - there is nowhere to go
            // from a preview pane, and showing both would double the text.
            if (text[i] == '[') {
                val labelEnd = text.indexOf(']', i)
                if (labelEnd > i && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') {
                    val targetEnd = text.indexOf(')', labelEnd)
                    if (targetEnd > labelEnd) {
                        val start = out.length
                        appendInline(out, text.substring(i + 1, labelEnd), palette)
                        out.setSpan(ForegroundColorSpan(palette.link), start, out.length, SPAN)
                        out.setSpan(UnderlineSpan(), start, out.length, SPAN)
                        i = targetEnd + 1
                        continue
                    }
                }
            }

            out.append(text[i])
            i++
        }
    }

    private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    private const val LIST_INDENT = 34
    private const val QUOTE_INDENT = 28

    private val BULLET = Regex("^\\s*[-*+] .*")
    private val NUMBERED = Regex("^\\s*\\d+[.)] .*")
}
