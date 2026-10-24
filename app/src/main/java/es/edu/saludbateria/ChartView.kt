package es.edu.saludbateria

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.roundToLong

/** Gráfica sencilla de la capacidad estimada en cada carga, con la línea de diseño. */
class ChartView(context: Context, private val pal: Palette) : View(context) {

    private var pts: List<Pair<Long, Double>> = emptyList()
    private var ref: Double? = null
    private val d = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.accent
        strokeWidth = 2.5f * d
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.accent
        style = Paint.Style.FILL
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.grid
        strokeWidth = 1f * d
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.sub
        strokeWidth = 1.5f * d
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f * d, 5f * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.sub
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }

    fun setData(points: List<Pair<Long, Double>>, reference: Double?) {
        pts = points
        ref = reference
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (190 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pts.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText("Aún no hay estimaciones", width / 2f, height / 2f, label)
            label.textAlign = Paint.Align.LEFT
            return
        }

        val values = pts.map { it.second } + listOfNotNull(ref)
        var lo = values.min()
        var hi = values.max()
        val span = max(hi - lo, 300.0)
        lo -= span * 0.2
        hi += span * 0.2

        val left = 46f * d
        val right = width - 10f * d
        val top = 10f * d
        val bottom = height - 22f * d

        fun y(v: Double): Float = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        val t0 = pts.first().first
        val t1 = pts.last().first
        fun x(t: Long): Float =
            if (t1 == t0) (left + right) / 2f
            else (left + (t - t0).toDouble() / (t1 - t0) * (right - left)).toFloat()

        for (k in 0..3) {
            val v = lo + (hi - lo) * k / 3
            val yy = y(v)
            canvas.drawLine(left, yy, right, yy, grid)
            canvas.drawText(Fmt.int(v.roundToLong()), 0f, yy + 4f * d, label)
        }

        ref?.let {
            val yy = y(it)
            canvas.drawLine(left, yy, right, yy, refPaint)
            val txt = "Diseño"
            canvas.drawText(txt, right - label.measureText(txt), yy - 5f * d, label)
        }

        if (pts.size > 1) {
            val path = Path()
            pts.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(x(p.first), y(p.second)) else path.lineTo(x(p.first), y(p.second))
            }
            canvas.drawPath(path, line)
        }
        for (p in pts) canvas.drawCircle(x(p.first), y(p.second), 3.5f * d, dot)

        val first = Fmt.date(t0).substringBefore(",")
        canvas.drawText(first, left, height - 4f * d, label)
        if (t1 != t0) {
            val last = Fmt.date(t1).substringBefore(",")
            canvas.drawText(last, right - label.measureText(last), height - 4f * d, label)
        }
    }
}
