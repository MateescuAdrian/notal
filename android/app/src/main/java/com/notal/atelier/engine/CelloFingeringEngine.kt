package com.notal.atelier.engine

import com.notal.atelier.model.Accidental
import com.notal.atelier.model.CelloPositionMode
import com.notal.atelier.model.CelloString
import com.notal.atelier.model.Clef
import com.notal.atelier.model.FingerAnnotation
import com.notal.atelier.model.FlatArrowStyle
import com.notal.atelier.model.Instrument
import com.notal.atelier.model.InstrumentDetectionResult
import com.notal.atelier.model.NoteEvent
import com.notal.atelier.model.SheetScore
import kotlin.math.pow
import kotlin.math.roundToInt

object CelloFingeringEngine {

    private val DIATONIC_STEPS = charArrayOf('C', 'D', 'E', 'F', 'G', 'A', 'B')

    private val STEP_SEMITONES = mapOf(
        'C' to 0,
        'D' to 2,
        'E' to 4,
        'F' to 5,
        'G' to 7,
        'A' to 9,
        'B' to 11
    )

    private val STEP_INDEX = mapOf(
        'C' to 0,
        'D' to 1,
        'E' to 2,
        'F' to 3,
        'G' to 4,
        'A' to 5,
        'B' to 6
    )

    fun computeMidi(step: Char, octave: Int, accidental: Accidental): Int {
        val base = STEP_SEMITONES[step.uppercaseChar()] ?: 0
        return (octave + 1) * 12 + base + accidental.semitoneOffset
    }

    fun midiToFrequencyHz(midi: Int): Double {
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }

    fun computeStaffLineOffset(step: Char, octave: Int, clef: Clef): Float {
        val idx = STEP_INDEX[step.uppercaseChar()] ?: 0
        val diatonicIndex = octave * 7 + idx
        val bottomLineDiatonic = when (clef) {
            Clef.BASS -> 2 * 7 + 4   // G2 = 18
            Clef.TENOR -> 3 * 7 + 1  // D3 = 22
            Clef.TREBLE -> 4 * 7 + 2 // E4 = 30
        }
        return (diatonicIndex - bottomLineDiatonic).toFloat()
    }

    fun staffOffsetToStepOctave(offset: Float, clef: Clef): Pair<Char, Int> {
        val bottomLineDiatonic = when (clef) {
            Clef.BASS -> 18
            Clef.TENOR -> 22
            Clef.TREBLE -> 30
        }
        val diatonicIndex = bottomLineDiatonic + offset.roundToInt()
        val octave = Math.floorDiv(diatonicIndex, 7).coerceIn(1, 6)
        val stepIdx = Math.floorMod(diatonicIndex, 7)
        return DIATONIC_STEPS[stepIdx] to octave
    }

    fun annotateNote(
        step: Char,
        octave: Int,
        accidental: Accidental,
        durationBeats: Float = 1.0f,
        clef: Clef = Clef.BASS,
        measureIndex: Int = 0,
        noteId: Int = 0,
        flatArrowStyle: FlatArrowStyle = FlatArrowStyle.DOWN_ARROW,
        positionMode: CelloPositionMode = CelloPositionMode.AUTO,
        instrument: Instrument = Instrument.CELLO,
        normalizedX: Float = 0f,
        normalizedY: Float = 0f
    ): NoteEvent {
        val upperStep = step.uppercaseChar()
        val rawMidi = computeMidi(upperStep, octave, accidental)
        val midi = when (instrument) {
            Instrument.CELLO -> rawMidi.coerceIn(36, 81)
            Instrument.VIOLIN -> rawMidi.coerceIn(55, 96)
            Instrument.VIOLA -> rawMidi.coerceIn(48, 88)
            Instrument.DOUBLE_BASS -> rawMidi.coerceIn(28, 67)
        }

        val annotation = computeFingeringForMidi(
            midi = midi,
            accidental = accidental,
            flatArrowStyle = flatArrowStyle,
            positionMode = positionMode,
            instrument = instrument
        )

        val offset = computeStaffLineOffset(upperStep, octave, clef)
        return NoteEvent(
            id = noteId,
            step = upperStep,
            octave = octave,
            accidental = accidental,
            durationBeats = durationBeats,
            staffLineOffset = offset,
            measureIndex = measureIndex,
            annotation = annotation,
            normalizedX = normalizedX,
            normalizedY = normalizedY
        )
    }

