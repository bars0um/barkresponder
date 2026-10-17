"""Trains a layer that recognises our dog's bark and exports it with YAMNet.

    python training/train_head.py bark-samples-<date>.zip

Reads the zip the app's Export button writes, runs the float YAMNet on each
clip, trains one logistic layer on YAMNet's 1,024 embedding numbers, and
writes `out/yamnet.tflite`: YAMNet's 521 scores with index 70 (Bark) replaced
by the layer's probability. It holds back about a fifth of the clips, whole
stretches of time at a time, and prints how the new model and the stock Bark
score do on them. See README.md.
"""

import argparse
import csv
import io
import lzma
import os
import random
import re
import sys
import time
import wave
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np

SAMPLE_RATE = 16_000
CLIP_SAMPLES = 32_000
WINDOW = 15_600
# The app scores the second that starts 0.25 s before the onset; the clip
# starts 0.5 s before it (#3), so the app's window starts 0.25 s in. A clip
# from the first 0.5 s of listening starts later than that; it is rare.
APP_WINDOW_START = 4_000
# Onset timing jitters, so training also sees the window 0.1 s either side.
TRAIN_SHIFTS = (-1_600, 0, 1_600)
# A valid clip is 64,044 bytes and its sidecar about 6 KB.
MAX_ENTRY_BYTES = 1_000_000
LABELS = "labels.csv"
# The app keeps 1,000 unlabelled clips; a few thousand labelled is plenty.
MAX_CLIPS = 10_000
BARK, NOT_BARK = "bark", "not_bark"
MIN_BARKS, MIN_NON_BARKS = 50, 100
MIN_HELD_PER_CLASS = 10
HOLD_OUT = 0.2
STRETCH_GAP_MS = 5 * 60_000
SEED = 42
BARK_INDEX = 70
CLASSES = 521
EMBEDDING = 1_024
APP_THRESHOLD = 0.4
INFO_THRESHOLDS = (0.3, 0.5, 0.7)
EPOCHS = 300
LEARNING_RATE = 0.01
L2 = 1e-3
YAMNET_HANDLE = "google/yamnet/tensorFlow2/yamnet/1"
MODEL_FILE = "yamnet.tflite"
HEAD_FILE = "head.npz"


class Refusal(Exception):
    """An input the script will not train on; the message says why."""


@dataclass
class Clip:
    id: int  # recording time in ms, from the file name
    label: str
    samples: np.ndarray  # float32, 32,000 samples at 16 kHz
    name: str  # the WAV's path inside the zip


def cut_windows(samples):
    """The window the app scores, and the three windows training uses."""
    app = samples[APP_WINDOW_START:APP_WINDOW_START + WINDOW]
    training = [samples[APP_WINDOW_START + s:APP_WINDOW_START + s + WINDOW]
                for s in TRAIN_SHIFTS]
    return app, training


def _read_entry(z, name):
    try:
        info = z.getinfo(name)
    except KeyError:
        raise Refusal(f"{name!r}: listed in {LABELS} but missing from the zip") from None
    if info.file_size > MAX_ENTRY_BYTES:
        raise Refusal(f"{name!r}: entry is over 1 MB ({info.file_size} bytes)")
    try:
        with z.open(info) as f:
            data = f.read(MAX_ENTRY_BYTES + 1)
    # Damaged data raises zlib.error or LZMAError; a password or an unknown
    # method raises RuntimeError (NotImplementedError is one).
    except (zipfile.BadZipFile, OSError, EOFError, zlib.error, lzma.LZMAError,
            RuntimeError) as e:
        raise Refusal(f"{name!r}: unreadable zip entry ({e})") from None
    if len(data) > MAX_ENTRY_BYTES:
        raise Refusal(f"{name!r}: entry is over 1 MB")
    return data


def _read_wav(name, data):
    try:
        with wave.open(io.BytesIO(data)) as w:
            rate, channels, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
            frames = w.readframes(w.getnframes())
    except (wave.Error, EOFError) as e:
        raise Refusal(f"{name!r}: not a PCM WAV file ({e})") from None
    if rate != SAMPLE_RATE:
        raise Refusal(f"{name!r}: {rate} Hz, expected {SAMPLE_RATE} Hz")
    if channels != 1:
        raise Refusal(f"{name!r}: {channels} channels, expected mono")
    if width != 2:
        raise Refusal(f"{name!r}: {8 * width}-bit, expected 16-bit")
    count = len(frames) // 2
    if count != CLIP_SAMPLES:
        raise Refusal(f"{name!r}: {count} samples, expected {CLIP_SAMPLES}")
    # The app writes round(x * 32767), so this gives back what it scored.
    return np.frombuffer(frames, dtype="<i2").astype(np.float32) / 32767.0


