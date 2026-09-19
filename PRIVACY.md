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

## Data collection

The app has no backend and collects no accounts, analytics, or recordings. OpenAI processes the
data you send according to its own terms and privacy policies.
