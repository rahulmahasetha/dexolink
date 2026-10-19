package com.macdroid.continuity

import com.macdroid.networking.ContinuityAckMessage
import com.macdroid.networking.ContinuityActivityMessage
import com.macdroid.networking.ContinuityDismissMessage
import com.macdroid.networking.ContinuityStateMessage
import com.macdroid.networking.ProtocolMessage
import org.junit.Assert.*
import org.junit.Test

class ContinuityTest {

    @Test
    fun testContinuityActivityModel() {
        val pdfActivity = ContinuityActivity(
            activityId = "act-1",
            activityType = "pdf",
            title = "Android Guide.pdf",
            url = "https://example.com/guide.pdf",
            position = 12,
            progress = 0.65,
            sourceDevice = "Rahul's MacBook"
        )

        assertEquals("act-1", pdfActivity.activityId)
        assertEquals("pdf", pdfActivity.activityType)
        assertEquals(12, pdfActivity.position)
        assertEquals(0.65, pdfActivity.progress, 0.001)
        assertTrue(pdfActivity.displaySubtitle.contains("Page 12"))

        val webActivity = ContinuityActivity(
            activityId = "act-2",
            activityType = "webpage",
            title = "Kotlin Documentation",
            subtitle = "kotlinlang.org",
            url = "https://kotlinlang.org/docs"
        )
        assertEquals("kotlinlang.org", webActivity.displaySubtitle)
    }

    @Test
    fun testContinuityActivityMessageJsonSerialization() {
        val msg = ContinuityActivityMessage(
            activityId = "act-test",
            activityType = "pdf",
            title = "Test PDF",
            subtitle = "Page 5",
            url = "https://example.com/test.pdf",
            contentPayload = null,
            position = 5,
            progress = 0.5,
            timestamp = 1700000000000L,
            sourceDevice = "Pixel 8"
        )

        val json = msg.toJson()
        val parsed = ProtocolMessage.fromJson(json.toString())

        assertTrue(parsed is ContinuityActivityMessage)
        val parsedActivity = parsed as ContinuityActivityMessage
        assertEquals("act-test", parsedActivity.activityId)
        assertEquals("pdf", parsedActivity.activityType)
        assertEquals("Test PDF", parsedActivity.title)
        assertEquals(5, parsedActivity.position)
        assertEquals(0.5, parsedActivity.progress, 0.001)
        assertEquals("Pixel 8", parsedActivity.sourceDevice)
    }

    @Test
    fun testContinuityControlMessagesJsonSerialization() {
        val ackMsg = ContinuityAckMessage(activityId = "act-ack", accepted = true)
        val ackParsed = ProtocolMessage.fromJson(ackMsg.toJson().toString())
        assertTrue(ackParsed is ContinuityAckMessage)
        assertEquals("act-ack", (ackParsed as ContinuityAckMessage).activityId)
        assertTrue(ackParsed.accepted)

        val dismissMsg = ContinuityDismissMessage(activityId = "act-dismiss")
        val dismissParsed = ProtocolMessage.fromJson(dismissMsg.toJson().toString())
        assertTrue(dismissParsed is ContinuityDismissMessage)
        assertEquals("act-dismiss", (dismissParsed as ContinuityDismissMessage).activityId)

        val stateMsg = ContinuityStateMessage(enabled = true)
        val stateParsed = ProtocolMessage.fromJson(stateMsg.toJson().toString())
        assertTrue(stateParsed is ContinuityStateMessage)
        assertTrue((stateParsed as ContinuityStateMessage).enabled)
    }

    @Test
    fun testAdaptersCanHandleMatchingTypes() {
        val webAdapter = WebContinuityAdapter()
        val pdfAdapter = PdfContinuityAdapter()
        val presAdapter = PresentationContinuityAdapter()
        val docAdapter = DocumentContinuityAdapter()

        val webActivity = ContinuityActivity(
            activityId = "w1",
            activityType = "webpage",
            title = "Web article",
            url = "https://news.ycombinator.com"
        )
        assertTrue(webAdapter.canHandle(webActivity))
        assertFalse(pdfAdapter.canHandle(webActivity))

        val pdfActivity = ContinuityActivity(
            activityId = "p1",
            activityType = "pdf",
            title = "Annual_Report.pdf",
            url = "https://example.com/report.pdf",
            position = 3
        )
        assertTrue(pdfAdapter.canHandle(pdfActivity))
        assertFalse(webAdapter.canHandle(pdfActivity))

        val presActivity = ContinuityActivity(
            activityId = "pr1",
            activityType = "presentation",
            title = "Deck.pptx",
            position = 10
        )
        assertTrue(presAdapter.canHandle(presActivity))

        val docActivity = ContinuityActivity(
            activityId = "d1",
            activityType = "document",
            title = "Notes.docx",
            position = 1
        )
        assertTrue(docAdapter.canHandle(docActivity))
    }
}
