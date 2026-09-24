package com.lukas.jarvis.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * The small part of Markdown that replies actually use, drawn properly instead
 * of shown as asterisks: **bold**, *italic*, `code`, [links](https://…), bare
 * links, headings, bullets and numbered lists, and fenced code blocks.
 *
 * Deliberately a line-by-line reader rather than a full parser. A reply is a
 * few short paragraphs; what matters is that nothing a model writes can make
 * it throw, and that anything it does not recognise is shown as plain text.
 */
object Markdown {

    fun render(
        source: String,
        accent: Color,
        codeBackground: Color,
        muted: Color
    ): AnnotatedString = buildAnnotatedString {
        val lines = source.replace("\r\n", "\n").trimEnd().split("\n")
        var inFence = false
        lines.forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                return@forEachIndexed
            }
            if (inFence) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                    append(line)
                }
            } else {
                val trimmed = line.trimStart()
                when {
                    HEADING.matches(trimmed) -> {
                        val text = trimmed.dropWhile { it == '#' }.trim()
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = accent)) {
                            inline(text, accent, codeBackground)
                        }
                    }
                    BULLET.matches(trimmed) -> {
                        val depth = (line.length - trimmed.length) / 2
                        append("  ".repeat(depth.coerceAtMost(3)))
                        withStyle(SpanStyle(color = accent)) { append("•  ") }
                        inline(trimmed.drop(2).trimStart(), accent, codeBackground)
                    }
                    NUMBERED.containsMatchIn(trimmed) -> {
                        val match = NUMBERED.find(trimmed)!!
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                            append(match.groupValues[1] + ".  ")
                        }
                        inline(trimmed.substring(match.value.length).trimStart(), accent, codeBackground)
                    }
                    trimmed.startsWith(">") -> {
                        withStyle(SpanStyle(color = muted, fontStyle = FontStyle.Italic)) {
                            inline(trimmed.drop(1).trimStart(), accent, codeBackground)
                        }
                    }
                    trimmed == "---" || trimmed == "***" -> append("—")
                    else -> inline(line, accent, codeBackground)
                }
            }
            if (index < lines.lastIndex) append("\n")
        }
    }

    /** Bold, italic, code and links inside one line. */
    private fun AnnotatedString.Builder.inline(text: String, accent: Color, codeBackground: Color) {
        var i = 0
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                linkify(plain.toString(), accent)
                plain.setLength(0)
            }
        }
        while (i < text.length) {
            val rest = text.substring(i)
            when {
                rest.startsWith("**") && rest.indexOf("**", 2) > 2 -> {
                    flush()
                    val end = rest.indexOf("**", 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        inline(rest.substring(2, end), accent, codeBackground)
                    }
                    i += end + 2
                }
                rest.startsWith("__") && rest.indexOf("__", 2) > 2 -> {
                    flush()
                    val end = rest.indexOf("__", 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(2, end)) }
                    i += end + 2
                }
                rest.startsWith("`") && rest.indexOf('`', 1) > 1 -> {
                    flush()
                    val end = rest.indexOf('`', 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                        append(rest.substring(1, end))
                    }
                    i += end + 1
                }
                rest.startsWith("*") && !rest.startsWith("* ") && rest.indexOf('*', 1) > 1 -> {
                    flush()
                    val end = rest.indexOf('*', 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(rest.substring(1, end)) }
                    i += end + 1
                }
                rest.startsWith("[") && MD_LINK.find(rest)?.range?.first == 0 -> {
                    flush()
                    val match = MD_LINK.find(rest)!!
                    val label = match.groupValues[1]
                    val url = match.groupValues[2]
                    link(label, url, accent)
                    i += match.value.length
                }
                else -> {
                    plain.append(text[i])
                    i++
                }
            }
        }
        flush()
    }

    /** Bare http(s) links in plain text become tappable. */
    private fun AnnotatedString.Builder.linkify(text: String, accent: Color) {
        var last = 0
        for (match in BARE_URL.findAll(text)) {
            append(text.substring(last, match.range.first))
            val url = match.value.trimEnd('.', ',', ')', ';', ':', '!', '?')
            link(url.removePrefix("https://").removePrefix("http://").take(48), url, accent)
            append(match.value.substring(url.length))
            last = match.range.last + 1
        }
        append(text.substring(last))
    }

    private fun AnnotatedString.Builder.link(label: String, url: String, accent: Color) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            append(label)
            return
        }
        withLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
            )
        ) { append(label) }
    }

    /** Markdown punctuation removed, for copying or sharing as plain text. */
    fun plain(source: String): String = source
        .replace(Regex("```[a-zA-Z]*\\n?"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1 ($2)")
        .replace(Regex("(?m)^#+\\s*"), "")
        .trim()

    private val HEADING = Regex("^#{1,6}\\s+.+")
    private val BULLET = Regex("^[-*•]\\s+.*")
    private val NUMBERED = Regex("^(\\d{1,3})[.)]\\s+")
    private val MD_LINK = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")
    private val BARE_URL = Regex("https?://[^\\s<>\"]+")
}
