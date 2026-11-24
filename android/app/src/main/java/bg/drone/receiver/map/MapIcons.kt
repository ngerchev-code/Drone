package bg.drone.receiver.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import bg.drone.rid.TargetKind

/** Рисува иконите за картата програмно (без растерни ресурси). Всички сочат на север. */
object MapIcons {
    fun create(kind: TargetKind, density: Float): Bitmap {
        val size = (44 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val color = kind.color.toInt()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFF05080C.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeJoin = Paint.Join.ROUND
        }
        when (kind) {
            TargetKind.DRONE -> drawQuad(c, s, fill, outline, density)
            TargetKind.MILITARY_UAV, TargetKind.UAV -> drawUav(c, s, fill, outline)
            TargetKind.RADIOSONDE -> drawBalloon(c, s, fill, outline, density)
        }
        return bmp
    }

    /** Квадрокоптер отгоре: рамка X, 4 ротора, нос (триъгълник) за посоката. */
    private fun drawQuad(c: Canvas, s: Float, fill: Paint, outline: Paint, d: Float) {
        val arm = Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = 3.4f * d; strokeCap = Paint.Cap.ROUND }
        val armOutline = Paint(outline).apply { strokeWidth = 6f * d; strokeCap = Paint.Cap.ROUND }
        val a = s * 0.27f
        val m = s / 2
        for (p in listOf(armOutline, arm)) {
            c.drawLine(m - a, m - a, m + a, m + a, p)
            c.drawLine(m + a, m - a, m - a, m + a, p)
        }
        val r = s * 0.12f
        for ((x, y) in listOf(m - a to m - a, m + a to m - a, m - a to m + a, m + a to m + a)) {
            c.drawCircle(x, y, r, fill)
            c.drawCircle(x, y, r, outline)
        }
        val nose = Path().apply {
            moveTo(m, s * 0.18f); lineTo(m + s * 0.09f, m - s * 0.02f); lineTo(m - s * 0.09f, m - s * 0.02f); close()
        }
        c.drawCircle(m, m, s * 0.1f, fill)
        c.drawCircle(m, m, s * 0.1f, outline)
        c.drawPath(nose, fill)
        c.drawPath(nose, outline)
    }

    /** Силует на голям БЛА: тънък фюзелаж, дълги прави крила, V-опашка. */
    private fun drawUav(c: Canvas, s: Float, fill: Paint, outline: Paint) {
        val m = s / 2
        val p = Path().apply {
            moveTo(m, s * 0.06f)                       // нос
            lineTo(m + s * 0.05f, s * 0.2f)
            lineTo(m + s * 0.05f, s * 0.36f)
            lineTo(m + s * 0.47f, s * 0.42f)           // дясно крило
            lineTo(m + s * 0.47f, s * 0.48f)
            lineTo(m + s * 0.05f, s * 0.5f)
            lineTo(m + s * 0.04f, s * 0.78f)
            lineTo(m + s * 0.2f, s * 0.92f)            // V-опашка
            lineTo(m + s * 0.19f, s * 0.96f)
            lineTo(m, s * 0.88f)
            lineTo(m - s * 0.19f, s * 0.96f)
            lineTo(m - s * 0.2f, s * 0.92f)
            lineTo(m - s * 0.04f, s * 0.78f)
            lineTo(m - s * 0.05f, s * 0.5f)
            lineTo(m - s * 0.47f, s * 0.48f)
            lineTo(m - s * 0.47f, s * 0.42f)
            lineTo(m - s * 0.05f, s * 0.36f)
            lineTo(m - s * 0.05f, s * 0.2f)
            close()
        }
        c.drawPath(p, fill)
        c.drawPath(p, outline)
    }

    /** Метеосонда: балон + въже + малка кутийка. */
    private fun drawBalloon(c: Canvas, s: Float, fill: Paint, outline: Paint, d: Float) {
        val m = s / 2
        val oval = RectF(m - s * 0.26f, s * 0.06f, m + s * 0.26f, s * 0.6f)
        val line = Paint(outline).apply { strokeWidth = 1.6f * d; color = fill.color }
        c.drawLine(m, s * 0.6f, m, s * 0.82f, Paint(outline).apply { strokeWidth = 3.5f * d })
        c.drawLine(m, s * 0.6f, m, s * 0.82f, line)
        c.drawOval(oval, fill)
        c.drawOval(oval, outline)
        val box = RectF(m - s * 0.08f, s * 0.8f, m + s * 0.08f, s * 0.94f)
        c.drawRect(box, fill)
        c.drawRect(box, outline)
        // отблясък
        c.drawOval(RectF(m - s * 0.15f, s * 0.13f, m - s * 0.04f, s * 0.27f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF })
    }

    /** Синята точка за позицията на телефона. */
    fun me(density: Float): Bitmap {
        val size = (26 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val m = size / 2f
        c.drawCircle(m, m, m, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553B82F6 })
        c.drawCircle(m, m, m * 0.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        c.drawCircle(m, m, m * 0.36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B82F6.toInt() })
        return bmp
    }
}
