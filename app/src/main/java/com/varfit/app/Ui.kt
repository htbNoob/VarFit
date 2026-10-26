package com.varfit.app

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** Colour palette, switched for dark mode at start-up. */
object Pal {
    var dark = false
    val bg get() = if (dark) 0xFF121417.toInt() else 0xFFF4F5F7.toInt()
    val surface get() = if (dark) 0xFF1D2025.toInt() else 0xFFFFFFFF.toInt()
    val surface2 get() = if (dark) 0xFF262A30.toInt() else 0xFFEEF0F3.toInt()
    val text get() = if (dark) 0xFFE8EAED.toInt() else 0xFF1B1D21.toInt()
    val muted get() = if (dark) 0xFF9AA1AB.toInt() else 0xFF5F6670.toInt()
    val border get() = if (dark) 0xFF343941.toInt() else 0xFFD9DDE3.toInt()
    val accent get() = if (dark) 0xFF7FA7FF.toInt() else 0xFF2F5BD3.toInt()
    val onAccent get() = if (dark) 0xFF0B1530.toInt() else 0xFFFFFFFF.toInt()
    val warn get() = if (dark) 0xFFF2B85B.toInt() else 0xFF9A5B00.toInt()
    val bad get() = if (dark) 0xFFFF8A80.toInt() else 0xFFB3261E.toInt()
    val good get() = if (dark) 0xFF7BD88F.toInt() else 0xFF1E7B34.toInt()
    // plot
    val plotData get() = if (dark) 0xFFE8EAED.toInt() else 0xFF1B1D21.toInt()
    val plotExcluded get() = if (dark) 0xFF6B717A.toInt() else 0xFFA4AAB3.toInt()
    val plotModel get() = if (dark) 0xFFFF9E57.toInt() else 0xFFD9480F.toInt()
    val plotBinned get() = if (dark) 0xFF7FA7FF.toInt() else 0xFF2F5BD3.toInt()
    val plotGrid get() = if (dark) 0xFF2C3036.toInt() else 0xFFE6E8EC.toInt()
    val plotEvent get() = if (dark) 0xFF7BD88F.toInt() else 0xFF1E7B34.toInt()
    val plotWindow get() = if (dark) 0x223F7FFF else 0x182F5BD3

    fun init(ctx: Context) {
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.vbox(padding: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.textView(s: CharSequence, sizeSp: Float = 15f, color: Int = Pal.text, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

fun Context.heading(s: String): TextView = textView(s, 17f, Pal.text, bold = true).apply {
    setPadding(0, 0, 0, dp(6))
}

fun Context.note(s: CharSequence, color: Int = Pal.muted): TextView = textView(s, 13f, color).apply {
    setPadding(0, dp(2), 0, dp(6))
}

fun Context.mono(s: CharSequence): TextView = textView(s, 12f, Pal.text).apply {
    typeface = Typeface.MONOSPACE
    setTextIsSelectable(true)
    setHorizontallyScrolling(false)
}

fun roundRect(color: Int, radius: Float, strokeColor: Int? = null, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokeColor != null) setStroke(strokePx, strokeColor)
    }

/** A rounded card holding [children]. */
fun Context.card(vararg children: View): LinearLayout = vbox(14).apply {
    background = roundRect(Pal.surface, dp(14).toFloat(), Pal.border, dp(1))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(12)
    }
    children.forEach { addView(it) }
}

fun Context.button(label: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    textSize = 14f
    minHeight = dp(42)
    minimumHeight = dp(42)
    stateListAnimator = null
    val bgColor = if (primary) Pal.accent else Pal.surface2
    setTextColor(if (primary) Pal.onAccent else Pal.text)
    background = RippleDrawable(ColorStateList.valueOf(0x33888888), roundRect(bgColor, dp(10).toFloat()), null)
    setPadding(dp(14), dp(6), dp(14), dp(6))
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        rightMargin = dp(8); topMargin = dp(4); bottomMargin = dp(4)
    }
}

/** Wrapping row of buttons (simple flow: new row every [perRow] items). */
fun Context.buttonRows(perRow: Int, vararg buttons: Button): LinearLayout {
    val col = vbox()
    var row: LinearLayout? = null
    for ((i, b) in buttons.withIndex()) {
        if (i % perRow == 0) { row = hbox(); col.addView(row) }
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(6); topMargin = dp(3); bottomMargin = dp(3)
        }
        row!!.addView(b)
    }
    val rem = buttons.size % perRow
    if (rem != 0) repeat(perRow - rem) {
        row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { rightMargin = dp(6) })
    }
    return col
}

fun Context.editText(value: String, numeric: Boolean = false, hint: String = ""): EditText = EditText(this).apply {
    setText(value)
    this.hint = hint
    textSize = 15f
    setTextColor(Pal.text)
    setHintTextColor(Pal.muted)
    isSingleLine = true
    inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    background = roundRect(Pal.surface2, dp(8).toFloat())
    setPadding(dp(10), dp(8), dp(10), dp(8))
}

/** Label above an input; returns the container and the EditText. */
class Field(val view: LinearLayout, val edit: EditText) {
    val text: String get() = edit.text.toString()
    val number: Double? get() = com.varfit.core.Fmt.parse(text)
}

fun Context.field(label: String, value: String, numeric: Boolean = false, hint: String = ""): Field {
    val box = vbox()
    box.addView(textView(label, 12f, Pal.muted).apply { setPadding(0, dp(6), 0, dp(3)) })
    val e = editText(value, numeric, hint)
    box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return Field(box, e)
}

/** Places views side by side with equal width. */
fun Context.columns(vararg views: View): LinearLayout = hbox().apply {
    gravity = Gravity.TOP
    for ((i, v) in views.withIndex()) {
        addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (i < views.size - 1) rightMargin = dp(10)
        })
    }
}

fun Context.spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner = Spinner(this).apply {
    val ad = ArrayAdapter(context, android.R.layout.simple_spinner_item, items)
    ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    adapter = ad
    background = roundRect(Pal.surface2, dp(8).toFloat())
    setPopupBackgroundDrawable(roundRect(Pal.surface, dp(8).toFloat(), Pal.border, dp(1)))
    setPadding(dp(4), dp(2), dp(4), dp(2))
    var current = if (selected in items.indices) selected else 0
    setSelection(current, false)
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            (view as? TextView)?.setTextColor(Pal.text)
            if (position == current) return
            current = position
            onSelect(position)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

fun Context.labeledSpinner(label: String, items: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val box = vbox()
    box.addView(textView(label, 12f, Pal.muted).apply { setPadding(0, dp(6), 0, dp(3)) })
    box.addView(spinner(items, selected, onSelect), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
    return box
}

fun Context.checkBox(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox = CheckBox(this).apply {
    text = label
    isChecked = checked
    textSize = 14f
    setTextColor(Pal.text)
    buttonTintList = ColorStateList.valueOf(Pal.accent)
    setOnCheckedChangeListener { _, b -> onChange(b) }
}

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(Pal.border)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
        topMargin = dp(8); bottomMargin = dp(8)
    }
}

fun Context.spacer(h: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(1, dp(h))
}

fun colorWithAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
