# Security

Voice Relay types into other apps and records your voice, so it asks for more access than most
apps. This page lists what it can reach, what protects it, and what you are trusting.

## What it can access

| Access | Why | When it is used |
|---|---|---|
| Microphone | Dictation, and the optional wake word | While you record; always while the wake word is on (a notification shows) |
| Accessibility service | The floating button, and typing into other apps; with Ask the Page, one screenshot per canvas | Always while enabled: it watches which text field is focused in every app, and reads a field's text only when you dictate into it, fix its grammar, or undo a change there. It declares `canTakeScreenshot`, used only when Ask the Page is on: one screenshot as the built-in Glyph canvas opens, kept in memory and cleared when the canvas closes. At the same moment it notes the name, package name and window title of the app under the canvas (never its text), sent with the question as text |
| Input method (the arrows keyboard) | Sending arrow keys and Enter for the optional arrow pad | Only while you switch to it. It has no keys, reads no text and has no network code |
| Display over other apps | Lets the wake word open Claude from the background or the lock screen | Only when the wake word fires. Nothing is drawn with it |
| Notifications | The wake word's foreground-service notification | While the wake word listens |
| Do Not Disturb access, exact alarms | The optional volume presets (Silent + DND for an hour) | Only when you pick a preset |
| Installed apps (launcher apps) | The built-in Glyph canvas's **Open an app** action and its app picker | When you pick an app for a shape, or a shape opens one. Read on the phone only |
| Internet | Requests to OpenAI, and to Anthropic if Claude does the cleanup or Ask the Page is on | Dictation, cleanup, grammar fix, Ask the Page, and the model lists |

The floating button, arrow pad, volume pill and Glyph pill are accessibility overlays, not
"display over other apps" windows.

## What protects it

- **No backend.** The app talks only to OpenAI (`api.openai.com`), and to Anthropic
  (`api.anthropic.com`) when you choose Claude for cleanup or turn on Ask the Page, directly from the phone, with your own
  API keys. There is no server of ours in between and no analytics.
- **The API keys are encrypted on the phone.** `SecretStore.kt` encrypts them with AES-GCM under a
  key that lives in the Android Keystore and never leaves it. App backup is off.
- **The wake word stays on the phone.** Its audio is processed in memory by an on-device model and
  never stored, logged or sent anywhere. When it hears "hey claude" (or the wake phrase you chose)
  it opens the Claude app's voice assistant, and from then on the Claude app handles your speech.
  A phrase you type is turned into phones on the phone, with a bundled dictionary.
- **Ask the Page sends only what you circle, or the whole screen when you ask for it.** Its images
  are your handwriting on black and, for each lit loop, the screen inside the loop with everything
  outside it black, or, when you tap the whole-screen button for that question, one picture of the
  whole screen instead. The button turns itself off after every send. A typed question
  goes as text instead of (or with) the handwriting image, and is never stored or logged. The screenshot they
  are cut from is taken once when the canvas opens, never stored or logged, and cleared when the
  canvas closes. Besides the images, one line of text names the app under the canvas and its
  screen title. Off by default, and only available with the built-in canvas on.
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
- **Anthropic, if you choose Claude for cleanup.** The transcript and any text you ask it to fix go
  to Anthropic instead of OpenAI, under your agreement with Anthropic. Your audio does not.
- **Anthropic, if you turn on Ask the Page.** Your handwriting or typed question, the circled parts of the screen (or the whole screen,
  when you tap the whole-screen button for that question), the
  name and screen title of the app under the canvas, and the earlier questions and answers of the
  same page go to Anthropic. Whatever is inside a lit
  loop is sent, so do not circle anything you would not paste into a chat with Claude, and do not
  tap the whole-screen button over anything you would not.
- **Other apps on the phone, for these exported entry points:**
  - **Fix grammar** in the text-selection menu (`FixGrammarActivity`, the `PROCESS_TEXT` intent)
    has to be reachable by every app, so any installed app can send it text and spend your OpenAI
    key (or your Anthropic key, when Claude does the cleanup). The app refuses more than 10 of
    these a minute, and each one shows a "Fixing grammar" dialog. **Use dedicated API keys with a
    monthly spend limit** (in your OpenAI project's limits, and the Claude Console's for Anthropic)
    so a misbehaving app cannot run up a large bill.
  - **Wake word on and off** (`WakeControlActivity`, for Tasker and adb) can be sent by any app.
    Turning it on only works after you have turned the wake word on once in Voice Relay, which is
    where the microphone permission is granted. The effect is bounded: the phone listens for
    its wake phrase locally, with its notification showing, and can open the Claude app. Another
    app could also turn the wake word off, open Claude in voice mode, or hide and show the floating
    buttons. The intents cannot change the phrase. Turn off **Allow other apps to control Voice
    Relay** to have the app ignore all of them (the app's own "tap to listen again" notification
    keeps working through a non-exported alias).
  - **The Glyph launcher icon and Quick Settings tile** (the `.glyph.CanvasLauncher` alias and
    `GlyphTileService`) exist only while **Use the built-in canvas** is on (the default). Any app can open the
    canvas through the launcher icon, as a launcher does; that shows the canvas and, with **Ask the
    Page** on, also takes the one in-memory screenshot described above (dropped when the canvas
    closes). It sends nothing until you write on it. The tile can only be bound by the system. The canvas activity
    itself is not exported.
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
