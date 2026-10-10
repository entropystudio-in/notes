package com.example.applestylenotes

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.TextView
import kotlin.math.abs

private const val INCL = Spanned.SPAN_INCLUSIVE_INCLUSIVE
private const val EXCL = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
private const val EXCL_INCL = Spanned.SPAN_EXCLUSIVE_INCLUSIVE
private const val CHECK_GRAY = 0xFF8E8E93.toInt()
private const val CHECK_YELLOW = 0xFFE0A800.toInt()

class EditorController {
    var view: NoteEditText? = null

    fun run(block: NoteEditText.() -> Unit) {
        view?.let {
            it.requestFocus()
            it.block()
        }
    }
}

/**
 * A native Android EditText with Apple-Notes-like behaviour:
 * first line = Title, bold/italic/underline/strikethrough, heading styles,
 * bulleted / dashed / numbered lists, tappable checklists, list continuation on Enter.
 */
class NoteEditText(context: Context) : EditText(context) {
    var onContentChanged: (() -> Unit)? = null
    var readOnly = false

    private var busy = false
    private var lastStart = 0
    private var lastBefore = 0
    private var lastCount = 0
    private var downX = 0f
    private var downY = 0f
    private var downOnCheck = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun dp(v: Float) = v * resources.displayMetrics.density

    init {
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setLineSpacing(dp(3f), 1f)
        val h = dp(20f).toInt()
        setPadding(h, dp(12f).toInt(), h, dp(32f).toInt())
        isVerticalScrollBarEnabled = true
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                lastStart = start; lastBefore = before; lastCount = count
            }

