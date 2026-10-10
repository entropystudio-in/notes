package com.example.applestylenotes

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import org.json.JSONArray

const val P_BODY = 0
const val P_TITLE = 1
const val P_HEADING = 2
const val P_SUBHEADING = 3
const val P_MONO = 4

/** List prefixes stored as real characters: "• ", "– ", "1. ", "○ " (open) and "● " (checked). */
val LIST_RE = Regex("^(?:•|–|\\d{1,6}\\.|○|●) ")

/** Paragraph style (title / heading / subheading / monospaced). Title is applied automatically to line 1. */
class ParaStyleSpan(val kind: Int) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) = style(p)
    override fun updateDrawState(p: TextPaint) = style(p)

    private fun style(p: TextPaint) {
        when (kind) {
            P_TITLE -> { p.textSize *= 1.65f; p.typeface = Typeface.create(p.typeface, Typeface.BOLD) }
            P_HEADING -> { p.textSize *= 1.35f; p.typeface = Typeface.create(p.typeface, Typeface.BOLD) }
            P_SUBHEADING -> { p.textSize *= 1.15f; p.typeface = Typeface.create(p.typeface, Typeface.BOLD) }
            P_MONO -> p.typeface = Typeface.MONOSPACE
        }
    }
}

/** Colours the checklist circle (never saved, re-applied automatically). */
class CheckDecorSpan(color: Int) : ForegroundColorSpan(color)

fun newCharSpan(kind: String): Any = when (kind) {
    "b" -> StyleSpan(Typeface.BOLD)
    "i" -> StyleSpan(Typeface.ITALIC)
    "u" -> UnderlineSpan()
    else -> StrikethroughSpan()
}

fun charKind(span: Any): String? = when {
    span is StyleSpan && span.style == Typeface.BOLD -> "b"
    span is StyleSpan && span.style == Typeface.ITALIC -> "i"
    span is UnderlineSpan -> "u"
    span is StrikethroughSpan -> "s"
    else -> null
}

fun lineStart(t: CharSequence, pos: Int): Int {
    var i = pos.coerceIn(0, t.length)
    while (i > 0 && t[i - 1] != '\n') i--
    return i
}

fun lineEnd(t: CharSequence, pos: Int): Int {
    var i = pos.coerceIn(0, t.length)
    while (i < t.length && t[i] != '\n') i++
    return i
}

fun serializeSpans(t: Spanned): String {
    val arr = JSONArray()
    for (sp in t.getSpans(0, t.length, Any::class.java)) {
        if (t.getSpanFlags(sp) and Spanned.SPAN_COMPOSING != 0) continue
        val s = t.getSpanStart(sp)
        val e = t.getSpanEnd(sp)
        if (e <= s) continue
        val key: String = when {
            sp is ParaStyleSpan -> when (sp.kind) {
                P_HEADING -> "h1"
                P_SUBHEADING -> "h2"
                P_MONO -> "m"
                else -> continue
            }
            else -> charKind(sp) ?: continue
        }
        arr.put(JSONArray().put(key).put(s).put(e))
    }
    return arr.toString()
}

fun buildSpannable(text: String, spansJson: String): SpannableStringBuilder {
    val sb = SpannableStringBuilder(text)
    try {
        val arr = JSONArray(spansJson)
        for (i in 0 until arr.length()) {
            val a = arr.getJSONArray(i)
            val key = a.getString(0)
            val s = a.getInt(1).coerceIn(0, text.length)
            val e = a.getInt(2).coerceIn(0, text.length)
            if (e <= s) continue
            when (key) {
                "h1" -> sb.setSpan(ParaStyleSpan(P_HEADING), s, e, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                "h2" -> sb.setSpan(ParaStyleSpan(P_SUBHEADING), s, e, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                "m" -> sb.setSpan(ParaStyleSpan(P_MONO), s, e, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                "b", "i", "u", "s" -> sb.setSpan(newCharSpan(key), s, e, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            }
        }
    } catch (e: Exception) {
        // ignore damaged formatting, keep the text
    }
    return sb
}

/** Used by the PDF exporter so the first line renders as a title. */
fun applyTitleSpan(sp: Spannable) {
    if (sp.isEmpty()) return
    sp.setSpan(ParaStyleSpan(P_TITLE), 0, lineEnd(sp, 0), Spanned.SPAN_INCLUSIVE_INCLUSIVE)
}
