<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper Android

Push-to-talk dictation for Android, based on
[kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper) (Apache-2.0, see
[NOTICE](NOTICE)).

Tap the floating button, speak, tap again, and the text is inserted into the currently
focused text field, without switching away from your keyboard.

It supports:

- **Local on-device transcription** with sherpa-onnx
- **Cloud transcription** with OpenAI
- **Optional cleanup** with OpenAI to fix punctuation and grammar

## Install

Sideload the signed APK over ADB. Prefer `adb install` over opening the APK from a browser
or file manager: on Android 13+ an APK opened that way needs "Allow restricted settings"
before its accessibility service can be enabled.

```bash
adb install -r phone-whisper-android-vX.Y.Z.apk
```

## Building locally (Windows)

Requirements: JDK 21, the Android SDK command-line tools with `platforms;android-36` and
`build-tools;36.0.0`, and Python for the helper scripts.

```bash
echo 'sdk.dir=D:/Android/Sdk' > local.properties   # your SDK path
bash scripts/fetch_sherpa.sh                        # pinned sherpa-onnx native libs
python scripts/make_keystore.py                     # once; skip if signing.properties exists
./gradlew.bat assembleRelease testReleaseUnitTest
adb install -r app/build/outputs/apk/release/app-release.apk
```

`scripts/fetch_sherpa.sh` downloads the sherpa-onnx Android libraries for the pinned version,
verifies the archive's SHA-256, copies only `libsherpa-onnx-jni.so` and `libonnxruntime.so`
into `app/src/main/jniLibs/arm64-v8a/` (gitignored), and reports their 16 KB page alignment.
The vendored Kotlin bindings in `app/src/main/kotlin/com/k2fsa/sherpa/onnx/` must match that
version.

### Signing

Release builds are signed with one keystore everywhere. Android only upgrades an installed
app when the new APK has the same signing key, so a build signed with a different key must be
uninstalled first, which deletes its settings and downloaded models.

- Locally, `app/build.gradle.kts` reads the gitignored `signing.properties` written by
  `scripts/make_keystore.py` (keystore at `.signing/release.jks`).
- In CI, the same values come from the `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
  `KEY_PASSWORD` environment variables, which take precedence.

Back up `.signing/release.jks` and `signing.properties`; losing them means reinstalling.

## How it works

1. A small overlay button floats on screen
2. Tap once to start recording
3. Tap again to stop
4. Audio is transcribed locally or in the cloud
5. The text is inserted into the focused text field
6. If insertion fails, the text is copied to the clipboard

## Setup

### First-time setup

1. Open **Phone Whisper**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud**: paste your OpenAI API key

Once setup is done, the floating button is ready.

## Why does it need Accessibility?

Phone Whisper uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

Phone Whisper supports two modes:

- **Local mode**: audio stays on-device
- **Cloud mode**: audio is sent directly from your device to OpenAI's transcription API
- **Optional cleanup**: transcript text is sent directly from your device to OpenAI's chat API

I don't run a backend for this app. In cloud mode, requests go straight from your phone to OpenAI using your own API key.

Full policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.lperezmo.phonewhisper/files/models/
```

Current catalog:

| Model | Size | Notes |
|---|---:|---|
| Parakeet 110M | 100 MB | Best default |
| Whisper Base | 199 MB | Solid baseline |
| Parakeet 0.6B | 465 MB | Best quality |
| Moonshine Tiny | 103 MB | Fastest |

The app downloads and extracts models directly from the sherpa-onnx release archives.

## App compatibility

Phone Whisper works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Phone Whisper falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Phone Whisper in Termux:

1. Focus Termux
2. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, arrows, etc.) left or right
3. Switch to Termux's native text input box
4. Dictate there

Once text is inserted into the native input box, Termux sends it to the terminal normally.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps may block paste or text injection
- Some apps use custom input surfaces instead of standard Android text fields
- Local models are large
- Cloud mode requires your own OpenAI API key

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
