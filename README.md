<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper Android

Push-to-talk dictation and one-tap grammar fixes for Android, using your own OpenAI API key.
Based on [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper) (Apache-2.0, see
[NOTICE](NOTICE)). On the phone this build is called **Whisper LP** and has a teal icon, so it is
easy to tell apart from the original if both are installed.

A small button floats over every app. Tap it, speak, tap again, and the text appears where you
are typing, without switching away from your keyboard. Long-press it to fix the grammar of
text you typed.

There is no on-device speech model: the app needs an internet connection.

## Using it

| Gesture on the floating button | What happens |
|---|---|
| **Tap** (idle) | Start recording. The button turns red and pulses. |
| **Tap** (recording) | Stop. The audio is transcribed, cleaned up, and inserted at the cursor. |
| **Long-press** (idle) | Fix the grammar of the selected text, or the whole focused field when nothing is selected. |
| **Tap** (amber, after a failure) | Retry the same recording. Nothing is lost on a network error. |
| **Long-press** (amber) | Discard the failed recording. |
| **Drag** | Move the button; it snaps to the nearest edge. |
| **Tap "Tap here to undo"** | Shown for 5 seconds after text is inserted or fixed. Puts the field back as it was, unless you have edited it since. |

Where the text goes:

- **A text field is focused:** the text is inserted at the cursor (replacing any selection),
  with a space added when it follows a word. The clipboard is not touched. If the app dropped
  focus while you were talking (long dictations, a chat app re-rendering), the field that was
  focused when you started recording is used, as long as it is still on screen.
- **No text field is focused:** the text is copied to the clipboard so you can paste it.
- **The app refuses the insert:** the text is copied to the clipboard and a message says so.

Clipboard copies are marked sensitive, so they stay out of clipboard previews and keyboard
clipboard history.

### On the lock screen

The button also works on the lock screen, which is handy for quick voice notes: with no text
field, the result goes to the clipboard. To keep a stray tap in a pocket or on the nightstand
from recording for hours:

- A recording started while the phone is locked stops after **2 minutes** and is transcribed.
  Locking the phone during a recording also caps it at 2 more minutes.
- After **5 recordings** on the lock screen, the button asks you to unlock first. Unlocking
  resets the count.

### Fix grammar from the selection menu

Select text in any app and choose **Fix grammar** in the selection menu (next to Cut, Copy,
Paste; it may be under the three-dot overflow). Editable text is replaced in place; read-only
text (a web page, a received message) is fixed onto the clipboard. This path does not use the
accessibility service at all.

## Settings

| Setting | Default | Notes |
|---|---|---|
| API key | none | Stored encrypted with an Android Keystore key. A dedicated key with a spend cap is a good idea. |
| Live transcription | off | Streams the audio to the Realtime API (`gpt-live-transcribe`) while you talk, so the transcript is ready about 0.5 s after you stop, however long you spoke. About $0.017/min versus $0.0045/min. If the stream fails, the same recording goes through the standard upload. |
| Transcription model | `gpt-transcribe` | Picker lists the speech models your key can use, plus **Other** for any model id. |
| Languages | `en, es` | Sent as hints so mixed-language dictation is recognized. Empty = auto-detect. |
| Vocabulary | empty | Names and terms to spell exactly. Sent as `keywords` to `gpt-transcribe` (a `prompt` for other models) and added to the cleanup and grammar-fix instructions. |
| Clean up transcript | on | Fixes grammar, punctuation, and filler words after transcription. |
| Cleanup model | `gpt-5.6-luna` | Also used for grammar fixes. Called through the Responses API with the lowest reasoning effort for speed. |
| Cleanup prompt | Simple | **Dev** also turns spoken commands into shell commands; **Custom** is your own text. |

The **Usage** section at the bottom of the settings screen counts dictations, minutes of audio,
cleanup and grammar-fix calls, and an estimated cost for this month and since the last reset
(tap it to reset). The estimate uses OpenAI list prices for known models; calls to a model
without a known price are counted and flagged rather than guessed. Your OpenAI usage page is
the actual bill.

If cleanup fails or changes the text implausibly (for example, it answered a dictated question
instead of cleaning it), the raw transcript is inserted instead.

## Install

Sideload the signed APK from the latest GitHub release over ADB. Prefer `adb install` over
opening the APK from a browser or file manager: on Android 13+ an APK opened that way needs
"Allow restricted settings" before its accessibility service can be enabled.

```bash
adb install -r phone-whisper-android-vX.Y.Z.apk
```

Then open **Whisper LP** and:

1. Allow the **microphone**.
2. Tap **Accessibility service**, read the explanation, and enable Whisper LP.
3. Tap **API key** and paste your OpenAI key.

## Why does it need Accessibility?

Only to show the floating button and to put text into the field you are using. It reads the
screen only at the moment it inserts or fixes text, to find the focused field. It does not
replace your keyboard, run background automation, or send screen content anywhere.

## Privacy

- **Dictation:** your recording is sent directly from the phone to OpenAI for transcription,
  and the transcript to OpenAI for cleanup (if enabled).