def read_export(path):
    """Reads every labelled clip from the export zip, in memory.

    Names inside the zip are only keys: nothing is extracted, so an entry
    named `../x` writes nothing.
    """
    try:
        z = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError) as e:
        raise Refusal(f"{path}: not a readable zip ({e})") from None
    with z:
        try:
            text = _read_entry(z, LABELS).decode("utf-8")
        except UnicodeDecodeError:
            raise Refusal(f"{LABELS}: not UTF-8 text") from None
        try:
            rows = list(csv.DictReader(io.StringIO(text)))
        except csv.Error as e:
            raise Refusal(f"{LABELS}: not a readable CSV file ({e})") from None
        if not rows or "file" not in rows[0] or "label" not in rows[0]:
            raise Refusal(f"{LABELS}: no rows with file and label columns")
        if len(rows) > MAX_CLIPS:
            raise Refusal(f"{LABELS}: lists {len(rows)} clips; the most it takes is {MAX_CLIPS}")
        clips, seen = [], set()
        for row in rows:
            name, label = row["file"], row["label"]
            if label not in (BARK, NOT_BARK):
                raise Refusal(f"{name!r}: label {label!r}, expected bark or not_bark")
            # Up to 18 digits, as the app's SampleStore names its clips.
            match = re.match(r"(\d{1,18})(?!\d)", name.rsplit("/", 1)[-1])
            if not match:
                raise Refusal(f"{name!r}: no recording time at the start of its name")
            if name in seen:
                raise Refusal(f"{name!r}: listed twice in {LABELS}")
            seen.add(name)
            samples = _read_wav(name, _read_entry(z, name))
            clips.append(Clip(id=int(match.group(1)), label=label, samples=samples, name=name))
    return clips


def _counts(clips):
    barks = sum(c.label == BARK for c in clips)
    return barks, len(clips) - barks


def check_counts(clips):
    barks, non_barks = _counts(clips)
    if barks < MIN_BARKS or non_barks < MIN_NON_BARKS:
        raise Refusal(f"found {barks} barks and {non_barks} non-barks; need at least "
                      f"{MIN_BARKS} barks and {MIN_NON_BARKS} non-barks")


def split(clips, seed=SEED):
    """Holds out whole stretches of time until about a fifth of the clips.

    Returns (training clips, held-out clips, number of held-out stretches).
    Clips more than 5 minutes apart start a new stretch, so near copies from
    one burst of barking never land on both sides.
    """
    ordered = sorted(clips, key=lambda c: c.id)
    stretches = []
    for clip in ordered:
        if stretches and clip.id - stretches[-1][-1].id <= STRETCH_GAP_MS:
            stretches[-1].append(clip)
        else:
            stretches.append([clip])
    random.Random(seed).shuffle(stretches)

    held, taken = [], 0
    for stretch in stretches:
        barks, non_barks = _counts(held)
        if (len(held) >= HOLD_OUT * len(clips) and barks >= MIN_HELD_PER_CLASS
                and non_barks >= MIN_HELD_PER_CLASS):
            break
        held.extend(stretch)
        taken += 1
    train = [c for s in stretches[taken:] for c in s]

    held_barks, held_non = _counts(held)
    train_barks, train_non = _counts(train)
    if (held_barks < MIN_HELD_PER_CLASS or held_non < MIN_HELD_PER_CLASS
            or len(held) > len(clips) / 2 or train_barks == 0 or train_non == 0):
        total_barks, total_non = _counts(clips)
        raise Refusal(
            f"cannot hold out at least {MIN_HELD_PER_CLASS} barks and "
            f"{MIN_HELD_PER_CLASS} non-barks in whole stretches of time and keep "
            f"most clips for training: {total_barks} barks and {total_non} "
            f"non-barks in {len(stretches)} stretches; holding out "
            f"{held_barks} barks and {held_non} non-barks would leave "
            f"{train_barks} barks and {train_non} non-barks to train on")
    held.sort(key=lambda c: c.id)
    train.sort(key=lambda c: c.id)
    return train, held, taken


def _tf():
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
    import tensorflow as tf
    return tf


def _yamnet_path():
    import kagglehub
    try:
        return kagglehub.model_download(YAMNET_HANDLE)
    except Exception as e:  # kagglehub raises many types; all mean "no model"
        raise Refusal(f"could not download YAMNet ({YAMNET_HANDLE}) from Kaggle "
                      f"Models: {e}. It needs the network once; kagglehub caches it.") from None


def load_yamnet():
    """The float YAMNet: window [15600] -> (scores [1,521], embeddings [1,1024])."""
    tf = _tf()
    model = tf.saved_model.load(_yamnet_path())

    def run(window):
        scores, embeddings, _ = model(tf.constant(window, dtype=tf.float32))
        return scores.numpy(), embeddings.numpy()

    run.model = model
    return run


def train_layer(x, y):
    """Logistic regression with balanced class weights; returns (w, b)."""
    tf = _tf()
    tf.random.set_seed(SEED)
    tf.config.experimental.enable_op_determinism()
    x = tf.constant(x, tf.float32)
    y = tf.constant(y, tf.float32)
    n, positives = float(len(y)), float(np.sum(y))
    weights = tf.where(y > 0.5, n / (2 * positives), n / (2 * (n - positives)))
    w = tf.Variable(tf.zeros([x.shape[1]]))
    b = tf.Variable(0.0)
    opt = tf.optimizers.Adam(LEARNING_RATE)

    @tf.function
    def step():
        with tf.GradientTape() as tape:
            logits = tf.linalg.matvec(x, w) + b
            losses = tf.nn.sigmoid_cross_entropy_with_logits(labels=y, logits=logits)
            loss = tf.reduce_mean(weights * losses) + L2 * tf.reduce_sum(w * w)
        opt.apply_gradients(zip(tape.gradient(loss, [w, b]), [w, b]))

    for _ in range(EPOCHS):
        step()
    return w.numpy(), np.float32(b.numpy())


