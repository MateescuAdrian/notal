package com.notal.atelier.model

enum class Clef(val symbol: String, val label: String) {
    BASS("𝄢", "Bass Clef"),
    TENOR("𝄡", "Tenor Clef"),
    TREBLE("𝄞", "Treble Clef")
}

enum class Instrument(
    val displayName: String,
    val italianName: String,
    val defaultClef: Clef,
    val openStrings: List<String>,
    val rangeDescription: String
) {
    CELLO("Cello", "Violoncello", Clef.BASS, listOf("C2", "G2", "D3", "A3"), "C2 – A5"),
    VIOLIN("Violin", "Violino", Clef.TREBLE, listOf("G3", "D4", "A4", "E5"), "G3 – E7"),
    VIOLA("Viola", "Viola", Clef.TENOR, listOf("C3", "G3", "D4", "A4"), "C3 – E6"),
    DOUBLE_BASS("Double Bass", "Contrabbasso", Clef.BASS, listOf("E1", "A1", "D2", "G2"), "E1 – G4")
}

enum class Accidental(val symbol: String, val ascii: String, val semitoneOffset: Int) {
    NONE("", "", 0),
    SHARP("♯", "#", 1),
    FLAT("♭", "b", -1),
    NATURAL("♮", "n", 0)
}

enum class FlatArrowStyle(val symbol: String, val label: String) {
    DOWN_ARROW("↓", "↓ for ♭ (Lowered Extension)"),
    UP_ARROW("↑", "↑ for both ♯ & ♭")
}

enum class CelloPositionMode(val label: String, val shortCode: String) {
    AUTO("Auto Optimal", "AUTO"),
    FIRST("1st Position", "I"),
    EXTENDED_FIRST("Extended 1st", "I+"),
    FOURTH("4th Position", "IV")
}

enum class CelloString(
    val roman: String,
    val openNote: String,
    val openMidi: Int,
    val colorHex: Int
) {
    C_IV("IV", "C2", 36, 0xFF5B6EE1.toInt()),
    G_III("III", "G2", 43, 0xFF2E9F72.toInt()),
    D_II("II", "D3", 50, 0xFFD64545.toInt()),
    A_I("I", "A3", 57, 0xFFE5A93C.toInt())
}

data class FingerAnnotation(
    val fingerNumber: Int,
    val arrowPrefix: String,
    val formattedLabel: String,
    val celloString: CelloString,
    val positionLabel: String,
    val stringStopRatio: Float,
    val shiftDescription: String
)

data class NoteEvent(
    val id: Int,
    val step: Char,
    val octave: Int,
    val accidental: Accidental,
    val durationBeats: Float,
    val staffLineOffset: Float,
    val measureIndex: Int,
    val annotation: FingerAnnotation,
    val normalizedX: Float = 0f,
    val normalizedY: Float = 0f
) {
    val pitchLabel: String
        get() = "$step${accidental.symbol}$octave"
}

data class InstrumentDetectionResult(
    val detectedInstrument: Instrument,
    val confidencePercent: Int,
    val detectedClef: Clef,
    val reasoning: String
)

data class SheetScore(
    val id: String,
    val title: String,
    val composer: String,
    val clef: Clef,
    val timeSignature: String,
    val keySignatureLabel: String,
    val defaultTempoBpm: Int,
    val detectionResult: InstrumentDetectionResult,
    val notes: List<NoteEvent>
)
