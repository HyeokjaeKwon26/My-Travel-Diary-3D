#!/usr/bin/env python3
"""
tools/generate_soundtrack.py

Synthesizes the default offline travel soundtrack for Traveler ("Travel Adventures Anthem").
Style: Cinematic Orchestral / Uplifting Pop Travel Anthem.
Character: Inspiring, majestic, energetic, lush strings, heroic brass, driving percussion, warm acoustics.
Tempo: 112 BPM (~68.57s loop, 32 bars).
Output: app/src/main/res/raw/traveler_memories.wav
"""

import os
import math
import struct
import wave
import time

SAMPLE_RATE = 44100
BPM = 112.0
BEAT_SEC = 60.0 / BPM      # ~0.5357s per beat
BAR_SEC = BEAT_SEC * 4.0   # ~2.1429s per 4/4 measure
NUM_BARS = 32
DURATION_SEC = BAR_SEC * NUM_BARS  # ~68.57 seconds
NUM_SAMPLES = int(SAMPLE_RATE * DURATION_SEC)

# Note frequencies (Hz) - D Major Scale
D2, E2, Fs2, G2, A2, B2 = 73.42, 82.41, 92.50, 98.00, 110.00, 123.47
Cs3, D3, E3, Fs3, G3, A3, B3, Cs4 = 138.59, 146.83, 164.81, 185.00, 196.00, 220.00, 246.94, 277.18
D4, E4, Fs4, G4, A4, B4, Cs5, D5 = 293.66, 329.63, 369.99, 392.00, 440.00, 493.88, 554.37, 587.33
E5, Fs5, G5, A5, B5, Cs6, D6 = 659.25, 739.99, 783.99, 880.00, 987.77, 1108.73, 1174.66

# Chords: Rich orchestral voicings
# Main progression: Dadd9 -> A -> Bm7 -> Gmaj7
CHORDS_MAIN = [
    {"root": D3, "bass": D2, "strings": [D3, A3, D4, Fs4, E5], "harp": [D4, Fs4, A4, D5, E5, Fs5]},     # Dadd9
    {"root": A3, "bass": A2, "strings": [Cs3, A3, E4, A4, Cs5], "harp": [A3, Cs4, E4, A4, Cs5, E5]},    # A
    {"root": B3, "bass": B2, "strings": [B2, Fs3, D4, Fs4, A4], "harp": [B3, D4, Fs4, A4, D5, Fs5]},    # Bm7
    {"root": G3, "bass": G2, "strings": [G2, D3, B3, G4, D5], "harp": [G3, B3, D4, G4, B4, D5]}         # Gmaj7
]

# Variation progression (Bars 16-23): Bm7 -> Gmaj7 -> Dadd9 -> A(sus4)
CHORDS_VAR = [
    {"root": B3, "bass": B2, "strings": [B2, Fs3, D4, Fs4, A4], "harp": [B3, D4, Fs4, A4, D5, Fs5]},
    {"root": G3, "bass": G2, "strings": [G2, D3, B3, G4, D5], "harp": [G3, B3, D4, G4, B4, D5]},
    {"root": D3, "bass": D2, "strings": [D3, A3, D4, Fs4, E5], "harp": [D4, Fs4, A4, D5, E5, Fs5]},
    {"root": A3, "bass": A2, "strings": [A2, E3, A3, D4, E4], "harp": [A3, D4, E4, A4, Cs5, E5]}
]

# Precomputed sine wavetable (16,384 entries)
TABLE_SIZE = 16384
SINE_TABLE = [math.sin(2.0 * math.pi * i / TABLE_SIZE) for i in range(TABLE_SIZE)]

