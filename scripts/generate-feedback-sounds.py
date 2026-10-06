#!/usr/bin/env python3
"""Reproducible original GrabX UI sounds; no recordings or external assets."""
import math
import struct
import wave
from pathlib import Path
RATE = 44100
ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/com/grabx/app/grabx/sounds'
# (onset seconds, fundamental Hz, note duration seconds, relative amplitude)
CUES = {
    'start': [(0, 523.25, .19, .85), (.085, 659.25, .23, 1)],
    'resume': [(0, 659.25, .16, .75), (.07, 783.99, .24, .95)],
    'pause': [(0, 440, .16, .8), (.08, 349.23, .18, .7)],
    'cancel': [(0, 392, .14, .65), (.065, 261.63, .20, .7)],
    'complete': [(0, 523.25, .23, .7), (.10, 659.25, .25, .75), (.20, 783.99, .32, .85)],
    'error': [(0, 349.23, .17, .7), (.12, 329.63, .23, .65)],
}
ROOT.mkdir(parents=True, exist_ok=True)
for name, notes in CUES.items():
    samples = [0.0] * int((max(t + d for t, f, d, a in notes) + .025) * RATE)
    for onset, freq, duration, amp in notes:
        for i in range(int(duration * RATE)):
            t = i / RATE
            attack = math.sin(min(1, t / .018) * math.pi / 2) ** 2
            release = math.sin(min(1, (duration - t) / .05) * math.pi / 2) ** 2
            envelope = attack * release * math.exp(-t / .105)
            phase = 2 * math.pi * freq * t
            tone = math.sin(phase) + .12 * math.sin(2 * phase) + .025 * math.sin(3 * phase)
            samples[int(onset * RATE) + i] += .52 * amp * envelope * tone
    with wave.open(str(ROOT / (name + '.wav')), 'wb') as out:
        out.setparams((1, 2, RATE, 0, 'NONE', 'not compressed'))
        out.writeframes(b''.join(struct.pack('<h', round(max(-.95, min(.95, v)) * 32767)) for v in samples))
    print(f'{name}: {len(samples)/RATE:.3f}s, peak={max(map(abs,samples)):.3f}')
