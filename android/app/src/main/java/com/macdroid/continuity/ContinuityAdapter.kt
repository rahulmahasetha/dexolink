package com.macdroid.continuity

import android.content.Context
import android.content.Intent
import android.net.Uri

interface ContinuityAdapter {
    val activityType: String
    fun canHandle(activity: ContinuityActivity): Boolean
    fun continueActivity(context: Context, activity: ContinuityActivity): Boolean
}

class WebContinuityAdapter : ContinuityAdapter {
    override val activityType: String = "webpage"

    override fun canHandle(activity: ContinuityActivity): Boolean {
        if (activity.activityType == activityType || activity.activityType == "web" || activity.activityType == "browser") {
            return true
        }
        if (activity.activityType.isNotEmpty()) return false
        val url = activity.url?.lowercase() ?: return false
        return (url.startsWith("http://") || url.startsWith("https://")) && !url.endsWith(".pdf")
    }

    override fun continueActivity(context: Context, activity: ContinuityActivity): Boolean {
        val urlStr = activity.url ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

class PdfContinuityAdapter : ContinuityAdapter {
    override val activityType: String = "pdf"

    override fun canHandle(activity: ContinuityActivity): Boolean {
        if (activity.activityType == activityType) return true
        val url = activity.url?.lowercase() ?: ""
        return url.endsWith(".pdf") || activity.title.lowercase().endsWith(".pdf")
    }

    override fun continueActivity(context: Context, activity: ContinuityActivity): Boolean {
        val urlStr = activity.url
        // 1. If web PDF URL, open with #page= fragment in browser or PdfViewerActivity
        if (urlStr != null && (urlStr.startsWith("http://") || urlStr.startsWith("https://"))) {
            return try {
                val targetUrl = if (activity.position > 0 && !urlStr.contains("#page=")) {
                    "$urlStr#page=${activity.position}"
                } else {
                    urlStr
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        // 2. Open in built-in PdfViewerActivity with exact page restoration
        return try {
            val intent = Intent(context, PdfViewerActivity::class.java).apply {
                putExtra(PdfViewerActivity.EXTRA_PDF_PATH, urlStr)
                putExtra(PdfViewerActivity.EXTRA_PAGE_NUMBER, activity.position)
                putExtra(PdfViewerActivity.EXTRA_TITLE, activity.title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

class PresentationContinuityAdapter : ContinuityAdapter {
    override val activityType: String = "presentation"

    override fun canHandle(activity: ContinuityActivity): Boolean {
        if (activity.activityType == activityType) return true
        val url = activity.url?.lowercase() ?: ""
        return url.endsWith(".pptx") || url.endsWith(".ppt") || url.endsWith(".key")
    }

    override fun continueActivity(context: Context, activity: ContinuityActivity): Boolean {
        val urlStr = activity.url ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("slide_number", activity.position)
                putExtra("current_slide", activity.position)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

class DocumentContinuityAdapter : ContinuityAdapter {
    override val activityType: String = "document"

    override fun canHandle(activity: ContinuityActivity): Boolean {
        if (activity.activityType == activityType) return true
        val url = activity.url?.lowercase() ?: ""
        return url.endsWith(".docx") || url.endsWith(".doc") || url.endsWith(".txt") || url.endsWith(".md")
    }

    override fun continueActivity(context: Context, activity: ContinuityActivity): Boolean {
        val urlStr = activity.url ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("reading_position", activity.position)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
