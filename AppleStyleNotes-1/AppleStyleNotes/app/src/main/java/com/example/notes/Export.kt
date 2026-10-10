package com.example.notes

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.TextPaint

/** Writes all given notes into one PDF in the Downloads folder. Each note starts on a new page. */
fun exportPdf(ctx: Context, name: String, notes: List<Note>): Uri? {
    val doc = PdfDocument()
    return try {
        val pageW = 595; val pageH = 842; val margin = 48
        val width = pageW - 2 * margin
        val bottom = (pageH - margin).toFloat()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = margin.toFloat()
        }

        for (n in notes) {
            newPage()
            var titleDone = false
            for (raw in n.text.split("\n")) {
                val pre = prefixOf(raw)
                var txt = raw
                var size = 12f; var bold = false; var mono = false
                when {
                    pre.startsWith("#") -> {
                        txt = raw.removePrefix(pre)
                        size = when (pre.length) { 2 -> 22f; 3 -> 17f; else -> 14f }; bold = true
                    }
                    pre == "` " -> { txt = raw.removePrefix(pre); mono = true }
                    pre.isEmpty() && !titleDone && raw.isNotBlank() -> { size = 22f; bold = true }
                }
                if (raw.isNotBlank()) titleDone = true
                val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = size
                    isFakeBoldText = bold
                    typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
                }
                val inl = parseInline(txt)
                if (inl.text.isEmpty()) {
                    val h = size * 1.3f
                    if (y + h > bottom) newPage()
                    y += h
                    continue
                }
                val sp = SpannableString(inl.text)
                for (x in inl.spans) {
                    val span: Any = when (x.kind) {
                        0 -> StyleSpan(Typeface.BOLD)
                        1 -> StyleSpan(Typeface.ITALIC)
                        else -> UnderlineSpan()
                    }
                    sp.setSpan(span, x.start, x.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val layout = StaticLayout.Builder.obtain(sp, 0, sp.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
                for (i in 0 until layout.lineCount) {
                    val top = layout.getLineTop(i)
                    val h = (layout.getLineBottom(i) - top).toFloat()
                    if (y + h > bottom) newPage()
                    val c = page!!.canvas
                    c.save()
                    c.translate(margin.toFloat(), y - top)
                    c.clipRect(0f, top.toFloat(), width.toFloat(), top + h)
                    layout.draw(c)
                    c.restore()
                    y += h
                }
                y += 3f
            }
        }
        page?.let { doc.finishPage(it) }

        val safe = name.replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "Notes" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$safe.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        uri
    } catch (e: Exception) {
        null
    } finally {
        doc.close()
    }
}
