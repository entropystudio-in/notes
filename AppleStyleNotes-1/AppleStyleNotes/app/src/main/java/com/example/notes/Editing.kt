package com.example.notes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

// Inline styles are stored as invisible marker characters inside the text. Each marker toggles a style
// on/off, and styles always end at the end of the line.
const val BOLD = '\uE001'
const val ITALIC = '\uE002'
const val UNDERLINE = '\uE003'
val INLINE_MARKS = charArrayOf(BOLD, ITALIC, UNDERLINE)

fun plain(s: String): String = s.filter { it !in INLINE_MARKS }

class InlineSpan(val start: Int, val end: Int, val kind: Int)   // kind: 0 bold, 1 italic, 2 underline
class Inline(val text: String, val spans: List<InlineSpan>, val o2v: IntArray)

/** Splits one line into visible text, style spans (visible offsets) and an original->visible offset map. */
fun parseInline(line: String): Inline {
    val vis = StringBuilder()
    val o2v = IntArray(line.length + 1)
    val spans = ArrayList<InlineSpan>()
    val open = IntArray(3) { -1 }
    for (i in line.indices) {
        o2v[i] = vis.length
        val k = INLINE_MARKS.indexOf(line[i])
        if (k < 0) vis.append(line[i])
        else if (open[k] < 0) open[k] = vis.length
        else { if (vis.length > open[k]) spans.add(InlineSpan(open[k], vis.length, k)); open[k] = -1 }
    }
    o2v[line.length] = vis.length
    for (k in 0..2) if (open[k] >= 0 && vis.length > open[k]) spans.add(InlineSpan(open[k], vis.length, k))
    return Inline(vis.toString(), spans, o2v)
}

/** Styles lines by their prefix and applies inline styles; the invisible markers are hidden. */
class NoteStyler(private val dim: Color, private val accent: Color, private val muted: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val lines = s.split("\n")
        val parsed = lines.map { parseInline(it) }
        val vis = parsed.joinToString("\n") { it.text }
        val o2v = IntArray(s.length + 1)
        val b = AnnotatedString.Builder(vis)
        var os = 0
        var vs = 0
        var titleDone = false
        for ((idx, inl) in parsed.withIndex()) {
            val line = inl.text
            for (i in inl.o2v.indices) o2v[os + i] = vs + inl.o2v[i]
            val start = vs
            val end = vs + line.length
            val pre = prefixOf(line)
            val isTitle = !titleDone && line.isNotBlank() && pre.isEmpty()
            when {
                pre.startsWith("#") -> {
                    val size = when (pre.length) { 2 -> 28.sp; 3 -> 22.sp; else -> 18.sp }
                    b.addStyle(SpanStyle(fontSize = size, fontWeight = FontWeight.Bold), start, end)
                    b.addStyle(SpanStyle(color = dim), start, start + pre.length)
                }
                pre == "` " -> {
                    b.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, end)
                    b.addStyle(SpanStyle(color = dim), start, start + 2)
                }
                pre == "☑ " -> {
                    b.addStyle(SpanStyle(color = muted, textDecoration = TextDecoration.LineThrough), start + 2, end)
                    b.addStyle(SpanStyle(color = accent, fontSize = 22.sp), start, start + 2)
                }
                pre == "☐ " -> b.addStyle(SpanStyle(color = accent, fontSize = 22.sp), start, start + 2)
                isTitle -> b.addStyle(SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold), start, end)
                else -> {}
            }
            for (sp in inl.spans) {
                val st = when (sp.kind) {
                    0 -> SpanStyle(fontWeight = FontWeight.Bold)
                    1 -> SpanStyle(fontStyle = FontStyle.Italic)
                    else -> SpanStyle(textDecoration = TextDecoration.Underline)
                }
                b.addStyle(st, start + sp.start, start + sp.end)
            }
            if (line.isNotBlank()) titleDone = true
            os += lines[idx].length + 1
            vs += line.length + 1
        }
        val visLen = vis.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = o2v[offset.coerceIn(0, s.length)]
            override fun transformedToOriginal(offset: Int): Int {
                val t = offset.coerceIn(0, visLen)
                var lo = 0
                var hi = s.length
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (o2v[mid] >= t) hi = mid else lo = mid + 1
                }
                return lo
            }
        }
        return TransformedText(b.toAnnotatedString(), mapping)
    }
}

private fun lineStart(t: String, pos: Int): Int = if (pos <= 0) 0 else t.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
private fun lineEnd(t: String, pos: Int): Int = t.indexOf('\n', pos).let { if (it < 0) t.length else it }
private val numberedRegex = Regex("\\d+\\. ")

/** Sets (or toggles off) a line prefix on the line containing the cursor. */
fun applyPrefix(v: TextFieldValue, newPrefix: String): TextFieldValue {
    val t = v.text
    val pos = v.selection.start.coerceIn(0, t.length)
    val s = lineStart(t, pos)
    val e = lineEnd(t, pos)
    val line = t.substring(s, e)
    val old = prefixOf(line)
    val same = old == newPrefix || (newPrefix == "1. " && numberedRegex.matches(old))
    val np = if (same) "" else newPrefix
    val nl = np + line.removePrefix(old)
    val nt = t.substring(0, s) + nl + t.substring(e)
    val cursor = (pos + np.length - old.length).coerceIn(s + np.length, s + nl.length)
    return TextFieldValue(nt, TextRange(cursor))
}

/** Checklist button: empty -> unchecked -> checked -> plain. */
fun toggleCheck(v: TextFieldValue): TextFieldValue {
    val t = v.text
    val pos = v.selection.start.coerceIn(0, t.length)
    val line = t.substring(lineStart(t, pos), lineEnd(t, pos))
    return when (prefixOf(line)) {
        "☐ " -> replacePrefix(v, "☑ ")
        "☑ " -> replacePrefix(v, "")
        else -> replacePrefix(v, "☐ ")
    }
}