def render_note_to_buffer(freq, duration_sec, timbre_type, out_l, out_r, start_samp, volume, pan):
    """Blazingly fast wavetable oscillator rendering."""
    num_samples = int(duration_sec * SAMPLE_RATE)
    phase_step = freq * (TABLE_SIZE / SAMPLE_RATE)
    vol_l = volume * (1.0 - pan)
    vol_r = volume * pan

    # Timbre harmonic weights
    if timbre_type == "string":
        # Lush string ensemble: fundamental + warm harmonics + detune
        harmonics = [(1, 0.50), (2, 0.30), (3, 0.18), (4, 0.10), (5, 0.06)]
        att_samples = int(0.20 * SAMPLE_RATE)
        rel_samples = int(0.28 * SAMPLE_RATE)
    elif timbre_type == "horn":
        # Noble French horn: bold 2nd & 3rd harmonics, sharp attack
        harmonics = [(1, 0.55), (2, 0.40), (3, 0.24), (4, 0.14), (5, 0.08)]
        att_samples = int(0.04 * SAMPLE_RATE)
        rel_samples = int(0.12 * SAMPLE_RATE)
    elif timbre_type == "harp":
        # Bright acoustic harp / bell chime
        harmonics = [(1, 0.58), (2, 0.26), (3, 0.14), (4, 0.06)]
        att_samples = int(0.005 * SAMPLE_RATE)
        rel_samples = num_samples
    else:  # bass
        harmonics = [(1, 0.75), (2, 0.26), (3, 0.10)]
        att_samples = int(0.012 * SAMPLE_RATE)
        rel_samples = num_samples

    phase = 0.0
    for s in range(num_samples):
        # Envelope calculation
        if timbre_type == "harp" or timbre_type == "bass":
            # Exponential decay
            t = s / SAMPLE_RATE
            decay = 3.5 if timbre_type == "harp" else 2.4
            env = math.exp(-decay * t)
            if s < att_samples:
                env *= (s / att_samples)
        else:
            # ADSR
            att = (s / att_samples) if s < att_samples else 1.0
            rem = num_samples - s
            rel = (rem / rel_samples) if rem < rel_samples else 1.0
            env = att * rel

        # Multi-harmonic synthesis via wavetable lookup
        sample_val = 0.0
        p_int = int(phase)
        for h_num, h_weight in harmonics:
            idx = (p_int * h_num) % TABLE_SIZE
            sample_val += SINE_TABLE[idx] * h_weight

        out_val = sample_val * env
        target = (start_samp + s) % NUM_SAMPLES
        out_l[target] += out_val * vol_l
        out_r[target] += out_val * vol_r

        phase = (phase + phase_step) % TABLE_SIZE

def render_timpani(freq, start_samp, out_l, out_r, volume):
    samp_count = int(1.4 * SAMPLE_RATE)
    for s in range(samp_count):
        t = s / SAMPLE_RATE
        f = freq * (1.0 + 0.15 * math.exp(-t * 26.0))
        env = math.exp(-t * 3.2) * volume
        idx = int(t * f * TABLE_SIZE) % TABLE_SIZE
        val = (SINE_TABLE[idx] * 0.75 + SINE_TABLE[(idx * 2) % TABLE_SIZE] * 0.25) * env
        target = (start_samp + s) % NUM_SAMPLES
        out_l[target] += val * 0.55
        out_r[target] += val * 0.45

def render_kick(start_samp, out_l, out_r, volume):
    samp_count = int(0.32 * SAMPLE_RATE)
    for s in range(samp_count):
        t = s / SAMPLE_RATE
        f = 52.0 + 95.0 * math.exp(-t * 36.0)
        env = math.exp(-t * 13.0) * volume
        idx = int(t * f * TABLE_SIZE) % TABLE_SIZE
        val = SINE_TABLE[idx] * env
        target = (start_samp + s) % NUM_SAMPLES
        out_l[target] += val * 0.5
        out_r[target] += val * 0.5

def render_snare(start_samp, out_l, out_r, volume):
    samp_count = int(0.20 * SAMPLE_RATE)
    for s in range(samp_count):
        t = s / SAMPLE_RATE
        body = math.sin(2.0 * math.pi * 185.0 * t) * math.exp(-t * 22.0) * 0.5
        noise = math.sin(t * 24681.35) * math.cos(t * 97531.24) * math.exp(-t * 32.0) * 0.5
        val = (body + noise) * volume
        target = (start_samp + s) % NUM_SAMPLES
        out_l[target] += val * 0.48
        out_r[target] += val * 0.52

def render_tambourine(start_samp, out_l, out_r, volume):
    samp_count = int(0.08 * SAMPLE_RATE)
    for s in range(samp_count):
        t = s / SAMPLE_RATE
        pseudo_noise = math.sin(t * 38291.45) * math.sin(t * 84920.12)
        val = pseudo_noise * math.exp(-t * 50.0) * volume
        target = (start_samp + s) % NUM_SAMPLES
        out_l[target] += val * 0.42
        out_r[target] += val * 0.58

