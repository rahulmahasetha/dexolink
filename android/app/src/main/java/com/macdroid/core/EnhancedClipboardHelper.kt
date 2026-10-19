package com.macdroid.core

import android.content.Intent

/**
 * Helper utility for DexoLink's Enhanced Clipboard feature.
 *
 * Provides legitimate formatting, sanitization, and alternative paste workflows:
 * 1. Line-ending normalization (\r\n -> \n, \r -> \n)
 * 2. Invisible / zero-width character stripping (\u200B, \uFEFF, etc.)
 * 3. Multi-MIME HTML generation for rich web editors (Google Docs, Notion, Slack, Samsung Notes)
 * 4. Direct Intent-based Share workflow for websites with browser paste restrictions
 */
object EnhancedClipboardHelper {

    // Regex matching invisible control / formatting characters:
    // \u200B: Zero-width space
    // \u200C: Zero-width non-joiner
    // \u200D: Zero-width joiner
    // \uFEFF: Zero-width no-break space / Byte Order Mark
    // \u200E, \u200F: Left-to-right / Right-to-left marks
    private val INVISIBLE_CHARS_REGEX = Regex("[\u200B-\u200D\uFEFF\u200E\u200F]")

    // Regex matching HTML tags
    private val HTML_TAG_REGEX = Regex("<([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*>(.*?)</\\1>|<([a-zA-Z][a-zA-Z0-9]*)\\b[^/>]*/>", RegexOption.DOT_MATCHES_ALL)

    // Regex matching standard HTTP/HTTPS URLs
    private val URL_REGEX = Regex("^https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=]+$")

    /**
     * Sanitizes plain text for stubborn web inputs and browser form fields:
     * - Normalizes Windows (\r\n) and legacy Mac (\r) line breaks to standard Unix (\n)
     * - Removes invisible zero-width characters that cause form validation errors
     */
    fun sanitizePlainText(input: String): String {
        if (input.isEmpty()) return ""
        val normalized = input.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.replace(INVISIBLE_CHARS_REGEX, "")
    }

    /**
     * Checks if the text already contains HTML markup.
     */
    fun containsHtml(text: String): Boolean {
        if (text.isEmpty()) return false
        return HTML_TAG_REGEX.containsMatchIn(text)
    }

    /**
     * Generates a rich HTML representation for web rich-text editors (e.g. Google Docs, Notion, Slack, Gmail)
     * so that multi-line structure, paragraphs, or links are parsed natively rather than collapsed.
     */
    fun toHtmlRepresentation(text: String): String {
        val clean = sanitizePlainText(text)
        if (clean.isEmpty()) return ""

        // If text already has HTML tags, return as-is
        if (containsHtml(clean)) {
            return clean
        }

        // If it's a URL, wrap in an anchor link
        if (URL_REGEX.matches(clean)) {
            return "<a href=\"$clean\">$clean</a>"
        }

        // Escape standard HTML entities
        val escaped = clean
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

        val lines = escaped.split("\n")
        val htmlBuilder = StringBuilder()
        for (line in lines) {
            if (line.isEmpty()) {
                htmlBuilder.append("<p><br></p>")
            } else {
                htmlBuilder.append("<p>").append(line).append("</p>")
            }
        }
        return htmlBuilder.toString()
    }

    /**
     * Creates an alternative paste intent via standard Android system Share.
     * This allows users to legitimately send text directly into target apps or browser tabs,
     * bypassing in-page JavaScript onpaste event blockers without violating security boundaries.
     */
    fun createDirectShareIntent(text: String, title: String = "Paste with DexoLink"): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sanitizePlainText(text))
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = try {
            Intent.createChooser(sendIntent, title)
        } catch (e: Exception) {
            null
        }
        return (chooser ?: sendIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
