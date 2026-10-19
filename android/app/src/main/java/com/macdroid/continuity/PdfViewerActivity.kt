package com.macdroid.continuity

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.File

class PdfViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PDF_PATH = "extra_pdf_path"
        const val EXTRA_PAGE_NUMBER = "extra_page_number"
        const val EXTRA_TITLE = "extra_title"
    }

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var currentBitmap: Bitmap? = null

    private var pageIndex = 0
    private var totalPages = 0

    private lateinit var pageImageView: ImageView
    private lateinit var pageIndicator: TextView
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pdfPath = intent.getStringExtra(EXTRA_PDF_PATH)
        val initialPage = intent.getIntExtra(EXTRA_PAGE_NUMBER, 1)
        val titleText = intent.getStringExtra(EXTRA_TITLE) ?: "PDF Document"

        // Setup clean programmatic view
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Top Header
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(32, 24, 32, 24)
            gravity = Gravity.CENTER_VERTICAL
        }

        val backButton = Button(this).apply {
            text = "← Back"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        headerLayout.addView(backButton)

        val titleView = TextView(this).apply {
            text = titleText
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(24, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(titleView)

        pageIndicator = TextView(this).apply {
            text = "Page $initialPage"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 14f
        }
        headerLayout.addView(pageIndicator)

        rootLayout.addView(headerLayout)

        // PDF Page Display
        pageImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#181818"))
        }
        rootLayout.addView(pageImageView)

        // Bottom Controls
        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(32, 16, 32, 16)
            gravity = Gravity.CENTER
        }

        prevButton = Button(this).apply {
            text = "Previous Page"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setOnClickListener {
                if (pageIndex > 0) {
                    showPage(pageIndex - 1)
                }
            }
        }
        bottomControls.addView(prevButton)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(32, 1)
        }
        bottomControls.addView(spacer)

        nextButton = Button(this).apply {
            text = "Next Page"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setOnClickListener {
                if (pageIndex < totalPages - 1) {
                    showPage(pageIndex + 1)
                }
            }
        }
        bottomControls.addView(nextButton)

        rootLayout.addView(bottomControls)
        setContentView(rootLayout)

        openRenderer(pdfPath, initialPage)
    }

    private fun openRenderer(path: String?, targetPage1Indexed: Int) {
        try {
            val file = resolveFile(path)
            if (file == null || !file.exists()) {
                Toast.makeText(this, "PDF file not found locally: $path", Toast.LENGTH_LONG).show()
                pageIndicator.text = "File not found"
                return
            }

            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fileDescriptor?.let {
                pdfRenderer = PdfRenderer(it)
                totalPages = pdfRenderer?.pageCount ?: 0
                val targetIndex = (targetPage1Indexed - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                showPage(targetIndex)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveFile(path: String?): File? {
        if (path.isNullOrEmpty()) return null
        return try {
            if (path.startsWith("file://")) {
                File(Uri.parse(path).path ?: "")
            } else {
                File(path)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index < 0 || index >= totalPages) return

        currentPage?.close()
        currentPage = renderer.openPage(index)
        pageIndex = index

        val page = currentPage ?: return
        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        currentBitmap?.recycle()
        currentBitmap = bitmap
        pageImageView.setImageBitmap(bitmap)

        pageIndicator.text = "Page ${index + 1} of $totalPages"
        prevButton.isEnabled = index > 0
        nextButton.isEnabled = index < totalPages - 1
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPage?.close()
        pdfRenderer?.close()
        fileDescriptor?.close()
        currentBitmap?.recycle()
    }
}