def generate_soundtrack():
    start_time = time.time()
    print(f"Synthesizing {DURATION_SEC:.2f}s Orchestral/Pop Travel Anthem ('Travel Adventures Anthem', {BPM} BPM)...")
    samples_left = [0.0] * NUM_SAMPLES
    samples_right = [0.0] * NUM_SAMPLES

    # =========================================================================
    # 1. Lush Orchestral Strings Section
    # =========================================================================
    print("  -> Rendering orchestral strings...")
    for bar in range(NUM_BARS):
        bar_time = bar * BAR_SEC
        chord = CHORDS_VAR[bar % 4] if (16 <= bar < 24) else CHORDS_MAIN[bar % 4]
        strings_vol = 0.16 if bar < 8 else (0.28 if bar >= 24 else 0.23)
        dur = BAR_SEC + 0.15
        start_samp = int(bar_time * SAMPLE_RATE)

        for note_idx, s_freq in enumerate(chord["strings"]):
            pan = 0.30 + 0.40 * (note_idx / max(1, len(chord["strings"]) - 1))
            render_note_to_buffer(s_freq, dur, "string", samples_left, samples_right, start_samp, strings_vol, pan)

    # =========================================================================
    # 2. Shimmering Orchestral Harp & Acoustic Arpeggios
    # =========================================================================
    print("  -> Rendering orchestral harp...")
    eighth_sec = BEAT_SEC / 2.0
    num_eighths = int(DURATION_SEC / eighth_sec)
    for step in range(num_eighths):
        t_step = step * eighth_sec
        bar = int(t_step / BAR_SEC)
        chord = CHORDS_VAR[bar % 4] if (16 <= bar < 24) else CHORDS_MAIN[bar % 4]
        pat = [0, 2, 1, 3, 2, 4, 3, 5]
        h_idx = pat[step % 8] % len(chord["harp"])
        freq = chord["harp"][h_idx]
        pan = 0.35 + 0.30 * ((step % 4) / 3.0)
        dur = 0.85
        start_samp = int(t_step * SAMPLE_RATE)
        vol = 0.20 if bar < 8 else (0.26 if bar >= 24 else 0.24)
        render_note_to_buffer(freq, dur, "harp", samples_left, samples_right, start_samp, vol, pan)

    # =========================================================================
    # 3. Noble French Horn / Brass Fanfare Lead
    # =========================================================================
    print("  -> Rendering brass & French horn lead...")
    melody_phrases = [
        [(0.0, D5, 0.9), (1.0, A4, 0.9), (2.0, Fs4, 0.45), (2.5, A4, 0.45), (3.0, D5, 0.9)],
        [(0.0, Cs5, 0.9), (1.0, B4, 0.9), (2.0, A4, 0.9), (3.0, E4, 0.9)],
        [(0.0, B4, 0.9), (1.0, D5, 0.9), (2.0, Fs5, 0.9), (3.0, E5, 0.9)],
        [(0.0, D5, 0.9), (1.0, B4, 0.9), (2.0, A4, 0.9), (3.0, Fs4, 0.9)]
    ]
    melody_var_phrases = [
        [(0.0, Fs5, 1.4), (1.5, E5, 0.5), (2.0, D5, 0.9), (3.0, B4, 0.9)],
        [(0.0, G5, 1.4), (1.5, Fs5, 0.5), (2.0, E5, 0.9), (3.0, D5, 0.9)],
        [(0.0, A5, 1.8), (2.0, Fs5, 0.9), (3.0, D5, 0.9)],
        [(0.0, E5, 1.8), (2.0, Cs5, 0.9), (3.0, A4, 0.9)]
    ]

    for bar in range(NUM_BARS):
        bar_time = bar * BAR_SEC
        phrase = None
        vol = 0.0

        if 8 <= bar < 16:
            phrase = melody_phrases[bar % 4]
            vol = 0.28
        elif 16 <= bar < 24:
            phrase = melody_var_phrases[bar % 4]
            vol = 0.27
        elif 24 <= bar < 32:
            phrase = melody_phrases[bar % 4]
            vol = 0.34  # Grand climax

        if phrase:
            for b_offset, note_freq, note_dur_beats in phrase:
                n_time = bar_time + b_offset * BEAT_SEC
                n_dur = note_dur_beats * BEAT_SEC
                start_samp = int(n_time * SAMPLE_RATE)
                render_note_to_buffer(note_freq, n_dur, "horn", samples_left, samples_right, start_samp, vol, 0.50)

    # =========================================================================
    # 4. Orchestral Bass & Timpani Accents
    # =========================================================================
    print("  -> Rendering double-bass & timpani...")
    for bar in range(NUM_BARS):
        bar_time = bar * BAR_SEC
        chord = CHORDS_VAR[bar % 4] if (16 <= bar < 24) else CHORDS_MAIN[bar % 4]
        bass_hits = [
            (0.0, chord["bass"]),
            (1.5 * BEAT_SEC, chord["root"]),
            (2.0 * BEAT_SEC, chord["bass"]),
            (3.0 * BEAT_SEC, chord["root"] * 1.5)
        ]
        b_vol = 0.28 if bar < 8 else 0.35
        for offset, b_freq in bass_hits:
            hit_time = bar_time + offset
            start_samp = int(hit_time * SAMPLE_RATE)
            render_note_to_buffer(b_freq, 1.1, "bass", samples_left, samples_right, start_samp, b_vol, 0.5)

        if bar in [0, 4, 8, 12, 16, 20, 24, 28, 30]:
            start_samp = int(bar_time * SAMPLE_RATE)
            t_vol = 0.40 if bar >= 8 else 0.26
            render_timpani(chord["bass"], start_samp, samples_left, samples_right, t_vol)

    # =========================================================================
    # 5. Hybrid Pop-Orchestral Percussion (Kick, Snare, Tambourine)
    # =========================================================================
    print("  -> Rendering rhythm percussion...")
    for bar in range(NUM_BARS):
        bar_time = bar * BAR_SEC
        kick_beats = [0.0, 2.0] if bar < 8 else [0.0, 1.0, 2.0, 3.0]
        for b in kick_beats:
            start_samp = int((bar_time + b * BEAT_SEC) * SAMPLE_RATE)
            k_vol = 0.32 if bar < 8 else 0.42
            render_kick(start_samp, samples_left, samples_right, k_vol)

        if bar >= 8:
            for b in [1.0, 3.0]:
                start_samp = int((bar_time + b * BEAT_SEC) * SAMPLE_RATE)
                render_snare(start_samp, samples_left, samples_right, 0.32)

        sixteenth_sec = BEAT_SEC / 4.0
        for shk in range(16):
            start_samp = int((bar_time + shk * sixteenth_sec) * SAMPLE_RATE)
            accent = 1.35 if shk % 4 == 2 else (1.1 if shk % 2 == 1 else 0.7)
            t_vol = 0.18 if bar < 8 else 0.25
            render_tambourine(start_samp, samples_left, samples_right, accent * t_vol)

    # =========================================================================
    # 6. Stereo Concert Hall Reverberation
    # =========================================================================
    print("  -> Applying stereo concert hall reverberation...")
    delays = [int(0.045 * SAMPLE_RATE), int(0.068 * SAMPLE_RATE), int(0.092 * SAMPLE_RATE)]
    decay = 0.20
    for d in delays:
        for i in range(d, NUM_SAMPLES):
            samples_left[i] += samples_right[i - d] * decay * 0.45
            samples_right[i] += samples_left[i - d] * decay * 0.45

    # =========================================================================
    # 7. Normalization & Mastering
    # =========================================================================
    max_peak = max(max(abs(s) for s in samples_left), max(abs(s) for s in samples_right))
    gain = (0.85 / max_peak) if max_peak > 0 else 1.0

    raw_dir = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw"))
    os.makedirs(raw_dir, exist_ok=True)
    import tempfile, subprocess, shutil
    ffmpeg = os.environ.get("FFMPEG") or shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("Install ffmpeg or set FFMPEG to its executable path before generating the soundtrack")
    out_wav = os.path.join(tempfile.mkdtemp(prefix="travel-soundtrack-"), "traveler_memories.wav")

    with wave.open(out_wav, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        interleaved = bytearray()
        for i in range(NUM_SAMPLES):
            sl = max(-32767, min(32767, int(samples_left[i] * gain * 32767.0)))
            sr = max(-32767, min(32767, int(samples_right[i] * gain * 32767.0)))
            interleaved.extend(struct.pack("<hh", sl, sr))
        wf.writeframes(interleaved)

    out_m4a = os.path.join(raw_dir, "traveler_memories.m4a")
    subprocess.run([ffmpeg, "-y", "-i", out_wav, "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", out_m4a], check=True)
    os.remove(out_wav)
    os.rmdir(os.path.dirname(out_wav))
    file_size = os.path.getsize(out_m4a)
    elapsed = time.time() - start_time
    print(f"Generated soundtrack asset in {elapsed:.2f}s: {out_m4a}")
    print(f"  Duration: {DURATION_SEC:.2f}s | Tempo: {BPM} BPM | Size: {file_size:,} bytes")
    print(f"  Character: Rich Orchestral Strings + French Horn Fanfare + Pop/Orchestral Drums + Hall Reverb")

if __name__ == "__main__":
    generate_soundtrack()
