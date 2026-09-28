# Security

Voice Relay types into other apps and records your voice, so it asks for more access than most
apps. This page lists what it can reach, what protects it, and what you are trusting.

## What it can access

| Access | Why | When it is used |
|---|---|---|
| Microphone | Dictation, and the optional wake word | While you record; always while the wake word is on (a notification shows) |
| Accessibility service | The floating button, and typing into other apps | Always while enabled: it watches which text field is focused in every app, and reads a field's text only when you dictate into it, fix its grammar, or undo a change there |
| Input method (the arrows keyboard) | Sending arrow keys and Enter for the optional arrow pad | Only while you switch to it. It has no keys, reads no text and has no network code |
| Display over other apps | Lets the wake word open Claude from the background or the lock screen | Only when the wake word fires. Nothing is drawn with it |
| Notifications | The wake word's foreground-service notification | While the wake word listens |
| Do Not Disturb access, exact alarms | The optional volume presets (Silent + DND for an hour) | Only when you pick a preset |
| Internet | Requests to OpenAI | Dictation, cleanup, grammar fix, and the model list |

The floating button, arrow pad and volume pill are accessibility overlays, not "display over other
apps" windows.

## What protects it

- **No backend.** The app talks only to OpenAI (`api.openai.com`), directly from the phone, with
  your own API key. There is no server of ours in between and no analytics.
- **The API key is encrypted on the phone.** `SecretStore.kt` encrypts it with AES-GCM under a key
  that lives in the Android Keystore and never leaves it. App backup is off.
- **The wake word stays on the phone.** Its audio is processed in memory by an on-device model and
  never stored, logged or sent anywhere. When it hears "hey claude" it opens the Claude app's voice
  assistant, and from then on the Claude app handles your speech.
- **Logs are metadata only.** Sizes, timings, which method inserted the text, and error messages
  from OpenAI. Never your audio, your transcripts, or the text of any field.
- **Clipboard copies are marked sensitive**, so they stay out of clipboard previews and keyboard
  clipboard history.
- **Limits on the lock screen.** A recording started while locked stops after 2 minutes, and after
  5 recordings the button asks you to unlock.
- **Releases are signed**, and only built from tagged commits on `main` by GitHub Actions.
  Pull-request builds never see the signing key.

## What you are trusting

- **OpenAI.** Your audio, the transcript (when cleanup is on) and any text you ask it to fix go to
  OpenAI and are handled under your agreement with them. Cleanup and grammar-fix requests are sent
  with `store: false`.
- **Other apps on the phone, for two exported entry points:**
  - **Fix grammar** in the text-selection menu (`FixGrammarActivity`, the `PROCESS_TEXT` intent)
    has to be reachable by every app, so any installed app can send it text and spend your OpenAI
    key. The app refuses more than 10 of these a minute, and each one shows a "Fixing grammar"
    dialog. **Use a dedicated OpenAI API key with a monthly spend limit** (set it in your OpenAI
    project's limits) so a misbehaving app cannot run up a large bill.
  - **Wake word on and off** (`WakeControlActivity`, for Tasker and adb) can be sent by any app.
    Turning it on only works after you have turned the wake word on once in Voice Relay, which is
    where the microphone permission is granted. The effect is bounded: the phone listens for
    "hey claude" locally, with its notification showing, and can open the Claude app. Another app
    could also turn the wake word off.
- **The Claude app.** Once the wake word opens it, what it can do on your phone is up to the
  Claude app and the permissions you gave it, not Voice Relay.
- **The build.** Official APKs come from this repository's GitHub releases. The wake word's native
  library is built by this repository's CI from the sherpa-onnx source at a pinned tag and commit
  (`scripts/build_sherpa.sh`), linking a prebuilt ONNX Runtime checked against a pinned SHA-256
  hash; the model is downloaded by `scripts/fetch_sherpa.sh` from the sherpa-onnx releases and
  checked against a pinned SHA-256 hash.

## Reporting a problem

Please report vulnerabilities privately through GitHub's
[private vulnerability reporting](https://github.com/lperezmo/voice-relay-android/security/advisories/new)
rather than in a public issue.
