package com.devhc.aidevmob.ui

import android.content.Context
import android.content.res.Configuration

/**
 * Colours for the file preview pane, kept separate from the app's own theme.
 *
 * The chrome around it stays light, but code is read for long stretches and plenty of people want it
 * dark regardless - so this is its own setting rather than a consequence of the system theme, with
 * "follow the system" available for those who do want it to track.
 *
 * Both schemes are authored, not inverted: a naive inversion turns a comment grey into a muddy
 * near-white and pushes every accent to the same lightness.
 */
data class PreviewTheme(
    val background: Int,
    val foreground: Int,
    /** Line-number gutter: present enough to count by, quiet enough to ignore. */
    val gutter: Int,
    val gutterBackground: Int,
    val syntax: SyntaxHighlighter.Palette,
    val markdown: MarkdownRenderer.Palette
) {
    companion object {

        /** What the user picked in settings. */
        enum class Mode { SYSTEM, LIGHT, DARK;

            companion object {
                fun from(stored: String?): Mode =
                    entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } ?: SYSTEM
            }
        }

        fun resolve(context: Context, mode: Mode): PreviewTheme = when (mode) {
            Mode.LIGHT -> LIGHT
            Mode.DARK -> DARK
            Mode.SYSTEM -> {
                val night = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                if (night) DARK else LIGHT
            }
        }

        private val LIGHT = PreviewTheme(
            background = 0xFFFBFDFB.toInt(),
            foreground = 0xFF1A211D.toInt(),
            gutter = 0xFFA8B5AE.toInt(),
            gutterBackground = 0xFFF1F6F3.toInt(),
            syntax = SyntaxHighlighter.Palette(
                keyword = 0xFF7A3E9D.toInt(),
                string = 0xFF1F6F43.toInt(),
                comment = 0xFF7C8A83.toInt(),
                number = 0xFFB2601F.toInt(),
                annotation = 0xFF2B5D9B.toInt()
            ),
            markdown = MarkdownRenderer.Palette(
                heading = 0xFF23594B.toInt(),
                code = 0xFF1F6F43.toInt(),
                codeBackground = 0xFFEDF3EF.toInt(),
                quote = 0xFF6B7A72.toInt(),
                link = 0xFF2F6F5E.toInt(),
                rule = 0xFFC6D5CC.toInt()
            )
        )

        private val DARK = PreviewTheme(
            background = 0xFF0F1512.toInt(),
            foreground = 0xFFDCE7E1.toInt(),
            gutter = 0xFF52635B.toInt(),
            gutterBackground = 0xFF141B18.toInt(),
            syntax = SyntaxHighlighter.Palette(
                keyword = 0xFFC792EA.toInt(),
                string = 0xFF7DD3A0.toInt(),
                comment = 0xFF6B7A72.toInt(),
                number = 0xFFF0A868.toInt(),
                annotation = 0xFF82AAFF.toInt()
            ),
            markdown = MarkdownRenderer.Palette(
                heading = 0xFF6FD3AE.toInt(),
                code = 0xFF7DD3A0.toInt(),
                codeBackground = 0xFF19231F.toInt(),
                quote = 0xFF93A69D.toInt(),
                link = 0xFF6FD3AE.toInt(),
                rule = 0xFF2A3830.toInt()
            )
        )
    }
}
