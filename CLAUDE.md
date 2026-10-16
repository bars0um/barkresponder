# barkresponder

## What this is

barkresponder is an Android app written in Kotlin. We build the UI in code in
`MainActivity.kt`. A YAMNet model, run through LiteRT, gates the response: the
app answers only when the classifier hears a bark. The app also saves a
2-second clip of each candidate sound with its scores, which the Samples
screen plays, labels and exports for training. `bin/ci` runs the unit tests,
lint and both APKs.

## Project-specific lessons

### Run phone tests with `bin/device-test`

Never run `./gradlew connectedDebugAndroidTest`. AGP 9.4.0 marks every Wi-Fi
adb device (a `host:port` serial) as failed, even when all tests pass. The
JUnit unique id stores the colon as `%3A`, and AGP compares it raw. You see
"There were failing tests" over a TEST xml with `failures="0"`. We left the
upstream report aside (#7). AGP also installs without `-r`, so an APK that is
already on the device fails with INSTALL_FAILED_ALREADY_EXISTS.

### The owner's test tablet

It is a Lenovo TB-7304I on Android 7.0 (API 24, the app's minSdk), arm64,
reached over Wi-Fi adb. A copy signed with another key blocks installing a
debug build (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Uninstalling that copy wipes
the app's saved settings, so ask the owner first. The tablet's logcat keeps
very few lines, so tests report their measurements as instrumentation status
lines.

### A bark played by the tablet itself scores 0

The CC0 test bark, played through the tablet's own speaker at media volume
9 of 15, passed the loudness check and scored 0% bark: the speaker-to-mic
path loses what YAMNet needs. Test the bark path with an instrumented test
that feeds the WAV into the ring, as `ClipCaptureDeviceTest` does, and keep
real barks for the owner's own hand check. Every clip the app saves while
listening can hold voices from the house: delete test clips with Delete
all before you hand the tablet back.