            override fun afterTextChanged(e: Editable) {
                if (busy) return
                busy = true
                try {
                    afterChange(e)
                } finally {
                    busy = false
                }
                onContentChanged?.invoke()
            }
        })
    }

    fun load(t: String, spansJson: String) {
        busy = true
        try {
            setText(buildSpannable(t, spansJson), TextView.BufferType.EDITABLE)
            normalize(text)
        } finally {
            busy = false
        }
    }

    fun makeReadOnly() {
        readOnly = true
        keyListener = null
        isCursorVisible = false
        setTextIsSelectable(true)
    }

    // ---------------- typing behaviour ----------------

    private fun afterChange(e: Editable) {
        if (lastBefore == 0 && lastCount == 1 && lastStart < e.length && e[lastStart] == '\n') {
            handleEnter(e, lastStart)
        }
        normalize(e)
    }

    /** Continue (or end) a list when Enter is pressed. */
    private fun handleEnter(e: Editable, nl: Int) {
        val ls = lineStart(e, nl)
        val line = e.subSequence(ls, nl).toString()
        val m = LIST_RE.find(line) ?: return
        val prefix = m.value
        val content = line.substring(prefix.length)
        if (content.isBlank()) {
            e.delete(ls, nl + 1)
            setSelection(ls.coerceIn(0, e.length))
            return
        }
        val next = when {
            prefix.startsWith("●") || prefix.startsWith("○") -> "○ "
            prefix[0].isDigit() -> "${prefix.takeWhile { it.isDigit() }.toInt() + 1}. "
            else -> prefix
        }
        e.insert(nl + 1, next)
        setSelection((nl + 1 + next.length).coerceIn(0, e.length))
    }

    /** Keeps title/paragraph spans tidy and colours checklist circles. */
    private fun normalize(e: Editable) {
        val keep = ArrayList<Triple<Int, Int, Int>>()
        for (sp in e.getSpans(0, e.length, ParaStyleSpan::class.java)) {
            val s = e.getSpanStart(sp)
            val en = e.getSpanEnd(sp)
            e.removeSpan(sp)
            if (sp.kind != P_TITLE) keep.add(Triple(sp.kind, s, en))
        }
        val firstEnd = lineEnd(e, 0)
        for ((kind, s0, en0) in keep) {
            var s = s0
            var en = en0
            if (en > s && en > 0 && en <= e.length && e[en - 1] == '\n') en--
            if (s <= firstEnd) s = firstEnd + 1
            if (s > e.length || en < s) continue
            if (en == s) {
                val emptyLine = (s == e.length || e[s] == '\n') && (s == 0 || e[s - 1] == '\n')
                if (!emptyLine) continue
            }
            e.setSpan(ParaStyleSpan(kind), s, en, INCL)
        }
        e.setSpan(ParaStyleSpan(P_TITLE), 0, firstEnd, INCL)

        for (d in e.getSpans(0, e.length, CheckDecorSpan::class.java)) e.removeSpan(d)
        var ls = 0
        while (ls < e.length) {
            val le = lineEnd(e, ls)
            if (le - ls >= 2 && e[ls + 1] == ' ') {
                when (e[ls]) {
                    '○' -> e.setSpan(CheckDecorSpan(CHECK_GRAY), ls, ls + 1, EXCL)
                    '●' -> e.setSpan(CheckDecorSpan(CHECK_YELLOW), ls, ls + 1, EXCL)
                }
            }
            ls = le + 1
        }
    }

    private fun pruneEmptySpans(e: Editable, cursor: Int) {
        for (sp in e.getSpans(0, e.length, Any::class.java)) {
            if (charKind(sp) == null) continue
            val s = e.getSpanStart(sp)
            if (s == e.getSpanEnd(sp) && s != cursor) e.removeSpan(sp)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        val e = (text as? Editable) ?: return
        if (busy) return
        pruneEmptySpans(e, selStart)
    }

    // ---------------- checklist tapping ----------------

    private fun checkAt(x: Float, y: Float): Int {
        val l = layout ?: return -1
        val yy = y - totalPaddingTop + scrollY
        val xx = x - totalPaddingLeft + scrollX
        if (yy < 0) return -1
        val line = l.getLineForVertical(yy.toInt())
        val ls = l.getLineStart(line)
        val e = text
        if (ls + 1 >= e.length) return -1
        if (ls > 0 && e[ls - 1] != '\n') return -1
        val c = e[ls]
        if (c != '○' && c != '●') return -1
        if (e[ls + 1] != ' ') return -1
        val edge = l.getPrimaryHorizontal(ls + 2)
        return if (xx < edge + dp(6f)) ls else -1
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!readOnly) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    downOnCheck = checkAt(ev.x, ev.y) >= 0
                }
                MotionEvent.ACTION_UP -> {
                    if (downOnCheck && abs(ev.x - downX) < slop && abs(ev.y - downY) < slop) {
                        val pos = checkAt(ev.x, ev.y)
                        downOnCheck = false
                        if (pos >= 0) {
                            ev.action = MotionEvent.ACTION_CANCEL
                            super.onTouchEvent(ev)
                            toggleCheck(pos)
                            return true
                        }
                    }
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun toggleCheck(pos: Int) {
        val e = text
        busy = true
        try {
            e.replace(pos, pos + 1, if (e[pos] == '○') "●" else "○")
            normalize(e)
        } finally {
            busy = false
        }
        onContentChanged?.invoke()
    }

    // ---------------- formatting commands ----------------

    private fun selMin() = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
    private fun selMax() = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)

    /** kind: "b", "i", "u" or "s" */
    fun toggleChar(kind: String) {
        val e = text
        val a = selMin()
        val b = selMax()
        val spans = e.getSpans(a, b, Any::class.java).filter { charKind(it) == kind }

        if (a == b) {
            val active = spans.filter {
                val st = e.getSpanStart(it)
                val en = e.getSpanEnd(it)
                (st < a && en >= a) || (st == a && en == a)
            }
            if (active.isNotEmpty()) {
                for (sp in active) {
                    val st = e.getSpanStart(sp)
                    val en = e.getSpanEnd(sp)
                    e.removeSpan(sp)
                    if (st < a) e.setSpan(newCharSpan(kind), st, a, EXCL)
                    if (en > a) e.setSpan(newCharSpan(kind), a, en, EXCL_INCL)
                }
            } else {
                e.setSpan(newCharSpan(kind), a, a, INCL)
            }
        } else {
            fun covered(): Boolean {
                var pos = a
                while (pos < b) {
                    val sp = spans.firstOrNull { e.getSpanStart(it) <= pos && e.getSpanEnd(it) > pos }
                        ?: return false
                    pos = e.getSpanEnd(sp)
                }
                return true
            }
            if (covered()) {
                for (sp in spans) {
                    val st = e.getSpanStart(sp)
                    val en = e.getSpanEnd(sp)
                    e.removeSpan(sp)
                    if (st < a) e.setSpan(newCharSpan(kind), st, a, EXCL_INCL)
                    if (en > b) e.setSpan(newCharSpan(kind), b, en, EXCL_INCL)
                }
            } else {
                var st = a
                var en = b
                for (sp in spans) {
                    st = minOf(st, e.getSpanStart(sp))
                    en = maxOf(en, e.getSpanEnd(sp))
                    e.removeSpan(sp)
                }
                e.setSpan(newCharSpan(kind), st, en, EXCL_INCL)
            }
        }
        onContentChanged?.invoke()
    }

    /** kind: P_BODY, P_HEADING, P_SUBHEADING or P_MONO (line 1 is always the Title). */
    fun setParagraph(kind: Int) {
        val e = text
        val b = selMax()
        var ls = lineStart(e, selMin())
        while (true) {
            val le = lineEnd(e, ls)
            if (ls > 0) applyLine(e, ls, le, kind)
            if (le >= b || le >= e.length) break
            ls = le + 1
        }
        normalize(e)
        onContentChanged?.invoke()
    }

    private fun applyLine(e: Editable, ls: Int, le: Int, kind: Int) {
        for (sp in e.getSpans(ls, le, ParaStyleSpan::class.java)) {
            if (sp.kind == P_TITLE) continue
            val st = e.getSpanStart(sp)
            val en = e.getSpanEnd(sp)
            e.removeSpan(sp)
            if (st < ls - 1) e.setSpan(ParaStyleSpan(sp.kind), st, ls - 1, INCL)
            if (en > le + 1) e.setSpan(ParaStyleSpan(sp.kind), le + 1, en, INCL)
        }
        if (kind != P_BODY) e.setSpan(ParaStyleSpan(kind), ls, le, INCL)
    }

    private fun listKind(prefix: String?): Int = when {
        prefix == null -> -1
        prefix.startsWith("•") -> 0
        prefix.startsWith("–") -> 1
        prefix[0].isDigit() -> 2
        else -> 3
    }

    private fun shift(p: Int, s: Int, oldLen: Int, newLen: Int): Int = when {
        p >= s + oldLen -> p + (newLen - oldLen)
        p > s -> s + newLen
        else -> p
    }

    /** type: 0 = bullet, 1 = dash, 2 = numbered, 3 = checklist. Tapping again removes it. */
    fun toggleList(type: Int) {
        val e = text
        var selS = selMin()
        var selE = selMax()
        busy = true
        try {
            if (e.indexOf('\n') < 0 && !LIST_RE.containsMatchIn(e)) {
                e.append("\n")
                selS = e.length
                selE = e.length
            }
            val starts = ArrayList<Int>()
            var ls = lineStart(e, selS)
            while (true) {
                starts.add(ls)
                val le = lineEnd(e, ls)
                if (le >= selE || le >= e.length) break
                ls = le + 1
            }
            val kinds = starts.map { s ->
                listKind(LIST_RE.find(e.subSequence(s, lineEnd(e, s)))?.value)
            }
            val remove = kinds.all { it == type }
            var cs = selS
            var ce = selE
            for (i in starts.indices.reversed()) {
                val s = starts[i]
                val oldLen = LIST_RE.find(e.subSequence(s, lineEnd(e, s)))?.value?.length ?: 0
                val newPrefix = if (remove) "" else when (type) {
                    0 -> "• "
                    1 -> "– "
                    2 -> "${i + 1}. "
                    else -> "○ "
                }
                e.replace(s, s + oldLen, newPrefix)
                cs = shift(cs, s, oldLen, newPrefix.length)
                ce = shift(ce, s, oldLen, newPrefix.length)
            }
            normalize(e)
            setSelection(cs.coerceIn(0, e.length), ce.coerceIn(0, e.length))
        } finally {
            busy = false
        }
        onContentChanged?.invoke()
    }
}
