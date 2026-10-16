"""Tests for train_head.py. Run from the repo root:

    training/.venv/bin/python -m unittest discover -s training/tests

The end-to-end tests download YAMNet from Kaggle Models once (kagglehub
caches it) and fail with the reason when that download fails.
"""

import contextlib
import io
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import fixtures  # noqa: E402
import train_head  # noqa: E402


def run_main(args):
    """Runs the script's main and returns (exit code, stdout, stderr)."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = train_head.main([str(a) for a in args])
    return code, out.getvalue(), err.getvalue()


class WindowTest(unittest.TestCase):
    def test_cuts_the_app_window_and_two_shifted_training_windows(self):
        clip = np.arange(32_000, dtype=np.float32)
        app, training = train_head.cut_windows(clip)
        self.assertEqual(app.shape, (15_600,))
        self.assertEqual(app[0], 4_000)
        self.assertEqual(app[-1], 19_599)
        self.assertEqual([w[0] for w in training], [2_400, 4_000, 5_600])
        self.assertTrue(all(w.shape == (15_600,) for w in training))


def clips_for(barks, non_barks, seed=0):
    return [train_head.Clip(id=i, label=label, samples=None, name=f"clips/{i}.wav")
            for i, label in fixtures.schedule(barks, non_barks, seed=seed)]


def stretch_of(clip_id, clips):
    """The index of the stretch of time a clip falls in (5-minute gaps)."""
    ids = sorted(c.id for c in clips)
    index = 0
    for prev, cur in zip(ids, ids[1:]):
        if cur > clip_id:
            break
        if cur - prev > 5 * 60_000:
            index += 1
    return index


class SplitTest(unittest.TestCase):
    def setUp(self):
        self.clips = clips_for(60, 110)

    def test_no_stretch_of_time_lands_in_both_parts(self):
        train, held, _ = train_head.split(self.clips, seed=42)
        train_stretches = {stretch_of(c.id, self.clips) for c in train}
        held_stretches = {stretch_of(c.id, self.clips) for c in held}
        self.assertTrue(held_stretches)
        self.assertEqual(train_stretches & held_stretches, set())
        self.assertEqual(len(train) + len(held), len(self.clips))

    def test_both_parts_hold_both_classes(self):
        train, held, _ = train_head.split(self.clips, seed=42)
        for part in (train, held):
            labels = [c.label for c in part]
            self.assertGreaterEqual(labels.count("bark"), 1)
            self.assertGreaterEqual(labels.count("not_bark"), 1)
        held_labels = [c.label for c in held]
        self.assertGreaterEqual(held_labels.count("bark"), 10)
        self.assertGreaterEqual(held_labels.count("not_bark"), 10)

    def test_holds_out_about_a_fifth(self):
        _, held, _ = train_head.split(self.clips, seed=42)
        self.assertGreaterEqual(len(held), 0.2 * len(self.clips))
        self.assertLess(len(held), 0.3 * len(self.clips))

    def test_same_seed_gives_the_same_split(self):
        first = [c.id for c in train_head.split(self.clips, seed=42)[1]]
        again = [c.id for c in train_head.split(list(reversed(self.clips)), seed=42)[1]]
        self.assertEqual(first, again)

    def test_holds_out_past_a_fifth_until_ten_barks(self):
        # Single barks between long stretches of other sounds (20 clips): a
        # fifth of the clips holds 6 barks.
        clips, start = [], 0
        for s in range(60):
            label, size = ("bark", 1) if s % 2 == 0 else ("not_bark", 20)
            clips += [train_head.Clip(id=start + k * 1000, label=label, samples=None,
                                      name="x") for k in range(size)]
            start += 10 * 60_000
        _, held, _ = train_head.split(clips, seed=42)
        self.assertGreaterEqual([c.label for c in held].count("bark"), 10)

    def test_refuses_with_counts_when_held_out_barks_cannot_reach_ten(self):
        # Every bark in one stretch of time: holding it out leaves none to train on.
        clips = [train_head.Clip(id=i * 1000, label="bark", samples=None, name="b")
                 for i in range(60)]
        clips += [train_head.Clip(id=10**9 + i * 10 * 60_000, label="not_bark",
                                  samples=None, name="n") for i in range(110)]
        with self.assertRaises(train_head.Refusal) as caught:
            train_head.split(clips, seed=42)
        self.assertIn("60 barks", str(caught.exception))


class TempDir(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="train_head_"))
        self.addCleanup(_rmtree, self.dir)
        self.out = self.dir / "out"

    def output_files(self):
        return sorted(p.name for p in self.out.iterdir()) if self.out.exists() else []


def _rmtree(path):
    for root, dirs, files in os.walk(path, topdown=False):
        for f in files:
            os.remove(os.path.join(root, f))
        for d in dirs:
            os.rmdir(os.path.join(root, d))
    os.rmdir(path)


class RefusalTest(TempDir):
    def test_refuses_49_barks_naming_the_count_and_writes_nothing(self):
        zip_path = self.dir / "export.zip"
        fixtures.export_zip(zip_path, 49, 200)
        code, out, err = run_main([zip_path, "--out", self.out])
        self.assertEqual(code, 1)
        self.assertIn("49 barks", err)
        self.assertEqual(self.output_files(), [])

    def test_refuses_99_non_barks_naming_the_count(self):
        zip_path = self.dir / "export.zip"
        fixtures.export_zip(zip_path, 60, 99)
        code, _, err = run_main([zip_path, "--out", self.out])
        self.assertEqual(code, 1)
        self.assertIn("99 non-barks", err)


class BadInputTest(TempDir):
    def refused(self, **export):
        zip_path = self.dir / "export.zip"
        rows = fixtures.export_zip(zip_path, 50, 100, **export)
        code, _, err = run_main([zip_path, "--out", self.out])
        self.assertEqual(code, 1, err)
        self.assertEqual(self.output_files(), [])
        return rows, err

    def first_id(self):
        return fixtures.schedule(50, 100)[0][0]

    def test_refuses_a_clip_at_44100_hz_naming_it(self):
        clip_id = self.first_id()
        _, err = self.refused(override={clip_id: fixtures.wav_bytes(np.zeros(32_000), rate=44_100)})
        self.assertIn(f"clips/{clip_id}.wav", err)
        self.assertIn("44100", err)

    def test_refuses_a_clip_of_16000_samples_naming_it(self):
        clip_id = self.first_id()
        _, err = self.refused(override={clip_id: fixtures.wav_bytes(np.zeros(16_000))})
        self.assertIn(f"clips/{clip_id}.wav", err)
        self.assertIn("16000 samples", err)

    def test_refuses_an_entry_that_declares_50_mb_naming_it(self):
        clip_id = self.first_id()
        _, err = self.refused(override={clip_id: bytes(50 * 1024 * 1024)})
        self.assertIn(f"clips/{clip_id}.wav", err)
        self.assertIn("over 1 MB", err)

    def test_reads_a_parent_path_entry_as_data_and_writes_nothing_for_it(self):
        clip_id = self.first_id()
        zip_path = self.dir / "export.zip"
        fixtures.export_zip(zip_path, 50, 100, rename={clip_id: f"../{clip_id}.wav"},
                            extra={"../evil.wav": b"x", "/abs/evil.wav": b"x"})
        clips = train_head.read_export(zip_path)
        self.assertIn(f"../{clip_id}.wav", [c.name for c in clips])
        self.assertEqual(len(clips), 150)
        self.assertEqual(sorted(os.listdir(self.dir)), ["export.zip"])

    def test_reads_a_name_with_a_space_a_bracket_and_non_ascii_letters(self):
        clip_id = self.first_id()
        name = f"clips/{clip_id} [Bällchen].wav"
        zip_path = self.dir / "export.zip"
        fixtures.export_zip(zip_path, 50, 100, rename={clip_id: name})
        clip = next(c for c in train_head.read_export(zip_path) if c.name == name)
        self.assertEqual(clip.id, clip_id)
        self.assertEqual(clip.samples.shape, (32_000,))


class HostileZipTest(TempDir):
    """Damaged or hostile zips each give a named refusal, never a traceback."""

    def refuse(self, zip_path, *expected):
        code, _, err = run_main([zip_path, "--out", self.out])
        self.assertEqual(code, 1, err)
        self.assertNotIn("Traceback", err)
        for text in expected:
            self.assertIn(text, err)
        self.assertEqual(self.output_files(), [])
        return err

    def rewrite(self, mutate):
        """A small export whose first clip goes through `mutate(zip, name, data)`."""
        plain, zip_path = self.dir / "plain.zip", self.dir / "export.zip"
        rows = fixtures.export_zip(plain, 2, 2)
        target = rows[0][0]
        with zipfile.ZipFile(plain) as src, zipfile.ZipFile(zip_path, "w") as dst:
            for info in src.infolist():
                data = src.read(info)
                if info.filename == target:
                    mutate(dst, target, data)
                else:
                    dst.writestr(info, data)
        return zip_path, target

    @staticmethod
    def patch(zip_path, name, offset_local, offset_central, value):
        raw = bytearray(zip_path.read_bytes())
        with zipfile.ZipFile(zip_path) as z:
            local = z.getinfo(name).header_offset
        raw[local + offset_local:local + offset_local + 2] = value.to_bytes(2, "little")
        central = raw.index(b"PK\x01\x02")  # the first central directory header
        while True:
            n = int.from_bytes(raw[central + 28:central + 30], "little")
            m = int.from_bytes(raw[central + 30:central + 32], "little")
            k = int.from_bytes(raw[central + 32:central + 34], "little")
            if raw[central + 46:central + 46 + n].decode() == name:
                raw[central + offset_central:central + offset_central + 2] = value.to_bytes(2, "little")
                break
            central += 46 + n + m + k
        zip_path.write_bytes(bytes(raw))

    def test_refuses_corrupt_deflate_data_naming_the_clip(self):
        self.refuse_corrupt(zipfile.ZIP_DEFLATED)

    def test_refuses_corrupt_lzma_data_naming_the_clip(self):
        self.refuse_corrupt(zipfile.ZIP_LZMA)

    def refuse_corrupt(self, method):
        def corrupt(z, name, data):
            z.writestr(name, data, compress_type=method)
        zip_path, name = self.rewrite(corrupt)
        raw = bytearray(zip_path.read_bytes())
        with zipfile.ZipFile(zip_path) as z:
            info = z.getinfo(name)
        start = info.header_offset + 30 + len(name.encode()) + len(info.extra)
        for i in range(start + 10, start + 60):
            raw[i] ^= 0xFF
        zip_path.write_bytes(bytes(raw))
        self.refuse(zip_path, name)

    def test_refuses_an_encrypted_entry_naming_it(self):
        zip_path, name = self.rewrite(lambda z, n, d: z.writestr(n, d))
        self.patch(zip_path, name, 6, 8, 0x1)  # general-purpose flag bit 0: encrypted
        self.refuse(zip_path, name)

    def test_refuses_an_unknown_compression_method_naming_it(self):
        zip_path, name = self.rewrite(lambda z, n, d: z.writestr(n, d))
        self.patch(zip_path, name, 8, 10, 99)  # compression method
        self.refuse(zip_path, name)

    def test_refuses_a_labels_field_over_the_csv_limit(self):
        zip_path = self.dir / "export.zip"
        with zipfile.ZipFile(zip_path, "w") as z:
            z.writestr("labels.csv", "file,label\n" + "1" * 200_000 + ",bark\n")
        self.refuse(zip_path, "labels.csv")

    def test_refuses_a_recording_time_of_5000_digits(self):
        zip_path = self.dir / "export.zip"
        name = "clips/" + "1" * 5000 + ".wav"
        with zipfile.ZipFile(zip_path, "w") as z:
            z.writestr(name, fixtures.wav_bytes(np.zeros(32_000)))
            z.writestr("labels.csv", f"file,label\n{name},bark\n")
        self.refuse(zip_path, "no recording time")

    def test_refuses_more_than_10000_rows_before_reading_clips(self):
        zip_path = self.dir / "export.zip"
        rows = "".join(f"clips/{i}.wav,bark\n" for i in range(10_001))
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("labels.csv", "file,label\n" + rows)
        self.refuse(zip_path, "10001 clips", "10000")

    def test_control_10000_rows_get_past_the_row_limit(self):
        zip_path = self.dir / "export.zip"
        rows = "".join(f"clips/{i}.wav,bark\n" for i in range(10_000))
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("labels.csv", "file,label\n" + rows)
        self.refuse(zip_path, "clips/0.wav", "missing from the zip")

    def test_prints_control_characters_in_names_escaped(self):
        zip_path = self.dir / "export.zip"
        with zipfile.ZipFile(zip_path, "w") as z:
            z.writestr("labels.csv", 'file,label\n"clips/1\x1b[2J\nheld out: 0.wav",bark\n')
        err = self.refuse(zip_path, "\\x1b[2J\\n")
        self.assertNotIn("\x1b", err)


class EndToEnd(TempDir):
    """Two full runs on one synthetic export, shared by the tests below.

    The export carries an unlisted `../evil.wav` entry and a bark whose name
    holds a space, a bracket and non-ASCII letters, so the full run covers both.
    """

    _runs = {}
    _work = None

    class Result:
        def __init__(self, code, out, err, out_dir, work):
            self.code, self.out, self.err = code, out, err
            self.out_dir, self.work = out_dir, work

    @classmethod
    def work(cls):
        if cls._work is None:
            cls._work = Path(tempfile.mkdtemp(prefix="train_head_e2e_"))
            first = fixtures.schedule(50, 100)[0][0]
            fixtures.export_zip(cls._work / "export.zip", 50, 100,
                                rename={first: f"clips/{first} [Bällchen].wav"},
                                extra={"../evil.wav": b"RIFF"})
        return cls._work

    @classmethod
    def run_once(cls, key):
        if key not in cls._runs:
            work = cls.work()
            out_dir = work / f"out-{key}"
            cwd = os.getcwd()
            os.chdir(work)  # a relative write of ../evil.wav would land beside work
            try:
                code, out, err = run_main([work / "export.zip", "--out", out_dir])
            finally:
                os.chdir(cwd)
            cls._runs[key] = cls.Result(code, out, err, out_dir, work)
        return cls._runs[key]

    @classmethod
    def tearDownClass(cls):
        if cls._work is not None:
            _rmtree(cls._work)
            cls._work, cls._runs = None, {}

    def ok_run(self, key="first"):
        result = self.run_once(key)
        self.assertEqual(result.code, 0, f"train_head failed:\n{result.err}")
        return result

    def test_runs_on_50_barks_and_100_non_barks(self):
        # The control for RefusalTest: the fewest clips the script accepts.
        self.ok_run()

    def test_writes_a_model_with_the_app_input_and_output(self):
        from ai_edge_litert.interpreter import Interpreter
        result = self.ok_run()
        model = Interpreter(model_path=str(result.out_dir / "yamnet.tflite"))
        self.assertEqual([list(d["shape"]) for d in model.get_input_details()], [[15_600]])
        self.assertEqual([list(d["shape"]) for d in model.get_output_details()], [[1, 521]])
        self.assertEqual([d["dtype"] for d in model.get_input_details()], [np.float32])
        self.assertEqual([d["dtype"] for d in model.get_output_details()], [np.float32])

    def test_report_gives_held_out_counts_and_both_figures(self):
        out = self.ok_run().out
        self.assertRegex(out, r"held out: \d+ barks, \d+ non-barks \(\d+ stretches of time\)")
        self.assertRegex(out, r"new model at 40%: missed \d+ of \d+ barks, flagged \d+ of \d+ non-barks")
        self.assertRegex(out, r"stock Bark score at 40%: missed \d+ of \d+ barks, flagged \d+ of \d+ non-barks")
        for pct in (30, 50, 70):
            self.assertRegex(out, rf"new model at {pct}%: missed \d+ of \d+ barks")

    def test_report_counts_come_from_the_written_file(self):
        from ai_edge_litert.interpreter import Interpreter
        result = self.ok_run()
        _, held, _ = train_head.split(train_head.read_export(result.work / "export.zip"))
        model = Interpreter(model_path=str(result.out_dir / "yamnet.tflite"))
        model.allocate_tensors()
        inp = model.get_input_details()[0]["index"]
        outp = model.get_output_details()[0]["index"]
        barks = sum(c.label == "bark" for c in held)
        missed = flagged = 0
        for clip in held:
            model.set_tensor(inp, train_head.cut_windows(clip.samples)[0])
            model.invoke()
            hit = model.get_tensor(outp)[0, 70] >= np.float32(0.4)
            missed += clip.label == "bark" and not hit
            flagged += clip.label != "bark" and hit
        self.assertIn(f"new model at 40%: missed {missed} of {barks} barks, "
                      f"flagged {flagged} of {len(held) - barks} non-barks", result.out)

    def test_writes_nothing_outside_the_output_folder(self):
        result = self.ok_run()
        self.assertEqual(sorted(os.listdir(result.work)),
                         sorted(["export.zip"] + [f"out-{k}" for k in self._runs]))
        self.assertFalse((result.work.parent / "evil.wav").exists())
        self.assertEqual(sorted(os.listdir(result.out_dir)), ["head.npz", "yamnet.tflite"])

    def test_same_inputs_give_the_same_report(self):
        self.assertEqual(self.ok_run("first").out, self.ok_run("second").out)

    def test_model_keeps_yamnet_scores_and_puts_the_head_at_bark(self):
        from ai_edge_litert.interpreter import Interpreter
        result = self.ok_run()
        clips = train_head.read_export(result.work / "export.zip")[:5]
        windows = [train_head.cut_windows(c.samples)[0] for c in clips]
        yamnet = train_head.load_yamnet()
        head = np.load(result.out_dir / "head.npz")
        model = Interpreter(model_path=str(result.out_dir / "yamnet.tflite"))
        model.allocate_tensors()
        inp = model.get_input_details()[0]["index"]
        outp = model.get_output_details()[0]["index"]
        self.assertEqual(len(windows), 5)
        for window in windows:
            scores, embedding = (np.asarray(t) for t in yamnet(window))
            model.set_tensor(inp, window)
            model.invoke()
            got = model.get_tensor(outp)
            others = np.arange(521) != 70
            np.testing.assert_allclose(got[0, others], scores[0, others], atol=1e-4)
            p = 1 / (1 + np.exp(-(embedding[0] @ head["w"] + head["b"])))
            self.assertAlmostEqual(float(got[0, 70]), float(p), delta=1e-4)


if __name__ == "__main__":
    unittest.main()
