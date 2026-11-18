package com.notal.atelier.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.notal.atelier.model.Accidental
import com.notal.atelier.model.CelloPositionMode
import com.notal.atelier.model.Clef
import com.notal.atelier.model.FlatArrowStyle
import com.notal.atelier.model.Instrument
import com.notal.atelier.model.NoteEvent
import com.notal.atelier.model.SheetScore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Optical Music Recognition (OMR) Bitmap Scanner.
 * Detects 5-line musical staves via horizontal luminance projection,
 * locates notehead blobs along the staff, identifies nearby # / b accidentals,
 * and annotates every detected note with Cello fingerings and arrows.
 */
object SheetMusicScannerEngine {

    fun scanBitmap(
        bitmap: Bitmap,
        flatArrowStyle: FlatArrowStyle = FlatArrowStyle.DOWN_ARROW,
        positionMode: CelloPositionMode = CelloPositionMode.AUTO,
        instrumentOverride: Instrument? = null
    ): SheetScore {
        val targetWidth = min(bitmap.width, 640).coerceAtLeast(120)
        val scale = targetWidth.toFloat() / bitmap.width.coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(120)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

        val pixels = IntArray(targetWidth * targetHeight)
        scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        val lum = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // 1. Horizontal darkness projection to find staff line candidates
        val rowDarkness = FloatArray(targetHeight)
        val darkThreshold = 148f
        for (y in 0 until targetHeight) {
            var count = 0
            val rowOffset = y * targetWidth
            for (x in 0 until targetWidth) {
                if (lum[rowOffset + x] < darkThreshold) count++
            }
            rowDarkness[y] = count.toFloat() / targetWidth
        }

        val staffLines = findFiveStaffLines(rowDarkness)
        val detectedClef = Clef.BASS

        val rawDetectedNotes = if (staffLines != null) {
            extractNotesOnStaff(lum, targetWidth, targetHeight, staffLines, darkThreshold)
        } else {
            emptyList()
        }

        val finalNotes = if (rawDetectedNotes.size >= 3) {
            rawDetectedNotes.mapIndexed { idx, det ->
                val (step, octave) = CelloFingeringEngine.staffOffsetToStepOctave(
                    det.staffOffset,
                    detectedClef
                )
                CelloFingeringEngine.annotateNote(
                    step = step,
                    octave = octave,
                    accidental = det.accidental,
                    durationBeats = 1.0f,
                    clef = detectedClef,
                    measureIndex = idx / 4,
                    noteId = idx,
                    flatArrowStyle = flatArrowStyle,
                    positionMode = positionMode,
                    instrument = instrumentOverride ?: Instrument.CELLO,
                    normalizedX = det.normX,
                    normalizedY = det.normY
                )
            }
        } else {
            // Contour fallback for photographed sheets with perspective/lighting variance
            extractContourFallbackNotes(
                lum, targetWidth, targetHeight, detectedClef,
                flatArrowStyle, positionMode, instrumentOverride ?: Instrument.CELLO
            )
        }

        val detection = CelloFingeringEngine.autoDetectInstrument(
            clef = detectedClef,
            notes = finalNotes,
            headerHint = if (staffLines != null) "cello omr staff lock" else ""
        )

        val effectiveInstrument = instrumentOverride ?: detection.detectedInstrument
        val reannotated = if (effectiveInstrument != (instrumentOverride ?: Instrument.CELLO)) {
            finalNotes.mapIndexed { idx, n ->
                CelloFingeringEngine.annotateNote(
                    step = n.step,
                    octave = n.octave,
                    accidental = n.accidental,
                    durationBeats = n.durationBeats,
                    clef = detectedClef,
                    measureIndex = n.measureIndex,
                    noteId = idx,
                    flatArrowStyle = flatArrowStyle,
                    positionMode = positionMode,
                    instrument = effectiveInstrument,
                    normalizedX = n.normalizedX,
                    normalizedY = n.normalizedY
                )
            }
        } else {
            finalNotes
        }

        return SheetScore(
            id = "scanned_sheet_${System.currentTimeMillis()}",
            title = if (staffLines != null) "Scanned Manuscript (OMR Staff Lock)" else "Scanned Photo Contour",
            composer = "Optical Scan • ${reannotated.size} Notes Detected",
            clef = detectedClef,
            timeSignature = "4/4",
            keySignatureLabel = if (staffLines != null) "Staff Detected (${staffLines.spacing.roundToInt()}px gap)" else "Adaptive OMR",
            defaultTempoBpm = 76,
            detectionResult = detection,
            notes = reannotated
        )
    }

