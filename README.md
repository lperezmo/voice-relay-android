<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper Android

Push-to-talk dictation for Android, based on
[kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper) (Apache-2.0, see
[NOTICE](NOTICE)).

Tap the floating button, speak, tap again, and the text is inserted into the currently
focused text field, without switching away from your keyboard.

It uses your own OpenAI API key for:

- **Transcription** of your recording
- **Optional cleanup** of the transcript to fix punctuation and grammar

There is no on-device speech model; the app needs an internet connection to transcribe.

## Install

Sideload the signed APK over ADB. Prefer `adb install` over opening the APK from a browser
or file manager: on Android 13+ an APK opened that way needs "Allow restricted settings"
before its accessibility service can be enabled.

```bash
adb install -r phone-whisper-android-vX.Y.Z.apk
```

## Building locally (Windows)

Requirements: JDK 21, the Android SDK command-line tools with `platforms;android-36` and
`build-tools;36.0.0`, and Python for `scripts/make_keystore.py`.

```bash
echo 'sdk.dir=D:/Android/Sdk' > local.properties   # your SDK path
python scripts/make_keystore.py                     # once; skip if signing.properties exists
./gradlew.bat assembleRelease testReleaseUnitTest
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Signing

Release builds are signed with one keystore everywhere. Android only upgrades an installed
app when the new APK has the same signing key, so a build signed with a different key must be
uninstalled first, which deletes its settings.

- Locally, `app/build.gradle.kts` reads the gitignored `signing.properties` written by
  `scripts/make_keystore.py` (keystore at `.signing/release.jks`).
- In CI, the same values come from the `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
  `KEY_PASSWORD` environment variables, which take precedence.

Back up `.signing/release.jks` and `signing.properties`; losing them means reinstalling.

## Releases

CI builds an unsigned APK for every pull request (`.github/workflows/android-build.yml`).
Every merge to `main` runs semantic-release (`.github/workflows/release.yml`): a `feat:` or
`fix:` commit cuts a version, tags it, and attaches the signed APK to the GitHub release;
`chore:`, `docs:`, `ci:` and `refactor:` commits do not. `versionName` is `0.4.0`, and it is
owned by semantic-release, so never edit it by hand. `versionCode` is derived from it by
`scripts/version_code.sh`, so every release upgrades in place over the previous one.

## How it works

1. A small overlay button floats on screen
2. Tap once to start recording
3. Tap again to stop
4. Audio is sent to OpenAI for transcription
5. The text is inserted into the focused text field
6. If insertion fails, the text is copied to the clipboard

## Setup

### First-time setup

1. Open **Phone Whisper**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Paste your **OpenAI API key** (a dedicated key with a spend cap is a good idea)

Once setup is done, the floating button is ready.

## Why does it need Accessibility?

Phone Whisper uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

- **Transcription**: recorded audio is sent directly from your device to OpenAI's transcription API
- **Optional cleanup**: transcript text is sent directly from your device to OpenAI

There is no backend for this app. Requests go straight from your phone to OpenAI using your own API key.

Full policy: [PRIVACY.md](PRIVACY.md)

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
- Requires an internet connection and your own OpenAI API key

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