private fun replacePrefix(v: TextFieldValue, np: String): TextFieldValue {
    val t = v.text
    val pos = v.selection.start.coerceIn(0, t.length)
    val s = lineStart(t, pos)
    val e = lineEnd(t, pos)
    val line = t.substring(s, e)
    val old = prefixOf(line)
    val nl = np + line.removePrefix(old)
    val cursor = (pos + np.length - old.length).coerceIn(s + np.length, s + nl.length)
    return TextFieldValue(t.substring(0, s) + nl + t.substring(e), TextRange(cursor))
}

/** Pressing Enter on a list line continues the list; Enter on an empty item ends it. */
fun autoContinue(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (new.text.length != old.text.length + 1 || !new.selection.collapsed || !old.selection.collapsed) return new
    val p = new.selection.start
    if (p < 1 || new.text[p - 1] != '\n' || p - 1 != old.selection.start) return new
    val t = new.text
    val ls = lineStart(t, p - 1)
    val line = t.substring(ls, p - 1)
    val pre = prefixOf(line)
    if (pre.isEmpty() || pre.startsWith("#") || pre == "` ") return new
    if (line.length == pre.length) {
        return TextFieldValue(t.substring(0, ls) + t.substring(p - 1), TextRange(ls))
    }
    val next = when {
        pre == "☑ " -> "☐ "
        numberedRegex.matches(pre) -> "${pre.dropLast(2).toInt() + 1}. "
        else -> pre
    }
    return TextFieldValue(t.substring(0, p) + next + t.substring(p), TextRange(p + next.length))
}

/** Toggles the checkbox under a tap. Returns null if the tap was not on a checkbox. */
fun toggleCheckAt(v: TextFieldValue, layout: TextLayoutResult, pos: Offset): TextFieldValue? {
    val t = v.text
    if (t.isEmpty() || layout.layoutInput.text.length != t.length) return null
    val off = layout.getOffsetForPosition(pos).coerceIn(0, t.length)
    val s = lineStart(t, off)
    val pre = prefixOf(t.substring(s, lineEnd(t, off)))
    if (pre != "☐ " && pre != "☑ ") return null
    val li = layout.getLineForOffset(s)
    if (pos.y < layout.getLineTop(li) || pos.y > layout.getLineBottom(li)) return null
    if (pos.x > layout.getBoundingBox(s).right + 40f) return null
    val np = if (pre == "☐ ") "☑ " else "☐ "
    return v.copy(text = t.substring(0, s) + np + t.substring(s + 2), composition = null)
}

/** True if [m] style is on at the cursor / start of the selection. */
fun activeStyle(v: TextFieldValue, m: Char): Boolean {
    val t = v.text
    val p = minOf(v.selection.start, v.selection.end).coerceIn(0, t.length)
    return t.substring(lineStart(t, p), p).count { it == m } % 2 == 1
}

/** Bold / italic / underline. With no selection it toggles the style for what you type next. */
fun toggleStyle(v: TextFieldValue, m: Char): TextFieldValue {
    val t = v.text
    val a = minOf(v.selection.start, v.selection.end).coerceIn(0, t.length)
    val b = maxOf(v.selection.start, v.selection.end).coerceIn(0, t.length)
    if (a == b) {
        val ls = lineStart(t, a)
        val pl = prefixOf(t.substring(ls, lineEnd(t, a))).length
        val p = maxOf(a, ls + pl)
        if (p > 0 && p < t.length && t[p - 1] == m && t[p] == m)
            return TextFieldValue(t.removeRange(p - 1, p + 1), TextRange(p - 1))
        return TextFieldValue(t.substring(0, p) + m + m + t.substring(p), TextRange(p + 1))
    }
    class Seg(val s: Int, val e: Int, val ls: Int)
    val segs = ArrayList<Seg>()
    var pos = a
    while (true) {
        val ls = lineStart(t, pos)
        val le = lineEnd(t, pos)
        val pl = prefixOf(t.substring(ls, le)).length
        val s0 = maxOf(pos, ls + pl)
        val e0 = minOf(le, b)
        if (e0 > s0) segs.add(Seg(s0, e0, ls))
        if (le >= b) break
        pos = le + 1
    }
    if (segs.isEmpty()) return v
    fun on(seg: Seg) = t.substring(seg.ls, seg.s).count { it == m } % 2 == 1
    val allOn = segs.all { on(it) && t.substring(it.s, it.e).none { c -> c == m } }
    val sb = StringBuilder()
    var last = 0
    var shift = 0
    var selStart = 0
    var selEnd = 0
    for ((i, seg) in segs.withIndex()) {
        val startOn = on(seg)
        val endOn = t.substring(seg.ls, seg.e).count { it == m } % 2 == 1
        val clean = t.substring(seg.s, seg.e).filter { it != m }
        val lead = allOn || !startOn
        val trail = if (allOn) endOn else !endOn
        val out = (if (lead) m.toString() else "") + clean + (if (trail) m.toString() else "")
        sb.append(t, last, seg.s).append(out)
        last = seg.e
        val newStart = seg.s + shift
        if (i == 0) selStart = newStart + (if (lead) 1 else 0)
        if (i == segs.lastIndex) selEnd = newStart + out.length - (if (trail) 1 else 0)
        shift += out.length - (seg.e - seg.s)
    }
    sb.append(t, last, t.length)
    return TextFieldValue(sb.toString(), TextRange(selStart, selEnd))
}