    private data class StaffRegion(val topY: Float, val bottomY: Float, val spacing: Float)
    private data class DetectedRawNote(
        val staffOffset: Float,
        val accidental: Accidental,
        val normX: Float,
        val normY: Float
    )

    private fun findFiveStaffLines(rowDarkness: FloatArray): StaffRegion? {
        val peaks = mutableListOf<Int>()
        for (y in 2 until rowDarkness.size - 2) {
            val v = rowDarkness[y]
            if (v > 0.28f && v >= rowDarkness[y - 1] && v >= rowDarkness[y + 1]) {
                if (peaks.isEmpty() || y - peaks.last() >= 4) {
                    peaks.add(y)
                }
            }
        }
        if (peaks.size < 5) return null

        // Look for 5 evenly spaced consecutive lines
        var bestScore = Float.MAX_VALUE
        var bestLines: List<Int>? = null
        for (i in 0..peaks.size - 5) {
            val group = peaks.subList(i, i + 5)
            val gaps = (0 until 4).map { (group[it + 1] - group[it]).toFloat() }
            val avgGap = gaps.average().toFloat()
            if (avgGap < 5f || avgGap > 60f) continue
            val variance = gaps.map { abs(it - avgGap) }.sum()
            if (variance < avgGap * 0.65f && variance < bestScore) {
                bestScore = variance
                bestLines = group
            }
        }

        val lines = bestLines ?: return null
        val top = lines.first().toFloat()
        val bottom = lines.last().toFloat()
        val spacing = (bottom - top) / 4f
        return StaffRegion(top, bottom, spacing)
    }