    private fun computeFingeringForMidi(
        midi: Int,
        accidental: Accidental,
        flatArrowStyle: FlatArrowStyle,
        positionMode: CelloPositionMode,
        instrument: Instrument
    ): FingerAnnotation {
        // Choose string (using CelloString color/roman channels across string instruments)
        val stringChoice = selectString(midi, accidental, positionMode, instrument)
        val baseOpenMidi = when (instrument) {
            Instrument.CELLO -> stringChoice.openMidi
            Instrument.VIOLIN -> when (stringChoice) {
                CelloString.C_IV -> 55 // G3
                CelloString.G_III -> 62 // D4
                CelloString.D_II -> 69 // A4
                CelloString.A_I -> 76 // E5
            }
            Instrument.VIOLA -> when (stringChoice) {
                CelloString.C_IV -> 48 // C3
                CelloString.G_III -> 55 // G3
                CelloString.D_II -> 62 // D4
                CelloString.A_I -> 69 // A4
            }
            Instrument.DOUBLE_BASS -> when (stringChoice) {
                CelloString.C_IV -> 28 // E1
                CelloString.G_III -> 33 // A1
                CelloString.D_II -> 38 // D2
                CelloString.A_I -> 43 // G2
            }
        }

        val semitones = (midi - baseOpenMidi).coerceAtLeast(0)
        val (fingerNumber, posLabel) = mapSemitonesToFinger(
            semitones = semitones,
            accidental = accidental,
            positionMode = positionMode,
            instrument = instrument
        )

        val arrowPrefix = when (accidental) {
            Accidental.SHARP -> "↑"
            Accidental.FLAT -> flatArrowStyle.symbol
            Accidental.NONE, Accidental.NATURAL -> ""
        }

        val formattedLabel = "$arrowPrefix$fingerNumber"
        // Fingerboard visual stop ratio (12 semitones = octave halfway point; clamp nicely)
        val stopRatio = if (semitones == 0) {
            0f
        } else {
            (1.0 - 2.0.pow(-semitones / 12.0)).toFloat().coerceIn(0.05f, 0.78f) * 1.15f
        }.coerceIn(0f, 0.85f)

        val shiftDesc = when (accidental) {
            Accidental.SHARP -> "Sharp (♯): Little ↑ up-arrow in front of Finger $fingerNumber — raise / forward-extend ½ step on String ${stringChoice.roman} (${stringChoice.openNote})."
            Accidental.FLAT -> "Flat (♭): ${arrowPrefix} arrow in front of Finger $fingerNumber — lower / back-extend ½ step on String ${stringChoice.roman} (${stringChoice.openNote})."
            Accidental.NATURAL -> "Natural (♮): Play Finger $fingerNumber cleanly in $posLabel on String ${stringChoice.roman} (${stringChoice.openNote})."
            Accidental.NONE -> if (fingerNumber == 0) {
                "Open String 0 on ${stringChoice.roman} (${stringChoice.openNote}) — resonant unstopped pitch."
            } else {
                "$posLabel Finger $fingerNumber on String ${stringChoice.roman} (${stringChoice.openNote})."
            }
        }

        return FingerAnnotation(
            fingerNumber = fingerNumber,
            arrowPrefix = arrowPrefix,
            formattedLabel = formattedLabel,
            celloString = stringChoice,
            positionLabel = posLabel,
            stringStopRatio = stopRatio,
            shiftDescription = shiftDesc
        )
    }

