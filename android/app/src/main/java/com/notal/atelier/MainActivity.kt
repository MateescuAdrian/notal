package com.notal.atelier

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.notal.atelier.audio.CelloAudioSynthesizer
import com.notal.atelier.engine.CelloFingeringEngine
import com.notal.atelier.engine.SheetMusicScannerEngine
import com.notal.atelier.model.Accidental
import com.notal.atelier.model.CelloPositionMode
import com.notal.atelier.model.FlatArrowStyle
import com.notal.atelier.model.Instrument
import com.notal.atelier.model.NoteEvent
import com.notal.atelier.model.SheetScore
import com.notal.atelier.ui.CelloFingerboardView
import com.notal.atelier.ui.SheetScoreCanvasView

class MainActivity : AppCompatActivity() {

    private val synthesizer = CelloAudioSynthesizer()

    private var flatArrowStyle = FlatArrowStyle.DOWN_ARROW
    private var positionMode = CelloPositionMode.AUTO
    private var selectedInstrument = Instrument.CELLO
    private var autoDetectLocked = true
    private var currentPresetIndex = 0
    private var loopEnabled = false
    private var tempoBpm = 76
    private var addNoteAccidental = Accidental.SHARP

    private lateinit var currentScore: SheetScore

    // Views
    private lateinit var scoreCanvasView: SheetScoreCanvasView
    private lateinit var fingerboardView: CelloFingerboardView
    private lateinit var detectionBannerText: TextView
    private lateinit var detectionReasonText: TextView
    private lateinit var inspectorBadgeText: TextView
    private lateinit var inspectorTitleText: TextView
    private lateinit var inspectorDetailText: TextView
    private lateinit var playButton: MaterialButton
    private lateinit var loopButton: MaterialButton
    private lateinit var tempoValueText: TextView
    private lateinit var playbackProgressText: TextView
    private lateinit var arrowRuleBadgeText: TextView
    private lateinit var addAccChipGroup: ChipGroup
    private lateinit var instrumentChipGroup: ChipGroup

