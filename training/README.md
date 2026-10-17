# Training the bark layer

Out of the box the app answers to YAMNet's stock Bark score, which fires for
any dog and hears our dog's high-pitched yaps as "Animal" or "Bird". This
script teaches one small layer our dog's bark from the clips you label on the
Samples screen. YAMNet stays as it is; the script saves YAMNet and the layer
as one model file that reads the same as the stock one, so the app needs no
change. The number at the Bark position now means "our dog's bark".

## Set up once

You need Python 3.11 or later. The CPU is enough; no GPU and no account.

```sh
python3 -m venv training/.venv
training/.venv/bin/pip install -r training/requirements.txt
```

The first run downloads the float YAMNet (14 MB) from Kaggle Models and
keeps it in `~/.cache/kagglehub`.

## Run it

Label clips on the Samples screen until you have at least 50 barks and 100
other sounds, then tap Export and copy the zip to your computer.

```sh
training/.venv/bin/python training/train_head.py bark-samples-20261016.zip
```

It refuses fewer than 50 barks or 100 non-barks and says how many it found. It
writes `training/out/yamnet.tflite` (about 15 MB) and `training/out/head.npz`
(the layer's weights). `--out <folder>` writes them elsewhere. The repo ignores
`training/out/`, `training/data/`, every `.zip` and every `.wav`: clips hold
voices from the house, so never commit them.

## Read the report

The script holds back about a fifth of the clips and never trains on them.
Clips from one burst of barking are near copies, so it holds back whole
stretches of time (clips more than 5 minutes apart start a new stretch). It
then runs the held-back clips through the file it wrote and prints:

```
held out: 21 barks, 74 non-barks (3 stretches of time)
new model at 40%: missed 2 of 21 barks, flagged 3 of 74 non-barks
stock Bark score at 40%: missed 4 of 21 barks, flagged 9 of 74 non-barks
new model at 30%: missed 1 of 21 barks, flagged 5 of 74 non-barks
new model at 50%: missed 3 of 21 barks, flagged 2 of 74 non-barks
new model at 70%: missed 5 of 21 barks, flagged 1 of 74 non-barks
```

"Missed" barks get no response; "flagged" non-barks get one they should not.
40% is the app's default Bark confidence. The stock line is YAMNet's own Bark
score at full precision; the app's smaller stock file differs by up to 0.28,
and it is the file the new model replaces. The new model helps when both of its
numbers at 40% sit at or below the stock line's. The 30%, 50% and 70% lines
show where to set the app's Bark confidence; the script picks none. With about
20 held-back barks, one clip moves the missed share by 5%.

## Put it in the app

Copy the file over the stock model and build:

```sh
cp training/out/yamnet.tflite app/src/main/assets/yamnet.tflite
```

Try it on the phone before you keep it. The file is float32, 15 MB against
the stock 4.1 MB: the stock file is quantized, which shifts its scores by up
to 0.28, and the layer needs YAMNet's exact numbers. After the swap a saved
clip's Bark score is the layer's, so retrain from the WAVs in an export, never
from stored scores.

The phone tests in `bin/device-test` play a stranger's bark from Freesound and
expect a Bark score of at least 0.4. The new layer knows only our dog, so that
test can fail after the swap; it then needs its own stock copy of the model.
