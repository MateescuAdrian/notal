package com.notal.atelier.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.notal.atelier.model.Accidental
import com.notal.atelier.model.CelloString
import com.notal.atelier.model.Instrument
import com.notal.atelier.model.NoteEvent
import kotlin.math.roundToInt

class CelloFingerboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var activeNote: NoteEvent? = null
        set(value) {
            field = value
            invalidate()
        }

    var scoreNotes: List<NoteEvent> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var activeInstrument: Instrument = Instrument.CELLO
        set(value) {
            field = value
            invalidate()
        }

    var onFingerboardPitchTapped: ((step: Char, octave: Int, accidental: Accidental) -> Unit)? = null

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val neckEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8DEC8.toInt()
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33D4B483
        strokeWidth = dp(1.2f)
    }
    private val guideTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB7A894.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val stringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stopStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFFFFFFFF.toInt()
    }
    private val stopTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = 0xFF140F0C.toInt()
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF140F0C.toInt()
    }

    private var boardLeft = 0f
    private var boardRight = 0f
    private var boardTop = 0f
    private var boardBottom = 0f

    // Visual string order top-to-bottom: I (A), II (D), III (G), IV (C)
    private val stringOrder = listOf(
        CelloString.A_I,
        CelloString.D_II,
        CelloString.G_III,
        CelloString.C_IV
    )

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(320)
        setMeasuredDimension(w, dp(164f).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Varnished maple/spruce outer frame
        val frameRect = RectF(dp(2f), dp(2f), w - dp(2f), h - dp(2f))
        neckEdgePaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF3E2616.toInt(), 0xFF24160D.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(frameRect, dp(16f), dp(16f), neckEdgePaint)
        neckEdgePaint.shader = null

        boardLeft = dp(52f)
        boardRight = w - dp(16f)
        boardTop = dp(24f)
        boardBottom = h - dp(18f)

        // Ebony fingerboard surface
        val boardRect = RectF(boardLeft, boardTop, boardRight, boardBottom)
        boardPaint.shader = LinearGradient(
            boardLeft, boardTop, boardLeft, boardBottom,
            intArrayOf(0xFF1A1614.toInt(), 0xFF100D0B.toInt(), 0xFF1A1614.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(boardRect, dp(10f), dp(10f), boardPaint)
        boardPaint.shader = null

        // Ivory nut at left
        val nutX = boardLeft + dp(14f)
        canvas.drawRect(nutX - dp(3f), boardTop + dp(2f), nutX + dp(3f), boardBottom - dp(2f), nutPaint)

        val usableLen = (boardRight - nutX - dp(16f)).coerceAtLeast(80f)

        // Position fret/tape guide lines (Open, 1st Pos, Ext, 4th Pos, Octave)
        val guides = listOf(
            0.0f to "Open",
            0.16f to "1st",
            0.29f to "Ext",
            0.46f to "4th",
            0.70f to "Upper"
        )
        guideTextPaint.textSize = sp(9.5f)
        for ((ratio, title) in guides) {
            val gx = nutX + ratio * usableLen
            if (ratio > 0f) {
                canvas.drawLine(gx, boardTop + dp(3f), gx, boardBottom - dp(3f), guidePaint)
            }
            canvas.drawText(title, gx, boardTop - dp(7f), guideTextPaint)
        }

        // Draw 4 strings with realistic thickness + color tint
        val stringSpacing = (boardBottom - boardTop - dp(20f)) / 3f
        val openNames = activeInstrument.openStrings // C2, G2, D3, A3 order -> reversed for top-down
        val displayNames = listOf(
            "I ${openNames.getOrElse(3) { "A3" }}",
            "II ${openNames.getOrElse(2) { "D3" }}",
            "III ${openNames.getOrElse(1) { "G2" }}",
            "IV ${openNames.getOrElse(0) { "C2" }}"
        )

        stringOrder.forEachIndexed { idx, celloStr ->
            val sy = boardTop + dp(10f) + idx * stringSpacing
            val isActiveString = activeNote?.annotation?.celloString == celloStr

            // String name label on left wood rail
            labelPaint.color = celloStr.colorHex
            labelPaint.textSize = sp(11f)
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(displayNames[idx], dp(10f), sy + dp(4f), labelPaint)

            // String line
            stringPaint.color = if (isActiveString) celloStr.colorHex else (celloStr.colorHex and 0x00FFFFFF) or 0xAA000000.toInt()
            stringPaint.strokeWidth = dp(1.4f + idx * 0.65f)
            canvas.drawLine(nutX, sy, boardRight - dp(8f), sy, stringPaint)
        }

        // Ghost dots for all score notes on fingerboard
        val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (n in scoreNotes) {
            val sIdx = stringOrder.indexOf(n.annotation.celloString).coerceAtLeast(0)
            val sy = boardTop + dp(10f) + sIdx * stringSpacing
            val sx = nutX + n.annotation.stringStopRatio * usableLen
            ghostPaint.color = (n.annotation.celloString.colorHex and 0x00FFFFFF) or 0x55000000
            canvas.drawCircle(sx, sy, dp(5.5f), ghostPaint)
        }

        // Active / Selected Note glowing stop marker with ↑/↓ + Finger Number
        val current = activeNote ?: return
        val sIdx = stringOrder.indexOf(current.annotation.celloString).coerceAtLeast(0)
        val sy = boardTop + dp(10f) + sIdx * stringSpacing
        val sx = nutX + current.annotation.stringStopRatio * usableLen

        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (current.annotation.celloString.colorHex and 0x00FFFFFF) or 0x55000000
        }
        canvas.drawCircle(sx, sy, dp(20f), halo)

        val hasArrow = current.annotation.arrowPrefix.isNotEmpty()
        val markerR = dp(13.5f)
        val pillW = if (hasArrow) dp(34f) else markerR * 2f
        val pillRect = RectF(sx - pillW / 2f, sy - markerR, sx + pillW / 2f, sy + markerR)

        stopPaint.color = current.annotation.celloString.colorHex
        canvas.drawRoundRect(pillRect, markerR, markerR, stopPaint)
        canvas.drawRoundRect(pillRect, markerR, markerR, stopStrokePaint)

        stopTextPaint.textSize = sp(13f)
        val textY = pillRect.centerY() - (stopTextPaint.descent() + stopTextPaint.ascent()) / 2f
        if (hasArrow) {
            drawArrow(
                canvas = canvas,
                cx = pillRect.left + dp(10.5f),
                cy = pillRect.centerY(),
                up = current.annotation.arrowPrefix == "↑"
            )
            canvas.drawText(current.annotation.fingerNumber.toString(), pillRect.right - dp(10f), textY, stopTextPaint)
        } else {
            canvas.drawText(current.annotation.fingerNumber.toString(), pillRect.centerX(), textY, stopTextPaint)
        }
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, up: Boolean) {
        arrowPaint.strokeWidth = dp(1.6f)
        val halfShaft = dp(4.8f)
        val hw = dp(3.1f)
        val hh = dp(3.4f)
        if (up) {
            canvas.drawLine(cx, cy + halfShaft, cx, cy - halfShaft + dp(1.2f), arrowPaint)
            val p = Path().apply {
                moveTo(cx, cy - halfShaft - dp(0.5f))
                lineTo(cx - hw, cy - halfShaft + hh)
                lineTo(cx + hw, cy - halfShaft + hh)
                close()
            }
            canvas.drawPath(p, arrowPaint)
        } else {
            canvas.drawLine(cx, cy - halfShaft, cx, cy + halfShaft - dp(1.2f), arrowPaint)
            val p = Path().apply {
                moveTo(cx, cy + halfShaft + dp(0.5f))
                lineTo(cx - hw, cy + halfShaft - hh)
                lineTo(cx + hw, cy + halfShaft - hh)
                close()
            }
            canvas.drawPath(p, arrowPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) {
            return event.action == MotionEvent.ACTION_DOWN
        }
        val tx = event.x
        val ty = event.y
        if (ty < boardTop || ty > boardBottom || tx < boardLeft || tx > boardRight) {
            return super.onTouchEvent(event)
        }

        val stringSpacing = (boardBottom - boardTop - dp(20f)) / 3f
        val relY = (ty - (boardTop + dp(10f))).coerceAtLeast(0f)
        val sIdx = (relY / stringSpacing).roundToInt().coerceIn(0, 3)
        val chosenString = stringOrder[sIdx]

        val nutX = boardLeft + dp(14f)
        val usableLen = (boardRight - nutX - dp(16f)).coerceAtLeast(80f)
        val ratio = ((tx - nutX) / usableLen).coerceIn(0f, 0.72f)
        val approxSemis = (ratio * 12f).roundToInt().coerceIn(0, 9)

        val midi = chosenString.openMidi + approxSemis
        val (step, octave, acc) = midiToStepOctaveAccidental(midi)
        onFingerboardPitchTapped?.invoke(step, octave, acc)
        return true
    }

    private fun midiToStepOctaveAccidental(midi: Int): Triple<Char, Int, Accidental> {
        val pc = midi % 12
        val octave = (midi / 12) - 1
        return when (pc) {
            0 -> Triple('C', octave, Accidental.NONE)
            1 -> Triple('C', octave, Accidental.SHARP)
            2 -> Triple('D', octave, Accidental.NONE)
            3 -> Triple('E', octave, Accidental.FLAT)
            4 -> Triple('E', octave, Accidental.NONE)
            5 -> Triple('F', octave, Accidental.NONE)
            6 -> Triple('F', octave, Accidental.SHARP)
            7 -> Triple('G', octave, Accidental.NONE)
            8 -> Triple('G', octave, Accidental.SHARP)
            9 -> Triple('A', octave, Accidental.NONE)
            10 -> Triple('B', octave, Accidental.FLAT)
            else -> Triple('B', octave, Accidental.NONE)
        }
    }
}
