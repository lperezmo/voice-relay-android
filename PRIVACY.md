# Privacy Policy for Phone Whisper Android

Phone Whisper Android is a personal Android dictation app that records speech, transcribes it,
and inserts the result into text fields across apps. It is based on
[kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper).

## Data handling

Recorded audio is sent directly from the device to OpenAI's transcription API to generate text.
There is no on-device speech recognition.

If optional cleanup is enabled, the transcribed text is also sent directly from the device to
OpenAI to improve punctuation, capitalization, and clarity.

When you use the grammar fix (long-press on the floating button, or **Fix grammar** in the
text-selection menu), the selected text, or the focused field's text when nothing is selected,
is sent directly from the device to OpenAI and the corrected text is returned.

Cleanup and grammar-fix requests are sent with `store: false`. The app's logs contain sizes and
timings only, never your text.

## API keys

Your OpenAI API key is stored on your device in app storage and used only to authenticate
requests sent directly to OpenAI. There is no relay server.

## Accessibility Service

The app uses Android Accessibility Service only to identify the currently focused text field and
insert or fix text after you explicitly interact with the floating overlay button. It does not
monitor browsing, collect screen content for analytics, or perform background automation.

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
