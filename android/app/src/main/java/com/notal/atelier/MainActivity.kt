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
import android.text.TextUtils
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
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

    // Core 3-Zone Views
    private lateinit var scoreCanvasView: SheetScoreCanvasView
    private lateinit var fingerboardView: CelloFingerboardView
    private lateinit var instrumentStatusPill: TextView
    private lateinit var repertoirePill: TextView
    private lateinit var arrowRulePill: TextView
    private lateinit var positionPill: TextView
    private lateinit var touchModePill: TextView
    private lateinit var themePill: TextView
    private lateinit var inspectorBadgeText: TextView
    private lateinit var inspectorTitleText: TextView
    private lateinit var inspectorDetailText: TextView
    private lateinit var playButton: MaterialButton
    private lateinit var loopButton: MaterialButton
    private lateinit var tempoValueText: TextView

    private val presetLabels = listOf(
        "Bach BWV 1007",
        "Saint-Saëns Swan",
        "Dvořák ♯ & ♭ Study",
        "Elgar Concerto"
    )

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
                    "Camera permission denied — choose Sheet Image or Optical Demo Scan.",
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

        val samples = CelloFingeringEngine.getSampleScores(
            flatArrowStyle,
            positionMode,
            selectedInstrument
        )
        currentScore = samples[0]
        tempoBpm = currentScore.defaultTempoBpm

        val root = buildRootLayout()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

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

    private fun roundedBg(
        fillColor: Int = 0xFF1A1410.toInt(),
        strokeColor: Int = 0xFF31261C.toInt(),
        radiusDp: Int = 20
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
        }
    }

    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E0B09.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Zone 1: Sleek Top Bar + Single Quick-Control Strip
        root.addView(buildTopHeader())
        root.addView(buildQuickControlStrip())

        // Scrollable Stage (Zone 2: Score Canvas + Zone 3: Unified Inspector & Fingerboard)
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = false
        }

        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(18))
        }

        scoreCanvasView = SheetScoreCanvasView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            onNoteTapped = { idx, note -> handleScoreNoteTapped(idx, note) }
            onStaffTappedToAdd = { step, octave, insertIdx ->
                handleAddNoteAtPitch(step, octave, addNoteAccidental, insertIdx)
            }
        }
        stage.addView(scoreCanvasView)
        stage.addView(spacer(14))
        stage.addView(buildUnifiedFingerboardCard())

        scrollView.addView(stage)
        root.addView(scrollView)

        // Compact Single-Row Transport Bar
        root.addView(buildCompactTransportBar())
        return root
    }

    private fun buildTopHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        leftCol.addView(TextView(this).apply {
            text = "Notal"
            textSize = 22f
            setTextColor(0xFFF6EFE2.toInt())
            setTypeface(Typeface.SERIF, Typeface.BOLD)
        })

        instrumentStatusPill = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFE5A93C.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(2), dp(8), dp(2))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { showStudioOptionsSheet() }
        }
        leftCol.addView(instrumentStatusPill)

        val scanBtn = makeCompactButton("Scan", 0xFFE5A93C.toInt(), 0xFF120E0B.toInt()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { showScanOptionsSheet() }
        }

        val optionsBtn = makeCompactButton("Studio", 0xFF231B15.toInt(), 0xFFF6EFE2.toInt()).apply {
            strokeColor = ColorStateList.valueOf(0xFF3E3024.toInt())
            strokeWidth = dp(1)
            setOnClickListener { showStudioOptionsSheet() }
        }

        header.addView(leftCol)
        header.addView(scanBtn)
        header.addView(optionsBtn)
        return header
    }

    private fun buildQuickControlStrip(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(16), dp(2), dp(16), dp(8))
            clipToPadding = false
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        repertoirePill = makeStripPill("Bach BWV 1007 ▾", highlighted = true).apply {
            setOnClickListener { showRepertoireSheet() }
        }
        arrowRulePill = makeStripPill("♯ → ↑  •  ♭ → ↓").apply {
            setOnClickListener {
                toggleFlatArrowStyle()
            }
        }
        positionPill = makeStripPill("Pos: Auto").apply {
            setOnClickListener { cyclePositionMode() }
        }
        touchModePill = makeStripPill("Tap: Play").apply {
            setOnClickListener { cycleTouchMode() }
        }
        themePill = makeStripPill("Parchment").apply {
            setOnClickListener {
                scoreCanvasView.parchmentTheme = !scoreCanvasView.parchmentTheme
                text = if (scoreCanvasView.parchmentTheme) "Parchment" else "Midnight"
            }
        }

        row.addView(repertoirePill)
        row.addView(arrowRulePill)
        row.addView(positionPill)
        row.addView(touchModePill)
        row.addView(themePill)

        scroll.addView(row)
        return scroll
    }

    private fun makeStripPill(label: String, highlighted: Boolean = false): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (highlighted) 0xFFE5A93C.toInt() else 0xFFE6DDD0.toInt())
            background = roundedBg(
                fillColor = if (highlighted) 0xFF281F16.toInt() else 0xFF1A1410.toInt(),
                strokeColor = if (highlighted) 0xFF6B4F23.toInt() else 0xFF32261C.toInt(),
                radiusDp = 99
            )
            setPadding(dp(13), dp(7), dp(13), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
    }

    private fun buildUnifiedFingerboardCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xFF17120E.toInt(), 0xFF31261C.toInt(), 20)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        inspectorBadgeText = TextView(this).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF120E0B.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFFE5A93C.toInt())
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                marginEnd = dp(12)
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        inspectorTitleText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFF6EFE2.toInt())
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        inspectorDetailText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(0xFFB7A894.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        textCol.addView(inspectorTitleText)
        textCol.addView(inspectorDetailText)
        infoRow.addView(inspectorBadgeText)
        infoRow.addView(textCol)
        card.addView(infoRow)
        card.addView(spacer(10))

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

    private fun buildCompactTransportBar(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF140F0C.toInt())
        }

        wrapper.addView(View(this).apply {
            setBackgroundColor(0xFF2E231A.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }

        playButton = makeCompactButton("▶ Play", 0xFFE5A93C.toInt(), 0xFF120E0B.toInt()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { togglePlayback() }
        }

        val stopButton = makeCompactButton("■", 0xFF241C16.toInt(), 0xFFE8DFD1.toInt()).apply {
            minWidth = dp(40)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                synthesizer.stop()
                scoreCanvasView.playingNoteIndex = -1
                playButton.text = "▶ Play"
            }
        }

        loopButton = makeCompactButton("↻", 0xFF241C16.toInt(), 0xFF9F917E.toInt()).apply {
            minWidth = dp(40)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                loopEnabled = !loopEnabled
                backgroundTintList = ColorStateList.valueOf(
                    if (loopEnabled) 0xFF3A2B16.toInt() else 0xFF241C16.toInt()
                )
                setTextColor(if (loopEnabled) 0xFFE5A93C.toInt() else 0xFF9F917E.toInt())
            }
        }

        val slider = Slider(this).apply {
            valueFrom = 44f
            valueTo = 160f
            stepSize = 2f
            value = tempoBpm.toFloat().coerceIn(44f, 160f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            thumbTintList = ColorStateList.valueOf(0xFFE5A93C.toInt())
            trackActiveTintList = ColorStateList.valueOf(0xFFE5A93C.toInt())
            trackInactiveTintList = ColorStateList.valueOf(0xFF34281E.toInt())
            addOnChangeListener { _, v, _ ->
                tempoBpm = v.toInt()
                tempoValueText.text = "$tempoBpm BPM"
            }
        }

        tempoValueText = TextView(this).apply {
            text = "$tempoBpm BPM"
            textSize = 11.5f
            setTextColor(0xFFE5A93C.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), 0, 0, 0)
        }

        row.addView(playButton)
        row.addView(stopButton)
        row.addView(loopButton)
        row.addView(slider)
        row.addView(tempoValueText)
        wrapper.addView(row)
        return wrapper
    }

    // =========================================================================
    // BottomSheetDialogs for Clean, Uncrowded Controls
    // =========================================================================

    private fun showScanOptionsSheet() {
        val dialog = BottomSheetDialog(this)
        val container = createSheetContainer("Scan Music Sheet")

        container.addView(TextView(this).apply {
            text = "Scan printed or photographed sheet music to auto-detect the instrument and write Cello fingerings (↑ for ♯, ↓/↑ for ♭)."
            textSize = 13f
            setTextColor(0xFFB7A894.toInt())
            setPadding(0, 0, 0, dp(14))
        })

        container.addView(makeSheetActionButton("Take Photo with Camera", 0xFFE5A93C.toInt(), 0xFF120E0B.toInt()) {
            dialog.dismiss()
            launchCameraScanner()
        })
        container.addView(spacer(10))
        container.addView(makeSheetActionButton("Choose Sheet Image from Gallery", 0xFF282019.toInt(), 0xFFF6EFE2.toInt()) {
            dialog.dismiss()
            galleryImageLauncher.launch("image/*")
        })
        container.addView(spacer(10))
        container.addView(makeSheetActionButton("Scan Built-in Manuscript (OMR Demo)", 0xFF223026.toInt(), 0xFFA7F3D0.toInt()) {
            dialog.dismiss()
            val rendered = SheetMusicScannerEngine.renderScoreToSampleBitmap(currentScore)
            runOpticalScanOnBitmap(rendered, "Engraved Sheet OMR")
        })

        dialog.setContentView(container)
        dialog.show()
    }

    private fun showRepertoireSheet() {
        val dialog = BottomSheetDialog(this)
        val container = createSheetContainer("Select Repertoire or Study")

        presetLabels.forEachIndexed { idx, label ->
            val isCurrent = idx == currentPresetIndex
            val btn = makeSheetActionButton(
                if (isCurrent) "✓  $label" else label,
                if (isCurrent) 0xFFE5A93C.toInt() else 0xFF241C16.toInt(),
                if (isCurrent) 0xFF120E0B.toInt() else 0xFFF6EFE2.toInt()
            ) {
                dialog.dismiss()
                selectPreset(idx)
            }
            container.addView(btn)
            if (idx < presetLabels.lastIndex) container.addView(spacer(8))
        }

        dialog.setContentView(container)
        dialog.show()
    }

    private fun showStudioOptionsSheet() {
        val dialog = BottomSheetDialog(this)
        val container = createSheetContainer("Atelier & Fingering Settings")

        // Instrument Detection & Override
        container.addView(sheetSectionLabel("INSTRUMENT (AUTO-DETECT OR MANUAL)"))
        val instGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        Instrument.entries.forEach { inst ->
            instGroup.addView(createStyledChip(inst.displayName).apply {
                id = View.generateViewId()
                isChecked = inst == selectedInstrument
                setOnClickListener {
                    autoDetectLocked = false
                    selectedInstrument = inst
                    reannotateCurrentScore()
                }
            })
        }
        container.addView(instGroup)
        container.addView(spacer(8))
        container.addView(makeSheetActionButton("Re-Run Instrument Auto-Detection", 0xFF261E18.toInt(), 0xFFE5A93C.toInt()) {
            autoDetectLocked = true
            val res = CelloFingeringEngine.autoDetectInstrument(currentScore.clef, currentScore.notes)
            selectedInstrument = res.detectedInstrument
            currentScore = CelloFingeringEngine.reannotateScore(
                currentScore.copy(detectionResult = res),
                flatArrowStyle,
                positionMode,
                selectedInstrument
            )
            bindScoreToUi(triggerScan = true)
            dialog.dismiss()
        })

        container.addView(spacer(14))
        container.addView(sheetSectionLabel("FLAT (♭) ARROW CONVENTION"))
        val flatBtn = makeSheetActionButton(
            if (flatArrowStyle == FlatArrowStyle.DOWN_ARROW) "Current: ♯ → ↑  &  ♭ → ↓  (Tap to use ↑ for both)"
            else "Current: ♯ → ↑  &  ♭ → ↑  (Tap to use ↓ for ♭)",
            0xFF261E18.toInt(),
            0xFF67E8F9.toInt()
        ) {
            toggleFlatArrowStyle()
            dialog.dismiss()
        }
        container.addView(flatBtn)

        container.addView(spacer(14))
        container.addView(sheetSectionLabel("CELLO POSITION MODE"))
        val posGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        CelloPositionMode.entries.forEach { pos ->
            posGroup.addView(createStyledChip(pos.label).apply {
                id = View.generateViewId()
                isChecked = pos == positionMode
                setOnClickListener {
                    positionMode = pos
                    reannotateCurrentScore()
                }
            })
        }
        container.addView(posGroup)

        container.addView(spacer(14))
        container.addView(sheetSectionLabel("TAP-TO-ADD ACCIDENTAL (IN '+NOTE' MODE)"))
        val accGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        listOf(
            Accidental.SHARP to "+♯ (↑)",
            Accidental.FLAT to "+♭ (↓/↑)",
            Accidental.NONE to "+♮ Natural"
        ).forEach { (acc, title) ->
            accGroup.addView(createStyledChip(title).apply {
                id = View.generateViewId()
                isChecked = acc == addNoteAccidental
                setOnClickListener { addNoteAccidental = acc }
            })
        }
        container.addView(accGroup)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun createSheetContainer(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF17120E.toInt())
            setPadding(dp(20), dp(18), dp(20), dp(26))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 18f
                setTextColor(0xFFF6EFE2.toInt())
                setTypeface(Typeface.SERIF, Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            })
        }
    }

    private fun makeSheetActionButton(
        label: String,
        bgColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 13.5f
            setTextColor(textColor)
            backgroundTintList = ColorStateList.valueOf(bgColor)
            cornerRadius = dp(14)
            minHeight = dp(48)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onClick() }
        }
    }

    // =========================================================================
    // Actions & State Sync
    // =========================================================================

    private fun selectPreset(idx: Int) {
        synthesizer.stop()
        playButton.text = "▶ Play"
        currentPresetIndex = idx
        val samples = CelloFingeringEngine.getSampleScores(
            flatArrowStyle,
            positionMode,
            selectedInstrument
        )
        currentScore = samples[idx]
        tempoBpm = currentScore.defaultTempoBpm
        if (autoDetectLocked) {
            selectedInstrument = currentScore.detectionResult.detectedInstrument
        }
        scoreCanvasView.scannedSourceBitmap = null
        scoreCanvasView.showScanBackdrop = false
        scoreCanvasView.selectedNoteIndex = 0
        bindScoreToUi(triggerScan = true)
    }

    private fun toggleFlatArrowStyle() {
        flatArrowStyle = if (flatArrowStyle == FlatArrowStyle.DOWN_ARROW) {
            FlatArrowStyle.UP_ARROW
        } else {
            FlatArrowStyle.DOWN_ARROW
        }
        reannotateCurrentScore()
    }

    private fun cyclePositionMode() {
        val all = CelloPositionMode.entries
        positionMode = all[(all.indexOf(positionMode) + 1) % all.size]
        reannotateCurrentScore()
    }

    private fun cycleTouchMode() {
        val all = SheetScoreCanvasView.TouchMode.entries
        scoreCanvasView.touchMode = all[(all.indexOf(scoreCanvasView.touchMode) + 1) % all.size]
        touchModePill.text = when (scoreCanvasView.touchMode) {
            SheetScoreCanvasView.TouchMode.AUDITION -> "Tap: Play"
            SheetScoreCanvasView.TouchMode.CYCLE_ACCIDENTAL -> "Tap: ♮/♯/♭"
            SheetScoreCanvasView.TouchMode.ADD_NOTE -> "Tap Staff: +Note"
        }
    }

    private fun togglePlayback() {
        if (synthesizer.isCurrentlyPlaying()) {
            synthesizer.stop()
            scoreCanvasView.playingNoteIndex = -1
            playButton.text = "▶ Play"
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
                }
            },
            onPlaybackComplete = {
                playButton.text = "▶ Play"
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
        playButton.text = "▶ Play"
        Thread {
            val scanned = SheetMusicScannerEngine.scanBitmap(
                bitmap = bitmap,
                flatArrowStyle = flatArrowStyle,
                positionMode = positionMode,
                instrumentOverride = if (autoDetectLocked) null else selectedInstrument
            ).copy(title = "$sourceLabel — Optical Score")

            runOnUiThread {
                currentScore = scanned
                if (autoDetectLocked) {
                    selectedInstrument = scanned.detectionResult.detectedInstrument
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
        instrumentStatusPill.text = if (autoDetectLocked) {
            "${currentScore.clef.symbol} ${det.detectedInstrument.displayName} (Auto ${det.confidencePercent}%) ▾"
        } else {
            "${currentScore.clef.symbol} ${selectedInstrument.displayName} (Manual) ▾"
        }

        repertoirePill.text = "${presetLabels.getOrElse(currentPresetIndex) { "Custom Scan" }} ▾"
        arrowRulePill.text = "♯ → ↑  •  ♭ → ${flatArrowStyle.symbol}"
        positionPill.text = "Pos: ${positionMode.shortCode}"

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

    private fun makeCompactButton(label: String, bgColor: Int, textColor: Int): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 12.5f
            setTextColor(textColor)
            backgroundTintList = ColorStateList.valueOf(bgColor)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(38)
            setPadding(dp(14), dp(6), dp(14), dp(6))
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
            chipBackgroundColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFFE5A93C.toInt(), 0xFF261E18.toInt())
            )
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(0xFF120E0B.toInt(), 0xFFE8DFD1.toInt())
                )
            )
            chipStrokeColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFFF59E0B.toInt(), 0xFF423428.toInt())
            )
            isCheckedIconVisible = false
        }
    }

    private fun sheetSectionLabel(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 10.5f
            letterSpacing = 0.08f
            setTextColor(0xFFB7A894.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
    }

    private fun spacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
        }
    }
}
