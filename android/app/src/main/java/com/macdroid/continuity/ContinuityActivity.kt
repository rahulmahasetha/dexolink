package com.macdroid.continuity

data class ContinuityActivity(
    val activityId: String,
    val activityType: String, // "webpage", "pdf", "presentation", "document"
    val title: String,
    val subtitle: String = "",
    val url: String? = null,
    val contentPayload: String? = null,
    val position: Int = 0,
    val progress: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceDevice: String = ""
) {
    val displaySubtitle: String
        get() = when (activityType) {
            "pdf" -> if (position > 0) "Page $position • PDF" else if (subtitle.isNotEmpty()) subtitle else "PDF Document"
            "presentation" -> if (position > 0) "Slide $position • Presentation" else if (subtitle.isNotEmpty()) subtitle else "Presentation"
            "document" -> if (position > 0) "Section $position • Document" else if (subtitle.isNotEmpty()) subtitle else "Document"
            else -> if (subtitle.isNotEmpty()) subtitle else (url ?: "Webpage")
        }
}
