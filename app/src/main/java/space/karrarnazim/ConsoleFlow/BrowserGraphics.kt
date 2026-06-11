package space.karrarnazim.ConsoleFlow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

internal fun isHomeUrl(url: String?): Boolean {
    if (url.isNullOrEmpty()) return true
    return url == HOME_URL_CONST || url == "about:blank" ||
           url == "error://page" || url.startsWith("error://")
}
internal fun generateHomePreviewBitmap(width: Int = 540, height: Int = 900): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    canvas.drawColor(Color.BLACK)

    val cardPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2D34") }
    val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A3A3A") }
    val textPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5E5E5")
        textSize = 40f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A7A7A")
        textSize = 22f
    }

    fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, paint: Paint) {
        canvas.drawRoundRect(RectF(l, t, r, b), radius, radius, paint)
    }

    val settingsStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawRoundRect(RectF(width - 92f, 22f, width - 22f, 92f), 18f, 18f, accentPaint)
    canvas.drawLine(width - 72f, 42f, width - 42f, 42f, settingsStroke)
    canvas.drawLine(width - 72f, 62f, width - 52f, 62f, settingsStroke)
    canvas.drawLine(width - 72f, 82f, width - 62f, 82f, settingsStroke)
    canvas.drawLine(width - 50f, 28f, width - 50f, 34f, settingsStroke)
    canvas.drawLine(width - 30f, 48f, width - 30f, 54f, settingsStroke)
    canvas.drawLine(width - 50f, 68f, width - 50f, 74f, settingsStroke)

    roundRect(34f, 168f, width - 34f, 326f, 72f, cardPaint)

    val gPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 12f; strokeCap = Paint.Cap.ROUND
    }
    val gx = 112f; val gy = 247f; val gr = 30f
    gPaint.color = Color.parseColor("#EA4335"); canvas.drawArc(gx-gr,gy-gr,gx+gr,gy+gr, 20f, 82f, false, gPaint)
    gPaint.color = Color.parseColor("#FBBC05"); canvas.drawArc(gx-gr,gy-gr,gx+gr,gy+gr,102f, 78f, false, gPaint)
    gPaint.color = Color.parseColor("#34A853"); canvas.drawArc(gx-gr,gy-gr,gx+gr,gy+gr,180f, 80f, false, gPaint)
    gPaint.color = Color.parseColor("#4285F4"); canvas.drawArc(gx-gr,gy-gr,gx+gr,gy+gr,260f, 74f, false, gPaint)
    canvas.drawText("Search", 184f, 258f, textPaint)

    fun iconTile(x: Float, y: Float) { roundRect(x, y, x+58f, y+58f, 18f, accentPaint) }
    iconTile(width - 222f, 221f); iconTile(width - 144f, 221f)

    val qrX = width - 204f; val qrY = 237f
    val qrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    repeat(3) { row -> repeat(3) { col ->
        canvas.drawRect(qrX+col*10f, qrY+row*10f, qrX+col*10f+7f, qrY+row*10f+7f, qrPaint)
    }}
    canvas.drawRect(qrX+28f, qrY+10f, qrX+33f, qrY+15f, qrPaint)
    canvas.drawRect(qrX+20f, qrY+28f, qrX+25f, qrY+33f, qrPaint)

    val micX = width - 122f
    val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    canvas.drawRoundRect(RectF(micX+16f, 234f, micX+38f, 272f), 11f, 11f, micPaint)
    canvas.drawArc(RectF(micX+11f, 232f, micX+43f, 270f), 0f, 180f, false, micPaint)
    canvas.drawLine(micX+27f, 272f, micX+27f, 289f, micPaint)
    canvas.drawLine(micX+16f, 289f, micX+38f, 289f, micPaint)

    canvas.drawText("Bookmarks", 40f, 406f, mutedPaint)

    val sample = listOf(
        Triple("GitHub",       Color.parseColor("#FFFFFF"), "GH"),
        Triple("Stack Overflow",Color.parseColor("#FFFFFF"), "SO"),
        Triple("MDN",          Color.parseColor("#FFFFFF"), "MDN"),
        Triple("npm",          Color.parseColor("#C63636"), "npm"),
        Triple("Docker",       Color.parseColor("#2496ED"), "D"),
        Triple("Dev.to",       Color.parseColor("#FFFFFF"), "DEV")
    )
    val lefts = floatArrayOf(36f, 206f, 376f)
    val tops  = floatArrayOf(446f, 650f)
    var idx = 0
    for (row in tops) {
        for (col in lefts) {
            if (idx >= sample.size) break
            val (name, bg, txt) = sample[idx++]
            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
            roundRect(col, row, col+96f, row+96f, 26f, tilePaint)
            val textColor = if (name == "npm") Color.WHITE else Color.BLACK
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor; textAlign = Paint.Align.CENTER
                textSize = if (txt.length > 2) 22f else 28f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(txt, col+48f, row+58f, lp)
            canvas.drawText(name, col+48f, row+126f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 19f
            })
        }
    }
    return bitmap
}

internal fun createMicBitmap(sizePx: Int): Bitmap {
    val size = maxOf(sizePx, 48)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = maxOf(4.2f, size * 0.11f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    val w = size.toFloat(); val h = size.toFloat()
    canvas.drawRoundRect(RectF(w*0.34f, h*0.12f, w*0.66f, h*0.64f), w*0.16f, w*0.16f, paint)
    canvas.drawLine(w*0.5f, h*0.64f, w*0.5f, h*0.78f, paint)
    canvas.drawLine(w*0.35f, h*0.78f, w*0.65f, h*0.78f, paint)
    canvas.drawArc(RectF(w*0.28f, h*0.10f, w*0.72f, h*0.66f), 200f, 140f, false, paint)
    return bitmap
}
