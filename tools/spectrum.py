#!/usr/bin/env python3
"""Spectrograms of the offline-rendered sounds (profile B20): tools/spectrum.py WAV_DIR OUT_PNG"""
import sys, glob, os, wave
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

files = sorted(glob.glob(os.path.join(sys.argv[1], "*.wav")))
fig, axes = plt.subplots(len(files), 1, figsize=(8, 1.6 * len(files)))
for ax, f in zip(axes, files):
    w = wave.open(f)
    x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16) / 32768.0
    ax.specgram(x, NFFT=512, Fs=w.getframerate(), noverlap=384, cmap="magma")
    ax.set_ylim(0, 5000)
    ax.set_ylabel(os.path.basename(f)[:-4], rotation=0, ha="right", fontsize=8)
    ax.set_xticks([])
fig.tight_layout()
fig.savefig(sys.argv[2], dpi=90)