    private fun selectString(
        midi: Int,
        accidental: Accidental,
        positionMode: CelloPositionMode,
        instrument: Instrument
    ): CelloString {
        if (instrument != Instrument.CELLO) {
            val openMap = when (instrument) {
                Instrument.VIOLIN -> listOf(CelloString.C_IV to 55, CelloString.G_III to 62, CelloString.D_II to 69, CelloString.A_I to 76)
                Instrument.VIOLA -> listOf(CelloString.C_IV to 48, CelloString.G_III to 55, CelloString.D_II to 62, CelloString.A_I to 69)
                Instrument.DOUBLE_BASS -> listOf(CelloString.C_IV to 28, CelloString.G_III to 33, CelloString.D_II to 38, CelloString.A_I to 43)
                else -> emptyList()
            }
            var chosen = openMap.first().first
            for ((str, open) in openMap) {
                if (midi >= open) {
                    if (midi == open && accidental == Accidental.SHARP && str != CelloString.C_IV) {
                        // Keep on lower string if sharp of open string isn't bottom string? Actually allow finger 1 or lower string
                        continue
                    }
                    chosen = str
                }
            }
            return chosen
        }

        // Cello standard open string MIDIs: C2=36, G2=43, D3=50, A3=57
        if (positionMode == CelloPositionMode.FOURTH) {
            return when {
                midi < 43 -> CelloString.C_IV
                midi < 50 -> CelloString.G_III
                midi < 57 -> CelloString.D_II
                else -> CelloString.A_I
            }
        }

        // Avoid open string 0 when note has a sharp/flat accidental if a lower string can stop it cleanly (except C2)
        return when {
            midi < 43 -> CelloString.C_IV
            midi == 43 && accidental != Accidental.NONE -> CelloString.C_IV
            midi < 50 -> CelloString.G_III
            midi == 50 && accidental != Accidental.NONE -> CelloString.G_III
            midi < 57 -> CelloString.D_II
            midi == 57 && accidental != Accidental.NONE -> CelloString.D_II
            else -> CelloString.A_I
        }
    }

    private fun mapSemitonesToFinger(
        semitones: Int,
        accidental: Accidental,
        positionMode: CelloPositionMode,
        instrument: Instrument
    ): Pair<Int, String> {
        if (instrument == Instrument.CELLO) {
            if (positionMode == CelloPositionMode.FOURTH && semitones >= 5) {
                return when (semitones) {
                    5 -> 1 to "4th Pos"
                    6 -> 1 to "4th Pos"
                    7 -> 1 to "4th Pos"
                    8 -> 2 to "4th Pos"
                    9 -> 3 to "4th Pos"
                    10 -> 4 to "4th Pos"
                    11 -> 4 to "4th Pos Ext"
                    12 -> 3 to "Thumb/5th"
                    else -> (((semitones - 1) % 4) + 1) to "Upper Pos"
                }
            }
            return when (semitones) {
                0 -> if (accidental == Accidental.SHARP || accidental == Accidental.FLAT) {
                    1 to "Half Pos"
                } else {
                    0 to "Open"
                }
                1 -> 1 to "Half Pos"
                2 -> 1 to "1st Pos"
                3 -> if (positionMode == CelloPositionMode.EXTENDED_FIRST) 2 to "1st Pos Ext" else 2 to "1st Pos"
                4 -> 3 to "1st Pos"
                5 -> 4 to "1st Pos"
                6 -> 4 to "1st Pos Ext"
                7 -> 1 to "4th Pos"
                8 -> 2 to "4th Pos"
                9 -> 3 to "4th Pos"
                10 -> 4 to "4th Pos"
                11 -> 4 to "5th Pos"
                12 -> 3 to "Harmonic / 5th"
                else -> (((semitones - 1) % 4) + 1) to "Upper Pos"
            }
        }

        // Violin / Viola / Double Bass generic finger mapping
        return when (semitones) {
            0 -> if (accidental == Accidental.SHARP || accidental == Accidental.FLAT) 1 to "Half Pos" else 0 to "Open"
            1, 2 -> 1 to "1st Pos"
            3, 4 -> 2 to "1st Pos"
            5, 6 -> 3 to "1st Pos"
            7 -> 4 to "1st Pos"
            else -> (((semitones - 1) % 4) + 1) to "Upper Pos"
        }
    }

