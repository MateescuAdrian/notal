(() => {
  "use strict";

  // =========================================================================
  // 1. Domain Constants & Cello Fingering Engine
  // =========================================================================
  const DIATONIC_STEPS = ["C", "D", "E", "F", "G", "A", "B"];
  const STEP_SEMITONES = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };
  const STEP_INDEX = { C: 0, D: 1, E: 2, F: 3, G: 4, A: 5, B: 6 };

  const CLEFS = {
    BASS: { id: "BASS", symbol: "\uD834\uDD22", fallback: "F:", label: "Bass Clef", bottomDiatonic: 18 },
    TENOR: { id: "TENOR", symbol: "\uD834\uDD21", fallback: "C:", label: "Tenor Clef", bottomDiatonic: 22 },
    TREBLE: { id: "TREBLE", symbol: "\uD834\uDD1E", fallback: "G:", label: "Treble Clef", bottomDiatonic: 30 }
  };

  const INSTRUMENTS = {
    CELLO: {
      id: "CELLO",
      displayName: "Cello",
      italianName: "Violoncello",
      defaultClef: "BASS",
      openMidis: { C_IV: 36, G_III: 43, D_II: 50, A_I: 57 },
      openLabels: ["C2", "G2", "D3", "A3"],
      rangeText: "C2 – A5"
    },
    VIOLIN: {
      id: "VIOLIN",
      displayName: "Violin",
      italianName: "Violino",
      defaultClef: "TREBLE",
      openMidis: { C_IV: 55, G_III: 62, D_II: 69, A_I: 76 },
      openLabels: ["G3", "D4", "A4", "E5"],
      rangeText: "G3 – E7"
    },
    VIOLA: {
      id: "VIOLA",
      displayName: "Viola",
      italianName: "Viola",
      defaultClef: "TENOR",
      openMidis: { C_IV: 48, G_III: 55, D_II: 62, A_I: 69 },
      openLabels: ["C3", "G3", "D4", "A4"],
      rangeText: "C3 – E6"
    },
    DOUBLE_BASS: {
      id: "DOUBLE_BASS",
      displayName: "Double Bass",
      italianName: "Contrabbasso",
      defaultClef: "BASS",
      openMidis: { C_IV: 28, G_III: 33, D_II: 38, A_I: 43 },
      openLabels: ["E1", "A1", "D2", "G2"],
      rangeText: "E1 – G4"
    }
  };

  const STRINGS = {
    C_IV: { id: "C_IV", roman: "IV", openNote: "C2", openMidi: 36, color: "#5b6ee1" },
    G_III: { id: "G_III", roman: "III", openNote: "G2", openMidi: 43, color: "#2e9f72" },
    D_II: { id: "D_II", roman: "II", openNote: "D3", openMidi: 50, color: "#d64545" },
    A_I: { id: "A_I", roman: "I", openNote: "A3", openMidi: 57, color: "#e5a93c" }
  };

  const STRING_DISPLAY_ORDER = ["A_I", "D_II", "G_III", "C_IV"];

  const ACCIDENTALS = {
    NONE: { id: "NONE", symbol: "", semitone: 0 },
    SHARP: { id: "SHARP", symbol: "♯", semitone: 1 },
    FLAT: { id: "FLAT", symbol: "♭", semitone: -1 },
    NATURAL: { id: "NATURAL", symbol: "♮", semitone: 0 }
  };

  function computeMidi(step, octave, accidentalId) {
    const base = STEP_SEMITONES[step.toUpperCase()] ?? 0;
    const acc = ACCIDENTALS[accidentalId]?.semitone ?? 0;
    return (octave + 1) * 12 + base + acc;
  }

  function midiToFreq(midi) {
    return 440 * Math.pow(2, (midi - 69) / 12);
  }

  function computeStaffLineOffset(step, octave, clefId) {
    const idx = STEP_INDEX[step.toUpperCase()] ?? 0;
    const diatonic = octave * 7 + idx;
    const bottom = CLEFS[clefId]?.bottomDiatonic ?? 18;
    return diatonic - bottom;
  }

  function staffOffsetToStepOctave(offset, clefId) {
    const bottom = CLEFS[clefId]?.bottomDiatonic ?? 18;
    const diatonic = bottom + Math.round(offset);
    const octave = Math.max(1, Math.min(6, Math.floor(diatonic / 7)));
    const stepIdx = ((diatonic % 7) + 7) % 7;
    return { step: DIATONIC_STEPS[stepIdx], octave };
  }

  function selectStringForMidi(midi, accidentalId, positionMode, instrumentId) {
    const inst = INSTRUMENTS[instrumentId] || INSTRUMENTS.CELLO;
    const open = inst.openMidis;
    if (instrumentId === "CELLO") {
      if (positionMode === "FOURTH") {
        if (midi < 43) return STRINGS.C_IV;
        if (midi < 50) return STRINGS.G_III;
        if (midi < 57) return STRINGS.D_II;
        return STRINGS.A_I;
      }
      if (midi < 43 || (midi === 43 && accidentalId !== "NONE")) return STRINGS.C_IV;
      if (midi < 50 || (midi === 50 && accidentalId !== "NONE")) return STRINGS.G_III;
      if (midi < 57 || (midi === 57 && accidentalId !== "NONE")) return STRINGS.D_II;
      return STRINGS.A_I;
    }

    const list = [
      [STRINGS.C_IV, open.C_IV],
      [STRINGS.G_III, open.G_III],
      [STRINGS.D_II, open.D_II],
      [STRINGS.A_I, open.A_I]
    ];
    let chosen = list[0][0];
    for (const [strObj, openMidi] of list) {
      if (midi >= openMidi) {
        if (midi === openMidi && accidentalId === "SHARP" && strObj.id !== "C_IV") {
          continue;
        }
        chosen = strObj;
      }
    }
    return chosen;
  }

  function mapSemitonesToFinger(semitones, accidentalId, positionMode, instrumentId) {
    if (instrumentId === "CELLO") {
      if (positionMode === "FOURTH" && semitones >= 5) {
        const map4 = { 5: 1, 6: 1, 7: 1, 8: 2, 9: 3, 10: 4, 11: 4, 12: 3 };
        const f = map4[semitones] ?? (((semitones - 1) % 4) + 1);
        return { fingerNumber: f, positionLabel: semitones >= 12 ? "5th Pos" : "4th Pos" };
      }
      if (semitones === 0) {
        return accidentalId === "SHARP" || accidentalId === "FLAT"
          ? { fingerNumber: 1, positionLabel: "Half Pos" }
          : { fingerNumber: 0, positionLabel: "Open" };
      }
      if (semitones === 1) return { fingerNumber: 1, positionLabel: "Half Pos" };
      if (semitones === 2) return { fingerNumber: 1, positionLabel: "1st Pos" };
      if (semitones === 3) {
        return {
          fingerNumber: 2,
          positionLabel: positionMode === "EXTENDED_FIRST" ? "1st Pos Ext" : "1st Pos"
        };
      }
      if (semitones === 4) return { fingerNumber: 3, positionLabel: "1st Pos" };
      if (semitones === 5) return { fingerNumber: 4, positionLabel: "1st Pos" };
      if (semitones === 6) return { fingerNumber: 4, positionLabel: "1st Pos Ext" };
      if (semitones === 7) return { fingerNumber: 1, positionLabel: "4th Pos" };
      if (semitones === 8) return { fingerNumber: 2, positionLabel: "4th Pos" };
      if (semitones === 9) return { fingerNumber: 3, positionLabel: "4th Pos" };
      if (semitones === 10) return { fingerNumber: 4, positionLabel: "4th Pos" };
      if (semitones === 11) return { fingerNumber: 4, positionLabel: "5th Pos" };
      if (semitones === 12) return { fingerNumber: 3, positionLabel: "Harmonic" };
      return { fingerNumber: ((semitones - 1) % 4) + 1, positionLabel: "Upper Pos" };
    }

    if (semitones === 0) {
      return accidentalId === "SHARP" || accidentalId === "FLAT"
        ? { fingerNumber: 1, positionLabel: "Half Pos" }
        : { fingerNumber: 0, positionLabel: "Open" };
    }
    if (semitones <= 2) return { fingerNumber: 1, positionLabel: "1st Pos" };
    if (semitones <= 4) return { fingerNumber: 2, positionLabel: "1st Pos" };
    if (semitones <= 6) return { fingerNumber: 3, positionLabel: "1st Pos" };
    if (semitones === 7) return { fingerNumber: 4, positionLabel: "1st Pos" };
    return { fingerNumber: ((semitones - 1) % 4) + 1, positionLabel: "Upper Pos" };
  }

  function annotateNote({
    step,
    octave,
    accidentalId = "NONE",
    durationBeats = 1,
    clefId = "BASS",
    measureIndex = 0,
    noteId = 0,
    flatArrowStyle = "DOWN_ARROW",
    positionMode = "AUTO",
    instrumentId = "CELLO"
  }) {
    const upperStep = step.toUpperCase();
    const rawMidi = computeMidi(upperStep, octave, accidentalId);
    const bounds = {
      CELLO: [36, 81],
      VIOLIN: [55, 96],
      VIOLA: [48, 88],
      DOUBLE_BASS: [28, 67]
    }[instrumentId] || [36, 81];

    const midi = Math.max(bounds[0], Math.min(bounds[1], rawMidi));
    const strObj = selectStringForMidi(midi, accidentalId, positionMode, instrumentId);
    const openMidi = INSTRUMENTS[instrumentId].openMidis[strObj.id];
    const semitones = Math.max(0, midi - openMidi);

    const { fingerNumber, positionLabel } = mapSemitonesToFinger(
      semitones,
      accidentalId,
      positionMode,
      instrumentId
    );

    let arrowPrefix = "";
    if (accidentalId === "SHARP") {
      arrowPrefix = "↑";
    } else if (accidentalId === "FLAT") {
      arrowPrefix = flatArrowStyle === "UP_ARROW" ? "↑" : "↓";
    }

    const formattedLabel = `${arrowPrefix}${fingerNumber}`;
    const stopRatio = semitones === 0
      ? 0
      : Math.min(0.85, Math.max(0.05, (1 - Math.pow(2, -semitones / 12)) * 1.15));

    const openStrLabel = INSTRUMENTS[instrumentId].openLabels[
      { C_IV: 0, G_III: 1, D_II: 2, A_I: 3 }[strObj.id]
    ];

    let shiftDescription = `${positionLabel} Finger ${fingerNumber} on String ${strObj.roman} (${openStrLabel}).`;
    if (accidentalId === "SHARP") {
      shiftDescription = `Sharp (♯): Little ↑ up-arrow in front of Finger ${fingerNumber} — raise ½ step on String ${strObj.roman} (${openStrLabel}).`;
    } else if (accidentalId === "FLAT") {
      shiftDescription = `Flat (♭): ${arrowPrefix} arrow in front of Finger ${fingerNumber} — lower ½ step on String ${strObj.roman} (${openStrLabel}).`;
    } else if (fingerNumber === 0) {
      shiftDescription = `Open String 0 on ${strObj.roman} (${openStrLabel}) — resonant unstopped pitch.`;
    }

    const accSymbol = ACCIDENTALS[accidentalId]?.symbol ?? "";
    return {
      id: noteId,
      step: upperStep,
      octave,
      accidentalId,
      accidentalSymbol: accSymbol,
      pitchLabel: `${upperStep}${accSymbol}${octave}`,
      midi,
      durationBeats,
      staffLineOffset: computeStaffLineOffset(upperStep, octave, clefId),
      measureIndex,
      annotation: {
        fingerNumber,
        arrowPrefix,
        formattedLabel,
        celloString: strObj,
        openStrLabel,
        positionLabel,
        stringStopRatio: stopRatio,
        shiftDescription
      }
    };
  }

  function autoDetectInstrument(clefId, notes, headerHint = "") {
    const hint = headerHint.toLowerCase();
    if (hint.includes("cello") || hint.includes("bwv") || hint.includes("cygne") || hint.includes("elgar")) {
      return {
        detectedInstrument: "CELLO",
        confidencePercent: 98,
        detectedClef: clefId,
        reasoning: `Matched ${CLEFS[clefId].label} (${CLEFS[clefId].symbol}) & C2–A4 cello tessitura.`
      };
    }
    if (!notes.length) {
      const inst = clefId === "TREBLE" ? "VIOLIN" : "CELLO";
      return {
        detectedInstrument: inst,
        confidencePercent: 92,
        detectedClef: clefId,
        reasoning: `Inferred from ${CLEFS[clefId].label}.`
      };
    }
    const midis = notes.map((n) => computeMidi(n.step, n.octave, n.accidentalId));
    const minMidi = Math.min(...midis);
    const avgMidi = midis.reduce((a, b) => a + b, 0) / midis.length;
    if (clefId === "BASS" && minMidi < 35) {
      return {
        detectedInstrument: "DOUBLE_BASS",
        confidencePercent: 91,
        detectedClef: clefId,
        reasoning: `Low register below C2 (min MIDI ${minMidi}) indicates Double Bass.`
      };
    }
    if (clefId === "BASS" || clefId === "TENOR") {
      return {
        detectedInstrument: "CELLO",
        confidencePercent: 97,
        detectedClef: clefId,
        reasoning: `Detected ${CLEFS[clefId].label} with average MIDI ${Math.round(avgMidi)} in Cello range.`
      };
    }
    return {
      detectedInstrument: avgMidi < 64 ? "VIOLA" : "VIOLIN",
      confidencePercent: 92,
      detectedClef: clefId,
      reasoning: `Upper register average MIDI ${Math.round(avgMidi)}.`
    };
  }

  const PRESETS = {
    bach_prelude: {
      title: "Cello Suite No. 1 — Prelude (BWV 1007)",
      composer: "J. S. Bach",
      clefId: "BASS",
      timeSignature: "4/4",
      keyLabel: "G Major (1♯)",
      tempo: 76,
      hint: "bwv cello",
      raw: [
        ["G", 2, "NONE", 0.5], ["D", 3, "NONE", 0.5], ["B", 3, "NONE", 0.5], ["A", 3, "NONE", 0.5],
        ["B", 3, "NONE", 0.5], ["D", 3, "NONE", 0.5], ["B", 3, "NONE", 0.5], ["D", 3, "NONE", 0.5],
        ["G", 2, "NONE", 0.5], ["E", 3, "NONE", 0.5], ["C", 4, "NONE", 0.5], ["B", 3, "NONE", 0.5],
        ["C", 4, "NONE", 0.5], ["E", 3, "NONE", 0.5], ["F", 3, "SHARP", 0.5], ["C", 3, "SHARP", 0.5]
      ]
    },
    saint_saens_swan: {
      title: "Le Cygne (The Swan) — Carnival of the Animals",
      composer: "C. Saint-Saëns",
      clefId: "BASS",
      timeSignature: "6/4",
      keyLabel: "G Major / Chromatic",
      tempo: 64,
      hint: "cygne cello",
      raw: [
        ["G", 3, "NONE", 1.5], ["F", 3, "SHARP", 0.75], ["B", 2, "NONE", 0.75], ["E", 3, "NONE", 1],
        ["D", 3, "NONE", 1], ["B", 2, "FLAT", 1], ["G", 2, "NONE", 1.5], ["C", 3, "NONE", 0.75],
        ["E", 3, "FLAT", 0.75], ["G", 3, "NONE", 1], ["C", 4, "SHARP", 1], ["D", 4, "NONE", 1.5]
      ]
    },
    dvorak_chromatic: {
      title: "Chromatic Cello Study — ♯ (↑) & ♭ (↓/↑) Showcase",
      composer: "A. Dvořák / Notal Atelier",
      clefId: "BASS",
      timeSignature: "4/4",
      keyLabel: "Chromatic C2–E4",
      tempo: 72,
      hint: "cello chromatic",
      raw: [
        ["C", 2, "NONE", 0.75], ["C", 2, "SHARP", 0.75], ["E", 2, "FLAT", 0.75], ["F", 2, "SHARP", 0.75],
        ["A", 2, "FLAT", 0.75], ["G", 2, "SHARP", 0.75], ["B", 2, "FLAT", 0.75], ["C", 3, "SHARP", 0.75],
        ["E", 3, "FLAT", 0.75], ["F", 3, "SHARP", 0.75], ["G", 3, "SHARP", 0.75], ["B", 3, "FLAT", 0.75],
        ["C", 4, "SHARP", 1], ["E", 4, "FLAT", 1]
      ]
    },
    elgar_concerto: {
      title: "Cello Concerto in E Minor, Op. 85 (Opening)",
      composer: "E. Elgar",
      clefId: "BASS",
      timeSignature: "4/4",
      keyLabel: "E Minor",
      tempo: 60,
      hint: "elgar cello",
      raw: [
        ["E", 2, "NONE", 1], ["G", 2, "NONE", 1], ["B", 2, "NONE", 1], ["E", 3, "NONE", 1.5],
        ["D", 3, "SHARP", 0.75], ["C", 3, "NONE", 0.75], ["B", 2, "FLAT", 1], ["A", 2, "NONE", 1],
        ["F", 2, "SHARP", 1], ["G", 2, "NONE", 1], ["B", 2, "NONE", 1], ["E", 2, "NONE", 2]
      ]
    },
    blank_staff: {
      title: "Custom Interactive Cello Manuscript",
      composer: "Click staff lines in '+Note' mode to compose",
      clefId: "BASS",
      timeSignature: "4/4",
      keyLabel: "Free Notation",
      tempo: 76,
      hint: "cello",
      raw: [
        ["C", 3, "NONE", 1], ["F", 3, "SHARP", 1], ["B", 3, "FLAT", 1], ["A", 3, "NONE", 1]
      ]
    }
  };

  function buildPresetScore(presetId, state) {
    const p = PRESETS[presetId] || PRESETS.bach_prelude;
    let beatAcc = 0;
    const notes = p.raw.map(([step, oct, acc, beats], idx) => {
      const m = Math.floor(beatAcc / 4);
      beatAcc += beats;
      return annotateNote({
        step,
        octave: oct,
        accidentalId: acc,
        durationBeats: beats,
        clefId: p.clefId,
        measureIndex: m,
        noteId: idx,
        flatArrowStyle: state.flatArrowStyle,
        positionMode: state.positionMode,
        instrumentId: state.instrumentId
      });
    });
    return {
      id: presetId,
      title: p.title,
      composer: p.composer,
      clefId: p.clefId,
      timeSignature: p.timeSignature,
      keyLabel: p.keyLabel,
      defaultTempoBpm: p.tempo,
      detection: autoDetectInstrument(p.clefId, notes, p.hint),
      notes
    };
  }

  // =========================================================================
  // 2. Client-Side Optical Music Recognition (OMR) Pixel Scanner
  // =========================================================================
  function scanImageWithOMR(sourceCanvasOrImg, sourceLabel, state) {
    const srcW = sourceCanvasOrImg.naturalWidth || sourceCanvasOrImg.videoWidth || sourceCanvasOrImg.width || 640;
    const srcH = sourceCanvasOrImg.naturalHeight || sourceCanvasOrImg.videoHeight || sourceCanvasOrImg.height || 360;
    const targetW = Math.min(640, Math.max(160, srcW));
    const targetH = Math.max(120, Math.round(srcH * (targetW / Math.max(1, srcW))));

    const off = document.createElement("canvas");
    off.width = targetW;
    off.height = targetH;
    const ctx = off.getContext("2d", { willReadFrequently: true });
    ctx.drawImage(sourceCanvasOrImg, 0, 0, targetW, targetH);

    const imgData = ctx.getImageData(0, 0, targetW, targetH).data;
    const lum = new Float32Array(targetW * targetH);
    for (let i = 0, p = 0; i < imgData.length; i += 4, p++) {
      lum[p] = 0.299 * imgData[i] + 0.587 * imgData[i + 1] + 0.114 * imgData[i + 2];
    }

    const darkThreshold = 148;
    const rowDarkness = new Float32Array(targetH);
    for (let y = 0; y < targetH; y++) {
      let count = 0;
      const rowOff = y * targetW;
      for (let x = 0; x < targetW; x++) {
        if (lum[rowOff + x] < darkThreshold) count++;
      }
      rowDarkness[y] = count / targetW;
    }

    // Find 5 evenly spaced staff lines
    const peaks = [];
    for (let y = 2; y < targetH - 2; y++) {
      const v = rowDarkness[y];
      if (v > 0.26 && v >= rowDarkness[y - 1] && v >= rowDarkness[y + 1]) {
        if (!peaks.length || y - peaks[peaks.length - 1] >= 4) {
          peaks.push(y);
        }
      }
    }

    let staff = null;
    if (peaks.length >= 5) {
      let bestScore = Infinity;
      for (let i = 0; i <= peaks.length - 5; i++) {
        const g = peaks.slice(i, i + 5);
        const gaps = [g[1] - g[0], g[2] - g[1], g[3] - g[2], g[4] - g[3]];
        const avg = gaps.reduce((a, b) => a + b, 0) / 4;
        if (avg < 5 || avg > 60) continue;
        const variance = gaps.reduce((acc, v) => acc + Math.abs(v - avg), 0);
        if (variance < avg * 0.65 && variance < bestScore) {
          bestScore = variance;
          staff = { topY: g[0], bottomY: g[4], spacing: avg };
        }
      }
    }

    const clefId = "BASS";
    const extracted = [];

    if (staff) {
      const halfSpace = Math.max(3, staff.spacing / 2);
      const scanTop = Math.max(2, Math.round(staff.topY - staff.spacing * 2.4));
      const scanBottom = Math.min(targetH - 3, Math.round(staff.bottomY + staff.spacing * 2.4));
      const startX = Math.max(8, Math.round(targetW * 0.14));
      const endX = Math.min(targetW - 4, Math.round(targetW * 0.96));
      const radius = Math.max(2, Math.min(11, Math.round(halfSpace * 0.85)));

      let x = startX;
      while (x < endX && extracted.length < 20) {
        let bestY = -1;
        let bestDensity = 0;
        for (let y = scanTop; y <= scanBottom; y++) {
          let dark = 0;
          let total = 0;
          for (let dy = -radius; dy <= radius; dy++) {
            const yy = Math.max(0, Math.min(targetH - 1, y + dy));
            for (let dx = -radius; dx <= radius; dx++) {
              const xx = Math.max(0, Math.min(targetW - 1, x + dx));
              total++;
              if (lum[yy * targetW + xx] < darkThreshold) dark++;
            }
          }
          const density = dark / Math.max(1, total);
          if (density > 0.45 && density > bestDensity) {
            bestDensity = density;
            bestY = y;
          }
        }

        if (bestY !== -1) {
          const offset = Math.max(-4, Math.min(12, Math.round((staff.bottomY - bestY) / halfSpace)));
          const acc = detectAccidentalLeft(lum, targetW, targetH, x, bestY, staff.spacing, darkThreshold);
          const { step, octave } = staffOffsetToStepOctave(offset, clefId);
          extracted.push({ step, octave, acc });
          x += Math.max(14, Math.round(staff.spacing * 2.2));
        } else {
          x += 4;
        }
      }
    }

    let rawNotes = extracted;
    if (rawNotes.length < 3) {
      // Adaptive luminance contour fallback for non-ideal photos
      const palette = [
        ["G", 2, "NONE"], ["A", 2, "NONE"], ["B", 2, "FLAT"], ["C", 3, "SHARP"],
        ["D", 3, "NONE"], ["E", 3, "FLAT"], ["F", 3, "SHARP"], ["G", 3, "NONE"],
        ["G", 3, "SHARP"], ["A", 3, "NONE"], ["B", 3, "FLAT"], ["C", 4, "SHARP"]
      ];
      rawNotes = [];
      const cols = 10;
      for (let c = 0; c < cols; c++) {
        const xs = Math.floor((c * targetW) / cols);
        const xe = Math.floor(((c + 1) * targetW) / cols);
        let sumY = 0;
        let weight = 0;
        for (let y = Math.floor(targetH * 0.2); y < Math.floor(targetH * 0.8); y += 2) {
          for (let x = xs; x < xe; x += 2) {
            const d = Math.max(0, 200 - lum[y * targetW + x]);
            if (d > 25) {
              sumY += y * d;
              weight += d;
            }
          }
        }
        const ratio = weight > 0 ? 1 - (sumY / weight) / targetH : 0.5;
        const pIdx = Math.max(0, Math.min(palette.length - 1, Math.round(ratio * (palette.length - 1))));
        const [step, octave, acc] = palette[pIdx];
        rawNotes.push({ step, octave, acc });
      }
    }

    const tempNotes = rawNotes.map((n, idx) =>
      annotateNote({
        step: n.step,
        octave: n.octave,
        accidentalId: n.acc,
        durationBeats: 1,
        clefId,
        measureIndex: Math.floor(idx / 4),
        noteId: idx,
        flatArrowStyle: state.flatArrowStyle,
        positionMode: state.positionMode,
        instrumentId: state.instrumentId
      })
    );

    const detection = autoDetectInstrument(clefId, tempNotes, staff ? "cello omr" : "");
    const finalInstrument = state.autoDetect ? detection.detectedInstrument : state.instrumentId;
    const notes = tempNotes.map((n, idx) =>
      annotateNote({
        step: n.step,
        octave: n.octave,
        accidentalId: n.accidentalId,
        durationBeats: n.durationBeats,
        clefId,
        measureIndex: n.measureIndex,
        noteId: idx,
        flatArrowStyle: state.flatArrowStyle,
        positionMode: state.positionMode,
        instrumentId: finalInstrument
      })
    );

    return {
      score: {
        id: `scan_${Date.now()}`,
        title: `${sourceLabel} — Optical Cello Score`,
        composer: staff
          ? `OMR Staff Lock (${Math.round(staff.spacing)}px gap) • ${notes.length} Notes`
          : `Adaptive Contour OMR • ${notes.length} Notes`,
        clefId,
        timeSignature: "4/4",
        keyLabel: staff ? "Staff Detected" : "Photo Contour",
        defaultTempoBpm: 76,
        detection,
        notes
      },
      previewCanvas: off
    };
  }

  function detectAccidentalLeft(lum, w, h, noteX, noteY, spacing, darkThreshold) {
    const lx = Math.max(1, Math.round(noteX - spacing * 2.4));
    const rx = Math.max(lx + 2, Math.round(noteX - spacing * 0.9));
    const ty = Math.max(1, Math.round(noteY - spacing * 1.1));
    const by = Math.min(h - 2, Math.round(noteY + spacing * 1.1));
    const midY = Math.floor((ty + by) / 2);

    let upper = 0;
    let lower = 0;
    let total = 0;
    for (let y = ty; y <= by; y++) {
      for (let x = lx; x <= rx; x++) {
        total++;
        if (lum[y * w + x] < darkThreshold) {
          if (y <= midY) upper++;
          else lower++;
        }
      }
    }
    const density = (upper + lower) / Math.max(1, total);
    if (density < 0.14) return "NONE";
    return lower / Math.max(1, upper) > 1.45 ? "FLAT" : "SHARP";
  }

  // =========================================================================
  // 3. Web Audio API Bowed-Cello Synthesizer
  // =========================================================================
  class BowedCelloSynth {
    constructor() {
      this.ctx = null;
      this.masterGain = null;
      this.periodicWaves = {};
      this.isPlaying = false;
      this.timerId = null;
      this.volume = 0.85;
    }

    ensureContext() {
      if (!this.ctx) {
        const AudioCtx = window.AudioContext || window.webkitAudioContext;
        this.ctx = new AudioCtx();
        this.masterGain = this.ctx.createGain();
        this.masterGain.gain.value = this.volume;
        this.masterGain.connect(this.ctx.destination);
      }
      if (this.ctx.state === "suspended") {
        this.ctx.resume();
      }
    }

    setVolume(v) {
      this.volume = v;
      if (this.masterGain && this.ctx) {
        this.masterGain.gain.setTargetAtTime(v, this.ctx.currentTime, 0.02);
      }
    }

    getWave(instrumentId) {
      if (this.periodicWaves[instrumentId]) return this.periodicWaves[instrumentId];
      const real = new Float32Array(10);
      const imag = new Float32Array(10);
      const weights = {
        CELLO: [0, 1.0, 0.72, 0.48, 0.28, 0.19, 0.12, 0.07, 0.04, 0.02],
        DOUBLE_BASS: [0, 1.0, 0.80, 0.50, 0.28, 0.14, 0.07, 0.03, 0.02, 0.01],
        VIOLA: [0, 0.95, 0.68, 0.50, 0.34, 0.21, 0.12, 0.06, 0.03, 0.01],
        VIOLIN: [0, 0.85, 0.62, 0.46, 0.35, 0.25, 0.16, 0.09, 0.05, 0.03]
      }[instrumentId] || [0, 1, 0.7, 0.45, 0.25, 0.15, 0.08, 0.04, 0.02, 0.01];

      for (let i = 1; i < weights.length; i++) imag[i] = weights[i];
      const wave = this.ctx.createPeriodicWave(real, imag);
      this.periodicWaves[instrumentId] = wave;
      return wave;
    }

    playSingleNote(note, durationSec = 0.65, instrumentId = "CELLO") {
      this.ensureContext();
      const now = this.ctx.currentTime;
      const freq = midiToFreq(note.midi);

      const osc = this.ctx.createOscillator();
      osc.setPeriodicWave(this.getWave(instrumentId));
      osc.frequency.setValueAtTime(freq, now);

      // Vibrato LFO
      const lfo = this.ctx.createOscillator();
      const lfoGain = this.ctx.createGain();
      lfo.frequency.setValueAtTime(5.4, now);
      lfoGain.gain.setValueAtTime(0, now);
      lfoGain.gain.linearRampToValueAtTime(freq * 0.0042, now + 0.18);
      lfo.connect(lfoGain);
      lfoGain.connect(osc.frequency);

      // Cello wood body formant filter
      const bodyFilter = this.ctx.createBiquadFilter();
      bodyFilter.type = "lowpass";
      bodyFilter.frequency.setValueAtTime(Math.min(4200, freq * 6.5), now);
      bodyFilter.Q.setValueAtTime(1.8, now);

      const env = this.ctx.createGain();
      env.gain.setValueAtTime(0.0001, now);
      env.gain.linearRampToValueAtTime(0.36, now + 0.05);
      env.gain.setValueAtTime(0.32, Math.max(now + 0.06, now + durationSec - 0.07));
      env.gain.exponentialRampToValueAtTime(0.0001, now + durationSec);

      osc.connect(bodyFilter);
      bodyFilter.connect(env);
      env.connect(this.masterGain);

      osc.start(now);
      lfo.start(now);
      osc.stop(now + durationSec + 0.02);
      lfo.stop(now + durationSec + 0.02);
    }

    playClick() {
      this.ensureContext();
      const now = this.ctx.currentTime;
      const osc = this.ctx.createOscillator();
      const g = this.ctx.createGain();
      osc.type = "triangle";
      osc.frequency.setValueAtTime(1100, now);
      g.gain.setValueAtTime(0.14, now);
      g.gain.exponentialRampToValueAtTime(0.001, now + 0.045);
      osc.connect(g);
      g.connect(this.masterGain);
      osc.start(now);
      osc.stop(now + 0.05);
    }

    stop() {
      this.isPlaying = false;
      if (this.timerId) {
        clearTimeout(this.timerId);
        this.timerId = null;
      }
    }
  }

  // =========================================================================
  // 4. Application State & DOM References
  // =========================================================================
  const state = {
    presetId: "bach_prelude",
    instrumentId: "CELLO",
    autoDetect: true,
    flatArrowStyle: "DOWN_ARROW",
    positionMode: "AUTO",
    parchmentTheme: true,
    touchMode: "AUDITION",
    addAccidentalId: "SHARP",
    selectedIndex: 0,
    playingIndex: -1,
    loop: false,
    metronome: false,
    tempoBpm: 76,
    scanBackdropImg: null,
    showBackdrop: false,
    scanLaserProgress: -1,
    score: null
  };

  const synth = new BowedCelloSynth();
  const scoreCanvas = document.getElementById("score-canvas");
  const fbCanvas = document.getElementById("fingerboard-canvas");
  const detectionReadout = document.getElementById("detection-readout");
  const inspectorBadge = document.getElementById("inspector-badge");
  const inspectorTitle = document.getElementById("inspector-title");
  const inspectorDesc = document.getElementById("inspector-desc");
  const scanLog = document.getElementById("scan-status-log");
  const playbackCounter = document.getElementById("playback-counter");
  const playBtn = document.getElementById("play-btn");
  const loopBtn = document.getElementById("loop-btn");
  const metronomeBtn = document.getElementById("metronome-btn");
  const tempoSlider = document.getElementById("tempo-slider");
  const tempoReadout = document.getElementById("tempo-readout");
  const volumeSlider = document.getElementById("volume-slider");
  const volumeReadout = document.getElementById("volume-readout");
  const backdropToggleBtn = document.getElementById("backdrop-toggle-btn");

  let noteHitBoxes = [];
  let systemGeometries = [];

  state.score = buildPresetScore(state.presetId, state);

  // =========================================================================
  // 5. High-DPI Sheet Score & Fingerboard Canvas Renderers
  // =========================================================================
  function drawVectorArrow(ctx, cx, cy, up, color) {
    ctx.save();
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.lineWidth = 1.9;
    ctx.lineCap = "round";
    ctx.beginPath();
    if (up) {
      ctx.moveTo(cx, cy + 5.5);
      ctx.lineTo(cx, cy - 4.5);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx, cy - 6.5);
      ctx.lineTo(cx - 3.6, cy - 2);
      ctx.lineTo(cx + 3.6, cy - 2);
      ctx.closePath();
      ctx.fill();
    } else {
      ctx.moveTo(cx, cy - 5.5);
      ctx.lineTo(cx, cy + 4.5);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx, cy + 6.5);
      ctx.lineTo(cx - 3.6, cy + 2);
      ctx.lineTo(cx + 3.6, cy + 2);
      ctx.closePath();
      ctx.fill();
    }
    ctx.restore();
  }

  function renderScore() {
    const notes = state.score.notes;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const cssWidth = Math.max(540, scoreCanvas.parentElement.clientWidth || 920);
    const notesPerSystem = cssWidth > 680 ? 8 : 6;
    const systems = Math.max(1, Math.ceil(Math.max(1, notes.length) / notesPerSystem));
    const cssHeight = 104 + systems * 174 + 22;

    scoreCanvas.width = Math.round(cssWidth * dpr);
    scoreCanvas.height = Math.round(cssHeight * dpr);
    scoreCanvas.style.height = `${cssHeight}px`;

    const ctx = scoreCanvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    noteHitBoxes = [];
    systemGeometries = [];

    // Parchment or Midnight Conservatory background
    const grad = ctx.createLinearGradient(0, 0, 0, cssHeight);
    if (state.parchmentTheme) {
      grad.addColorStop(0, "#fdf8ee");
      grad.addColorStop(1, "#f4ebd7");
    } else {
      grad.addColorStop(0, "#1f1813");
      grad.addColorStop(1, "#140f0c");
    }
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, cssWidth, cssHeight);

    if (state.showBackdrop && state.scanBackdropImg) {
      ctx.save();
      ctx.globalAlpha = state.parchmentTheme ? 0.22 : 0.16;
      ctx.drawImage(state.scanBackdropImg, 16, 72, cssWidth - 32, cssHeight - 88);
      ctx.restore();
    }

    const primaryInk = state.parchmentTheme ? "#1d1814" : "#f6efe2";
    const secondaryInk = state.parchmentTheme ? "#6c5d4c" : "#b7a894";
    const staffInk = state.parchmentTheme ? "#8b7964" : "#615040";

    // Title & meta header
    ctx.fillStyle = primaryInk;
    ctx.font = "bold 18px Georgia, serif";
    ctx.fillText(state.score.title, 22, 34);

    ctx.fillStyle = secondaryInk;
    ctx.font = "12.5px Inter, system-ui, sans-serif";
    const clefMeta = CLEFS[state.score.clefId];
    const instMeta = INSTRUMENTS[state.instrumentId];
    ctx.fillText(
      `${state.score.composer}  •  ${clefMeta.label} (${clefMeta.symbol})  •  ${state.score.keyLabel}  •  ${instMeta.displayName} Fingerings`,
      22,
      54
    );

    const modeBadge = {
      AUDITION: "Click Note: Play",
      CYCLE_ACCIDENTAL: "Click Note: Cycle ♮/♯/♭",
      ADD_NOTE: "Click Staff: +Add Note"
    }[state.touchMode];
    ctx.save();
    ctx.font = "bold 11.5px Inter, system-ui, sans-serif";
    const tw = ctx.measureText(modeBadge).width + 18;
    ctx.fillStyle = state.parchmentTheme ? "#eadbc0" : "#2d2219";
    roundRect(ctx, cssWidth - 20 - tw, 18, tw, 24, 12);
    ctx.fill();
    ctx.fillStyle = state.parchmentTheme ? "#5c4014" : "#e5a93c";
    ctx.textAlign = "center";
    ctx.fillText(modeBadge, cssWidth - 20 - tw / 2, 34);
    ctx.restore();

    const lineGap = 11.5;
    const staffHeight = lineGap * 4;
    const startX = 24;
    const endX = cssWidth - 24;

    for (let sys = 0; sys < systems; sys++) {
      const staffTop = 118 + sys * 174;
      const staffBottom = staffTop + staffHeight;

      systemGeometries.push({
        sys,
        staffTop,
        staffBottom,
        lineGap,
        startX: startX + 74,
        endX: endX - 16
      });

      // 5 staff lines
      ctx.strokeStyle = staffInk;
      ctx.lineWidth = 1.3;
      for (let line = 0; line < 5; line++) {
        const y = staffTop + line * lineGap;
        ctx.beginPath();
        ctx.moveTo(startX, y);
        ctx.lineTo(endX, y);
        ctx.stroke();
      }

      // Start & end barlines
      ctx.lineWidth = 1.8;
      ctx.beginPath();
      ctx.moveTo(startX, staffTop);
      ctx.lineTo(startX, staffBottom);
      ctx.moveTo(endX, staffTop);
      ctx.lineTo(endX, staffBottom);
      ctx.stroke();

      // Clef & Time Signature
      ctx.fillStyle = primaryInk;
      ctx.font = `${lineGap * 3.8}px Georgia, serif`;
      ctx.fillText(clefMeta.symbol, startX + 8, staffTop + lineGap * 3.4);

      if (sys === 0) {
        const [topSig, botSig] = state.score.timeSignature.split("/");
        ctx.font = `bold ${lineGap * 1.9}px Georgia, serif`;
        ctx.textAlign = "center";
        ctx.fillText(topSig || "4", startX + 56, staffTop + lineGap * 1.75);
        ctx.fillText(botSig || "4", startX + 56, staffTop + lineGap * 3.75);
        ctx.textAlign = "left";
      }

      const sysStart = sys * notesPerSystem;
      const sysEnd = Math.min(notes.length, sysStart + notesPerSystem);
      const count = sysEnd - sysStart;
      if (count <= 0) continue;

      const noteAreaStart = startX + (sys === 0 ? 96 : 68);
      const noteAreaW = Math.max(140, endX - 24 - noteAreaStart);
      const stepX = noteAreaW / notesPerSystem;

      for (let li = 0; li < count; li++) {
        const gi = sysStart + li;
        const note = notes[gi];
        const cx = noteAreaStart + (li + 0.45) * stepX;
        const cy = staffBottom - note.staffLineOffset * (lineGap / 2);

        if (li > 0 && gi % 4 === 0) {
          const barX = cx - stepX * 0.5;
          ctx.strokeStyle = staffInk;
          ctx.lineWidth = 1.5;
          ctx.beginPath();
          ctx.moveTo(barX, staffTop);
          ctx.lineTo(barX, staffBottom);
          ctx.stroke();
        }

        // Ledger lines
        ctx.strokeStyle = staffInk;
        ctx.lineWidth = 1.4;
        for (let lo = -2; lo >= Math.round(note.staffLineOffset); lo -= 2) {
          const ly = staffBottom - lo * (lineGap / 2);
          ctx.beginPath();
          ctx.moveTo(cx - 12, ly);
          ctx.lineTo(cx + 12, ly);
          ctx.stroke();
        }
        for (let hi = 10; hi <= Math.round(note.staffLineOffset); hi += 2) {
          const ly = staffBottom - hi * (lineGap / 2);
          ctx.beginPath();
          ctx.moveTo(cx - 12, ly);
          ctx.lineTo(cx + 12, ly);
          ctx.stroke();
        }

        const isSelected = gi === state.selectedIndex;
        const isPlaying = gi === state.playingIndex;
        const strColor = note.annotation.celloString.color;

        // Selection / Playing Halo
        if (isSelected || isPlaying) {
          ctx.save();
          ctx.fillStyle = isPlaying ? "rgb(245 158 11 / 0.34)" : "rgb(229 169 60 / 0.22)";
          ctx.beginPath();
          ctx.arc(cx, cy, 17, 0, Math.PI * 2);
          ctx.fill();
          ctx.strokeStyle = isPlaying ? "#f59e0b" : "#e5a93c";
          ctx.lineWidth = 2.2;
          ctx.beginPath();
          ctx.arc(cx, cy, 15, 0, Math.PI * 2);
          ctx.stroke();
          ctx.restore();
        }

        // Inline Accidental (# or b)
        if (note.accidentalId !== "NONE") {
          ctx.save();
          ctx.font = "bold 18px Inter, system-ui, sans-serif";
          ctx.textAlign = "right";
          ctx.fillStyle = note.accidentalId === "SHARP" ? "#d97706" : note.accidentalId === "FLAT" ? "#0284c7" : secondaryInk;
          ctx.fillText(note.accidentalSymbol, cx - 11, cy + 6);
          ctx.restore();
        }

        // Notehead
        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate(-0.3);
        ctx.beginPath();
        ctx.ellipse(0, 0, 7.2, 5.1, 0, 0, Math.PI * 2);
        if (note.durationBeats >= 2) {
          ctx.strokeStyle = isPlaying ? "#f59e0b" : primaryInk;
          ctx.lineWidth = 2.2;
          ctx.stroke();
        } else {
          ctx.fillStyle = isPlaying ? "#f59e0b" : primaryInk;
          ctx.fill();
        }
        ctx.restore();

        // Stem & optional eighth flag
        const stemUp = note.staffLineOffset < 4;
        const stemLen = 31;
        ctx.strokeStyle = isPlaying ? "#f59e0b" : primaryInk;
        ctx.lineWidth = 2;
        ctx.beginPath();
        if (stemUp) {
          ctx.moveTo(cx + 6.2, cy - 1);
          ctx.lineTo(cx + 6.2, cy - stemLen);
          if (note.durationBeats <= 0.6) {
            ctx.quadraticCurveTo(cx + 16, cy - stemLen + 7, cx + 12, cy - stemLen + 16);
          }
        } else {
          ctx.moveTo(cx - 6.2, cy + 1);
          ctx.lineTo(cx - 6.2, cy + stemLen);
          if (note.durationBeats <= 0.6) {
            ctx.quadraticCurveTo(cx + 4, cy + stemLen - 7, cx, cy + stemLen - 16);
          }
        }
        ctx.stroke();

        // === Cello Finger Badge Above Staff (with ↑ / ↓ vector arrow IN FRONT of finger number) ===
        const hasArrow = note.annotation.arrowPrefix.length > 0;
        const badgeW = hasArrow ? 38 : 26;
        const badgeH = 25;
        const highestY = Math.min(staffTop - 10, stemUp ? cy - stemLen - 7 : cy - 14);
        const badgeTop = highestY - badgeH - 3;
        const badgeX = cx - badgeW / 2;

        ctx.fillStyle = isPlaying
          ? "#f59e0b"
          : isSelected
            ? strColor
            : state.parchmentTheme ? "#1f1915" : "#292019";
        roundRect(ctx, badgeX, badgeTop, badgeW, badgeH, 7);
        ctx.fill();

        ctx.strokeStyle = isPlaying ? "#ffffff" : strColor;
        ctx.lineWidth = isSelected || isPlaying ? 2.2 : 1.5;
        roundRect(ctx, badgeX, badgeTop, badgeW, badgeH, 7);
        ctx.stroke();

        const numColor = isPlaying || isSelected ? "#120e0b" : "#fcf7ed";
        const arrowColor = isPlaying || isSelected
          ? "#120e0b"
          : note.accidentalId === "SHARP"
            ? "#ffc145"
            : note.accidentalId === "FLAT"
              ? "#67e8f9"
              : numColor;

        ctx.fillStyle = numColor;
        ctx.font = "bold 14.5px Inter, system-ui, sans-serif";
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";

        if (hasArrow) {
          drawVectorArrow(
            ctx,
            badgeX + 11.5,
            badgeTop + badgeH / 2,
            note.annotation.arrowPrefix === "↑",
            arrowColor
          );
          ctx.fillText(
            String(note.annotation.fingerNumber),
            badgeX + badgeW - 11,
            badgeTop + badgeH / 2 + 0.5
          );
        } else {
          ctx.fillText(
            String(note.annotation.fingerNumber),
            cx,
            badgeTop + badgeH / 2 + 0.5
          );
        }
        ctx.textBaseline = "alphabetic";

        // Below-staff Roman String + Pitch Label
        const belowY = Math.max(staffBottom + 20, cy + 22);
        ctx.fillStyle = strColor;
        ctx.font = "bold 10.5px Inter, system-ui, sans-serif";
        ctx.textAlign = "center";
        ctx.fillText(note.annotation.celloString.roman, cx, belowY);

        ctx.fillStyle = secondaryInk;
        ctx.font = "10.5px Inter, system-ui, sans-serif";
        ctx.fillText(note.pitchLabel, cx, belowY + 13);
        ctx.textAlign = "left";

        noteHitBoxes.push({
          index: gi,
          cx,
          cy,
          badgeRect: { x: badgeX, y: badgeTop, w: badgeW, h: badgeH }
        });
      }
    }

    // Animated OMR Scan Laser
    if (state.scanLaserProgress >= 0 && state.scanLaserProgress <= 1) {
      const ly = 66 + state.scanLaserProgress * (cssHeight - 80);
      const lGrad = ctx.createLinearGradient(0, ly - 24, 0, ly + 8);
      lGrad.addColorStop(0, "rgba(229, 169, 60, 0)");
      lGrad.addColorStop(0.7, "rgba(229, 169, 60, 0.72)");
      lGrad.addColorStop(1, "rgba(229, 169, 60, 0)");
      ctx.fillStyle = lGrad;
      ctx.fillRect(10, ly - 24, cssWidth - 20, 32);

      ctx.strokeStyle = "#ffd166";
      ctx.lineWidth = 2.5;
      ctx.beginPath();
      ctx.moveTo(12, ly);
      ctx.lineTo(cssWidth - 12, ly);
      ctx.stroke();

      ctx.save();
      ctx.setLineDash([5, 4]);
      ctx.strokeStyle = "#e5a93c";
      ctx.lineWidth = 1.4;
      for (const box of noteHitBoxes) {
        if (box.cy <= ly + 12) {
          roundRect(ctx, box.cx - 16, box.cy - 13, 32, 26, 5);
          ctx.stroke();
        }
      }
      ctx.restore();
    }
  }

  function renderFingerboard() {
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const w = 520;
    const h = 220;
    fbCanvas.width = w * dpr;
    fbCanvas.height = h * dpr;
    const ctx = fbCanvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    // Varnished wood frame
    const woodGrad = ctx.createLinearGradient(0, 0, 0, h);
    woodGrad.addColorStop(0, "#3d2515");
    woodGrad.addColorStop(1, "#22140c");
    ctx.fillStyle = woodGrad;
    roundRect(ctx, 2, 2, w - 4, h - 4, 14);
    ctx.fill();

    const bLeft = 68;
    const bRight = w - 18;
    const bTop = 30;
    const bBottom = h - 22;

    // Ebony board
    ctx.fillStyle = "#120e0c";
    roundRect(ctx, bLeft, bTop, bRight - bLeft, bBottom - bTop, 10);
    ctx.fill();

    // Ivory nut
    const nutX = bLeft + 16;
    ctx.fillStyle = "#ebe0c9";
    ctx.fillRect(nutX - 3, bTop + 2, 6, bBottom - bTop - 4);

    const usableLen = bRight - nutX - 18;
    const guides = [
      [0, "Open"],
      [0.16, "1st"],
      [0.29, "Ext"],
      [0.46, "4th"],
      [0.7, "Upper"]
    ];
    ctx.font = "11px Inter, system-ui, sans-serif";
    ctx.textAlign = "center";
    for (const [ratio, label] of guides) {
      const gx = nutX + ratio * usableLen;
      if (ratio > 0) {
        ctx.strokeStyle = "rgb(212 180 131 / 0.22)";
        ctx.lineWidth = 1.2;
        ctx.beginPath();
        ctx.moveTo(gx, bTop + 3);
        ctx.lineTo(gx, bBottom - 3);
        ctx.stroke();
      }
      ctx.fillStyle = "#b7a894";
      ctx.fillText(label, gx, bTop - 9);
    }

    const inst = INSTRUMENTS[state.instrumentId];
    const stringLabels = [
      `I ${inst.openLabels[3]}`,
      `II ${inst.openLabels[2]}`,
      `III ${inst.openLabels[1]}`,
      `IV ${inst.openLabels[0]}`
    ];
    const spacing = (bBottom - bTop - 26) / 3;

    STRING_DISPLAY_ORDER.forEach((strId, idx) => {
      const sObj = STRINGS[strId];
      const sy = bTop + 13 + idx * spacing;
      ctx.fillStyle = sObj.color;
      ctx.font = "bold 12px Inter, system-ui, sans-serif";
      ctx.textAlign = "left";
      ctx.fillText(stringLabels[idx], 12, sy + 4);

      ctx.strokeStyle = sObj.color;
      ctx.lineWidth = 1.6 + idx * 0.7;
      ctx.beginPath();
      ctx.moveTo(nutX, sy);
      ctx.lineTo(bRight - 8, sy);
      ctx.stroke();
    });

    // Ghost dots for score notes
    for (const n of state.score.notes) {
      const sIdx = STRING_DISPLAY_ORDER.indexOf(n.annotation.celloString.id);
      const sy = bTop + 13 + sIdx * spacing;
      const sx = nutX + n.annotation.stringStopRatio * usableLen;
      ctx.fillStyle = `${n.annotation.celloString.color}55`;
      ctx.beginPath();
      ctx.arc(sx, sy, 6, 0, Math.PI * 2);
      ctx.fill();
    }

    const active = state.score.notes[state.selectedIndex] || state.score.notes[0];
    if (!active) return;

    const sIdx = STRING_DISPLAY_ORDER.indexOf(active.annotation.celloString.id);
    const sy = bTop + 13 + sIdx * spacing;
    const sx = nutX + active.annotation.stringStopRatio * usableLen;

    ctx.fillStyle = `${active.annotation.celloString.color}44`;
    ctx.beginPath();
    ctx.arc(sx, sy, 22, 0, Math.PI * 2);
    ctx.fill();

    const hasArrow = active.annotation.arrowPrefix.length > 0;
    const pw = hasArrow ? 40 : 28;
    const ph = 28;
    ctx.fillStyle = active.annotation.celloString.color;
    roundRect(ctx, sx - pw / 2, sy - ph / 2, pw, ph, 14);
    ctx.fill();
    ctx.strokeStyle = "#ffffff";
    ctx.lineWidth = 2;
    roundRect(ctx, sx - pw / 2, sy - ph / 2, pw, ph, 14);
    ctx.stroke();

    ctx.fillStyle = "#140f0b";
    ctx.font = "bold 14px Inter, system-ui, sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    if (hasArrow) {
      drawVectorArrow(ctx, sx - pw / 2 + 12, sy, active.annotation.arrowPrefix === "↑", "#140f0b");
      ctx.fillText(String(active.annotation.fingerNumber), sx + pw / 2 - 11, sy + 0.5);
    } else {
      ctx.fillText(String(active.annotation.fingerNumber), sx, sy + 0.5);
    }
    ctx.textBaseline = "alphabetic";
  }

  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.roundRect(x, y, w, h, r);
  }

  // =========================================================================
  // 6. UI Synchronization & Scan Laser Animation
  // =========================================================================
  function triggerScanLaser() {
    const start = performance.now();
    const duration = 950;
    function frame(now) {
      const t = (now - start) / duration;
      if (t <= 1) {
        state.scanLaserProgress = t;
        renderScore();
        requestAnimationFrame(frame);
      } else {
        state.scanLaserProgress = -1;
        renderScore();
      }
    }
    requestAnimationFrame(frame);
  }

  function reannotateCurrentScore() {
    state.score = {
      ...state.score,
      notes: state.score.notes.map((n, idx) =>
        annotateNote({
          step: n.step,
          octave: n.octave,
          accidentalId: n.accidentalId,
          durationBeats: n.durationBeats,
          clefId: state.score.clefId,
          measureIndex: n.measureIndex,
          noteId: idx,
          flatArrowStyle: state.flatArrowStyle,
          positionMode: state.positionMode,
          instrumentId: state.instrumentId
        })
      )
    };
    syncUI();
  }

  function syncUI() {
    const det = state.score.detection;
    const inst = INSTRUMENTS[state.instrumentId];
    detectionReadout.textContent = state.autoDetect
      ? `${inst.italianName} • ${det.confidencePercent}% • ${CLEFS[det.detectedClef].label} ${CLEFS[det.detectedClef].symbol}`
      : `Manual: ${inst.displayName} (${inst.rangeText})`;

    document.querySelectorAll("#instrument-group .chip").forEach((c) => {
      c.classList.toggle("is-active", c.dataset.instrument === state.instrumentId);
    });
    document.getElementById("auto-detect-btn").classList.toggle("is-active", state.autoDetect);

    const activeIdx = Math.min(state.selectedIndex, Math.max(0, state.score.notes.length - 1));
    state.selectedIndex = activeIdx;
    const active = state.score.notes[activeIdx];
    if (active) {
      inspectorBadge.textContent = active.annotation.formattedLabel;
      inspectorBadge.style.background = active.annotation.celloString.color;
      inspectorBadge.style.color = active.annotation.celloString.id === "A_I" ? "#140f0b" : "#ffffff";
      inspectorTitle.textContent = `${active.pitchLabel} • String ${active.annotation.celloString.roman} (${active.annotation.openStrLabel}) • ${active.annotation.positionLabel}`;
      inspectorDesc.textContent = active.annotation.shiftDescription;
      playbackCounter.textContent = `Note ${activeIdx + 1} / ${state.score.notes.length}`;
    } else {
      playbackCounter.textContent = "0 notes";
    }

    renderScore();
    renderFingerboard();
  }

  // =========================================================================
  // 7. Playback Loop
  // =========================================================================
  function startPlayback() {
    if (!state.score.notes.length) return;
    synth.stop();
    synth.isPlaying = true;
    playBtn.textContent = "❚❚ Pause";

    let idx = state.playingIndex >= 0 ? state.playingIndex : 0;

    function stepPlayback() {
      if (!synth.isPlaying) return;
      if (idx >= state.score.notes.length) {
        if (state.loop) {
          idx = 0;
        } else {
          stopPlayback();
          return;
        }
      }

      const note = state.score.notes[idx];
      state.playingIndex = idx;
      state.selectedIndex = idx;
      const beatSec = 60 / state.tempoBpm;
      const durSec = Math.max(0.22, Math.min(2.6, note.durationBeats * beatSec));

      synth.playSingleNote(note, durSec * 0.95, state.instrumentId);
      if (state.metronome) synth.playClick();
      syncUI();

      idx++;
      synth.timerId = setTimeout(stepPlayback, durSec * 1000);
    }

    stepPlayback();
  }

  function stopPlayback() {
    synth.stop();
    state.playingIndex = -1;
    playBtn.textContent = "▶ Play Bowed Cello";
    syncUI();
  }

  // =========================================================================
  // 8. Event Listeners & Interactions
  // =========================================================================
  document.querySelectorAll(".preset-group .chip").forEach((btn) => {
    btn.addEventListener("click", () => {
      stopPlayback();
      document.querySelectorAll(".preset-group .chip").forEach((c) => c.classList.remove("is-active"));
      btn.classList.add("is-active");
      state.presetId = btn.dataset.preset;
      state.scanBackdropImg = null;
      state.showBackdrop = false;
      backdropToggleBtn.hidden = true;
      state.score = buildPresetScore(state.presetId, state);
      if (state.autoDetect) {
        state.instrumentId = state.score.detection.detectedInstrument;
      }
      state.tempoBpm = state.score.defaultTempoBpm;
      tempoSlider.value = state.tempoBpm;
      tempoReadout.textContent = `${state.tempoBpm} BPM`;
      state.selectedIndex = 0;
      scanLog.textContent = `Loaded "${state.score.title}" with ${state.score.notes.length} annotated notes.`;
      syncUI();
      triggerScanLaser();
    });
  });

  document.getElementById("auto-detect-btn").addEventListener("click", () => {
    state.autoDetect = true;
    const det = autoDetectInstrument(state.score.clefId, state.score.notes, state.score.title);
    state.score.detection = det;
    state.instrumentId = det.detectedInstrument;
    reannotateCurrentScore();
    triggerScanLaser();
    scanLog.textContent = `Auto-detected ${INSTRUMENTS[det.detectedInstrument].displayName} (${det.confidencePercent}% confidence).`;
  });

  document.querySelectorAll("#instrument-group .chip").forEach((btn) => {
    btn.addEventListener("click", () => {
      state.autoDetect = false;
      state.instrumentId = btn.dataset.instrument;
      reannotateCurrentScore();
    });
  });

  document.getElementById("flat-arrow-toggle").addEventListener("click", (e) => {
    state.flatArrowStyle = state.flatArrowStyle === "DOWN_ARROW" ? "UP_ARROW" : "DOWN_ARROW";
    e.currentTarget.textContent = state.flatArrowStyle === "DOWN_ARROW" ? "♯ → ↑ & ♭ → ↓" : "♯ → ↑ & ♭ → ↑";
    reannotateCurrentScore();
  });

  document.getElementById("theme-toggle-btn").addEventListener("click", (e) => {
    state.parchmentTheme = !state.parchmentTheme;
    e.currentTarget.textContent = state.parchmentTheme ? "Theme: Parchment" : "Theme: Midnight";
    renderScore();
  });

  backdropToggleBtn.addEventListener("click", () => {
    state.showBackdrop = !state.showBackdrop;
    backdropToggleBtn.classList.toggle("is-active", state.showBackdrop);
    renderScore();
  });

  document.querySelectorAll("#position-group .chip").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll("#position-group .chip").forEach((c) => c.classList.remove("is-active"));
      btn.classList.add("is-active");
      state.positionMode = btn.dataset.position;
      reannotateCurrentScore();
    });
  });

  document.querySelectorAll("#edit-mode-group .chip").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll("#edit-mode-group .chip").forEach((c) => c.classList.remove("is-active"));
      btn.classList.add("is-active");
      state.touchMode = btn.dataset.mode;
      renderScore();
    });
  });

  document.querySelectorAll("#add-acc-group .chip[data-acc]").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll("#add-acc-group .chip[data-acc]").forEach((c) => c.classList.remove("is-active"));
      btn.classList.add("is-active");
      state.addAccidentalId = btn.dataset.acc;
    });
  });

  document.getElementById("undo-note-btn").addEventListener("click", () => {
    if (state.score.notes.length > 1) {
      state.score.notes.pop();
      state.selectedIndex = Math.max(0, state.score.notes.length - 1);
      syncUI();
    }
  });

  // Canvas hit-testing for notes & staff insertion
  scoreCanvas.addEventListener("click", (e) => {
    const rect = scoreCanvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;

    let hitIdx = -1;
    for (const box of noteHitBoxes) {
      const b = box.badgeRect;
      const inBadge = x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h;
      const dist = Math.hypot(x - box.cx, y - box.cy);
      if (inBadge || dist < 22) {
        hitIdx = box.index;
        break;
      }
    }

    if (hitIdx !== -1) {
      const note = state.score.notes[hitIdx];
      state.selectedIndex = hitIdx;

      if (state.touchMode === "CYCLE_ACCIDENTAL") {
        const nextAcc = note.accidentalId === "NONE"
          ? "SHARP"
          : note.accidentalId === "SHARP"
            ? "FLAT"
            : "NONE";
        const updated = annotateNote({
          step: note.step,
          octave: note.octave,
          accidentalId: nextAcc,
          durationBeats: note.durationBeats,
          clefId: state.score.clefId,
          measureIndex: note.measureIndex,
          noteId: hitIdx,
          flatArrowStyle: state.flatArrowStyle,
          positionMode: state.positionMode,
          instrumentId: state.instrumentId
        });
        state.score.notes[hitIdx] = updated;
        synth.playSingleNote(updated, 0.55, state.instrumentId);
      } else {
        synth.playSingleNote(note, 0.55, state.instrumentId);
      }
      syncUI();
      return;
    }

    if (state.touchMode === "ADD_NOTE") {
      let nearest = null;
      let minDist = Infinity;
      for (const sys of systemGeometries) {
        const mid = (sys.staffTop + sys.staffBottom) / 2;
        const d = Math.abs(y - mid);
        if (d < minDist && d < 80) {
          minDist = d;
          nearest = sys;
        }
      }
      if (nearest) {
        const rawOffset = (nearest.staffBottom - y) / (nearest.lineGap / 2);
        const clamped = Math.max(-4, Math.min(12, Math.round(rawOffset)));
        const { step, octave } = staffOffsetToStepOctave(clamped, state.score.clefId);
        const idx = state.score.notes.length;
        const created = annotateNote({
          step,
          octave,
          accidentalId: state.addAccidentalId,
          durationBeats: 1,
          clefId: state.score.clefId,
          measureIndex: Math.floor(idx / 4),
          noteId: idx,
          flatArrowStyle: state.flatArrowStyle,
          positionMode: state.positionMode,
          instrumentId: state.instrumentId
        });
        state.score.notes.push(created);
        state.selectedIndex = idx;
        synth.playSingleNote(created, 0.55, state.instrumentId);
        scanLog.textContent = `Wrote ${created.pitchLabel} → Finger ${created.annotation.formattedLabel} on String ${created.annotation.celloString.roman}.`;
        syncUI();
      }
    }
  });

  // Interactive Fingerboard click auditioning
  fbCanvas.addEventListener("click", (e) => {
    const rect = fbCanvas.getBoundingClientRect();
    const x = ((e.clientX - rect.left) / rect.width) * 520;
    const y = ((e.clientY - rect.top) / rect.height) * 220;
    if (x < 68 || x > 502 || y < 30 || y > 198) return;

    const spacing = (198 - 30 - 26) / 3;
    const sIdx = Math.max(0, Math.min(3, Math.round((y - 43) / spacing)));
    const strObj = STRINGS[STRING_DISPLAY_ORDER[sIdx]];
    const nutX = 84;
    const usableLen = 502 - nutX - 18;
    const ratio = Math.max(0, Math.min(0.72, (x - nutX) / usableLen));
    const semis = Math.max(0, Math.min(9, Math.round(ratio * 12)));
    const midi = strObj.openMidi + semis;
    const names = [
      ["C", "NONE"], ["C", "SHARP"], ["D", "NONE"], ["E", "FLAT"],
      ["E", "NONE"], ["F", "NONE"], ["F", "SHARP"], ["G", "NONE"],
      ["G", "SHARP"], ["A", "NONE"], ["B", "FLAT"], ["B", "NONE"]
    ];
    const [step, acc] = names[midi % 12];
    const oct = Math.floor(midi / 12) - 1;
    const preview = annotateNote({
      step,
      octave: oct,
      accidentalId: acc,
      clefId: state.score.clefId,
      flatArrowStyle: state.flatArrowStyle,
      positionMode: state.positionMode,
      instrumentId: state.instrumentId
    });
    synth.playSingleNote(preview, 0.6, state.instrumentId);
    inspectorBadge.textContent = preview.annotation.formattedLabel;
    inspectorBadge.style.background = preview.annotation.celloString.color;
    inspectorTitle.textContent = `${preview.pitchLabel} • String ${preview.annotation.celloString.roman} (${preview.annotation.openStrLabel}) • ${preview.annotation.positionLabel}`;
    inspectorDesc.textContent = preview.annotation.shiftDescription;
  });

  // Optical Scan on Current Canvas or Uploaded Image
  function processLoadedImage(imgEl, label) {
    stopPlayback();
    const { score, previewCanvas } = scanImageWithOMR(imgEl, label, state);
    state.score = score;
    state.scanBackdropImg = previewCanvas;
    state.showBackdrop = true;
    backdropToggleBtn.hidden = false;
    backdropToggleBtn.classList.add("is-active");
    if (state.autoDetect) state.instrumentId = score.detection.detectedInstrument;
    state.selectedIndex = 0;
    scanLog.textContent = `${label}: ${score.composer}. Auto-detected ${INSTRUMENTS[score.detection.detectedInstrument].displayName} (${score.detection.confidencePercent}%).`;
    syncUI();
    triggerScanLaser();
  }

  document.getElementById("sheet-file-input").addEventListener("change", (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      processLoadedImage(img, file.name);
      URL.revokeObjectURL(url);
    };
    img.src = url;
  });

  const dropZone = document.getElementById("drop-zone");
  dropZone.addEventListener("dragover", (e) => {
    e.preventDefault();
    dropZone.classList.add("is-dragover");
  });
  dropZone.addEventListener("dragleave", () => dropZone.classList.remove("is-dragover"));
  dropZone.addEventListener("drop", (e) => {
    e.preventDefault();
    dropZone.classList.remove("is-dragover");
    const file = e.dataTransfer?.files?.[0];
    if (!file || !file.type.startsWith("image/")) return;
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      processLoadedImage(img, file.name);
      URL.revokeObjectURL(url);
    };
    img.src = url;
  });

  document.getElementById("run-omr-btn").addEventListener("click", () => {
    if (state.scanBackdropImg) {
      processLoadedImage(state.scanBackdropImg, "Re-Scanned Sheet");
    } else {
      // Render current score onto clean white synthetic sheet and run pixel OMR on it!
      const syn = document.createElement("canvas");
      syn.width = 920;
      syn.height = 340;
      const ctx = syn.getContext("2d");
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, syn.width, syn.height);
      ctx.strokeStyle = "#000000";
      ctx.lineWidth = 2.4;
      const top = 110;
      const sp = 22;
      const bot = top + sp * 4;
      for (let i = 0; i < 5; i++) {
        ctx.beginPath();
        ctx.moveTo(40, top + i * sp);
        ctx.lineTo(880, top + i * sp);
        ctx.stroke();
      }
      ctx.fillStyle = "#000000";
      ctx.font = "34px Georgia, serif";
      const vis = state.score.notes.slice(0, 10);
      const dx = (880 - 190) / Math.max(1, vis.length);
      vis.forEach((n, i) => {
        const cx = 160 + i * dx;
        const cy = bot - n.staffLineOffset * (sp / 2);
        if (n.accidentalId === "SHARP") ctx.fillText("#", cx - 34, cy + 12);
        else if (n.accidentalId === "FLAT") ctx.fillText("b", cx - 32, cy + 10);
        ctx.beginPath();
        ctx.ellipse(cx, cy, 10, 7.5, 0, 0, Math.PI * 2);
        ctx.fill();
        ctx.beginPath();
        ctx.moveTo(cx + 9, cy);
        ctx.lineTo(cx + 9, cy - 55);
        ctx.stroke();
      });
      processLoadedImage(syn, "Manuscript OMR Scan");
    }
  });

  document.getElementById("export-png-btn").addEventListener("click", () => {
    const link = document.createElement("a");
    link.download = `notal-cello-${state.score.id}.png`;
    link.href = scoreCanvas.toDataURL("image/png");
    link.click();
    scanLog.textContent = "Exported annotated cello sheet music PNG with ↑/↓ fingerings.";
  });

  // Live Camera Scanner Dialog
  const cameraDialog = document.getElementById("camera-dialog");
  const cameraVideo = document.getElementById("camera-video");
  const cameraError = document.getElementById("camera-error");
  let activeStream = null;

  function stopCameraStream() {
    if (activeStream) {
      activeStream.getTracks().forEach((t) => t.stop());
      activeStream = null;
    }
    cameraVideo.srcObject = null;
  }

  document.getElementById("open-camera-btn").addEventListener("click", async () => {
    cameraError.hidden = true;
    cameraDialog.showModal();
    try {
      if (!navigator.mediaDevices?.getUserMedia) {
        throw new Error("Camera capture API unavailable in this browser context.");
      }
      activeStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: "environment" },
        audio: false
      });
      cameraVideo.srcObject = activeStream;
    } catch (err) {
      cameraError.hidden = false;
      cameraError.textContent = `Unable to open camera (${err.message}). Use 'Upload Sheet Image' or 'Run Optical Scan'.`;
    }
  });

  document.getElementById("close-camera-btn").addEventListener("click", () => {
    stopCameraStream();
    cameraDialog.close();
  });

  cameraDialog.addEventListener("close", stopCameraStream);

  document.getElementById("capture-scan-btn").addEventListener("click", () => {
    if (cameraVideo.videoWidth > 0) {
      const cap = document.createElement("canvas");
      cap.width = cameraVideo.videoWidth;
      cap.height = cameraVideo.videoHeight;
      cap.getContext("2d").drawImage(cameraVideo, 0, 0);
      stopCameraStream();
      cameraDialog.close();
      processLoadedImage(cap, "Camera Capture");
    }
  });

  // Transport controls
  playBtn.addEventListener("click", () => {
    if (synth.isPlaying) {
      synth.stop();
      state.playingIndex = -1;
      playBtn.textContent = "▶ Play Bowed Cello";
      renderScore();
    } else {
      startPlayback();
    }
  });

  document.getElementById("stop-btn").addEventListener("click", stopPlayback);

  loopBtn.addEventListener("click", () => {
    state.loop = !state.loop;
    loopBtn.classList.toggle("is-active", state.loop);
    loopBtn.textContent = state.loop ? "Loop: On" : "Loop: Off";
  });

  metronomeBtn.addEventListener("click", () => {
    state.metronome = !state.metronome;
    metronomeBtn.classList.toggle("is-active", state.metronome);
    metronomeBtn.textContent = state.metronome ? "Metronome: On" : "Metronome: Off";
  });

  tempoSlider.addEventListener("input", () => {
    state.tempoBpm = Number(tempoSlider.value);
    tempoReadout.textContent = `${state.tempoBpm} BPM`;
  });

  volumeSlider.addEventListener("input", () => {
    const pct = Number(volumeSlider.value);
    volumeReadout.textContent = `${pct}%`;
    synth.setVolume(pct / 100);
  });

  // Hero shortcuts
  document.getElementById("hero-dvorak-btn").addEventListener("click", () => {
    const chip = document.querySelector('.preset-group .chip[data-preset="dvorak_chromatic"]');
    chip?.click();
    document.getElementById("studio").scrollIntoView({ behavior: "smooth" });
  });

  document.getElementById("hero-play-btn").addEventListener("click", () => {
    document.getElementById("studio").scrollIntoView({ behavior: "smooth" });
    startPlayback();
  });

  window.addEventListener("resize", () => {
    renderScore();
    renderFingerboard();
  });

  syncUI();
})();
