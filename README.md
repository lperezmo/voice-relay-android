<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Voice Relay logo">
</p>

# Voice Relay

[![Build](https://img.shields.io/github/actions/workflow/status/lperezmo/voice-relay-android/release.yml?branch=main&label=build)](https://github.com/lperezmo/voice-relay-android/actions/workflows/release.yml)
[![Release](https://img.shields.io/github/v/release/lperezmo/voice-relay-android)](https://github.com/lperezmo/voice-relay-android/releases)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Android 11+](https://img.shields.io/badge/Android-11%2B-3ddc84)

**Dictate into any app on your Android phone, using your own OpenAI API key.** A small button
floats over every app. Double-tap it, speak, tap again, and the text appears where you are typing,
without switching away from your keyboard. Long-press it to fix the grammar of text you typed.

It also has a few extras, all off until you turn them on:

- **"Hey claude" wake word**, or a phrase of your own. Say it from anywhere, even with the screen
  off, and the Claude app's voice assistant opens, so you can talk to Claude hands-free and let it
  use the phone. The listening runs entirely on the phone.
- **Fix grammar** in every app's text-selection menu.
- **Claude for cleanup**: OpenAI stays the speech to text, and Claude (with your own Anthropic
  key) cleans up the transcript and fixes grammar instead of OpenAI's text model.
- **An arrow pad** for remote desktop sessions and terminals, where a phone keyboard has no
  arrow keys.
- **Volume presets** (Silent + Do Not Disturb for an hour, Mute, Medium, Loud) on one gesture.
- **Glyph pill**: opens the shape canvas of Glyph (a separate shape-launcher app) from below the mic, when Glyph is installed.

There is no server in between and no account to create: the app talks straight to OpenAI with
your key. Dictation needs an internet connection; the wake word does not.

Architecture overview: https://lperezmo.github.io/voice-relay-android/ (published once the
repository is public; until then, open [`docs/index.html`](docs/index.html) locally).

Voice Relay is based on [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper)
(Apache-2.0, see [NOTICE](NOTICE)). On the phone it shows up as **Voice Relay**, with a teal
icon. It pairs with [Phone Relay](https://github.com/lperezmo/phone-relay-android), which lets
Claude use the phone: say "hey Claude", ask, and Claude can act on it.

Voice Relay is an independent project. It is not affiliated with, endorsed by, or sponsored by
Anthropic or OpenAI. "Claude" and "OpenAI" are trademarks of their owners and are named only to
describe what the app works with.

## What you need

- An Android phone on **Android 11 or newer** with a 64-bit ARM processor (arm64-v8a), which is
  nearly every phone from the last several years. The app is tested on a Samsung Galaxy S25.
- An **OpenAI account with API access and billing set up**, and an
  [API key](https://platform.openai.com/api-keys). This is separate from a ChatGPT
  subscription. A dedicated key with a monthly spend limit is a good idea (see
  [SECURITY.md](SECURITY.md)).
- For the wake word: the [Claude app](https://play.google.com/store/apps/details?id=com.anthropic.claude).
  Without it, the wake word opens your default assistant instead.

**What it costs.** You pay OpenAI directly for what you use. With the default model,
transcription is about $0.0045 per minute of audio, and cleanup or a grammar fix adds a small
fraction of a cent each. Ten minutes of dictation a day comes to roughly $1.50 a month. Live
transcription (off by default) costs about $0.017 per minute. The app keeps its own estimate in
**Settings > Usage**; your OpenAI usage page is the actual bill.

## Install

1. On the phone, download `voice-relay-vX.Y.Z.apk` from the
   [latest release](https://github.com/lperezmo/voice-relay-android/releases/latest) and open
   it. Allow installs from your browser or file manager when Android asks.
2. Open **Voice Relay**, allow the **microphone**, and tap **API key** to paste your OpenAI key.
3. Tap **Accessibility service**, read the explanation, and turn on Voice Relay.

   **On Android 13 and newer the switch may be greyed out** ("Restricted setting"). Android does
   this for apps installed from a browser or file manager. Go to **Settings > Apps > Voice Relay**,
   open the three-dot menu at the top right, tap **Allow restricted settings**, confirm, and go
   back to the accessibility switch.

The status line at the top of the app says **Ready** when the setup is done, and the floating
button appears.

Or install from a computer with [adb](https://developer.android.com/tools/adb), which also
avoids the restricted-setting step:

```sh
adb install -r voice-relay-vX.Y.Z.apk
```

Updates install over the previous version and keep your settings, as long as both came from this
repository's releases (see [Signing](#signing-and-forks)).

## Permissions

| Permission | Needed for | Asked when |
|---|---|---|
| Microphone | Dictation and the wake word | First launch |
| Accessibility service | The floating button, and typing into other apps. It watches which text field is focused so it knows where to type, and reads a field's text only when you dictate into it, fix its grammar, or undo a change there | Setup step 3 |
| Notifications | The wake word's "listening" notification (Android 13+) | Turning on the wake word |
| Display over other apps | Letting the wake word open Claude from the background or the lock screen. Nothing is drawn with it | Turning on the wake word |
| Do Not Disturb access | The volume presets | Tapping the row in the Volume presets section |
| Alarms and reminders | Ending the one-hour Do Not Disturb preset on time (optional) | Tapping the row in the Volume presets section |
| Input method (Voice Relay arrows) | Sending arrow keys and Enter for the arrow pad (optional) | Enabled by hand in the system keyboard settings |
| Internet | Requests to OpenAI | Granted at install |

Why a keyboard, and why accessibility: see [Why does it register a keyboard?](#why-does-it-register-a-keyboard)
and [SECURITY.md](SECURITY.md).

## Using it

| Gesture on the floating button | What happens |
|---|---|
| **Double-tap** (idle) | Start recording. The button turns red and pulses. A single tap does nothing, so a stray finger cannot start one. |
| **Tap** (recording) | Stop. The audio is transcribed, cleaned up, and inserted at the cursor. |
| **Long-press** (idle) | Fix the grammar of the selected text, or the whole focused field when nothing is selected. |
| **Tap** (amber with a warning badge, after a failure) | Retry the same recording. Nothing is lost on a network error, and the recording survives an app restart, update or reboot. |
| **Long-press** (gray, working) | Gives up on a transcription or grammar fix that is taking too long. The recording is kept: the button turns amber for a retry. |
| **Long-press** (amber) | Asks to discard the failed recording; tap the red bubble to confirm. |
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

If cleanup fails or changes the text implausibly (for example, it answered a dictated question
instead of cleaning it up), the raw transcript is inserted instead.

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
accessibility service at all. It accepts at most 10 requests a minute, because any app can call
it.

## Wake word

Off by default. Turn on the **Wake word** switch (its title shows the phrase, "hey claude" until
you change it) and say the phrase anywhere, from any app or with the screen off, to open Claude's
voice assistant (the same overlay as holding the assistant key). With "claude" in the phrase,
"cloud" and "clod" work too, for accents where Claude comes out that way. It is
meant for hands-free use, such as asking Claude to change the music while driving.

**Wake phrase** changes what it listens for. Type a phrase, such as "hey jarvis", and the app
spells it as the model's phones with the bundled
[CMU Pronouncing Dictionary](https://github.com/cmusphinx/cmudict), shows them, and restarts the
listener with it; whatever the phrase, it opens Claude. Every pronunciation the dictionary lists
for each word is added as a variant (up to 16 lines per phrase), and any phrase with "claude" keeps
the "cloud" and "clod" variants. A word the dictionary lacks is named, and **Advanced** takes the
phones by hand instead (ARPAbet as in CMUdict, such as `HH EY1 JH AA1 R V AH0 S`, one
pronunciation per line, at most 16); they are used only while Advanced is open, and changing the
phrase's words clears them. **Generate with AI** there asks your cleanup model for 2 to 8 ways to
say the phrase (Auto, English or Spanish; for Spanish, the native pronunciation and how an English
speaker says it), approximated with the model's phones. Only the phrase's text is sent, with your
key; the answer goes through the same checks, fills the field for you to review or edit, and is
saved only when you tap **Save**. Every phone is checked against the model before it is saved, and
every pronunciation needs a vowel (a lone "S" or "SH" would fire on almost anything). If a saved
phrase ever stops fitting the model, the app listens for "hey claude" and says so. Phrases of
2 to 4 syllables with distinct sounds work best; very short or common ones trigger by accident.
**Reset to default** goes back to "hey claude".

How it works:

- A foreground service keeps the microphone open and feeds it, 100 ms at a time, to a small
  keyword-spotting model ([sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), about 5 MB) that
  runs on the phone. Nothing is recorded, stored, or sent anywhere. A notification shows while it
  listens, with a **Stop** button.
- When it hears the phrase it lets go of the microphone and opens Claude with
  `android.intent.action.VOICE_ASSIST` (falling back to your default assistant if Claude is not
  installed). Claude's overlay opens in text chat since its 2026-10-29 update, so the
  accessibility service then taps its **Voice Mode** button (found by its label, for up to
  2.5 seconds); if the button never shows, Claude stays in text chat.
- It stays quiet while anything else uses the microphone: Claude's whole conversation, phone
  calls, and the dictate button. It starts listening again 1 second after the microphone is
  free, and never sooner than 4 seconds after a detection.
- **Resume music after Claude** (on by default): Claude pauses your music when it opens and does
  not always restart it. If music was playing when you said the phrase, the app presses play
  once Claude is done, unless something is already playing.
- **Sensitivity** runs from 1 (strict) to 7 (loose); the default 5 is a detection threshold of
  0.20. Raise it if it misses you, lower it if it fires by mistake. Words that sound like the
  phrase ("hey clown", "okay cloudy" for "hey claude") can trigger it at any setting.
- **Display over other apps** is what Android requires before an app may open another app from
  the background. Grant it when asked; Voice Relay draws nothing with it.

After a reboot or an app update it comes back on its own when Android reconnects the
accessibility service. If Android refuses to start it at that moment, a notification says so, and
one tap turns it back on.

### Turning it on and off from Tasker

Use **Send Intent** with **Target: Activity** (an activity, not a broadcast, because Android only
lets the microphone start while the app is in the foreground; the activity is invisible and
finishes at once, including over the lock screen):

| Field | Value |
|---|---|
| Action | `com.lperezmo.voicerelay.WAKE_ON`, `com.lperezmo.voicerelay.WAKE_OFF`, `com.lperezmo.voicerelay.WAKE_TOGGLE`, `com.lperezmo.voicerelay.CLAUDE_VOICE` (opens Claude straight into voice mode, as the wake word does), or `BUTTONS_HIDE` / `BUTTONS_SHOW` / `BUTTONS_TOGGLE` (same prefix; hides or shows every floating button) |
| Package | `com.lperezmo.voicerelay` |
| Class | `com.lperezmo.voicerelay.WakeControlActivity` |
| Target | Activity |

The same from a computer:

```sh
adb shell am start -a com.lperezmo.voicerelay.WAKE_ON -n com.lperezmo.voicerelay/.WakeControlActivity
```

`WAKE_ON` only works after the wake word has been turned on once in the app, which is where its
permissions are granted; before that it is ignored, so another app cannot switch the microphone
on by itself. `WAKE_OFF` always works.

### Hiding the buttons for a screen recording

Add the **Voice Relay buttons** tile to Quick Settings. Tapping it hides the dictate button and
every pill at once, and tapping again brings them back. A recording in progress is finished
first. Hiding is not saved, so the buttons come back if the service restarts. If the service is
off (a force stop turns it off), the tile reads **Service off, tap to fix** and opens
Accessibility settings.

The app has the same switch under **Status**: **Show the floating buttons**.

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

Arrow keys can only reach another app through a keyboard, so the pad comes with one. Enable
**Voice Relay arrows** once in the system keyboard settings (the **Arrows keyboard** row in the app
links there). By default, touching the knob switches to it and your own keyboard comes back 4
seconds after the last arrow, or at once when you tap into a text field. Turn **Switch keyboard
automatically** off to switch by hand with a long-press on the ring.

Where the arrows keyboard is not selected, the pad falls back to moving the caret through
accessibility. That works in ordinary text fields, but not in a remote desktop canvas.

### Why does it register a keyboard?

Injecting key events into another app is a system-only privilege that an accessibility service
does not have; a keyboard may send keys through its `InputConnection`. So the pad ships with one,
`KeysInputMethodService`, which

- **draws nothing and takes no screen space**, so it is not something you can type on, and
- **types nothing and reads nothing.** The only thing it asks its `InputConnection` to do is
  `sendKeyEvent` for the arrows and Enter. It does not read the text around the cursor, the
  selection, or the field, and logs nothing. It is 55 lines:
  [`KeysInputMethodService.kt`](app/src/main/kotlin/com/lperezmo/voicerelay/KeysInputMethodService.kt).

Android shows its usual warning that a keyboard can collect what you type when you enable it.
That warning is generic and applies to every keyboard; this one has no code that could.

## Volume presets

Off by default. Turn on **Show the volume pill** and a small oval rides on top of the dictate
button, showing which preset is on. Tap it to open a four-stop track:

| Stop | System, notifications, ringtone | Media | Ringer | Do Not Disturb |
|---|---|---|---|---|
| **DND** | 0 | 0 | Silent, no vibration | On for 1 hour (alarms still ring) |
| **Mute** | 0 | 0 | Vibrate | Off |
| **Med** | 40% | 5% | Normal | Off |
| **Loud** | 100% | 20% | Normal | Off |

A preset is picked with a V drawn in one stroke: press, dip down about 20dp to unlock (the edge
lights up in the target's color), slide sideways to a stop, then come back up about 16dp to apply
it. Releasing while still low cancels, and taps on the track do nothing. The track closes itself
6 seconds after it was last touched, on a tap outside, and when the screen turns off.

When the DND hour ends, only DND turns off; the volumes stay at 0 and the pill reads **Sil**. It
reads **Cust** when a volume was changed by hand and no preset matches. Percentages are rounded
to the stream's steps (15 on a Galaxy S25), and anything above 0% gets at least one step.

The presets need **Do Not Disturb access**. The **End the DND hour on time** row allows exact
alarms; without it Android may end the hour up to 10 minutes late. Alarm and call volume are never
touched.

## Glyph pill

When Glyph (a separate shape-launcher app) is installed, a small **Glyph** pill (a gold star and "Glyph") rides under the
dictate button, mirroring the volume pill above it. Tap it, or press it and pull down, and Glyph's
see-through canvas opens over the current app (the pull fires as soon as the finger has moved about
12dp down). Lift, then draw your shape on the canvas. The pill sparkles gold when it fires.

It opens Glyph with the `com.lperezmo.glyph.OPEN_CANVAS` intent, so it needs a Glyph build that
has it; with no Glyph, or an older one, the pill stays hidden. Turn it off with **Show the Glyph
pill**. A swipe down on the mic itself is not a trigger, because any drag there moves the button.

## Settings

| Setting | Default | Notes |
|---|---|---|
| API key | none | Stored encrypted with an Android Keystore key. |
| Live transcription | off | Streams the audio to OpenAI's Realtime API (`gpt-live-transcribe`) while you talk, so the text is ready about 0.5 s after you stop, however long you spoke. About $0.017/min instead of $0.0045/min. If the stream fails, the same recording goes through the standard upload. |
| Transcription model | `gpt-transcribe` | The picker lists the speech models your key can use, plus **Other** for any model id. |
| Languages | empty (auto-detect) | Two-letter codes, such as `en, es`. Sent as hints so mixed-language dictation is recognized. |
| Vocabulary | empty | Names and terms to spell exactly, one per line or comma-separated; a whole list can be pasted in. Put the ones you say most first: the transcription model gets the first ones that fit about 800 characters (its hint is short), and the cleanup and grammar-fix instructions get all of them, with an instruction never to swap one for a word that sounds like it ("bun", not "bon"). |
| Cleanup service | OpenAI | **Claude, by Anthropic** sends cleanup and grammar fixes to Claude instead. Speech to text always stays on OpenAI. See [Claude for cleanup](#claude-for-cleanup). |
| Anthropic API key | none | Shown when Claude is the cleanup service. Stored encrypted like the OpenAI key. |
| Cleanup model | `gpt-6-luna`, or `claude-sonnet-5-5` for Claude | Also used for grammar fixes, with the lowest reasoning effort for speed. The picker lists the models the selected service's key can use. |
| Clean up transcript | on | Fixes grammar, punctuation, and filler words after transcription. |
| Cleanup prompt | Simple | **Dev** also turns spoken commands into shell commands; **Custom** is your own text. |
| Grammar fix prompt | built-in | Used by the long-press fix and the **Fix grammar** selection menu. "Use default" puts the built-in one back. |
| Wake word | off | The row's title shows the phrase. See [Wake word](#wake-word). |
| Wake phrase | hey claude | Any phrase the dictionary knows, or phones typed by hand or generated with AI. **Reset to default** puts "hey claude" back. |
| Sensitivity | 5 of 7 | Wake word detection threshold, 0.40 (1) to 0.10 (7). |
| Resume music after Claude | on | Presses play once Claude is done, if music was playing when the wake word fired. |
| Show the arrow pad | off | See [The arrow pad](#the-arrow-pad). |
| Switch keyboard automatically | on | Switches to the arrows keyboard and back on its own. |
| Show the volume pill | off | See [Volume presets](#volume-presets). |
| Show the Glyph pill | on | Hidden anyway while Glyph is not installed. See [Glyph pill](#glyph-pill). |

### Claude for cleanup

OpenAI always does the speech to text. To have Claude do the cleanup and the grammar fix instead:

1. Get an API key from the [Claude Console](https://platform.claude.com/).
2. In **Cleanup**, tap **Cleanup service** and choose **Claude, by Anthropic**. Paste the key when
   it asks (or tap **Anthropic API key** later).
3. Optionally tap **Cleanup model** to switch between Haiku 5.5 (fastest and cheapest), Sonnet 5.5
   (the default) and Opus 5.5 (most careful, costs the most), or pick any other Claude model your key can use.

The same prompts and vocabulary are used. If the Claude call fails, is refused, or no Anthropic
key is set, dictation inserts the raw transcript with a note, as it does when OpenAI cleanup fails;
the transcript is not sent to OpenAI's text model instead. Switch back to **OpenAI** at any time.

The **Usage** section counts dictations, minutes of audio, cleanup and grammar-fix calls, and an
estimated cost for this month and since the last reset (tap it to reset). **About > Open-source
licenses** shows the app's license and the notices for the software it includes.

## Privacy and security

- **Dictation:** your recording goes directly from the phone to OpenAI for transcription, and
  the transcript to OpenAI for cleanup (if on), or to Anthropic when Claude is the cleanup service.
- **Grammar fix:** the selected text, or the focused field's text, goes to OpenAI, or to Anthropic
  when Claude is the cleanup service.
- **No backend.** Requests go straight to OpenAI (and Anthropic, if you chose Claude) with your
  keys, with `store: false` on OpenAI cleanup and grammar-fix requests. No analytics, no accounts.
- **The wake word and the arrows keyboard send nothing anywhere.**
- **Logs** contain sizes, timings and error messages only, never your audio or text.

Full details: [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md). Please report
vulnerabilities privately, as SECURITY.md describes.

## Troubleshooting

- **The accessibility switch is greyed out.** Allow restricted settings: **Settings > Apps >
  Voice Relay**, three-dot menu, **Allow restricted settings**. See [Install](#install).
- **The floating button is gone after an update.** Turn Voice Relay off and on again in
  **Settings > Accessibility**. Some phones also stop it to save battery: set the app's battery
  use to unrestricted.
- **The text lands on the clipboard instead of the field.** That app's text field does not
  accept inserted text, or no field was focused. Paste it by hand. In Termux, swipe the
  extra-keys row to switch to its text input box and dictate there.
- **The button turns amber with "Failed: ...".** The message is OpenAI's. An invalid or revoked
  key needs replacing under **API key**; a quota or billing message means the OpenAI account is
  out of credit or over its limit. The recording is saved: tap the button to retry, or hold and
  confirm to discard it.
- **The wake word does not open Claude.** Allow **Display over other apps** (the row under the
  wake word switch), and install the Claude app. Check that the "listening" notification shows.
- **The wake word fires by mistake, or misses you.** Lower or raise **Sensitivity**.
- **The wake word stopped after a reboot.** It restarts when Android reconnects the
  accessibility service; if Android blocks that, tap the notification that says so.
- **Logs.** `adb logcat -s VoiceRelay`. Each dictation logs a line such as
  `Dictation: upload audio.m4a 58 KB, transcribe 1340 ms, cleanup 910 ms`.

## Building from source

Requirements: JDK 21, the Android SDK with `platforms;android-36` and `build-tools;36.0.0`,
`bash` and `curl` (Git Bash works on Windows), and Python 3 for the helper scripts.

```sh
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # or set ANDROID_HOME
bash scripts/fetch_sherpa.sh                             # wake word library and model
./gradlew testReleaseUnitTest assembleRelease            # gradlew.bat on Windows
```

`scripts/fetch_sherpa.sh` downloads the pinned keyword-spotting model and checks its SHA-256, and
installs the wake word's native library, both into gitignored folders. The build stops with a
pointer to it when the files are missing.

The native library is sherpa-onnx built from source by CI (`scripts/build_sherpa.sh`, run by the
`sherpa-build` workflow) with only keyword spotting's parts, so it contains no GPL code. The script
downloads the CI build that matches your checkout with the GitHub CLI (`gh`, logged in to an
account that can read this repository). CI keeps that build for 3 days; if there is none, start
one with `gh workflow run sherpa-build.yml --ref <branch>` and run the script again when it
finishes. A fork runs the same workflow in its own Actions. The script refuses a library that
contains espeak-ng or is not 16 KB page aligned.

Without a signing key the release build is unsigned and cannot be installed. For a quick build
you can install, use `./gradlew assembleDebug` (signed with your debug key), or create a release
key as below. Then:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release APK is about 31 MB, of which 24 MB is the wake word's native library (sherpa-onnx
with ONNX Runtime, arm64 only) and 5 MB its model.

### Signing and forks

Android only installs an update signed with the same key as the installed app. The official
releases are signed with this repository's key, which is not shared.

- **Your own builds and forks need their own key.** `python scripts/make_keystore.py --dname
  "CN=Your Name"` creates `.signing/release.jks` and `signing.properties` (both gitignored), which
  the build picks up. Back both up: losing them means every install must be uninstalled before
  the next update.
- **For CI on a fork**, copy the same values into your fork's secrets with
  `python scripts/push_signing_secrets.py your-name/your-fork`.
- **A build signed with a different key cannot update the official app**, and the other way
  round. Uninstall first, which clears the settings and the stored API key. To install a fork
  next to the official app, change `applicationId` in `app/build.gradle.kts`.

A local build gets a `versionCode` one above the release it is based on, so it installs over that
release (when signed with the same key), and the next release installs over it.

## Releases

CI builds and tests an unsigned APK for every pull request (`.github/workflows/android-build.yml`).
Every merge to `main` runs semantic-release (`.github/workflows/release.yml`): a `feat:` or
`fix:` commit cuts a version, tags it, and attaches the signed APK, `NOTICE` and
`THIRD_PARTY_NOTICES.md` to the GitHub release; `chore:`, `docs:`, `ci:` and `refactor:` commits
do not. Build artifacts are kept 3 days (30 for a release run, so its failed jobs can be re-run),
and a release deletes its own artifacts once the APK is attached. `versionName` is `0.21.0`, and it is owned by semantic-release, so never edit it by
hand. `versionCode` is derived from it by `scripts/version_code.sh`.

See [CHANGELOG.md](CHANGELOG.md) for what each release changed.

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
| `AnthropicClient.kt`, `TextService.kt` | Claude cleanup through the Messages API; which service and key cleanup and the grammar fix use |
| `Vocabulary.kt` | The vocabulary list: what transcription gets, pasting a list |
| `TextInjector.kt` | Find the field, insert at the cursor, replace a range, undo, clipboard fallback |
| `FixGrammarActivity.kt`, `RateLimiter.kt` | **Fix grammar** in the text-selection menu (`ACTION_PROCESS_TEXT`), at most 10 a minute |
| `JoystickView.kt` | The arrow pad: drag to move, one arrow per push of the knob, double-tap for Enter |
| `KeysInputMethodService.kt` | A keyboard that draws and types nothing; relays the pad's arrows and Enter |
| `VolumePresets.kt`, `VolumeController.kt` | The four presets, the V gesture, and applying them (streams, ringer, DND) |
| `VolumePillView.kt`, `VolumeTrackView.kt` | The pill above the button and the four-stop track |
| `GlyphPillView.kt`, `GlyphGesture.kt` | The Glyph pill below the button, its tap and pull-down, and the sparkle |
| `WakeWord.kt` | Wake word keywords (phrase to phones, variants, checks), sensitivity, and the listen, pause, and resume state machine |
| `WakeWordAi.kt` | Generate with AI: the prompt and schema for suggested pronunciations, and the checks on the answer |
| `WakeWordService.kt` | Wake word foreground service: mic, keyword spotter, opening Claude, resuming music |
| `WakeControlActivity.kt` | Invisible activity behind the Tasker `WAKE_ON` / `WAKE_OFF` / `WAKE_TOGGLE` / `CLAUDE_VOICE` intents |
| `assets/wake/cmudict.txt.gzip` | CMUdict, to spell a typed wake phrase as the model's phones (`HH EY1 K L AO1 D`); built by `scripts/build_cmudict.py` |
| `MainActivity.kt`, `LicensesActivity.kt` | Settings screen, and the open-source licenses screen |
| `Usage.kt` | Usage counter and list-price cost estimate |
| `AppSettings.kt`, `SecretStore.kt`, `Prompts.kt` | Settings, encrypted API keys, cleanup prompts |

## Known limitations

- Dictation needs an internet connection and an OpenAI API key; there is no offline mode.
- The wake word keeps the microphone open, which costs battery (about a tenth of one CPU core),
  and can be set off by speech, podcasts, or songs that sound like the wake phrase.
- Apps with custom text surfaces may not accept inserted text; the clipboard is the fallback.
- arm64 phones only, because the wake word's native library is built for arm64.

Ideas for later: picking a prompt per dictation, hiding the button in chosen apps, a translate
mode, and showing live partial transcripts on screen.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Third-party software included
in the app is listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