def export(yamnet, w, b):
    """YAMNet plus the layer as the bytes of one float32 TFLite file."""
    tf = _tf()

    class WithHead(tf.Module):
        def __init__(self):
            super().__init__()
            self.yamnet = yamnet.model
            self.w = tf.constant(w.reshape(EMBEDDING, 1))
            self.b = tf.constant(b)
            self.bark = tf.one_hot(BARK_INDEX, CLASSES)

        @tf.function(input_signature=[tf.TensorSpec([WINDOW], tf.float32)])
        def __call__(self, waveform):
            scores, embeddings, _ = self.yamnet(waveform)
            p = tf.sigmoid(tf.matmul(embeddings, self.w) + self.b)  # [1, 1]
            return scores * (1.0 - self.bark) + p * self.bark

    module = WithHead()
    converter = tf.lite.TFLiteConverter.from_concrete_functions(
        [module.__call__.get_concrete_function()], module)
    return converter.convert()


def tflite_bark_scores(path, windows):
    """Index 70 of the exported file, read back through LiteRT as the app does."""
    from ai_edge_litert.interpreter import Interpreter
    model = Interpreter(model_path=str(path))
    model.allocate_tensors()
    inp = model.get_input_details()[0]["index"]
    out = model.get_output_details()[0]["index"]
    scores = []
    for window in windows:
        model.set_tensor(inp, np.asarray(window, np.float32))
        model.invoke()
        scores.append(float(model.get_tensor(out)[0, BARK_INDEX]))
    return np.array(scores, np.float32)


def outcome(labels, scores, threshold):
    """(missed barks, flagged non-barks) when the app responds at score >= threshold."""
    hit = np.asarray(scores, np.float32) >= np.float32(threshold)  # the app compares floats
    barks = np.asarray(labels) == BARK
    return int(np.sum(barks & ~hit)), int(np.sum(~barks & hit))


def report(labels, new_scores, stock_scores, stretches):
    barks = sum(1 for label in labels if label == BARK)
    non_barks = len(labels) - barks

    def line(who, scores, threshold):
        missed, flagged = outcome(labels, scores, threshold)
        return (f"{who} at {round(threshold * 100)}%: missed {missed} of {barks} barks, "
                f"flagged {flagged} of {non_barks} non-barks")

    lines = [f"held out: {barks} barks, {non_barks} non-barks ({stretches} stretches of time)",
             line("new model", new_scores, APP_THRESHOLD),
             line("stock Bark score", stock_scores, APP_THRESHOLD)]
    lines += [line("new model", new_scores, t) for t in INFO_THRESHOLDS]
    return "\n".join(lines)


def run(zip_path, out_dir):
    started = time.monotonic()
    clips = read_export(zip_path)
    check_counts(clips)
    train, held, stretches = split(clips)
    log(f"{len(train)} clips to train on, {len(held)} held out")

    yamnet = load_yamnet()
    x, y = [], []
    for clip in train:
        for window in cut_windows(clip.samples)[1]:
            x.append(yamnet(window)[1][0])
            y.append(1.0 if clip.label == BARK else 0.0)
    held_windows = [cut_windows(c.samples)[0] for c in held]
    stock = np.array([yamnet(w)[0][0, BARK_INDEX] for w in held_windows], np.float32)
    log(f"read {len(x) + len(held)} windows through YAMNet")

    w, b = train_layer(np.array(x, np.float32), np.array(y, np.float32))
    out_dir = Path(out_dir)
    model_path, head_path = out_dir / MODEL_FILE, out_dir / HEAD_FILE
    # Both files take their names only once the model has been measured, so
    # out/ never holds a model without its report or a head from another run.
    partial_model = out_dir / (MODEL_FILE + ".tmp")
    partial_head = out_dir / (HEAD_FILE + ".tmp")
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
        partial_model.write_bytes(export(yamnet, w, b))
        with open(partial_head, "wb") as f:
            np.savez(f, w=w, b=b)
        text = report([c.label for c in held],
                      tflite_bark_scores(partial_model, held_windows), stock, stretches)
        os.replace(partial_head, head_path)
        os.replace(partial_model, model_path)
    finally:
        partial_model.unlink(missing_ok=True)
        partial_head.unlink(missing_ok=True)
    log(f"wrote {model_path} ({model_path.stat().st_size} bytes) in "
        f"{time.monotonic() - started:.0f} s")
    print(text)


def log(message):
    print(message, file=sys.stderr, flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("export", help="the bark-samples-<date>.zip the app exported")
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent / "out"),
                        help="folder for yamnet.tflite and head.npz (default: training/out)")
    args = parser.parse_args(argv)
    try:
        run(args.export, args.out)
    except Refusal as e:
        print(f"train_head: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
