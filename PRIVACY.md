# Privacy Policy for Voice Relay

Voice Relay is a personal Android dictation app that records speech, transcribes it,
and inserts the result into text fields across apps. It is based on
[kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper).

## Data handling

Recorded audio is sent directly from the device to OpenAI's transcription API to generate text.
Dictation has no on-device speech recognition; the optional wake word (below) is the only speech
processing that happens on the phone.

While a recording is being sent, a copy of it is kept in the app's private storage so a failed
upload can be retried, even after the app restarts. The copy is deleted as soon as the recording
is transcribed, or when you discard it (long-press the amber button, then confirm). Only the one
most recent untranscribed recording is ever kept.

If optional cleanup is enabled, the transcribed text is also sent directly from the device to
OpenAI to improve punctuation, capitalization, and clarity. If you choose **Claude, by Anthropic**
as the cleanup service, it is sent to Anthropic instead, with your own Anthropic API key; the audio
still goes only to OpenAI.

When you use the grammar fix (long-press on the floating button, or **Fix grammar** in the
text-selection menu), the selected text, or the focused field's text when nothing is selected,
is sent directly from the device to OpenAI, or to Anthropic when Claude is the cleanup service,
and the corrected text is returned.

## Ask the Page (optional, off by default)

Ask the Page works on the built-in Glyph canvas and is off until you turn it on. When it is on and
you draw ? on the canvas, you write a question by hand (or type it in the page's text field), and
can draw loops around parts of the screen to show them to Claude. When you pause (or tap Send on a
typed question), the app sends Anthropic, with your own Anthropic API key:

- your handwriting, drawn as light strokes on a black image, and/or the question you typed, as
  text (typed text is kept only in memory until it is sent, never stored or logged), and
- for each loop you left lit, the part of the screen inside that loop, with everything outside the
  loop line blacked out, or, only when you tap the whole-screen button for that question, one
  picture of the whole screen instead of the loops (the button turns itself off once the question
  is sent, so the next question goes back to the loops), and
- one line of text naming the app under the canvas (its name and package name) and the title of
  its screen, when Android reports them, so Claude knows where you are. The page's hint names the
  app ("Asking about ..."). The app reads only that name, package and window title, never anything
  written in the app, keeps them in memory with the screenshot, and clears them with it. Opened
  from the Quick Settings tile, the canvas names no app.

Nothing else of the screen is sent. A retry after a failed request sends the same images again. To have something to show inside a loop, the accessibility
service takes one screenshot when the canvas opens, before the canvas draws over the screen. That
screenshot is kept only in the app's memory: it is never written to storage, never logged, and it
is cleared when the canvas closes. Follow-up questions on the same page send the text of the
earlier questions and answers with the new images; that conversation is also kept only in memory
and ends when the canvas closes. **Copy** puts the question and the answer on the clipboard
only when you tap it. With Ask the Page off, the canvas takes no
screenshot and sends nothing.

OpenAI cleanup and grammar-fix requests are sent with `store: false`. Requests to Anthropic are
handled under your agreement with Anthropic. The app's logs contain sizes and timings only, never
your text.

## Wake word (optional, off by default)

When you turn on the wake word, the app keeps the microphone open in a foreground service (with a
notification you can stop it from) and runs a small keyword-spotting model on the phone to hear
"hey claude", or the wake phrase you chose. This audio is processed in memory on the device and
discarded as it goes: it is never recorded to storage, never sent to OpenAI or anywhere else, and
never logged. A phrase you type is stored in the app's settings and turned into phones on the
phone with a bundled pronunciation dictionary; it is not sent anywhere, unless you tap **Generate
with AI** in the wake phrase dialog: then the phrase's text (no audio) is sent to OpenAI with your
key and cleanup model (with `store: false`, like cleanup), which suggests pronunciations; the logs
note only whether that worked and how many suggestions were kept. Beyond that, the logs record only the
wake phrase, which keyword fired, and the detection settings. When the keyword is heard, the app
opens the Claude app in voice mode (the Claude app then handles your speech under Anthropic's terms).

While it listens, the app reads Android's audio state (whether another app is recording, the
audio mode, whether Android has silenced its own recording, and whether music is playing) to pause
while the microphone is in use, to say in its notification when it cannot hear, and, if you allow
it, to press play again after Claude is done. It does not read other apps' audio.

Other apps on the phone (Tasker, adb, or anything installed) can send the app's control intents to
turn the wake word on (only once you have turned it on in the app) or off, open Claude in voice
mode, and hide or show the floating buttons. They carry no data in or out. **Allow other apps to
control Voice Relay** in the settings (on by default) turns all of them off; the app then ignores
them and logs only that it did.

## API keys

Your OpenAI API key, and your Anthropic API key if you set one, are stored on your device,
encrypted with a key held in the Android Keystore, and used only to authenticate requests sent
directly to OpenAI or Anthropic. There is no relay server.

## Accessibility Service

The app uses Android's Accessibility Service to show its floating button and to put text where you
are typing. To know where that is, it is told whenever a text field gains focus or its cursor
moves, in any app, and keeps a reference to the last focused field in memory. It reads a field's
text only when you dictate into it, fix its grammar (that text is then sent to OpenAI, or to Anthropic when Claude is the cleanup
service), or undo a
change there. With Ask the Page on, it also takes one screenshot each time the built-in Glyph
canvas opens (Android's accessibility screenshot, which is why the service declares
`canTakeScreenshot`), and notes the name, package name and screen title of the app under the
canvas; only the circled parts (or the whole screen, when you tap the whole-screen button for that
question) and that app line are sent, as described in
[Ask the Page](#ask-the-page-optional-off-by-default), and both are gone when the canvas
closes. Nothing it sees is written to storage or to the logs. It does not monitor browsing,
collect screen content for analytics, or perform background automation.

## Installed apps

The built-in Glyph canvas lets a shape open any app, so the app can see the list of installed apps
that have a launcher icon (a package-visibility query for launcher activities), and checks whether
a few specific apps its actions hand off to are installed (Glyph, Phone Relay, Fridge Labels and
the Claude app). The list is read on the phone, when you pick an app for a shape or a shape opens
one, and is never sent anywhere or logged.

## Input Method (the arrows keyboard)

The optional arrow pad ships with an input method, `KeysInputMethodService`, because sending arrow
keys to another app is something only a keyboard can do. Android asks you to enable it by hand, and
shows the standard warning that a keyboard may be able to collect what you type. For this one that
warning does not apply in practice:

- It has no input view: it draws nothing and there is nothing on it to type on.
- It never reads your text. The only operation it performs is sending arrow keys and Enter
  (`InputConnection.sendKeyEvent`). It does not read the text around the cursor, the selection, or
  the field's contents, does not track the selection, and writes nothing to the logs.
- It has no network code of any kind, so nothing it handles can leave the device.

While the pad is in use the app switches your keyboard to this one and switches back to yours
about 4 seconds after the last arrow, or immediately when you tap into a text field. Your previous
keyboard's id is stored on the device so it can be restored. A long-press on the pad's ring keeps
the arrows keyboard selected until you switch back yourself.

The whole file is about 55 lines and the repository is the authoritative copy:
[`KeysInputMethodService.kt`](app/src/main/kotlin/com/lperezmo/voicerelay/KeysInputMethodService.kt).

## Data collection

The app has no backend and collects no accounts, analytics, or recordings. OpenAI and Anthropic
process the data you send them according to their own terms and privacy policies.

## Contact

Questions about this policy: open an issue at
[github.com/lperezmo/voice-relay-android/issues](https://github.com/lperezmo/voice-relay-android/issues).
To report a security problem, use
[private vulnerability reporting](https://github.com/lperezmo/voice-relay-android/security/advisories/new)
instead of a public issue (see [SECURITY.md](SECURITY.md)).