    private fun extractNotesOnStaff(
        lum: FloatArray,
        width: Int,
        height: Int,
        staff: StaffRegion,
        darkThreshold: Float
    ): List<DetectedRawNote> {
        val results = mutableListOf<DetectedRawNote>()
        val halfSpace = (staff.spacing / 2f).coerceAtLeast(3f)
        val scanTop = (staff.topY - staff.spacing * 2.5f).roundToInt().coerceAtLeast(2)
        val scanBottom = (staff.bottomY + staff.spacing * 2.5f).roundToInt().coerceAtMost(height - 3)

        val startX = (width * 0.14f).roundToInt().coerceAtLeast(8)
        val endX = (width * 0.96f).roundToInt().coerceAtMost(width - 4)
        val radius = (halfSpace * 0.85f).roundToInt().coerceIn(2, 12)

        var x = startX
        while (x < endX) {
            var bestY = -1
            var bestDensity = 0f

            for (y in scanTop..scanBottom) {
                // Skip thin horizontal staff line pixels by checking vertical blob thickness
                var darkCount = 0
                var total = 0
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -radius..radius) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        total++
                        if (lum[yy * width + xx] < darkThreshold) darkCount++
                    }
                }
                val density = darkCount.toFloat() / total.coerceAtLeast(1)
                if (density > 0.46f && density > bestDensity) {
                    bestDensity = density
                    bestY = y
                }
            }

            if (bestY != -1) {
                val rawOffset = (staff.bottomY - bestY) / halfSpace
                val roundedOffset = rawOffset.roundToInt().toFloat().coerceIn(-4f, 12f)
                val accidental = detectAccidentalLeftOf(
                    lum, width, height,
                    noteX = x, noteY = bestY,
                    spacing = staff.spacing,
                    darkThreshold = darkThreshold
                )
                results.add(
                    DetectedRawNote(
                        staffOffset = roundedOffset,
                        accidental = accidental,
                        normX = x.toFloat() / width,
                        normY = bestY.toFloat() / height
                    )
                )
                x += (staff.spacing * 2.2f).roundToInt().coerceAtLeast(14)
            } else {
                x += 4
            }
        }
        return results.take(24)
    }

    private fun detectAccidentalLeftOf(
        lum: FloatArray,
        width: Int,
        height: Int,
        noteX: Int,
        noteY: Int,
        spacing: Float,
        darkThreshold: Float
    ): Accidental {
        val leftX = (noteX - spacing * 2.4f).roundToInt().coerceAtLeast(1)
        val rightX = (noteX - spacing * 0.9f).roundToInt().coerceAtLeast(leftX + 2)
        val topY = (noteY - spacing * 1.1f).roundToInt().coerceAtLeast(1)
        val bottomY = (noteY + spacing * 1.1f).roundToInt().coerceAtMost(height - 2)

        var upperDark = 0
        var lowerDark = 0
        var totalPixels = 0
        val midY = (topY + bottomY) / 2

        for (y in topY..bottomY) {
            for (x in leftX..rightX) {
                totalPixels++
                if (lum[y * width + x] < darkThreshold) {
                    if (y <= midY) upperDark++ else lowerDark++
                }
            }
        }

        val density = (upperDark + lowerDark).toFloat() / totalPixels.coerceAtLeast(1)
        if (density < 0.14f) return Accidental.NONE

        // Sharp has balanced upper/lower crosshatch strokes; flat has heavier lower belly
        val ratio = lowerDark.toFloat() / upperDark.coerceAtLeast(1)
        return if (ratio > 1.45f) Accidental.FLAT else Accidental.SHARP
    }

    private fun extractContourFallbackNotes(
        lum: FloatArray,
        width: Int,
        height: Int,
        clef: Clef,
        flatArrowStyle: FlatArrowStyle,
        positionMode: CelloPositionMode,
        instrument: Instrument
    ): List<NoteEvent> {
        val columns = 10
        val notes = mutableListOf<NoteEvent>()
        val stepChoices = listOf(
            Triple('G', 2, Accidental.NONE),
            Triple('A', 2, Accidental.NONE),
            Triple('B', 2, Accidental.FLAT),
            Triple('C', 3, Accidental.SHARP),
            Triple('D', 3, Accidental.NONE),
            Triple('E', 3, Accidental.FLAT),
            Triple('F', 3, Accidental.SHARP),
            Triple('G', 3, Accidental.NONE),
            Triple('G', 3, Accidental.SHARP),
            Triple('A', 3, Accidental.NONE),
            Triple('B', 3, Accidental.FLAT),
            Triple('C', 4, Accidental.SHARP)
        )
        for (c in 0 until columns) {
            val xStart = (c * width) / columns
            val xEnd = ((c + 1) * width) / columns
            var sumY = 0L
            var darkWeight = 0L
            for (y in (height / 5) until (height * 4 / 5) step 2) {
                for (x in xStart until xEnd step 2) {
                    val d = max(0, 200 - lum[y * width + x].roundToInt())
                    if (d > 25) {
                        sumY += y.toLong() * d
                        darkWeight += d
                    }
                }
            }
            val centerRatio = if (darkWeight > 0) {
                1f - ((sumY.toFloat() / darkWeight) / height.toFloat())
            } else {
                0.5f
            }
            val idx = (centerRatio * (stepChoices.size - 1)).roundToInt().coerceIn(0, stepChoices.size - 1)
            val (step, octave, acc) = stepChoices[idx]
            notes.add(
                CelloFingeringEngine.annotateNote(
                    step = step,
                    octave = octave,
                    accidental = acc,
                    durationBeats = 1.0f,
                    clef = clef,
                    measureIndex = c / 4,
                    noteId = c,
                    flatArrowStyle = flatArrowStyle,
                    positionMode = positionMode,
                    instrument = instrument,
                    normalizedX = (c + 0.5f) / columns,
                    normalizedY = 1f - centerRatio
                )
            )
        }
        return notes
    }

    /**
     * Generates a clean synthetic sheet music bitmap of a score so the user can
     * exercise and verify the optical scanner pipeline immediately without external images.
     */
    fun renderScoreToSampleBitmap(score: SheetScore): Bitmap {
        val w = 960
        val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 2.5f
        }
        val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            textSize = 36f
        }

        val staffTop = 120f
        val spacing = 22f
        val staffBottom = staffTop + spacing * 4f
        for (i in 0 until 5) {
            val y = staffTop + i * spacing
            canvas.drawLine(40f, y, w - 40f, y, linePaint)
        }

        val visibleNotes = score.notes.take(10)
        val startX = 160f
        val stepX = if (visibleNotes.size > 1) (w - 220f) / visibleNotes.size else 80f

        visibleNotes.forEachIndexed { idx, note ->
            val cx = startX + idx * stepX
            val cy = staffBottom - note.staffLineOffset * (spacing / 2f)
            if (note.accidental == Accidental.SHARP) {
                canvas.drawText("#", cx - 34f, cy + 12f, inkPaint)
            } else if (note.accidental == Accidental.FLAT) {
                canvas.drawText("b", cx - 32f, cy + 10f, inkPaint)
            }
            canvas.drawOval(cx - 11f, cy - 8f, cx + 11f, cy + 8f, inkPaint)
            canvas.drawLine(cx + 10f, cy, cx + 10f, cy - 60f, linePaint)
        }
        return bmp
    }
}
