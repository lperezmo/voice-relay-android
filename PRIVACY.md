# Privacy Policy for Phone Whisper Android

Phone Whisper Android is a personal Android dictation app that records speech, transcribes it,
and inserts the result into text fields across apps. It is based on
[kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper).

## Data handling

Recorded audio is sent directly from the device to OpenAI's transcription API to generate text.
Dictation has no on-device speech recognition; the optional wake word (below) is the only speech
processing that happens on the phone.

If optional cleanup is enabled, the transcribed text is also sent directly from the device to
OpenAI to improve punctuation, capitalization, and clarity.

When you use the grammar fix (long-press on the floating button, or **Fix grammar** in the
text-selection menu), the selected text, or the focused field's text when nothing is selected,
is sent directly from the device to OpenAI and the corrected text is returned.

Cleanup and grammar-fix requests are sent with `store: false`. The app's logs contain sizes and
timings only, never your text.

## Wake word (optional, off by default)

When you turn on the wake word, the app keeps the microphone open in a foreground service (with a
notification you can stop it from) and runs a small keyword-spotting model on the phone to hear
"hey claude". This audio is processed in memory on the device and discarded as it goes: it is
never recorded to storage, never sent to OpenAI or anywhere else, and never logged. The logs
record only which keyword fired and the detection settings. When the keyword is heard, the app
opens Claude's voice assistant (the Claude app then handles your speech under Anthropic's terms).

While it listens, the app reads Android's audio state (whether another app is recording, the
audio mode, and whether music is playing) to pause while the microphone is in use and, if you
allow it, to press play again after Claude is done. It does not read other apps' audio.

## API keys

Your OpenAI API key is stored on your device in app storage and used only to authenticate
requests sent directly to OpenAI. There is no relay server.

## Accessibility Service

The app uses Android's Accessibility Service to show its floating button and to put text where you
are typing. To know where that is, it is told whenever a text field gains focus or its cursor
moves, in any app, and keeps a reference to the last focused field in memory. It reads a field's
text only when you dictate into it, fix its grammar (that text is then sent to OpenAI), or undo a
change there. Nothing it sees is written to storage or to the logs. It does not monitor browsing,
collect screen content for analytics, or perform background automation.

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
[`KeysInputMethodService.kt`](app/src/main/kotlin/com/lperezmo/phonewhisper/KeysInputMethodService.kt).

## Data collection

The app has no backend and collects no accounts, analytics, or recordings. OpenAI processes the
data you send according to its own terms and privacy policies.
