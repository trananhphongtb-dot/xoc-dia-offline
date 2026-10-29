package vn.xocdiaoffline.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/** Bộ vẽ dùng chung cho các màn bàn chơi (bài, nút, chữ, nền nỉ, hiệu ứng). */
class Painter(ctx: Context) {
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Float) = v * d

    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val bold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    fun table(c: Canvas, w: Float, h: Float) {
        fill.shader = RadialGradient(w / 2, h * 0.45f, maxOf(w, h) * 0.7f,
            Color.parseColor("#13824F"), Ui.FELT_DARK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        stroke.color = Color.parseColor("#33F2C14E"); stroke.strokeWidth = dp(2f)
        c.drawRoundRect(RectF(w * 0.14f, h * 0.14f, w * 0.86f, h * 0.70f), h * 0.28f, h * 0.28f, stroke)
    }

    /** Lá bài ngửa. rankText/suit theo mã bài A=1..K=13 (Liêng, Ba cây, Phỏm). */
    fun face(c: Canvas, x: Float, y: Float, w: Float, h: Float, rankText: String, suit: Int,
             sel: Boolean = false, mark: Boolean = false, dim: Boolean = false) {
        val r = RectF(x, y, x + w, y + h)
        fill.color = Color.parseColor("#40000000")
        c.drawRoundRect(RectF(x + dp(1.5f), y + dp(2f), x + w + dp(1.5f), y + h + dp(2f)), w * 0.1f, w * 0.1f, fill)
        fill.color = when { dim -> Color.parseColor("#BDBDBD"); sel -> Color.parseColor("#FFF8E1"); else -> Color.WHITE }
        c.drawRoundRect(r, w * 0.1f, w * 0.1f, fill)
        stroke.strokeWidth = if (sel || mark) dp(2.5f) else dp(1f)
        stroke.color = when { sel -> Ui.GOLD; mark -> Color.parseColor("#2E7D32"); else -> Color.parseColor("#9E9E9E") }
        c.drawRoundRect(r, w * 0.1f, w * 0.1f, stroke)
        val sym = when (suit) { 0 -> "♠"; 1 -> "♣"; 2 -> "♦"; else -> "♥" }
        txt.color = if (suit >= 2) Color.parseColor("#D32F2F") else Color.parseColor("#212121")
        txt.textAlign = Paint.Align.CENTER; txt.typeface = bold
        txt.textSize = h * 0.21f
        c.drawText(rankText, x + w * 0.2f, y + h * 0.22f, txt)
        txt.textSize = h * 0.17f
        c.drawText(sym, x + w * 0.2f, y + h * 0.40f, txt)
        txt.textSize = h * 0.40f
        c.drawText(sym, x + w * 0.58f, y + h * 0.82f, txt)
    }

    fun faceA13(c: Canvas, x: Float, y: Float, w: Float, h: Float, card: Int, sel: Boolean = false, mark: Boolean = false, dim: Boolean = false) {
        val r = card / 4
        val t = when (r) { 1 -> "A"; 11 -> "J"; 12 -> "Q"; 13 -> "K"; else -> r.toString() }
        face(c, x, y, w, h, t, card % 4, sel, mark, dim)
    }

    fun back(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val r = RectF(x, y, x + w, y + h)
        fill.shader = LinearGradient(x, y, x + w, y + h, Color.parseColor("#C62828"), Color.parseColor("#7F0000"), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, w * 0.12f, w * 0.12f, fill)
        fill.shader = null
        stroke.color = Color.WHITE; stroke.strokeWidth = dp(1.5f)
        c.drawRoundRect(r, w * 0.12f, w * 0.12f, stroke)
        stroke.color = Color.parseColor("#88F2C14E"); stroke.strokeWidth = dp(1f)
        c.drawRoundRect(RectF(x + w * 0.14f, y + w * 0.14f, x + w * 0.86f, y + h - w * 0.14f), w * 0.08f, w * 0.08f, stroke)
    }

    fun button(c: Canvas, r: RectF, text: String, bg: Int, fg: Int, size: Float = dp(14f)) {
        fill.color = bg
        c.drawRoundRect(r, dp(12f), dp(12f), fill)
        label(c, text, r.centerX(), r.centerY() + size * 0.35f, size, fg, true)
    }

    fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, isBold: Boolean, align: Paint.Align = Paint.Align.CENTER) {
        txt.textAlign = align; txt.textSize = size; txt.color = color
        txt.typeface = if (isBold) bold else Typeface.DEFAULT
        c.drawText(s, x, y, txt)
    }

    fun pill(c: Canvas, s: String, x: Float, y: Float, size: Float, bg: Int, fg: Int) {
        txt.textSize = size; txt.typeface = bold
        val tw = txt.measureText(s)
        fill.color = bg
        c.drawRoundRect(RectF(x - tw / 2 - dp(8f), y - size - dp(3f), x + tw / 2 + dp(8f), y + dp(5f)), dp(9f), dp(9f), fill)
        label(c, s, x, y, size, fg, true)
    }

    fun avatar(c: Canvas, x: Float, y: Float, r: Float, name: String, active: Boolean, isTurn: Boolean, frac: Float) {
        fill.color = if (active) Color.parseColor("#1E3A2C") else Color.parseColor("#33263238")
        c.drawCircle(x, y, r, fill)
        stroke.strokeWidth = dp(3f)
        stroke.color = if (isTurn) Ui.GOLD else Color.parseColor("#55FFFFFF")
        c.drawCircle(x, y, r, stroke)
        if (frac >= 0) {
            stroke.color = if (frac < 0.3f) Ui.RED else Color.WHITE
            c.drawArc(RectF(x - r - dp(5f), y - r - dp(5f), x + r + dp(5f), y + r + dp(5f)), -90f, 360f * frac, false, stroke)
        }
        label(c, name.take(1).uppercase(), x, y + r * 0.36f, r * 0.9f, if (active) Color.WHITE else Ui.MUTED, true)
    }

    companion object {
        /** Đường cong nhanh-chậm cho hiệu ứng bay. */
        fun ease(t: Float): Float { val x = t.coerceIn(0f, 1f); return 1f - (1f - x) * (1f - x) * (1f - x) }
    }
}
