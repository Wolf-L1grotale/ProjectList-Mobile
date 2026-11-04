package com.projectlist.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullets(val items: List<String>) : MarkdownBlock
    data class Numbered(val items: List<Pair<Int, String>>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val code: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    SelectionContainer {
        Column(modifier) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> {
                        Text(
                            text = inlineMarkdown(block.text),
                            style = when (block.level) {
                                1 -> MaterialTheme.typography.headlineSmall
                                2 -> MaterialTheme.typography.titleLarge
                                3 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            },
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = if (block.level <= 2) 14.dp else 10.dp, bottom = 4.dp),
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        Text(
                            text = inlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }

                    is MarkdownBlock.Bullets -> Column(Modifier.padding(vertical = 2.dp)) {
                        block.items.forEach { item ->
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text("•", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = inlineMarkdown(item),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    is MarkdownBlock.Numbered -> Column(Modifier.padding(vertical = 2.dp)) {
                        block.items.forEach { (number, item) ->
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    "$number.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = inlineMarkdown(item),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    is MarkdownBlock.Quote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .height(IntrinsicSize.Min),
                        ) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = inlineMarkdown(block.text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is MarkdownBlock.Code -> CodeBlock(block.code, Modifier.padding(vertical = 6.dp))

                    MarkdownBlock.Divider -> HorizontalDivider(Modifier.padding(vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .horizontalScroll(scroll),
    ) {
        SelectionContainer {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, linkColor, codeBackground) {
        buildAnnotatedString {
            var index = 0
            while (index < text.length) {
                when {
                    text.startsWith("**", index) || text.startsWith("__", index) -> {
                        val marker = text.substring(index, index + 2)
                        val end = text.indexOf(marker, index + 2)
                        if (end > index + 2) {
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                            append(text.substring(index + 2, end))
                            pop()
                            index = end + 2
                        } else {
                            append(text[index])
                            index++
                        }
                    }

                    text[index] == '`' -> {
                        val end = text.indexOf('`', index + 1)
                        if (end > index + 1) {
                            pushStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = codeBackground,
                                    fontSize = 13.sp,
                                ),
                            )
                            append(text.substring(index + 1, end))
                            pop()
                            index = end + 1
                        } else {
                            append(text[index])
                            index++
                        }
                    }

                    text[index] == '*' || text[index] == '_' -> {
                        val marker = text[index]
                        val end = text.indexOf(marker, index + 1)
                        if (end > index + 1) {
                            pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                            append(text.substring(index + 1, end))
                            pop()
                            index = end + 1
                        } else {
                            append(text[index])
                            index++
                        }
                    }

                    text[index] == '[' -> {
                        val close = text.indexOf(']', index + 1)
                        val openParen = if (close >= 0) text.indexOf('(', close + 1) else -1
                        val end = if (openParen == close + 1) text.indexOf(')', openParen + 1) else -1
                        if (close > index + 1 && end > openParen) {
                            pushStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            )
                            append(text.substring(index + 1, close))
                            pop()
                            index = end + 1
                        } else {
                            append(text[index])
                            index++
                        }
                    }

                    text.startsWith("~~", index) -> {
                        val end = text.indexOf("~~", index + 2)
                        if (end > index + 2) {
                            pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                            append(text.substring(index + 2, end))
                            pop()
                            index = end + 2
                        } else {
                            append(text[index])
                            index++
                        }
                    }

                    else -> {
                        append(text[index])
                        index++
                    }
                }
            }
        }
    }
}

private fun parseMarkdown(source: String): List<MarkdownBlock> {
    val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    var bullets = mutableListOf<String>()
    var numbered = mutableListOf<Pair<Int, String>>()
    var code = mutableListOf<String>()
    var inCode = false
    var codeFence = "```"

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim()))
            paragraph.clear()
        }
    }

    fun flushLists() {
        if (bullets.isNotEmpty()) {
            blocks.add(MarkdownBlock.Bullets(bullets.toList()))
            bullets = mutableListOf()
        }
        if (numbered.isNotEmpty()) {
            blocks.add(MarkdownBlock.Numbered(numbered.toList()))
            numbered = mutableListOf()
        }
    }

    for (raw in lines) {
        val line = raw.trimEnd()
        val trimmed = line.trim()

        if (inCode) {
            if (trimmed.startsWith(codeFence)) {
                blocks.add(MarkdownBlock.Code(code.joinToString("\n")))
                code = mutableListOf()
                inCode = false
            } else {
                code.add(raw)
            }
            continue
        }

        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushParagraph()
            flushLists()
            codeFence = if (trimmed.startsWith("```")) "```" else "~~~"
            inCode = true
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            flushLists()
            continue
        }

        val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (heading != null) {
            flushParagraph()
            flushLists()
            blocks.add(MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            continue
        }

        if (Regex("^(---+|\\*\\*\\*+|___+)$").matches(trimmed)) {
            flushParagraph()
            flushLists()
            blocks.add(MarkdownBlock.Divider)
            continue
        }

        val bullet = Regex("^[-*+]\\s+(.*)$").find(trimmed)
        if (bullet != null) {
            flushParagraph()
            if (numbered.isNotEmpty()) flushLists()
            bullets.add(bullet.groupValues[1])
            continue
        }

        val ordered = Regex("^(\\d+)[.)]\\s+(.*)$").find(trimmed)
        if (ordered != null) {
            flushParagraph()
            if (bullets.isNotEmpty()) flushLists()
            val number = ordered.groupValues[1].toIntOrNull() ?: (numbered.size + 1)
            numbered.add(number to ordered.groupValues[2])
            continue
        }

        if (trimmed.startsWith("> ")) {
            flushParagraph()
            flushLists()
            blocks.add(MarkdownBlock.Quote(trimmed.removePrefix("> ").trim()))
            continue
        }
        if (trimmed == ">") continue

        flushLists()
        paragraph.add(trimmed)
    }

    if (inCode && code.isNotEmpty()) {
        blocks.add(MarkdownBlock.Code(code.joinToString("\n")))
    }
    flushParagraph()
    flushLists()
    return blocks
}
