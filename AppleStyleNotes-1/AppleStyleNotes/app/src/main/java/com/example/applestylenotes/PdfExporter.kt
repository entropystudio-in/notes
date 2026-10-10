package com.example.applestylenotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

// ---------------------------------------------------------------- images

fun decodeSampled(file: File, maxDim: Int): Bitmap? {
    return try {
        if (!file.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Throwable) {
        null
    }
}

/** Copies a picked photo into the app's private storage (downscaled to 2048 px, rotation fixed). */
fun importImage(ctx: Context, uri: Uri, dir: File): String? {
    return try {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= 2048) s *= 2
        var bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: return null
        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val name = UUID.randomUUID().toString() + ".jpg"
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        name
    } catch (e: Throwable) {
        null
    }
}

// ---------------------------------------------------------------- PDF

private const val PW = 595   // A4 width in points
private const val PH = 842   // A4 height in points
private const val MARGIN = 48f

private class PageWriter(val doc: PdfDocument) {
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var n = 0
    private var y = MARGIN
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun newPage() {
        page?.let { doc.finishPage(it) }
        n++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, n).create())
        page = p
        canvas = p.canvas
        y = MARGIN
    }

    fun finish() {
        page?.let { doc.finishPage(it) }
        page = null
    }

    fun drawLayout(layout: StaticLayout, contentWidth: Float) {
        var line = 0
        while (line < layout.lineCount) {
            val top = layout.getLineTop(line)
            var last = line
            while (last < layout.lineCount && layout.getLineBottom(last) - top <= PH - MARGIN - y) last++
            if (last == line) {
                if (y > MARGIN + 1f) {
                    newPage()
                    continue
                } else {
                    last = line + 1
                }
            }
            val bottom = layout.getLineBottom(last - 1)
            canvas.save()
            canvas.translate(MARGIN, y - top)
            canvas.clipRect(0f, top.toFloat(), contentWidth, bottom.toFloat())
            layout.draw(canvas)
            canvas.restore()
            y += (bottom - top).toFloat()
            line = last
            if (line < layout.lineCount) newPage()
        }
        y += 8f
    }

    fun drawImage(b: Bitmap) {
        val maxW = PW - 2 * MARGIN
        val scale = minOf(maxW / b.width, 360f / b.height)
        val dw = b.width * scale
        val dh = b.height * scale
        if (y + dh > PH - MARGIN) newPage()
        canvas.drawBitmap(b, null, RectF(MARGIN, y, MARGIN + dw, y + dh), imgPaint)
        y += dh + 8f
    }
}

object PdfExporter {
    /** Every note starts on a new page; formatting and photos are kept. */
    fun write(out: OutputStream, notes: List<Note>, attachDir: File) {
        val doc = PdfDocument()
        try {
            val w = PageWriter(doc)
            val width = (PW - 2 * MARGIN).toInt()
            for (note in notes) {
                w.newPage()
                val sp = buildSpannable(note.text, note.spans)
                applyTitleSpan(sp)
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 12f
                    color = Color.BLACK
                }
                val layout = StaticLayout.Builder.obtain(sp, 0, sp.length, paint, width)
                    .setLineSpacing(3f, 1f)
                    .build()
                w.drawLayout(layout, width.toFloat())
                for (name in note.attachments) {
                    decodeSampled(File(attachDir, name), 900)?.let { w.drawImage(it) }
                }
            }
            w.finish()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
