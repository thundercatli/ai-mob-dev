package com.devhc.aidevmob.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Colours source code for the preview pane.
 *
 * A single left-to-right scan rather than a pile of regexes: comments, strings and code overlap in
 * ways regex passes get wrong - a `//` inside a string literal, a quote inside a comment - and the
 * scanner simply never leaves the state it is in until the closing delimiter arrives.
 *
 * Deliberately not a parser. It knows about comments, strings, numbers and a keyword list, which is
 * what makes a wall of code readable on a phone; anything more would be a language server.
 *
 * Runs on the caller's thread - hand it a big file only from a worker.
 */
object SyntaxHighlighter {

    /** Palette pulled from theme-resolved colours, so the pane matches the rest of the app. */
    data class Palette(
        val keyword: Int,
        val string: Int,
        val comment: Int,
        val number: Int,
        val annotation: Int
    )

    /** Beyond this the spans cost more than the readability is worth on a phone. */
    private const val MAX_HIGHLIGHT_CHARS = 200_000

    private val C_FAMILY = setOf(
        "abstract", "as", "assert", "async", "await", "boolean", "break", "byte", "case", "catch",
        "char", "class", "companion", "const", "constructor", "continue", "data", "def", "default",
        "delete", "do", "double", "elif", "else", "enum", "export", "extends", "external", "false",
        "final", "finally", "float", "fn", "for", "from", "fun", "func", "function", "get", "go",
        "if", "impl", "implements", "import", "in", "init", "instanceof", "int", "interface",
        "internal", "is", "lateinit", "let", "long", "match", "mod", "module", "mut", "new", "null",
        "nullptr", "object", "open", "operator", "override", "package", "private", "protected",
        "pub", "public", "range", "return", "sealed", "self", "set", "short", "static", "struct",
        "super", "suspend", "switch", "this", "throw", "throws", "trait", "true", "try", "type",
        "typedef", "typeof", "union", "unsafe", "use", "val", "var", "void", "when", "where",
        "while", "with", "yield"
    )

    private val SHELL = setOf(
        "alias", "break", "case", "cd", "continue", "do", "done", "echo", "elif", "else", "esac",
        "eval", "exec", "exit", "export", "fi", "for", "function", "if", "in", "local", "read",
        "return", "set", "shift", "source", "then", "unset", "until", "while"
    )

    private val SQL = setOf(
        "alter", "and", "as", "asc", "between", "by", "case", "create", "delete", "desc", "distinct",
        "drop", "exists", "from", "group", "having", "in", "index", "inner", "insert", "into",
        "join", "left", "like", "limit", "not", "null", "on", "or", "order", "outer", "primary",
        "select", "set", "table", "then", "union", "update", "values", "where"
    )

    /** What a given extension treats as a comment, a string, and a keyword. */
    private class Dialect(
        val lineComments: List<String>,
        val blockComment: Pair<String, String>?,
        val stringQuotes: Set<Char>,
        val keywords: Set<String>,
        val caseSensitive: Boolean = true
    )

    private val PLAIN = Dialect(emptyList(), null, emptySet(), emptySet())

    private fun dialectFor(fileName: String): Dialect {
        val name = fileName.substringAfterLast('/').lowercase()
        val extension = name.substringAfterLast('.', "")
        return when {
            extension in setOf("kt", "kts", "java", "js", "mjs", "ts", "tsx", "jsx", "go", "rs",
                "c", "h", "cpp", "hpp", "cc", "cs", "swift", "php", "scala", "dart", "gradle") ->
                Dialect(listOf("//"), "/*" to "*/", setOf('"', '\'', '`'), C_FAMILY)

            extension in setOf("py", "rb", "pl", "r") ->
                Dialect(listOf("#"), null, setOf('"', '\''), C_FAMILY)

            extension in setOf("sh", "bash", "zsh", "fish", "env", "conf", "cfg", "ini",
                "properties", "toml", "yaml", "yml", "dockerfile") ->
                Dialect(listOf("#"), null, setOf('"', '\''), SHELL)

            extension == "sql" ->
                Dialect(listOf("--"), "/*" to "*/", setOf('"', '\''), SQL, caseSensitive = false)

            extension in setOf("css", "scss", "less") ->
                Dialect(emptyList(), "/*" to "*/", setOf('"', '\''), emptySet())

            extension in setOf("json", "xml", "html", "htm", "svg") ->
                Dialect(emptyList(), "<!--" to "-->", setOf('"', '\''), emptySet())

            name == "makefile" || name == "dockerfile" || name.startsWith(".") ->
                Dialect(listOf("#"), null, setOf('"', '\''), SHELL)

            else -> PLAIN
        }
    }

    /** True when [fileName] has a dialect worth colouring; plain text is left alone. */
    fun canHighlight(fileName: String): Boolean = dialectFor(fileName) !== PLAIN

    fun highlight(text: String, fileName: String, palette: Palette): CharSequence {
        val dialect = dialectFor(fileName)
        if (dialect === PLAIN || text.length > MAX_HIGHLIGHT_CHARS) return text

        val out = SpannableStringBuilder(text)
        var i = 0

        fun paint(start: Int, end: Int, color: Int, italic: Boolean = false) {
            out.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (italic) {
                out.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        while (i < text.length) {
            val c = text[i]

            // Comments first: inside one, nothing else counts.
            val lineComment = dialect.lineComments.firstOrNull { text.startsWith(it, i) }
            if (lineComment != null) {
                val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                paint(i, end, palette.comment, italic = true)
                i = end
                continue
            }

            val block = dialect.blockComment
            if (block != null && text.startsWith(block.first, i)) {
                val closed = text.indexOf(block.second, i + block.first.length)
                val end = if (closed < 0) text.length else closed + block.second.length
                paint(i, end, palette.comment, italic = true)
                i = end
                continue
            }

            if (c in dialect.stringQuotes) {
                val end = closingQuote(text, i, c)
                paint(i, end, palette.string)
                i = end
                continue
            }

            if (c.isDigit() && (i == 0 || !text[i - 1].isLetterOrDigit() && text[i - 1] != '_')) {
                var j = i
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == 'x')) j++
                paint(i, j, palette.number)
                i = j
                continue
            }

            // Annotations and decorators read as structure, so they get their own colour.
            if ((c == '@' || c == '#') && i + 1 < text.length && text[i + 1].isLetter() &&
                dialect.lineComments.none { it.startsWith(c) }
            ) {
                var j = i + 1
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.')) j++
                paint(i, j, palette.annotation)
                i = j
                continue
            }

            if (c.isLetter() || c == '_') {
                var j = i
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                val match = if (dialect.caseSensitive) word else word.lowercase()
                if (match in dialect.keywords) paint(i, j, palette.keyword)
                i = j
                continue
            }

            i++
        }
        return out
    }

    /**
     * End of the string literal opened at [start], one past the closing quote. An unterminated literal
     * stops at the line end rather than swallowing the rest of the file.
     */
    private fun closingQuote(text: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                quote -> return i + 1
                '\n' -> if (quote != '`') return i
            }
            i++
        }
        return text.length
    }
}
