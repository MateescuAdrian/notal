package com.notal.atelier

import com.notal.atelier.engine.CelloFingeringEngine
import com.notal.atelier.model.Accidental
import com.notal.atelier.model.CelloPositionMode
import com.notal.atelier.model.CelloString
import com.notal.atelier.model.Clef
import com.notal.atelier.model.FlatArrowStyle
import com.notal.atelier.model.Instrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CelloFingeringEngineTest {

    @Test
    fun sharpNotesAlwaysHaveUpArrowInFrontOfFingerNumber() {
        val sharpPitches = listOf(
            'C' to 2, 'F' to 2, 'G' to 2,
            'C' to 3, 'D' to 3, 'F' to 3, 'G' to 3,
            'C' to 4, 'D' to 4, 'F' to 4
        )
        for ((step, octave) in sharpPitches) {
            val annotated = CelloFingeringEngine.annotateNote(
                step = step,
                octave = octave,
                accidental = Accidental.SHARP,
                flatArrowStyle = FlatArrowStyle.DOWN_ARROW
            )
            assertEquals("Sharp note $step#$octave must have ↑ prefix", "↑", annotated.annotation.arrowPrefix)
            assertTrue(
                "Sharp formattedLabel (${annotated.annotation.formattedLabel}) must start with ↑ followed by finger 1..4",
                annotated.annotation.formattedLabel.matches(Regex("^↑[1-4]$"))
            )
        }
    }

    @Test
    fun flatNotesSupportDownArrowAndUpArrowInFrontOfFingerNumber() {
        val flatPitches = listOf('E' to 2, 'A' to 2, 'B' to 2, 'E' to 3, 'B' to 3, 'E' to 4)
        for ((step, octave) in flatPitches) {
            val downAnnotated = CelloFingeringEngine.annotateNote(
                step = step,
                octave = octave,
                accidental = Accidental.FLAT,
                flatArrowStyle = FlatArrowStyle.DOWN_ARROW
            )
            assertEquals("↓", downAnnotated.annotation.arrowPrefix)
            assertTrue(downAnnotated.annotation.formattedLabel.matches(Regex("^↓[1-4]$")))

            val upAnnotated = CelloFingeringEngine.annotateNote(
                step = step,
                octave = octave,
                accidental = Accidental.FLAT,
                flatArrowStyle = FlatArrowStyle.UP_ARROW
            )
            assertEquals("↑", upAnnotated.annotation.arrowPrefix)
            assertTrue(upAnnotated.annotation.formattedLabel.matches(Regex("^↑[1-4]$")))
        }
    }

    @Test
    fun naturalOpenStringsGetZeroWithoutArrow() {
        val c2 = CelloFingeringEngine.annotateNote('C', 2, Accidental.NONE)
        assertEquals("", c2.annotation.arrowPrefix)
        assertEquals("0", c2.annotation.formattedLabel)
        assertEquals(CelloString.C_IV, c2.annotation.celloString)

        val fSharp3 = CelloFingeringEngine.annotateNote('F', 3, Accidental.SHARP)
        assertEquals(CelloString.D_II, fSharp3.annotation.celloString)
        assertEquals("↑3", fSharp3.annotation.formattedLabel)
    }

    @Test
    fun autoDetectIdentifiesCelloFromBassClefAndTessitura() {
        val scores = CelloFingeringEngine.getSampleScores()
        val bach = scores.first()
        val detection = CelloFingeringEngine.autoDetectInstrument(Clef.BASS, bach.notes)
        assertEquals(Instrument.CELLO, detection.detectedInstrument)
        assertTrue(detection.confidencePercent >= 90)
    }

    @Test
    fun fourthPositionShiftsHigherNotesCorrectly() {
        val e4 = CelloFingeringEngine.annotateNote(
            step = 'E',
            octave = 4,
            accidental = Accidental.NONE,
            positionMode = CelloPositionMode.FOURTH
        )
        assertEquals(CelloString.A_I, e4.annotation.celloString)
        assertEquals("1", e4.annotation.formattedLabel)
        assertEquals("4th Pos", e4.annotation.positionLabel)
    }
}
