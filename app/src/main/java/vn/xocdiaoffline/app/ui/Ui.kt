package vn.xocdiaoffline.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val FELT = Color.parseColor("#0B5D3B")
    val FELT_DARK = Color.parseColor("#06301F")
    val GOLD = Color.parseColor("#F2C14E")
    val RED = Color.parseColor("#C62828")
    val BLUE = Color.parseColor("#1565C0")
    val GREY = Color.parseColor("#546E7A")
    val PANEL = Color.parseColor("#CC0A2A1C")
    val TEXT = Color.WHITE
    val MUTED = Color.parseColor("#B8D8C8")

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun text(ctx: Context, s: String, size: Float = 16f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun button(ctx: Context, s: String, bg: Int = GOLD, fg: Int = Color.parseColor("#3A2A00"),
               size: Float = 16f, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val h = dp(ctx, 16); val v = dp(ctx, 10)
            setPadding(h, v, h, v)
            background = rounded(bg, dp(ctx, 12).toFloat())
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }

    fun col(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun lp(w: Int, h: Int, weight: Float = 0f, margin: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(margin, margin, margin, margin) }

    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun spacer(ctx: Context, h: Int): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h)) }
}