    fun autoDetectInstrument(
        clef: Clef,
        notes: List<NoteEvent>,
        headerHint: String = ""
    ): InstrumentDetectionResult {
        val hint = headerHint.lowercase()
        if (hint.contains("cello") || hint.contains("violoncello") || hint.contains("bwv 1007") || hint.contains("cygne")) {
            return InstrumentDetectionResult(
                detectedInstrument = Instrument.CELLO,
                confidencePercent = 98,
                detectedClef = clef,
                reasoning = "Matched ${clef.label} (${clef.symbol}) + C2–A4 tessitura & cello repertoire signature."
            )
        }
        if (hint.contains("violin") || hint.contains("violino")) {
            return InstrumentDetectionResult(
                detectedInstrument = Instrument.VIOLIN,
                confidencePercent = 95,
                detectedClef = Clef.TREBLE,
                reasoning = "Matched Treble Clef (𝄞) + violin header & upper tessitura."
            )
        }
        if (hint.contains("viola")) {
            return InstrumentDetectionResult(
                detectedInstrument = Instrument.VIOLA,
                confidencePercent = 94,
                detectedClef = Clef.TENOR,
                reasoning = "Matched C-clef & middle-register tessitura."
            )
        }
        if (hint.contains("bass") || hint.contains("contrabbasso")) {
            return InstrumentDetectionResult(
                detectedInstrument = Instrument.DOUBLE_BASS,
                confidencePercent = 92,
                detectedClef = Clef.BASS,
                reasoning = "Matched low bass register below C2."
            )
        }

        if (notes.isEmpty()) {
            val inst = when (clef) {
                Clef.BASS, Clef.TENOR -> Instrument.CELLO
                Clef.TREBLE -> Instrument.VIOLIN
            }
            return InstrumentDetectionResult(
                detectedInstrument = inst,
                confidencePercent = 90,
                detectedClef = clef,
                reasoning = "Inferred from ${clef.label} (${clef.symbol})."
            )
        }

        val midis = notes.map { computeMidi(it.step, it.octave, it.accidental) }
        val minMidi = midis.minOrNull() ?: 43
        val avgMidi = midis.average()

        return when {
            clef == Clef.BASS && minMidi < 35 -> InstrumentDetectionResult(
                Instrument.DOUBLE_BASS, 91, clef,
                "Bass clef with sub-C2 notes (min MIDI $minMidi) indicates Double Bass."
            )
            clef == Clef.BASS || clef == Clef.TENOR -> InstrumentDetectionResult(
                Instrument.CELLO, 97, clef,
                "Detected ${clef.label} (${clef.symbol}) with average pitch MIDI ${avgMidi.roundToInt()} inside Cello range (C2–A5)."
            )
            avgMidi < 64.0 -> InstrumentDetectionResult(
                Instrument.VIOLA, 88, clef,
                "Mid-register contour (avg MIDI ${avgMidi.roundToInt()}) suits Viola / Tenor Cello."
            )
            else -> InstrumentDetectionResult(
                Instrument.VIOLIN, 93, clef,
                "Treble Clef (𝄞) with high tessitura (avg MIDI ${avgMidi.roundToInt()}) indicates Violin."
            )
        }
    }

    fun reannotateScore(
        score: SheetScore,
        flatArrowStyle: FlatArrowStyle,
        positionMode: CelloPositionMode,
        instrument: Instrument
    ): SheetScore {
        val updatedNotes = score.notes.mapIndexed { idx, n ->
            annotateNote(
                step = n.step,
                octave = n.octave,
                accidental = n.accidental,
                durationBeats = n.durationBeats,
                clef = score.clef,
                measureIndex = n.measureIndex,
                noteId = idx,
                flatArrowStyle = flatArrowStyle,
                positionMode = positionMode,
                instrument = instrument,
                normalizedX = n.normalizedX,
                normalizedY = n.normalizedY
            )
        }
        return score.copy(notes = updatedNotes)
    }

