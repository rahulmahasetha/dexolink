package com.macdroid.core

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class EnhancedClipboardTest {

    @Test
    fun testSanitizePlainText_normalizesLineEndings() {
        val windowsText = "Line 1\r\nLine 2\r\nLine 3"
        val legacyMacText = "Line A\rLine B\rLine C"
        val mixedText = "Mix 1\r\nMix 2\rMix 3\nMix 4"

        assertEquals("Line 1\nLine 2\nLine 3", EnhancedClipboardHelper.sanitizePlainText(windowsText))
        assertEquals("Line A\nLine B\nLine C", EnhancedClipboardHelper.sanitizePlainText(legacyMacText))
        assertEquals("Mix 1\nMix 2\nMix 3\nMix 4", EnhancedClipboardHelper.sanitizePlainText(mixedText))
    }

    @Test
    fun testSanitizePlainText_stripsInvisibleZeroWidthChars() {
        val invisiblePayload = "Password\u200B123\u200C456\u200D\uFEFF\u200E\u200F"
        val sanitized = EnhancedClipboardHelper.sanitizePlainText(invisiblePayload)

        assertEquals("Password123456", sanitized)
        assertFalse(sanitized.contains('\u200B'))
        assertFalse(sanitized.contains('\uFEFF'))
    }

    @Test
    fun testSanitizePlainText_preservesLegitimateWhitespace() {
        val codeSnippet = "    fun test() {\n        val x = 1\n    }"
        val sanitized = EnhancedClipboardHelper.sanitizePlainText(codeSnippet)

        assertEquals(codeSnippet, sanitized)
    }

    @Test
    fun testContainsHtml() {
        assertTrue(EnhancedClipboardHelper.containsHtml("<p>Hello World</p>"))
        assertTrue(EnhancedClipboardHelper.containsHtml("<b>Important</b>"))
        assertTrue(EnhancedClipboardHelper.containsHtml("<a href=\"https://dexo.link\">Link</a>"))
        assertTrue(EnhancedClipboardHelper.containsHtml("<div class=\"box\"><span /></div>"))

        assertFalse(EnhancedClipboardHelper.containsHtml("Plain text without tags"))
        assertFalse(EnhancedClipboardHelper.containsHtml("Math expression: 5 < 10 and 10 > 5"))
        assertFalse(EnhancedClipboardHelper.containsHtml(""))
    }

    @Test
    fun testToHtmlRepresentation_plainText() {
        val plain = "Hello World\nSecond Line\n\nFourth Line"
        val html = EnhancedClipboardHelper.toHtmlRepresentation(plain)

        assertTrue(html.contains("<p>Hello World</p>"))
        assertTrue(html.contains("<p>Second Line</p>"))
        assertTrue(html.contains("<p><br></p>"))
        assertTrue(html.contains("<p>Fourth Line</p>"))
    }

    @Test
    fun testToHtmlRepresentation_escapesHtmlEntities() {
        val plain = "AT&T <Phone> \"5G\" 'fast'"
        val html = EnhancedClipboardHelper.toHtmlRepresentation(plain)

        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("&lt;Phone&gt;"))
        assertTrue(html.contains("&quot;5G&quot;"))
        assertTrue(html.contains("&#39;fast&#39;"))
    }

    @Test
    fun testToHtmlRepresentation_urls() {
        val url = "https://github.com/rahulmahasetha/dexolink"
        val html = EnhancedClipboardHelper.toHtmlRepresentation(url)

        assertEquals("<a href=\"https://github.com/rahulmahasetha/dexolink\">https://github.com/rahulmahasetha/dexolink</a>", html)
    }

    @Test
    fun testToHtmlRepresentation_existingHtmlPreserved() {
        val existingHtml = "<h1>Title</h1><p>Body text</p>"
        val result = EnhancedClipboardHelper.toHtmlRepresentation(existingHtml)

        assertEquals(existingHtml, result)
    }

    @Test
    fun testCreateDirectShareIntentRunsCleanly() {
        val sampleText = "Text to share directly\r\nwith line break"
        val intent = EnhancedClipboardHelper.createDirectShareIntent(sampleText, "Test Title")
        assertNotNull(intent)
    }

    @Test
    fun testClipboardSyncModeEnumIntegrity() {
        assertEquals("disabled", ClipboardSyncMode.DISABLED.id)
        assertEquals("android_to_mac", ClipboardSyncMode.ANDROID_TO_MAC.id)
        assertEquals("mac_to_android", ClipboardSyncMode.MAC_TO_ANDROID.id)
        assertEquals("two_way", ClipboardSyncMode.TWO_WAY.id)

        assertEquals(ClipboardSyncMode.TWO_WAY, ClipboardSyncMode.fromId("two_way"))
        assertEquals(ClipboardSyncMode.DISABLED, ClipboardSyncMode.fromId("disabled"))
        assertEquals(ClipboardSyncMode.TWO_WAY, ClipboardSyncMode.fromId("unknown_mode"))
    }
}