- **Grammar fix:** the selected text (or the focused field's text) is sent to OpenAI.
- There is no backend. Requests go straight to OpenAI with your key, with `store: false` on
  cleanup requests.
- Logs contain sizes and timings only, never your text.

Full policy: [PRIVACY.md](PRIVACY.md)

## Performance

- Recordings are uploaded as 64 kbps AAC (`.m4a`), about 4x smaller than WAV, with WAV as a
  fallback if the phone's encoder fails.
- The TLS connection to the API is opened while you are still speaking, so the upload starts
  without a handshake.
- The release APK is shrunk with R8 (about 2.5 MB).
- Each dictation logs one line such as
  `Dictation: upload audio.m4a 58 KB, transcribe 1340 ms, cleanup 910 ms`, plus an `Insert:`
  line saying how the target field was found (package, class, focus flags, window ids; never
  text). Read them with `adb logcat -s PhoneWhisper`.

## App compatibility

Works best in apps that use standard Android text fields. Some apps use custom text surfaces
that do not accept inserted text; the text is then copied to the clipboard.

### Termux

Termux's terminal area is not a standard text field. Swipe the extra-keys row (`ESC`, `CTRL`,
...) to switch to Termux's native text input box and dictate there.

## Building locally (Windows)

Requirements: JDK 21, the Android SDK command-line tools with `platforms;android-36` and
`build-tools;36.0.0`, and Python for the helper scripts.

```bash
echo 'sdk.dir=D:/Android/Sdk' > local.properties   # your SDK path
python scripts/make_keystore.py                     # once; skip if signing.properties exists
./gradlew.bat testReleaseUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

A local build gets a `versionCode` one above the release it is based on, so it installs over
that release, and the next release installs over it.

### Signing

Every build is signed with one keystore. Android only upgrades an installed app when the new
APK has the same signing key; a differently signed build must be uninstalled first, which
deletes its settings.

- Locally, `app/build.gradle.kts` reads the gitignored `signing.properties` written by
  `scripts/make_keystore.py` (keystore at `.signing/release.jks`).
- In CI, the same values come from repository secrets, pushed with
  `python scripts/push_signing_secrets.py` (values are never printed).

Back up `.signing/release.jks` and `signing.properties`; losing them means reinstalling.

## Releases

CI builds and tests an unsigned APK for every pull request (`.github/workflows/android-build.yml`).
Every merge to `main` runs semantic-release (`.github/workflows/release.yml`): a `feat:` or
`fix:` commit cuts a version, tags it, and attaches the signed APK to the GitHub release;
`chore:`, `docs:`, `ci:` and `refactor:` commits do not. To stay inside the Actions storage
quota, build artifacts are kept 3 days, and a release deletes its own artifact once the APK is
attached (release assets do not count against the quota). `versionName` is `0.7.0`, and it is
owned by semantic-release, so never edit it by hand. `versionCode` is derived from it by
`scripts/version_code.sh`.

## Code map

| File | Role |
|---|---|
| `WhisperAccessibilityService.kt` | Floating button, gestures, and the dictation state machine |
| `Recorder.kt` | 16 kHz mono microphone capture, 10-minute cap (lower on the lock screen) |
| `LockScreenLimits.kt` | 2-minute and 5-recording limits while the phone is locked |
| `AacEncoder.kt` | PCM to AAC `.m4a` for upload |
| `LiveTranscription.kt` | Live mode: Realtime API WebSocket stream, 16 to 24 kHz resampling |
| `DictationPipeline.kt` | Transcribe, clean up, plausibility guard, grammar fix; timing log |
| `OpenAiClient.kt` | Transcription, Responses API cleanup, model list, connection prewarm |
| `TextInjector.kt` | Find the field, insert at the cursor, replace a range, undo, clipboard fallback |
| `FixGrammarActivity.kt` | **Fix grammar** in the text-selection menu (`ACTION_PROCESS_TEXT`) |
| `MainActivity.kt` | Settings screen |
| `Usage.kt` | Usage counter and list-price cost estimate |
| `AppSettings.kt`, `SecretStore.kt`, `Prompts.kt` | Settings, encrypted API key, cleanup prompts |

## Future plans

Ideas, not commitments, roughly in order of usefulness:

- **Per-dictation style:** pick a prompt on the fly (casual, formal email, shell command)
  instead of only the default one in settings.
- **Hide the button in chosen apps** (games, video, banking), or while no keyboard is open.
- **Translate mode:** dictate in one language and insert another (for example Spanish in,
  English out).
- **Live partial transcripts** shown on screen while you talk (live mode already streams; the
  partial text is not displayed yet).
- **Google Play (internal testing):** the accessibility disclosure is already in place; this
  still needs Play's accessibility permission declaration and review.

Known limitations:

- Needs an internet connection and an OpenAI API key; there is no offline mode.
- Apps with custom text surfaces may not accept inserted text; the clipboard is the fallback.
- Installing a build signed with a different key requires uninstalling first, which resets
  settings and the stored key.

See [CHANGELOG.md](CHANGELOG.md) for what each release changed.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