    private val cameraPreviewLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) {
                runOpticalScanOnBitmap(bitmap, "Camera Capture")
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cameraPreviewLauncher.launch(null)
            } else {
                Toast.makeText(
                    this,
                    "Camera permission denied — try Import Sheet Image or Scan Demo Sheet.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val galleryImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                decodeUriAndScan(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val samples = CelloFingeringEngine.getSampleScores(flatArrowStyle, positionMode, selectedInstrument)
        currentScore = samples[0]
        tempoBpm = currentScore.defaultTempoBpm

        setContentView(buildRootLayout())
        bindScoreToUi(triggerScan = false)
    }

    override fun onDestroy() {
        synthesizer.stop()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun cardBackground(fillColor: Int = 0xFF1B1511.toInt(), strokeColor: Int = 0xFF34281E.toInt()): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), strokeColor)
        }
    }

    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F0C0A.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(18))
        }

        content.addView(buildAtelierHeaderCard())
        content.addView(spacer(10))
        content.addView(buildInstrumentDetectCard())
        content.addView(spacer(10))
        content.addView(buildScoreControlsCard())
        content.addView(spacer(10))

        // Main Interactive Optical Music Sheet View
        scoreCanvasView = SheetScoreCanvasView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            onNoteTapped = { idx, note ->
                handleScoreNoteTapped(idx, note)
            }
            onStaffTappedToAdd = { step, octave, insertIdx ->
                handleAddNoteAtPitch(step, octave, addNoteAccidental, insertIdx)
            }
        }
        content.addView(scoreCanvasView)
        content.addView(spacer(10))

        content.addView(buildInspectorAndFingerboardCard())

        scrollView.addView(content)
        root.addView(scrollView)
        root.addView(buildTransportDock())
        return root
    }

    private fun buildAtelierHeaderCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(0xFF1A1410.toInt(), 0xFF3E2E20.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "NOTAL  •  CELLO SCORE ATELIER"
            textSize = 11.5f
            setTextColor(0xFFE5A93C.toInt())
            letterSpacing = 0.12f
            setTypeface(null, Typeface.BOLD)
        })
        titleCol.addView(TextView(this).apply {
            text = "Optical Sheet Scanner & Fingering Engraver"
            textSize = 17.5f
            setTextColor(0xFFF6EFE2.toInt())
            setTypeface(Typeface.SERIF, Typeface.BOLD)
        })

        val themeBtn = makeCompactButton("Parchment", 0xFF2B221A.toInt(), 0xFFE5A93C.toInt()).apply {
            setOnClickListener {
                scoreCanvasView.parchmentTheme = !scoreCanvasView.parchmentTheme
                text = if (scoreCanvasView.parchmentTheme) "Parchment" else "Midnight"
            }
        }
        titleRow.addView(titleCol)
        titleRow.addView(themeBtn)
        card.addView(titleRow)

        card.addView(spacer(10))

        // Scanner Action Buttons Row
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val camBtn = makeCompactButton("Camera Scan", 0xFFE5A93C.toInt(), 0xFF120E0B.toInt()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { launchCameraScanner() }
        }
        val imgBtn = makeCompactButton("Import Sheet", 0xFF2B221A.toInt(), 0xFFF6EFE2.toInt()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { galleryImageLauncher.launch("image/*") }
        }
        val demoScanBtn = makeCompactButton("Optical Scan", 0xFF2D3A2E.toInt(), 0xFFA7F3D0.toInt()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val rendered = SheetMusicScannerEngine.renderScoreToSampleBitmap(currentScore)
                runOpticalScanOnBitmap(rendered, "Engraved Sheet OMR")
            }
        }

        actionRow.addView(camBtn)
        actionRow.addView(imgBtn)
        actionRow.addView(demoScanBtn)
        card.addView(actionRow)

        return card
    }

    private fun buildInstrumentDetectCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        detectionBannerText = TextView(this).apply {
            textSize = 13.5f
            setTextColor(0xFFF6EFE2.toInt())
            setTypeface(null, Typeface.BOLD)
        }
        detectionReasonText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(0xFFB7A894.toInt())
        }
        textCol.addView(detectionBannerText)
        textCol.addView(detectionReasonText)

        val autoBtn = makeCompactButton("Auto-Detect", 0xFF2A2118.toInt(), 0xFFE5A93C.toInt()).apply {
            setOnClickListener {
                autoDetectLocked = true
                val res = CelloFingeringEngine.autoDetectInstrument(currentScore.clef, currentScore.notes)
                selectedInstrument = res.detectedInstrument
                syncInstrumentChips()
                currentScore = CelloFingeringEngine.reannotateScore(
                    currentScore.copy(detectionResult = res),
                    flatArrowStyle,
                    positionMode,
                    selectedInstrument
                )
                bindScoreToUi(triggerScan = true)
            }
        }
        topRow.addView(textCol)
        topRow.addView(autoBtn)
        card.addView(topRow)

        card.addView(spacer(6))

        // Instrument Chips
        val hScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        instrumentChipGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        Instrument.entries.forEach { inst ->
            val chip = createStyledChip(inst.displayName).apply {
                id = View.generateViewId()
                tag = inst
                isChecked = inst == selectedInstrument
                setOnClickListener {
                    autoDetectLocked = false
                    selectedInstrument = inst
                    currentScore = CelloFingeringEngine.reannotateScore(
                        currentScore,
                        flatArrowStyle,
                        positionMode,
                        selectedInstrument
                    )
                    bindScoreToUi(triggerScan = false)
                }
            }
            instrumentChipGroup.addView(chip)
        }
        hScroll.addView(instrumentChipGroup)
        card.addView(hScroll)

        return card
    }

    private fun buildScoreControlsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // Preset Scores row
        card.addView(sectionLabel("REPERTOIRE & CHROMATIC STUDIES"))
        val presetScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val presetGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val presetTitles = listOf("Bach BWV 1007", "Saint-Saëns Swan", "Dvořák ♯ & ♭ Study", "Elgar Concerto")
        presetTitles.forEachIndexed { idx, label ->
            val chip = createStyledChip(label).apply {
                id = View.generateViewId()
                isChecked = idx == 0
                setOnClickListener {
                    synthesizer.stop()
                    currentPresetIndex = idx
                    val samples = CelloFingeringEngine.getSampleScores(
                        flatArrowStyle, positionMode, selectedInstrument
                    )
                    currentScore = samples[idx]
                    tempoBpm = currentScore.defaultTempoBpm
                    if (autoDetectLocked) {
                        selectedInstrument = currentScore.detectionResult.detectedInstrument
                        syncInstrumentChips()
                    }
                    scoreCanvasView.scannedSourceBitmap = null
                    scoreCanvasView.showScanBackdrop = false
                    scoreCanvasView.selectedNoteIndex = 0
                    bindScoreToUi(triggerScan = true)
                }
            }
            presetGroup.addView(chip)
        }
        presetScroll.addView(presetGroup)
        card.addView(presetScroll)

        card.addView(spacer(8))

        // Arrow Rule + Position Row
        val ruleHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ruleHeaderRow.addView(sectionLabel("ARROW NOTATION & POSITION").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        arrowRuleBadgeText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(0xFFE5A93C.toInt())
            setTypeface(null, Typeface.BOLD)
        }
        ruleHeaderRow.addView(arrowRuleBadgeText)
        card.addView(ruleHeaderRow)

        val ruleScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val ruleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val flatStyleBtn = makeCompactButton("♭ Arrow: ↓ (Toggle ↑)", 0xFF261F19.toInt(), 0xFF67E8F9.toInt()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener {
                flatArrowStyle = if (flatArrowStyle == FlatArrowStyle.DOWN_ARROW) {
                    FlatArrowStyle.UP_ARROW
                } else {
                    FlatArrowStyle.DOWN_ARROW
                }
                text = if (flatArrowStyle == FlatArrowStyle.DOWN_ARROW) {
                    "♭ Arrow: ↓ (Tap for ↑)"
                } else {
                    "♭ Arrow: ↑ (Tap for ↓)"
                }
                reannotateCurrentScore()
            }
        }
        ruleRow.addView(flatStyleBtn)

        val posGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        CelloPositionMode.entries.forEach { pos ->
            val chip = createStyledChip(pos.label).apply {
                id = View.generateViewId()
                isChecked = pos == positionMode
                setOnClickListener {
                    positionMode = pos
                    reannotateCurrentScore()
                }
            }
            posGroup.addView(chip)
        }
        ruleRow.addView(posGroup)
        ruleScroll.addView(ruleRow)
        card.addView(ruleScroll)

        card.addView(spacer(8))

        // Interactive Staff Editing & Quick Accidental Injector
        card.addView(sectionLabel("INTERACTIVE SCORE EDITOR (WRITE FINGERS & ARROWS)"))
        val editScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val editRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val modeGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val modeOptions = listOf(
            SheetScoreCanvasView.TouchMode.AUDITION to "Play Note",
            SheetScoreCanvasView.TouchMode.CYCLE_ACCIDENTAL to "Tap Note: ♮/♯/♭",
            SheetScoreCanvasView.TouchMode.ADD_NOTE to "Tap Staff: +Note"
        )
        modeOptions.forEachIndexed { idx, (mode, title) ->
            modeGroup.addView(createStyledChip(title).apply {
                id = View.generateViewId()
                isChecked = idx == 0
                setOnClickListener {
                    scoreCanvasView.touchMode = mode
                }
            })
        }
        editRow.addView(modeGroup)

        addAccChipGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(dp(8), 0, 0, 0)
        }
        listOf(
            Accidental.SHARP to "+♯ (↑)",
            Accidental.FLAT to "+♭ (↓/↑)",
            Accidental.NONE to "+♮"
        ).forEachIndexed { idx, (acc, label) ->
            addAccChipGroup.addView(createStyledChip(label).apply {
                id = View.generateViewId()
                isChecked = idx == 0
                setOnClickListener { addNoteAccidental = acc }
            })
        }
        editRow.addView(addAccChipGroup)

        editScroll.addView(editRow)
        card.addView(editScroll)

        return card
    }

    private fun buildInspectorAndFingerboardCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        inspectorBadgeText = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF140F0C.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFFE5A93C.toInt())
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(46)).apply {
                marginEnd = dp(12)
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inspectorTitleText = TextView(this).apply {
            textSize = 14.5f
            setTextColor(0xFFF6EFE2.toInt())
            setTypeface(null, Typeface.BOLD)
        }
        inspectorDetailText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFCBBBA4.toInt())
        }
        textCol.addView(inspectorTitleText)
        textCol.addView(inspectorDetailText)

        infoRow.addView(inspectorBadgeText)
        infoRow.addView(textCol)
        card.addView(infoRow)

        card.addView(spacer(10))

        card.addView(sectionLabel("INTERACTIVE CELLO FINGERBOARD (TAP STRING TO AUDITION)"))
        fingerboardView = CelloFingerboardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            onFingerboardPitchTapped = { step, octave, acc ->
                val previewNote = CelloFingeringEngine.annotateNote(
                    step = step,
                    octave = octave,
                    accidental = acc,
                    clef = currentScore.clef,
                    flatArrowStyle = flatArrowStyle,
                    positionMode = positionMode,
                    instrument = selectedInstrument
                )
                updateInspector(previewNote)
                fingerboardView.activeNote = previewNote
                synthesizer.playNotePreview(previewNote, selectedInstrument)
            }
        }
        card.addView(fingerboardView)

        return card
    }

    private fun buildTransportDock(): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF17120E.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }

        val divider = View(this).apply {
            setBackgroundColor(0xFF3A2D22.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
        dock.addView(divider)
        dock.addView(spacer(8))

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        playButton = makeCompactButton("▶  Play Cello", 0xFFE5A93C.toInt(), 0xFF120E0B.toInt()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { togglePlayback() }
        }

        val stopButton = makeCompactButton("■ Stop", 0xFF2B221A.toInt(), 0xFFF6EFE2.toInt()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                synthesizer.stop()
                scoreCanvasView.playingNoteIndex = -1
                playButton.text = "▶  Play Cello"
                playbackProgressText.text = "Stopped"
            }
        }

        loopButton = makeCompactButton("Loop: Off", 0xFF2B221A.toInt(), 0xFFB7A894.toInt()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
            setOnClickListener {
                loopEnabled = !loopEnabled
                text = if (loopEnabled) "Loop: On" else "Loop: Off"
                setTextColor(if (loopEnabled) 0xFFE5A93C.toInt() else 0xFFB7A894.toInt())
            }
        }

        topRow.addView(playButton)
        topRow.addView(stopButton)
        topRow.addView(loopButton)
        dock.addView(topRow)

        val tempoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        tempoValueText = TextView(this).apply {
            text = "Tempo: $tempoBpm BPM"
            textSize = 11.5f
            setTextColor(0xFFF6EFE2.toInt())
            setTypeface(null, Typeface.BOLD)
        }

        val slider = Slider(this).apply {
            valueFrom = 44f
            valueTo = 160f
            stepSize = 2f
            value = tempoBpm.toFloat().coerceIn(44f, 160f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            thumbTintList = ColorStateList.valueOf(0xFFE5A93C.toInt())
            trackActiveTintList = ColorStateList.valueOf(0xFFE5A93C.toInt())
            trackInactiveTintList = ColorStateList.valueOf(0xFF3A2D22.toInt())
            addOnChangeListener { _, v, _ ->
                tempoBpm = v.toInt()
                tempoValueText.text = "Tempo: $tempoBpm BPM"
            }
        }

        playbackProgressText = TextView(this).apply {
            text = "Ready"
            textSize = 11.5f
            setTextColor(0xFFE5A93C.toInt())
        }

        tempoRow.addView(tempoValueText)
        tempoRow.addView(slider)
        tempoRow.addView(playbackProgressText)
        dock.addView(tempoRow)

        return dock
    }

    private fun togglePlayback() {
        if (synthesizer.isCurrentlyPlaying()) {
            synthesizer.stop()
            scoreCanvasView.playingNoteIndex = -1
            playButton.text = "▶  Play Cello"
            playbackProgressText.text = "Paused"
            return
        }

        playButton.text = "❚❚ Pause"
        synthesizer.playScore(
            score = currentScore,
            tempoBpm = tempoBpm,
            instrument = selectedInstrument,
            loop = loopEnabled,
            onNoteActive = { idx, note ->
                scoreCanvasView.playingNoteIndex = idx
                if (idx >= 0 && note != null) {
                    scoreCanvasView.selectedNoteIndex = idx
                    updateInspector(note)
                    fingerboardView.activeNote = note
                    playbackProgressText.text = "${idx + 1}/${currentScore.notes.size} (${note.annotation.formattedLabel})"
                }
            },
            onPlaybackComplete = {
                playButton.text = "▶  Play Cello"
                playbackProgressText.text = "Finished"
            }
        )
    }

    private fun handleScoreNoteTapped(index: Int, note: NoteEvent) {
        if (scoreCanvasView.touchMode == SheetScoreCanvasView.TouchMode.CYCLE_ACCIDENTAL) {
            val nextAcc = when (note.accidental) {
                Accidental.NONE, Accidental.NATURAL -> Accidental.SHARP
                Accidental.SHARP -> Accidental.FLAT
                Accidental.FLAT -> Accidental.NONE
            }
            val updated = CelloFingeringEngine.annotateNote(
                step = note.step,
                octave = note.octave,
                accidental = nextAcc,
                durationBeats = note.durationBeats,
                clef = currentScore.clef,
                measureIndex = note.measureIndex,
                noteId = note.id,
                flatArrowStyle = flatArrowStyle,
                positionMode = positionMode,
                instrument = selectedInstrument
            )
            val newNotes = currentScore.notes.toMutableList()
            newNotes[index] = updated
            currentScore = currentScore.copy(notes = newNotes)
            bindScoreToUi(triggerScan = false)
            scoreCanvasView.selectedNoteIndex = index
            updateInspector(updated)
            fingerboardView.activeNote = updated
            synthesizer.playNotePreview(updated, selectedInstrument)
        } else {
            updateInspector(note)
            fingerboardView.activeNote = note
            synthesizer.playNotePreview(note, selectedInstrument)
        }
    }

    private fun handleAddNoteAtPitch(step: Char, octave: Int, acc: Accidental, insertIdx: Int) {
        val newNote = CelloFingeringEngine.annotateNote(
            step = step,
            octave = octave,
            accidental = acc,
            durationBeats = 1.0f,
            clef = currentScore.clef,
            measureIndex = insertIdx / 4,
            noteId = insertIdx,
            flatArrowStyle = flatArrowStyle,
            positionMode = positionMode,
            instrument = selectedInstrument
        )
        val updatedList = currentScore.notes.toMutableList()
        updatedList.add(newNote)
        currentScore = currentScore.copy(notes = updatedList)
        scoreCanvasView.selectedNoteIndex = updatedList.lastIndex
        bindScoreToUi(triggerScan = false)
        synthesizer.playNotePreview(newNote, selectedInstrument)
    }

    private fun launchCameraScanner() {
        val perm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (perm == PackageManager.PERMISSION_GRANTED) {
            cameraPreviewLauncher.launch(null)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun decodeUriAndScan(uri: Uri) {
        Thread {
            val bitmap = try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (bitmap != null) {
                    runOpticalScanOnBitmap(bitmap, "Imported Sheet")
                } else {
                    Toast.makeText(this, "Could not decode image.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun runOpticalScanOnBitmap(bitmap: Bitmap, sourceLabel: String) {
        synthesizer.stop()
        Thread {
            val scanned = SheetMusicScannerEngine.scanBitmap(
                bitmap = bitmap,
                flatArrowStyle = flatArrowStyle,
                positionMode = positionMode,
                instrumentOverride = if (autoDetectLocked) null else selectedInstrument
            ).copy(title = "$sourceLabel — Optical Cello Score")

            runOnUiThread {
                currentScore = scanned
                if (autoDetectLocked) {
                    selectedInstrument = scanned.detectionResult.detectedInstrument
                    syncInstrumentChips()
                }
                scoreCanvasView.scannedSourceBitmap = bitmap
                scoreCanvasView.showScanBackdrop = true
                scoreCanvasView.selectedNoteIndex = 0
                bindScoreToUi(triggerScan = true)
            }
        }.start()
    }

    private fun reannotateCurrentScore() {
        currentScore = CelloFingeringEngine.reannotateScore(
            currentScore,
            flatArrowStyle,
            positionMode,
            selectedInstrument
        )
        bindScoreToUi(triggerScan = false)
    }

    private fun bindScoreToUi(triggerScan: Boolean) {
        val det = currentScore.detectionResult
        detectionBannerText.text = if (autoDetectLocked) {
            "Auto-Detected: ${det.detectedInstrument.displayName} (${det.detectedInstrument.italianName}) • ${det.confidencePercent}%"
        } else {
            "Manual Instrument: ${selectedInstrument.displayName} (${selectedInstrument.rangeDescription})"
        }
        detectionReasonText.text = det.reasoning

        arrowRuleBadgeText.text = "♯ → ↑1..4   |   ♭ → ${flatArrowStyle.symbol}1..4"

        scoreCanvasView.activeInstrument = selectedInstrument
        scoreCanvasView.score = currentScore
        fingerboardView.activeInstrument = selectedInstrument
        fingerboardView.scoreNotes = currentScore.notes

        val selIdx = scoreCanvasView.selectedNoteIndex.coerceIn(0, (currentScore.notes.size - 1).coerceAtLeast(0))
        val active = currentScore.notes.getOrNull(selIdx)
        if (active != null) {
            updateInspector(active)
            fingerboardView.activeNote = active
        }
        playbackProgressText.text = "${currentScore.notes.size} notes ready"

        if (triggerScan) {
            scoreCanvasView.triggerScanAnimation()
        }
    }

    private fun updateInspector(note: NoteEvent) {
        inspectorBadgeText.text = note.annotation.formattedLabel
        (inspectorBadgeText.background as? GradientDrawable)?.setColor(note.annotation.celloString.colorHex)
        inspectorTitleText.text =
            "${note.pitchLabel}  •  String ${note.annotation.celloString.roman} (${note.annotation.celloString.openNote})  •  ${note.annotation.positionLabel}"
        inspectorDetailText.text = note.annotation.shiftDescription
    }

    private fun syncInstrumentChips() {
        for (i in 0 until instrumentChipGroup.childCount) {
            val child = instrumentChipGroup.getChildAt(i) as? Chip ?: continue
            child.isChecked = child.tag == selectedInstrument
        }
    }

    private fun makeCompactButton(label: String, bgColor: Int, textColor: Int): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(textColor)
            backgroundTintList = ColorStateList.valueOf(bgColor)
            cornerRadius = dp(12)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(38)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
    }

    private fun createStyledChip(label: String): Chip {
        return Chip(this).apply {
            text = label
            isCheckable = true
            textSize = 12f
            shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(dp(12).toFloat())
                .build()
            chipStrokeWidth = dp(1).toFloat()
            val bgStates = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFFE5A93C.toInt(), 0xFF261E18.toInt())
            )
            val txtStates = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF120E0B.toInt(), 0xFFE8DFD1.toInt())
            )
            val strokeStates = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFFF59E0B.toInt(), 0xFF423428.toInt())
            )
            chipBackgroundColor = bgStates
            setTextColor(txtStates)
            chipStrokeColor = strokeStates
            isCheckedIconVisible = false
        }
    }

    private fun sectionLabel(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 10.5f
            letterSpacing = 0.08f
            setTextColor(0xFFB7A894.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
    }

    private fun spacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
        }
    }
}
