"""Synthetic exports in the layout the app's Export button writes (#3)."""

import csv
import io
import json
import wave
import zipfile

import numpy as np

RATE = 16_000
CLIP = 32_000
# 2025-10-09T08:53:20Z; any fixed time works.
START_MS = 1_760_000_000_000
MINUTE_MS = 60_000


def bark(rng):
    """A decaying 600 Hz burst with two harmonics, starting near the onset."""
    t = np.arange(CLIP) / RATE
    onset = 0.5 + rng.uniform(-0.05, 0.05)
    after = np.clip(t - onset, 0, None)
    tone = (np.sin(2 * np.pi * 600 * t) + 0.5 * np.sin(2 * np.pi * 1200 * t)
            + 0.25 * np.sin(2 * np.pi * 1800 * t))
    env = np.where(t >= onset, np.exp(-after * 6), 0.0)
    return 0.5 * rng.uniform(0.6, 1.0) * tone * env


def noise(rng):
    return rng.normal(0, rng.uniform(0.05, 0.2), CLIP)


def wav_bytes(samples, rate=RATE):
    pcm = np.round(np.clip(samples, -1, 1) * 32767).astype("<i2")
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())
    return buf.getvalue()


def schedule(barks, non_barks, per_group=10, seed=0):
    """(id, label) pairs: groups of clips 10 s apart, groups 10 minutes apart.

    Labels are shuffled across the whole set, so most groups hold both.
    """
    rng = np.random.default_rng(seed)
    labels = ["bark"] * barks + ["not_bark"] * non_barks
    rng.shuffle(labels)
    out = []
    for i, label in enumerate(labels):
        group, k = divmod(i, per_group)
        out.append((START_MS + group * 10 * MINUTE_MS + k * 10_000, label))
    return out


def export_zip(path, barks, non_barks, seed=0, extra=None, rename=None,
               override=None):
    """Writes an export of synthetic clips to `path` and returns its rows.

    `extra` maps zip entry names to bytes added as they are. `rename` maps a
    clip id to the name its WAV gets inside the zip, and `override` maps a
    clip id to the bytes written in place of its WAV.
    """
    rng = np.random.default_rng(seed + 1)
    rows = []
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for clip_id, label in schedule(barks, non_barks, seed=seed):
            samples = bark(rng) if label == "bark" else noise(rng)
            name = (rename or {}).get(clip_id, f"clips/{clip_id}.wav")
            z.writestr(name, (override or {}).get(clip_id) or wav_bytes(samples))
            z.writestr(name[:-4] + ".json", json.dumps(
                {"recordedAt": clip_id, "barkScore": 0.0, "responded": False,
                 "minScoreSetting": 0.4, "label": label, "scores": [0.0] * 521}))
            rows.append((name, label, clip_id))
        for name, data in (extra or {}).items():
            z.writestr(name, data)
        out = io.StringIO()
        writer = csv.writer(out, lineterminator="\n")
        writer.writerow(["file", "label", "recorded_at", "bark_score", "responded"])
        for name, label, clip_id in reversed(rows):
            writer.writerow([name, label, "2025-10-09T08:53:20.000Z", "0.0000", "false"])
        z.writestr("labels.csv", out.getvalue())
    return rows
