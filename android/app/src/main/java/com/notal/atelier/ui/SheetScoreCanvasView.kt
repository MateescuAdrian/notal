package com.notal.atelier.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.notal.atelier.engine.CelloFingeringEngine
import com.notal.atelier.model.Accidental
import com.notal.atelier.model.Clef
import com.notal.atelier.model.Instrument
import com.notal.atelier.model.NoteEvent
import com.notal.atelier.model.SheetScore
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SheetScoreCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class TouchMode {
        AUDITION,
        CYCLE_ACCIDENTAL,
        ADD_NOTE
    }

    var score: SheetScore? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var activeInstrument: Instrument = Instrument.CELLO
        set(value) {
            field = value
            invalidate()
        }

    var selectedNoteIndex: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var playingNoteIndex: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    var parchmentTheme: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var touchMode: TouchMode = TouchMode.AUDITION
        set(value) {
            field = value
            invalidate()
        }

    var scannedSourceBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var showScanBackdrop: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onNoteTapped: ((index: Int, note: NoteEvent) -> Unit)? = null
    var onStaffTappedToAdd: ((step: Char, octave: Int, insertIndex: Int) -> Unit)? = null

    private var scanProgress: Float = -1f
    private var scanAnimator: ValueAnimator? = null

    private data class NoteHitBox(
        val index: Int,
        val cx: Float,
        val cy: Float,
        val badgeRect: RectF,
        val systemIndex: Int
    )

    private data class SystemGeometry(
        val systemIndex: Int,
        val staffTop: Float,
        val staffBottom: Float,
        val lineGap: Float,
        val startX: Float,
        val endX: Float
    )

    private val noteHitBoxes = mutableListOf<NoteHitBox>()
    private val systemGeometries = mutableListOf<SystemGeometry>()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val staffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.4f)
    }
    private val barlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.8f)
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2.1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val clefPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SERIF
    }
    private val headerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val headerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val accPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val scanLaserPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scanBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        v,
        resources.displayMetrics
    )

    fun triggerScanAnimation() {
        scanAnimator?.cancel()
        scanAnimator = ValueAnimator.ofFloat(0f, 1.05f).apply {
            duration = 1250L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                scanProgress = anim.animatedValue as Float
                if (scanProgress > 1.02f) {
                    scanProgress = -1f
                }
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(320)
        val noteCount = score?.notes?.size ?: 0
        val notesPerSystem = if (w > dp(560f)) 8 else 4
        val systems = max(1, ceil(max(1, noteCount).toDouble() / notesPerSystem).toInt())
        val desiredHeight = (dp(98f) + systems * dp(176f) + dp(18f)).roundToInt()
        setMeasuredDimension(w, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        noteHitBoxes.clear()
        systemGeometries.clear()

        val w = width.toFloat()
        val h = height.toFloat()
        val corner = dp(18f)
        val cardRect = RectF(dp(2f), dp(2f), w - dp(2f), h - dp(2f))

        // Manuscript background
        if (parchmentTheme) {
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0xFFFDF8EE.toInt(), 0xFFF5ECD8.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            borderPaint.color = 0xFFD9C7A3.toInt()
        } else {
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0xFF1E1713.toInt(), 0xFF140F0C.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            borderPaint.color = 0xFF3E3024.toInt()
        }
        canvas.drawRoundRect(cardRect, corner, corner, bgPaint)
        bgPaint.shader = null
        canvas.drawRoundRect(cardRect, corner, corner, borderPaint)

        // Optional translucent scanned sheet photo backdrop
        val bmp = scannedSourceBitmap
        if (showScanBackdrop && bmp != null) {
            val save = canvas.save()
            val clipPath = Path().apply { addRoundRect(cardRect, corner, corner, Path.Direction.CW) }
            canvas.clipPath(clipPath)
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = if (parchmentTheme) 48 else 36 }
            val dst = RectF(dp(14f), dp(68f), w - dp(14f), h - dp(14f))
            canvas.drawBitmap(bmp, null, dst, bmpPaint)
            canvas.restoreToCount(save)
        }

        val currentScore = score ?: return

        val primaryInk = if (parchmentTheme) 0xFF1E1915.toInt() else 0xFFF5EFE3.toInt()
        val secondaryInk = if (parchmentTheme) 0xFF6B5D4D.toInt() else 0xFFB7A894.toInt()
        val staffInk = if (parchmentTheme) 0xFF8C7B65.toInt() else 0xFF5D4D3E.toInt()

        // Title & concise metadata header
        headerTitlePaint.color = primaryInk
        headerTitlePaint.textSize = sp(16f)
        canvas.drawText(currentScore.title, dp(22f), dp(33f), headerTitlePaint)

        headerSubPaint.color = secondaryInk
        headerSubPaint.textSize = sp(11.5f)
        val metaLine = "${currentScore.composer}  •  ${currentScore.clef.label} (${currentScore.clef.symbol})  •  ${currentScore.keySignatureLabel}"
        canvas.drawText(metaLine, dp(22f), dp(52f), headerSubPaint)

        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (parchmentTheme) 0x338C7B65 else 0x33D4B483
            strokeWidth = dp(1f)
        }
        canvas.drawLine(dp(22f), dp(66f), w - dp(22f), dp(66f), dividerPaint)

        // Systems layout (4 notes per system on portrait phones for uncrowded readability)
        val notes = currentScore.notes
        val notesPerSystem = if (w > dp(560f)) 8 else 4
        val systems = max(1, ceil(max(1, notes.size).toDouble() / notesPerSystem).toInt())

        val lineGap = dp(11.5f)
        val staffHeight = lineGap * 4f
        val systemBlockHeight = dp(176f)
        val startX = dp(20f)
        val endX = w - dp(20f)

        staffPaint.color = staffInk
        barlinePaint.color = staffInk

        for (sys in 0 until systems) {
            val staffTop = dp(116f) + sys * systemBlockHeight
            val staffBottom = staffTop + staffHeight

            systemGeometries.add(
                SystemGeometry(sys, staffTop, staffBottom, lineGap, startX + dp(68f), endX - dp(12f))
            )

            // Draw 5 staff lines
            for (line in 0 until 5) {
                val y = staffTop + line * lineGap
                canvas.drawLine(startX, y, endX, y, staffPaint)
            }
            // Start & end barlines
            canvas.drawLine(startX, staffTop, startX, staffBottom, barlinePaint)
            canvas.drawLine(endX, staffTop, endX, staffBottom, barlinePaint)

            // Draw Clef & Time Signature
            drawClefAndSignature(canvas, currentScore.clef, currentScore.timeSignature, sys == 0, startX, staffTop, lineGap, primaryInk)

            val sysStartIdx = sys * notesPerSystem
            val sysEndIdx = min(notes.size, sysStartIdx + notesPerSystem)
            val sysNotesCount = sysEndIdx - sysStartIdx
            if (sysNotesCount <= 0) continue

            val noteAreaStart = startX + dp(if (sys == 0) 90f else 66f)
            val noteAreaWidth = (endX - dp(22f) - noteAreaStart).coerceAtLeast(dp(120f))
            val stepX = noteAreaWidth / max(1, notesPerSystem)

            for (localIdx in 0 until sysNotesCount) {
                val globalIdx = sysStartIdx + localIdx
                val note = notes[globalIdx]
                val cx = noteAreaStart + (localIdx + 0.45f) * stepX
                val cy = staffBottom - note.staffLineOffset * (lineGap / 2f)

                // Draw measure barlines every 4 notes
                if (localIdx > 0 && globalIdx % 4 == 0) {
                    val barX = cx - stepX * 0.5f
                    canvas.drawLine(barX, staffTop, barX, staffBottom, barlinePaint)
                }

                val isSelected = globalIdx == selectedNoteIndex
                val isPlaying = globalIdx == playingNoteIndex

                drawLedgerLines(canvas, cx, note.staffLineOffset, staffTop, staffBottom, lineGap, staffInk)
                drawNoteAndFingering(
                    canvas = canvas,
                    note = note,
                    globalIdx = globalIdx,
                    systemIndex = sys,
                    cx = cx,
                    cy = cy,
                    staffTop = staffTop,
                    staffBottom = staffBottom,
                    lineGap = lineGap,
                    primaryInk = primaryInk,
                    secondaryInk = secondaryInk,
                    isSelected = isSelected,
                    isPlaying = isPlaying
                )
            }
        }

        // Animated Optical Scanner laser sweep overlay
        if (scanProgress in 0f..1f) {
            val laserY = dp(64f) + scanProgress * (h - dp(80f))
            scanLaserPaint.shader = LinearGradient(
                0f, laserY - dp(22f), 0f, laserY + dp(8f),
                intArrayOf(0x00E5A93C, 0xCCE5A93C.toInt(), 0x00E5A93C),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(dp(8f), laserY - dp(22f), w - dp(8f), laserY + dp(8f), scanLaserPaint)
            scanLaserPaint.shader = null

            val beamLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFC845.toInt()
                strokeWidth = dp(2.5f)
            }
            canvas.drawLine(dp(10f), laserY, w - dp(10f), laserY, beamLine)

            // Draw dashed OMR detection boxes on notes already swept by the laser
            scanBoxPaint.color = 0xFFE5A93C.toInt()
            for (box in noteHitBoxes) {
                if (box.cy <= laserY + dp(12f)) {
                    canvas.drawRoundRect(
                        box.cx - dp(15f), box.cy - dp(13f),
                        box.cx + dp(15f), box.cy + dp(13f),
                        dp(5f), dp(5f), scanBoxPaint
                    )
                }
            }
        }
    }

    private fun drawClefAndSignature(
        canvas: Canvas,
        clef: Clef,
        timeSignature: String,
        drawTimeSig: Boolean,
        startX: Float,
        staffTop: Float,
        lineGap: Float,
        inkColor: Int
    ) {
        clefPaint.color = inkColor
        clefPaint.textSize = lineGap * 3.9f
        val clefY = when (clef) {
            Clef.BASS -> staffTop + lineGap * 3.4f
            Clef.TENOR -> staffTop + lineGap * 3.6f
            Clef.TREBLE -> staffTop + lineGap * 3.7f
        }
        canvas.drawText(clef.symbol, startX + dp(8f), clefY, clefPaint)

        if (drawTimeSig) {
            val parts = timeSignature.split("/")
            val topSig = parts.getOrElse(0) { "4" }
            val botSig = parts.getOrElse(1) { "4" }
            val tsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkColor
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textSize = lineGap * 1.9f
                textAlign = Paint.Align.CENTER
            }
            val tsX = startX + dp(52f)
            canvas.drawText(topSig, tsX, staffTop + lineGap * 1.75f, tsPaint)
            canvas.drawText(botSig, tsX, staffTop + lineGap * 3.75f, tsPaint)
        }
    }

    private fun drawLedgerLines(
        canvas: Canvas,
        cx: Float,
        offset: Float,
        staffTop: Float,
        staffBottom: Float,
        lineGap: Float,
        staffInk: Int
    ) {
        val ledgerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = staffInk
            strokeWidth = dp(1.6f)
        }
        val halfW = dp(11f)
        // Below staff: offset <= -2, step -2
        var lo = -2
        while (lo >= offset.roundToInt()) {
            val ly = staffBottom - lo * (lineGap / 2f)
            canvas.drawLine(cx - halfW, ly, cx + halfW, ly, ledgerPaint)
            lo -= 2
        }
        // Above staff: offset >= 10, step +2
        var hi = 10
        while (hi <= offset.roundToInt()) {
            val ly = staffBottom - hi * (lineGap / 2f)
            canvas.drawLine(cx - halfW, ly, cx + halfW, ly, ledgerPaint)
            hi += 2
        }
    }

    private fun drawNoteAndFingering(
        canvas: Canvas,
        note: NoteEvent,
        globalIdx: Int,
        systemIndex: Int,
        cx: Float,
        cy: Float,
        staffTop: Float,
        staffBottom: Float,
        lineGap: Float,
        primaryInk: Int,
        secondaryInk: Int,
        isSelected: Boolean,
        isPlaying: Boolean
    ) {
        val stringColor = note.annotation.celloString.colorHex

        // Selection / Playback Halo behind notehead
        if (isPlaying || isSelected) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isPlaying) 0x55F59E0B else 0x33E5A93C
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, dp(16f), glowPaint)
            haloPaint.color = if (isPlaying) 0xFFF59E0B.toInt() else 0xFFE5A93C.toInt()
            canvas.drawCircle(cx, cy, dp(14f), haloPaint)
        }

        // Inline Accidental symbol in front of notehead (# or b)
        if (note.accidental != Accidental.NONE) {
            accPaint.color = when (note.accidental) {
                Accidental.SHARP -> 0xFFD97706.toInt()
                Accidental.FLAT -> 0xFF0284C7.toInt()
                Accidental.NATURAL -> secondaryInk
                Accidental.NONE -> secondaryInk
            }
            accPaint.textSize = sp(17f)
            canvas.drawText(note.accidental.symbol, cx - dp(10.5f), cy + dp(5.5f), accPaint)
        }

        // Tilted elliptical notehead
        val isOpenDuration = note.durationBeats >= 2.0f
        notePaint.color = if (isPlaying) 0xFFF59E0B.toInt() else primaryInk
        notePaint.style = if (isOpenDuration) Paint.Style.STROKE else Paint.Style.FILL
        notePaint.strokeWidth = dp(2.2f)

        val save = canvas.save()
        canvas.rotate(-18f, cx, cy)
        val rx = dp(6.6f)
        val ry = dp(4.7f)
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, notePaint)
        canvas.restoreToCount(save)

        // Note stem (up if below middle line, down if on or above middle line)
        stemPaint.color = if (isPlaying) 0xFFF59E0B.toInt() else primaryInk
        val stemUp = note.staffLineOffset < 4f
        val stemLen = dp(29f)
        if (stemUp) {
            val sx = cx + dp(5.6f)
            canvas.drawLine(sx, cy - dp(1f), sx, cy - stemLen, stemPaint)
            if (note.durationBeats <= 0.6f) {
                // Eighth-note flag
                val flagPath = Path().apply {
                    moveTo(sx, cy - stemLen)
                    quadTo(sx + dp(9f), cy - stemLen + dp(6f), sx + dp(6f), cy - stemLen + dp(15f))
                }
                val flagPaint = Paint(stemPaint).apply { style = Paint.Style.STROKE }
                canvas.drawPath(flagPath, flagPaint)
            }
        } else {
            val sx = cx - dp(5.6f)
            canvas.drawLine(sx, cy + dp(1f), sx, cy + stemLen, stemPaint)
            if (note.durationBeats <= 0.6f) {
                val flagPath = Path().apply {
                    moveTo(sx, cy + stemLen)
                    quadTo(sx + dp(9f), cy + stemLen - dp(6f), sx + dp(6f), cy + stemLen - dp(15f))
                }
                val flagPaint = Paint(stemPaint).apply { style = Paint.Style.STROKE }
                canvas.drawPath(flagPath, flagPaint)
            }
        }

        // === CELLO FINGERING BADGE ABOVE STAFF (level horizontal track with ↑ / ↓ arrow IN FRONT) ===
        val hasArrow = note.annotation.arrowPrefix.isNotEmpty()
        val badgeW = if (hasArrow) dp(34f) else dp(24f)
        val badgeH = dp(23f)
        val defaultBadgeBottom = staffTop - dp(18f)
        val noteTopY = if (stemUp) cy - stemLen else cy - dp(8f)
        val badgeBottom = min(defaultBadgeBottom, noteTopY - dp(8f))
        val badgeTop = badgeBottom - badgeH
        val badgeRect = RectF(cx - badgeW / 2f, badgeTop, cx + badgeW / 2f, badgeBottom)

        // Badge background & outline
        badgeFillPaint.color = when {
            isPlaying -> 0xFFF59E0B.toInt()
            isSelected -> stringColor
            parchmentTheme -> 0xFF1F1915.toInt()
            else -> 0xFF281F18.toInt()
        }
        canvas.drawRoundRect(badgeRect, dp(7f), dp(7f), badgeFillPaint)

        badgeStrokePaint.color = if (isPlaying) 0xFFFFFFFF.toInt() else stringColor
        badgeStrokePaint.strokeWidth = if (isSelected || isPlaying) dp(2f) else dp(1.4f)
        canvas.drawRoundRect(badgeRect, dp(7f), dp(7f), badgeStrokePaint)

        val contentColor = if (isPlaying || isSelected) 0xFF120E0B.toInt() else 0xFFFCF7ED.toInt()
        val arrowAccentColor = when {
            isPlaying || isSelected -> 0xFF120E0B.toInt()
            note.accidental == Accidental.SHARP -> 0xFFFFC145.toInt()
            note.accidental == Accidental.FLAT -> 0xFF67E8F9.toInt()
            else -> contentColor
        }

        badgeTextPaint.color = contentColor
        badgeTextPaint.textSize = sp(13.5f)
        val textCenterY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f

        if (hasArrow) {
            val isUpArrow = note.annotation.arrowPrefix == "↑"
            val arrowX = badgeRect.left + dp(10.5f)
            drawLittleVectorArrow(
                canvas = canvas,
                cx = arrowX,
                cy = badgeRect.centerY(),
                pointingUp = isUpArrow,
                color = arrowAccentColor
            )
            // Finger number positioned cleanly AFTER the arrow
            canvas.drawText(
                note.annotation.fingerNumber.toString(),
                badgeRect.right - dp(10f),
                textCenterY,
                badgeTextPaint
            )
        } else {
            canvas.drawText(
                note.annotation.fingerNumber.toString(),
                badgeRect.centerX(),
                textCenterY,
                badgeTextPaint
            )
        }

        // Below-staff Pitch + Roman String Label on a level baseline
        val noteBottomY = if (stemUp) cy + dp(8f) else cy + stemLen
        val belowY = max(staffBottom + dp(20f), noteBottomY + dp(14f))
        subLabelPaint.color = stringColor
        subLabelPaint.textSize = sp(9.5f)
        subLabelPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(note.annotation.celloString.roman, cx, belowY, subLabelPaint)

        subLabelPaint.color = secondaryInk
        subLabelPaint.textSize = sp(9.2f)
        subLabelPaint.typeface = Typeface.DEFAULT
        canvas.drawText(note.pitchLabel, cx, belowY + dp(11.5f), subLabelPaint)

        noteHitBoxes.add(NoteHitBox(globalIdx, cx, cy, badgeRect, systemIndex))
    }

    private fun drawLittleVectorArrow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        pointingUp: Boolean,
        color: Int
    ) {
        arrowPaint.color = color
        arrowPaint.strokeWidth = dp(1.6f)
        val halfShaft = dp(5.2f)
        val headW = dp(3.4f)
        val headH = dp(3.8f)

        if (pointingUp) {
            // Vertical shaft
            canvas.drawLine(cx, cy + halfShaft, cx, cy - halfShaft + dp(1.5f), arrowPaint)
            // Arrowhead pointing up
            val head = Path().apply {
                moveTo(cx, cy - halfShaft - dp(0.6f))
                lineTo(cx - headW, cy - halfShaft + headH)
                lineTo(cx + headW, cy - halfShaft + headH)
                close()
            }
            canvas.drawPath(head, arrowPaint)
        } else {
            // Vertical shaft
            canvas.drawLine(cx, cy - halfShaft, cx, cy + halfShaft - dp(1.5f), arrowPaint)
            // Arrowhead pointing down
            val head = Path().apply {
                moveTo(cx, cy + halfShaft + dp(0.6f))
                lineTo(cx - headW, cy + halfShaft - headH)
                lineTo(cx + headW, cy + halfShaft - headH)
                close()
            }
            canvas.drawPath(head, arrowPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) {
            return event.action == MotionEvent.ACTION_DOWN
        }

        val tx = event.x
        val ty = event.y

        // Check existing note hitboxes (both notehead & finger badge)
        var bestIdx = -1
        var bestDist = dp(28f)
        for (box in noteHitBoxes) {
            val inBadge = box.badgeRect.contains(tx, ty)
            val d = hypot(tx - box.cx, ty - box.cy)
            if (inBadge) {
                bestIdx = box.index
                break
            }
            if (d < bestDist) {
                bestDist = d
                bestIdx = box.index
            }
        }

        val currentScore = score
        if (bestIdx != -1 && currentScore != null && bestIdx < currentScore.notes.size) {
            selectedNoteIndex = bestIdx
            onNoteTapped?.invoke(bestIdx, currentScore.notes[bestIdx])
            return true
        }

        // If in ADD_NOTE mode and user tapped a staff system, insert a note at that pitch!
        if (touchMode == TouchMode.ADD_NOTE && currentScore != null) {
            var nearestSys: SystemGeometry? = null
            var minVert = Float.MAX_VALUE
            for (sys in systemGeometries) {
                val centerY = (sys.staffTop + sys.staffBottom) / 2f
                val dist = abs(ty - centerY)
                if (dist < minVert && dist < dp(82f)) {
                    minVert = dist
                    nearestSys = sys
                }
            }
            if (nearestSys != null) {
                val rawOffset = (nearestSys.staffBottom - ty) / (nearestSys.lineGap / 2f)
                val clampedOffset = rawOffset.roundToInt().toFloat().coerceIn(-4f, 12f)
                val (step, octave) = CelloFingeringEngine.staffOffsetToStepOctave(
                    clampedOffset,
                    currentScore.clef
                )
                onStaffTappedToAdd?.invoke(step, octave, currentScore.notes.size)
                return true
            }
        }

        return super.onTouchEvent(event)
    }
}