    private data class RawNote(
        val step: Char,
        val octave: Int,
        val acc: Accidental,
        val beats: Float
    )

    fun getSampleScores(
        flatArrowStyle: FlatArrowStyle = FlatArrowStyle.DOWN_ARROW,
        positionMode: CelloPositionMode = CelloPositionMode.AUTO,
        instrument: Instrument = Instrument.CELLO
    ): List<SheetScore> {
        return listOf(
            buildScore(
                id = "bach_prelude",
                title = "Cello Suite No. 1 — Prelude (BWV 1007)",
                composer = "J. S. Bach",
                clef = Clef.BASS,
                timeSignature = "4/4",
                keyLabel = "G Major (1♯)",
                tempo = 76,
                hint = "violoncello bwv 1007",
                rawNotes = listOf(
                    RawNote('G', 2, Accidental.NONE, 0.5f),
                    RawNote('D', 3, Accidental.NONE, 0.5f),
                    RawNote('B', 3, Accidental.NONE, 0.5f),
                    RawNote('A', 3, Accidental.NONE, 0.5f),
                    RawNote('B', 3, Accidental.NONE, 0.5f),
                    RawNote('D', 3, Accidental.NONE, 0.5f),
                    RawNote('B', 3, Accidental.NONE, 0.5f),
                    RawNote('D', 3, Accidental.NONE, 0.5f),

                    RawNote('G', 2, Accidental.NONE, 0.5f),
                    RawNote('E', 3, Accidental.NONE, 0.5f),
                    RawNote('C', 4, Accidental.NONE, 0.5f),
                    RawNote('B', 3, Accidental.NONE, 0.5f),
                    RawNote('C', 4, Accidental.NONE, 0.5f),
                    RawNote('E', 3, Accidental.NONE, 0.5f),
                    RawNote('F', 3, Accidental.SHARP, 0.5f),
                    RawNote('C', 3, Accidental.SHARP, 0.5f)
                ),
                flatArrowStyle = flatArrowStyle,
                positionMode = positionMode,
                instrument = instrument
            ),
            buildScore(
                id = "saint_saens_swan",
                title = "Le Cygne (The Swan) — Carnival of the Animals",
                composer = "C. Saint-Saëns",
                clef = Clef.BASS,
                timeSignature = "6/4",
                keyLabel = "G Major / Chromatic ♯ & ♭",
                tempo = 64,
                hint = "cello cygne",
                rawNotes = listOf(
                    RawNote('G', 3, Accidental.NONE, 1.5f),
                    RawNote('F', 3, Accidental.SHARP, 0.75f),
                    RawNote('B', 2, Accidental.NONE, 0.75f),
                    RawNote('E', 3, Accidental.NONE, 1.0f),
                    RawNote('D', 3, Accidental.NONE, 1.0f),
                    RawNote('B', 2, Accidental.FLAT, 1.0f),
                    RawNote('G', 2, Accidental.NONE, 1.5f),
                    RawNote('C', 3, Accidental.NONE, 0.75f),
                    RawNote('E', 3, Accidental.FLAT, 0.75f),
                    RawNote('G', 3, Accidental.NONE, 1.0f),
                    RawNote('C', 4, Accidental.SHARP, 1.0f),
                    RawNote('D', 4, Accidental.NONE, 1.5f)
                ),
                flatArrowStyle = flatArrowStyle,
                positionMode = positionMode,
                instrument = instrument
            ),
            buildScore(
                id = "dvorak_chromatic",
                title = "Chromatic Cello Study — ♯ (↑) & ♭ (↓/↑) Showcase",
                composer = "A. Dvořák / Notal Atelier",
                clef = Clef.BASS,
                timeSignature = "4/4",
                keyLabel = "Chromatic C–A",
                tempo = 72,
                hint = "violoncello chromatic study",
                rawNotes = listOf(
                    RawNote('C', 2, Accidental.NONE, 0.75f),
                    RawNote('C', 2, Accidental.SHARP, 0.75f),
                    RawNote('E', 2, Accidental.FLAT, 0.75f),
                    RawNote('F', 2, Accidental.SHARP, 0.75f),
                    RawNote('A', 2, Accidental.FLAT, 0.75f),
                    RawNote('G', 2, Accidental.SHARP, 0.75f),
                    RawNote('B', 2, Accidental.FLAT, 0.75f),
                    RawNote('C', 3, Accidental.SHARP, 0.75f),
                    RawNote('E', 3, Accidental.FLAT, 0.75f),
                    RawNote('F', 3, Accidental.SHARP, 0.75f),
                    RawNote('G', 3, Accidental.SHARP, 0.75f),
                    RawNote('B', 3, Accidental.FLAT, 0.75f),
                    RawNote('C', 4, Accidental.SHARP, 1.0f),
                    RawNote('E', 4, Accidental.FLAT, 1.0f)
                ),
                flatArrowStyle = flatArrowStyle,
                positionMode = positionMode,
                instrument = instrument
            ),
            buildScore(
                id = "elgar_concerto",
                title = "Cello Concerto in E Minor, Op. 85 (Opening)",
                composer = "E. Elgar",
                clef = Clef.BASS,
                timeSignature = "4/4",
                keyLabel = "E Minor",
                tempo = 60,
                hint = "violoncello concerto elgar",
                rawNotes = listOf(
                    RawNote('E', 2, Accidental.NONE, 1.0f),
                    RawNote('G', 2, Accidental.NONE, 1.0f),
                    RawNote('B', 2, Accidental.NONE, 1.0f),
                    RawNote('E', 3, Accidental.NONE, 1.5f),
                    RawNote('D', 3, Accidental.SHARP, 0.75f),
                    RawNote('C', 3, Accidental.NONE, 0.75f),
                    RawNote('B', 2, Accidental.FLAT, 1.0f),
                    RawNote('A', 2, Accidental.NONE, 1.0f),
                    RawNote('F', 2, Accidental.SHARP, 1.0f),
                    RawNote('G', 2, Accidental.NONE, 1.0f),
                    RawNote('B', 2, Accidental.NONE, 1.0f),
                    RawNote('E', 2, Accidental.NONE, 2.0f)
                ),
                flatArrowStyle = flatArrowStyle,
                positionMode = positionMode,
                instrument = instrument
            )
        )
    }

    private fun buildScore(
        id: String,
        title: String,
        composer: String,
        clef: Clef,
        timeSignature: String,
        keyLabel: String,
        tempo: Int,
        hint: String,
        rawNotes: List<RawNote>,
        flatArrowStyle: FlatArrowStyle,
        positionMode: CelloPositionMode,
        instrument: Instrument
    ): SheetScore {
        var beatAcc = 0f
        val notes = rawNotes.mapIndexed { idx, raw ->
            val measure = (beatAcc / 4f).toInt()
            beatAcc += raw.beats
            annotateNote(
                step = raw.step,
                octave = raw.octave,
                accidental = raw.acc,
                durationBeats = raw.beats,
                clef = clef,
                measureIndex = measure,
                noteId = idx,
                flatArrowStyle = flatArrowStyle,
                positionMode = positionMode,
                instrument = instrument
            )
        }
        val detection = autoDetectInstrument(clef, notes, hint)
        return SheetScore(
            id = id,
            title = title,
            composer = composer,
            clef = clef,
            timeSignature = timeSignature,
            keySignatureLabel = keyLabel,
            defaultTempoBpm = tempo,
            detectionResult = detection,
            notes = notes
        )
    }
}
