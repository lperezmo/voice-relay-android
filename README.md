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
| **Tap the undo chip** | A one-line chip ("Inserted", a hairline, an undo arrow) sits beside the button for 5 seconds after text is inserted or fixed. Tap it to put the field back as it was, unless you have edited it since. |

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

## The arrow pad

Off by default. Turn on **Show the arrow pad** and a second, transparent control appears: a thin
ring with a thin knob in the middle, 92dp across, drawn as hairlines so it does not hide what is
underneath. It sends arrow keys, for remote desktop sessions and terminals where a phone keyboard
has no arrow row.

| Gesture on the pad | What happens |
|---|---|
| **Push the knob** | One arrow, in the direction you pushed. The knob has to come back toward the middle before it sends another, so a push is one key and there is no auto-repeat. Haptic tick per key. |
| **Double-tap the knob** | Enter. A single tap does nothing, so a stray finger is harmless. |
| **Drag the ring** | Move the pad. Its position is remembered. |
| **Long-press the ring** | Switch to the arrows keyboard by hand, and pin it there until you switch back. |

Arrow keys can only reach another app through a keyboard, so the pad comes with one: see
[Why does it register a keyboard?](#why-does-it-register-a-keyboard) below.

## Volume presets

Off by default. Turn on **Show the volume pill** and a small oval rides on top of the dictate
button, showing which preset is on. Tap it to open a four-stop track:

| Stop | System, notifications, ringtone | Media | Ringer | Do Not Disturb |
|---|---|---|---|---|
| **DND** | 0 | 0 | Silent, no vibration | On for 1 hour (alarms still ring) |
| **MUTE** | 0 | 0 | Vibrate | Off |
| **MED** | 40% | 5% | Normal | Off |
| **LOUD** | 100% | 20% | Normal | Off |

Taps on the track do nothing. A preset is picked with a V drawn in one stroke: press, dip down
about 20dp to unlock (the edge lights up in the target's color), slide sideways to a stop, then
come back up about 16dp to apply it. Releasing while still low cancels. The track closes itself
6 seconds after it was last touched, on a tap outside, and when the screen turns off.

When the DND hour ends, only DND turns off; the volumes stay at 0 and the pill reads **SIL**. It
reads **CUST** when a volume was changed by hand and no preset matches. Percentages are rounded
to the stream's steps (15 on a Galaxy S25), and anything above 0% gets at least one step, so 5%
is 1/15.

The presets need **Do Not Disturb access** (a row in the settings screen links to it). The
**End the DND hour on time** row allows exact alarms; without it Android may end the hour up to
10 minutes late. Alarm and call volume are never touched, and neither is the AI assistant
volume: Android lets only system apps set that stream (it needs `MODIFY_AUDIO_ROUTING`).

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
| Cleanup prompt | Simple | **Dev** also turns spoken commands into shell commands; **Custom** is your own text. Your custom text is kept while a preset is selected, and "Edit custom prompt" selects Custom when you save. |
| Grammar fix prompt | built-in | Sent by the long-press fix and the **Fix grammar** selection menu. Separate from the cleanup prompt on purpose: that text was dictated, this text was typed. "Use default" puts the built-in one back. |
| Show the arrow pad | off | The floating arrow pad described above. |
| Show the volume pill | off | The volume presets described above. |
| Switch keyboard automatically | on | Touching the knob switches to the arrows keyboard, and your own comes back 4 seconds after the last arrow. Off means you switch by hand with a long-press on the ring. |

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
screen only at the moment it inserts or fixes text, to find the focused field. It does not run
background automation and does not send screen content anywhere. It never becomes your typing
keyboard, though the arrow pad does switch keyboards temporarily, which the next section explains.

## Why does it register a keyboard?

Only so the arrow pad can send arrow keys. Injecting key events into another app is a system-only
privilege that an accessibility service does not have; a keyboard, on the other hand, may send
keys through its `InputConnection`. So the pad ships with one: `KeysInputMethodService`, which

- **draws nothing and takes no screen space** (`onCreateInputView` returns null and
  `onEvaluateInputViewShown` is false), so it is not something you can type on, and
- **types nothing and reads nothing.** The only thing it ever asks its `InputConnection` to do is
  `sendKeyEvent` for the arrows and Enter. It does not call `getTextBeforeCursor`,
  `getSelectedText`, or `getExtractedText`, does not track the selection, and logs nothing. It is
  55 lines; read it for yourself in
  [`KeysInputMethodService.kt`](app/src/main/kotlin/com/lperezmo/phonewhisper/KeysInputMethodService.kt).

Android still requires you to enable it by hand once, in the system keyboard settings, with the
usual warning that a keyboard can collect what you type. That warning is generic and applies to
every keyboard; this one has no code that could.

**Switching is automatic by default.** Touching the knob switches to the arrows keyboard
(`SoftKeyboardController.switchToInputMethod`, API 30+), remembering the keyboard you were on, and
yours comes back 4 seconds after the last arrow. The restore is armed from every path that can
leave the arrows keyboard selected: touching the knob, every attempt to deliver a key whatever its
outcome, and lifting your finger. On top of that, focusing an editable field hands your keyboard
back at once, since tapping into a text field means you want to type, and the service restores it
on connect, so an update or a crash cannot leave you stranded on a keyboard that types nothing.
A restore that comes due mid-push waits for your finger to lift. Only a deliberate long-press pins
the arrows keyboard; turn **Switch keyboard automatically** off if you would rather always switch
by hand.

Where the arrows keyboard is not selected, the pad falls back to walking the caret through
accessibility instead. That works in ordinary text fields, but not in a remote desktop canvas,
which has no accessibility nodes to walk.

## Privacy

- **Dictation:** your recording is sent directly from the phone to OpenAI for transcription,
  and the transcript to OpenAI for cleanup (if enabled).
- **Grammar fix:** the selected text (or the focused field's text) is sent to OpenAI.
- There is no backend. Requests go straight to OpenAI with your key, with `store: false` on
  cleanup requests.
- Logs contain sizes and timings only, never your text.
- **The arrows keyboard sends nothing anywhere.** It has no network code and reads no text; it only
  relays arrow keys and Enter into the app in front of you.

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

Remote desktop clients and terminals are the opposite case: they take key events but expose no
text field to insert into, which is what the arrow pad is for. Dictation still reaches them
through the clipboard.

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
attached (release assets do not count against the quota). `versionName` is `0.10.1`, and it is
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
| `JoystickView.kt` | The arrow pad: drag to move, one arrow per push of the knob, double-tap for Enter |
| `VolumePresets.kt` | The four presets, step math, reading the phone back as a pill state, and the V gesture |
| `VolumeController.kt` | Applies a preset (streams, ringer, DND) and runs the DND hour as an alarm |
| `VolumePillView.kt`, `VolumeTrackView.kt` | The pill above the button and the four-stop track |
| `KeysInputMethodService.kt` | A keyboard that draws and types nothing; relays the pad's arrows and Enter |
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
