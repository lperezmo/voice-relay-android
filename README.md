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
The ring around the button is a small gauge that shows what Voice Relay is doing (see
[The ring](#the-ring)).

It also has a few extras. The timer pill and the Glyph pill are there from the start; the rest
are off until you turn them on:

- **"Hey claude" wake word**, or a phrase of your own. Say it from anywhere, even with the screen
  off, and the Claude app's voice assistant opens, so you can talk to Claude hands-free and let it
  use the phone. The listening runs entirely on the phone.
- **Fix grammar** in every app's text-selection menu.
- **Claude for cleanup**: OpenAI stays the speech to text, and Claude (with your own Anthropic
  key) cleans up the transcript and fixes grammar instead of OpenAI's text model.
- **An arrow pad** for remote desktop sessions and terminals, where a phone keyboard has no
  arrow keys.
- **Volume presets** (Silent + Do Not Disturb for an hour, Mute, Medium, Loud) on one gesture.
- **"Hey timer"**, a second wake phrase, for timers with your hands busy. Say it, wait for the
  tone, then ask any way you like ("for 10:50 AM", "the pasta, 9 minutes"). OpenAI writes down
  what you said, Claude (with your own Anthropic key) works out the timer, and the phone answers
  out loud: "Timer set for 10:50, 23 minutes from now." It cancels them too: "cancel the pasta
  timer", "cancel all timers".
- **Timer pill**: kitchen timers without the clock app. A small upright tab beside the mic that
  reads "Timer" while nothing runs and shows a countdown while a timer runs. Tap it to see what is
  running, add a minute or stop one, and to start a timer with a V on its track: 5, 10, 15 or 30
  minutes, or your own time.
- **Rename timers** (off by default): a pencil and a microphone next to each timer's name in the
  timer pill's list. Tap the pencil to type a new name, or the microphone to say one ("Pasta");
  OpenAI writes it down, with no Claude and no Anthropic key.
- **Glyph pill**: opens a shape canvas from below the mic: the **built-in Glyph canvas** (the
  default), which needs no other app and also gets its own launcher icon and Quick Settings tile,
  or, with that switched off, the one of Glyph (a separate shape-launcher app) when it is installed.
- **Flip the screen**, on the built-in canvas: give a shape "Flip the screen" and drawing it holds
  the screen the other way (upright or sideways); draw it again to turn it back. It stays that way
  until you draw a shape set to "Rotation back to auto". Needs the floating button on.
- **Ask the Page**, on the built-in canvas: draw ?, handwrite a question, circle what it is about,
  and Claude (with your own Anthropic key) writes the answer on the page in gold cursive.

There is no server in between and no account to create: the app talks straight to OpenAI with
your key. Dictation needs an internet connection; the wake word does not.

Architecture overview: https://lperezmo.github.io/voice-relay-android/

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
| Microphone | Dictation, the wake word, recording a timer request after the timer phrase, and saying a timer's new name | First launch |
| Accessibility service | The floating button, and typing into other apps. It watches which text field is focused so it knows where to type, and reads a field's text only when you dictate into it, fix its grammar, or undo a change there. With Ask the Page on, it also takes one picture of the screen when the Glyph canvas opens, and notes the name and screen title of the app under it (see [Ask the Page](#ask-the-page)) | Setup step 3 |
| Notifications | The wake word's "listening" notification, the timer phrase's spoken answers in writing, and the notification that rings when a timer ends (Android 13+) | The first time you open Voice Relay (for the timer pill, which is on from the start), and turning on the wake word, the timer phrase or the timer pill |
| Display over other apps | Letting the wake word open Claude from the background or the lock screen. Nothing is drawn with it | Turning on the wake word |
| Do Not Disturb access | The volume presets | Tapping the row in the Volume presets section |
| Alarms and reminders | Ending the one-hour Do Not Disturb preset on time, and ringing timers on time | Granted at install on Android 13 and up. On Android 12, tapping the row in the Volume presets or Timers section (optional) |
| Input method (Voice Relay arrows) | Sending arrow keys and Enter for the arrow pad (optional) | Enabled by hand in the system keyboard settings |
| Internet | Requests to OpenAI, and to Anthropic for Claude cleanup, Ask the Page and the timer phrase | Granted at install |

Why a keyboard, and why accessibility: see [Why does it register a keyboard?](#why-does-it-register-a-keyboard)
and [SECURITY.md](SECURITY.md).

## Using it

| Gesture on the floating button | What happens |
|---|---|
| **Double-tap** (idle) | Start recording. The mark turns red and a red arc fills the ring once a minute. A single tap does nothing, so a stray finger cannot start one. |
| **Tap** (recording) | Stop. The audio is transcribed, cleaned up, and inserted at the cursor. |
| **Long-press** (idle) | Fix the grammar of the selected text, or the whole focused field when nothing is selected. |
| **Tap** (amber arc with a warning badge, after a failure) | Retry the same recording. Nothing is lost on a network error, and the recording survives an app restart, update or reboot. |
| **Long-press** (white arc spinning, working) | Gives up on a transcription or grammar fix that is taking too long. The recording is kept: the button turns amber for a retry. |
| **Long-press** (amber) | Asks to discard the failed recording; tap the red bubble to confirm. |
| **Drag** | Move the button; it snaps to the nearest edge. Its side and height are remembered, also across restarts, and kept on screen after a rotation. |
| **Tap the undo chip** | A one-line chip ("Inserted", a hairline, an undo arrow) sits beside the button for 5 seconds after text is inserted or fixed. Tap it to put the field back as it was, unless you have edited it since. |

### The ring

The ring around the button shows what is going on, with no extra pixels:

| Ring | Means |
|---|---|
| Plain, with 12 ticks | Idle. |
| Red arc filling clockwise, red mark, a thin ring moving inside | Recording. The arc fills once a minute. |
| Short white arc spinning | Transcribing (or fixing grammar). |
| Green flash | The text landed. |
| Amber arc, still, with a badge | The transcription failed; tap to retry. |
| Whole ring glowing clay, clay mark | "Hey claude" was heard and Claude is opening. |
| Blue flash, blue mark, a thin blue ring moving inside | "Hey timer" was heard and it is listening. |
| White arc spinning, then a clay arc sweeping | "Hey timer" is writing down what you said, then Claude is working out the timer. |
| Gold flash, then the whole ring glowing clay | The timer is set and the answer is being said out loud. |
| Thin gold arc inside the ticks | The next timer to end; it shrinks as the time runs out. |
| Whole ring flashing gold | A timer finished. It stays gold until you stop it (with the timer pill off, only while the timer rings, up to 10 minutes). |

**Dim when idle** (Settings, Floating buttons, off by default) fades the button and its pills 3
seconds after you last touch them, while nothing is going on. A touch or anything on the ring
brings them back.

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
you change it) and say the phrase from any app to open the Claude app on a new chat in voice
mode. With "claude" in the phrase,
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
- When it hears the phrase it lets go of the microphone and opens a new chat in the Claude app
  by link (falling back to your default assistant if Claude is not installed). Claude has no way
  for another app to start voice directly, so the accessibility service then presses its
  **Voice Mode** button (found by its label, for up to 6 seconds); if the button never shows,
  Claude stays in text chat. With the phone locked, Claude opens once you unlock.
- It stays quiet while anything else uses the microphone: Claude's whole conversation, phone
  calls, and the dictate button. It starts listening again 1 second after the microphone is
  free, and never sooner than 4 seconds after a detection.
- Android does not always say when it takes the microphone away (another app holding it, or the
  microphone switch in Quick Settings turned off); it hands over silence instead. When Android
  marks the recording silenced, or the audio has been nothing but zeros for 5 seconds, the
  notification says it **cannot hear right now**, and goes back to "Listening" as soon as sound
  returns.
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

### Timers by voice

Off by default. **Timers by voice** (in the Wake word card) turns on a second wake phrase with
its own switch, "hey timer" until you change it under **Timer phrase**. The wake word keeps opening Claude;
this one does the work inside Voice Relay. Both are heard by the same listener at the same time,
with the same sensitivity, and either can be on without the other. It works with the screen
locked.

> "Hey timer." (tone) "Set a timer for 10:50 AM."
> A few seconds later: "Timer set for 10:50, 23 minutes from now."

1. The phone plays a short tone and records what you say next, until you have been quiet for
   about a second and a half (15 seconds at most). Music pauses while it works.
2. The recording goes to OpenAI's transcription, the same one dictation uses, with your speech
   model.
3. The text goes to Claude (**Timer model**, Opus 5.5 unless you change it) together with the
   phone's date, time and time zone. Claude works out how long the timer must run and sets it;
   the app itself never reads times. So any wording works: "10 minutes", "an hour and a half",
   "until quarter past", "the pasta, 9 minutes" (which names the timer Pasta).
4. The answer is said out loud and shown in a notification. The timer shows in the
   [timer pill](#timer-pill) with the others, where you can add a minute or stop it.

It cancels timers the same way: "cancel the pasta timer", "stop the timer", "cancel all timers".
Claude gets the names and times left of your timers with the request, so it can tell which one you
mean; if it can't (say, two timers and "cancel the timer"), it asks which. Cancelling a finished
timer also stops it ringing. With no timers it says "There are no timers to cancel."

A time that has already passed today means tomorrow, so the phone asks first ("That's tomorrow at
10:50, 23 hours from now. Set it?"), plays the tone again and waits for your yes or no. If it
hears no time, or nothing at all for six seconds, it says "I didn't catch a time" and sets
nothing; with no internet it says so. Saying the phrase again while it is still working on a
request does nothing.

Each phrase has its own Tasker intents: `WAKE_ON` / `WAKE_OFF` / `WAKE_TOGGLE` for the wake word
and `TIMER_ON` / `TIMER_OFF` / `TIMER_TOGGLE` for the timer phrase. Turning one off leaves the
other listening, so send both off intents to close the microphone. **Stop** on the notification
turns both off. Like the wake word, the timer phrase must be turned on once in the app before an
intent can turn it on.

It needs both keys: OpenAI for the speech and Anthropic for Claude. With one missing it does
nothing, and tells you once in a notification. Each request is one transcription and a few short
Claude requests, counted in **Settings > Usage**, and there is a limit of 100 requests a day so a
phrase that misfires cannot run up a bill.

### Turning it on and off from Tasker

Use **Send Intent** with **Target: Activity** (an activity, not a broadcast, because Android only
lets the microphone start while the app is in the foreground; the activity is invisible and
finishes at once, including over the lock screen):

| Field | Value |
|---|---|
| Action | `com.lperezmo.voicerelay.WAKE_ON`, `com.lperezmo.voicerelay.WAKE_OFF`, `com.lperezmo.voicerelay.WAKE_TOGGLE`, `com.lperezmo.voicerelay.TIMER_ON`, `com.lperezmo.voicerelay.TIMER_OFF`, `com.lperezmo.voicerelay.TIMER_TOGGLE` (the same three for the timer phrase), `com.lperezmo.voicerelay.CLAUDE_VOICE` (opens Claude straight into voice mode, as the wake word does), or `BUTTONS_HIDE` / `BUTTONS_SHOW` / `BUTTONS_TOGGLE` (same prefix; hides or shows every floating button) |
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

Any app on the phone can send these intents, not only Tasker. If you do not use them, turn off
**Allow other apps to control Voice Relay** (under the wake word settings, on by default): every
action is then ignored, and the log says so.

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
button, showing which preset is on with its short name and four level bars (none lit for DND, one
for Mute, two for Medium, all four for Loud). Tap it to open a four-stop track:

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

## Timer pill

On by default: a third small pill rides with the dictate button, upright beside it on the side
facing the middle of the screen, between the volume and Glyph pills (turn it off with **Timer pill**
in Settings, Floating buttons, Timers). Voice Relay runs these timers itself, so it can list them, extend
them and stop them; they are not the clock app's timers.

- **Tap the pill** to open the timer panel toward the middle of the screen. On top, every timer,
  soonest first, with a **+1** button (a minute more) and an **×** button that stops it in one
  tap ("No timers running." when there are none). Below, the timer track with **5m**, **10m**,
  **15m**, **30m** and **Own**. A touch outside, or another tap on the pill, closes it.
- **Start a timer with a V on the track**, like the volume track: press, dip down, slide to a time
  and rise. A plain tap on the track, or dipping and letting go, starts nothing, so a timer cannot
  start by accident. Holding or dragging the pill does nothing.
- **Own** opens a small card for a time of your own: −5, −1, a minutes field you can type in, +1,
  +5, then Cancel or Start.
- **Rename a timer** (with **Rename timers** on in Settings, Floating buttons, Timers): each timer
  in the list has a pencil and a microphone after its name. The pencil opens a small card with the
  name to edit, then Cancel or Save. The microphone listens for a short name, stops by itself when
  you stop talking, and OpenAI writes it down (it needs your OpenAI key and is not counted in the
  "hey timer" daily limit). If something else is using the microphone it says "The microphone is
  busy."
- The pill is an upright tab with a small gold ring and "Timer" while nothing runs. While a timer
  runs it shows the countdown of the timer that ends first ("9:12", "1h05", read from the bottom
  up) and how many there are, with a thin gold line up its outer side that shrinks as that timer
  runs out. When one finishes the pill turns gold and blinks until you
  dismiss it, the phone rings on the alarm volume, and a notification offers **Stop**.

Timers are kept on the phone and survive the buttons being hidden, the service restarting and a
reboot (after a reboot they come back once the phone is unlocked). Two optional rows help them
ring properly: **Ring with a Stop button** needs notifications allowed (without them a finished
timer only rings for a minute), and **Ring on time** shows whether exact alarms are allowed. On
Android 13 and up they are, from install; on Android 12 tap the row to allow them, or a timer can
ring late while the phone sleeps. A finished timer rings for up to ten minutes; swiping its
notification away silences it, and it stays in the pill's list until you dismiss it there.

## Glyph pill

A small **Glyph** pill (a gold star and "Glyph") rides under the dictate button, mirroring the
volume pill above it. Tap it, or press it and pull down, and a see-through shape canvas opens over
the current app (the pull fires as soon as the finger has moved about
12dp down). Lift, then draw your shape on the canvas. The pill sparkles gold when it fires.

By default the pill opens the [built-in canvas](#the-built-in-glyph-canvas). With **Use the
built-in canvas** turned off, it opens Glyph with the `com.lperezmo.glyph.OPEN_CANVAS` intent, so
it needs a Glyph build that has it; with no Glyph, or an older one, the pill stays hidden. Turn it off with **Show the Glyph pill**. A swipe down on the
mic itself is not a trigger, because any drag there moves the button.

### The built-in Glyph canvas

On by default (the separate Glyph app is being retired). With **Use the built-in canvas** on
(under **Show the Glyph pill**) the pill opens Voice Relay's own copy of the Glyph canvas, so the
Glyph app is not needed; the pill then shows whether Glyph is installed or not. It works the same way: draw a shape, and the action
you gave it runs (open an app or a link, send the clipboard to Phone Relay, turn the wake word on
or off, print a date label, flip the screen or set rotation back to auto). **Shapes and settings** is where you teach shapes, pick what each one
does and tune recognition. The shapes and settings are the built-in canvas's own; they are not
copied from the Glyph app.

While the switch is on, the canvas also gets:

- a **Glyph** icon in the app drawer, with the gold spiral (opened from it, the canvas also shows a
  **Shapes and settings** handle at the bottom), and
- a **Glyph canvas** Quick Settings tile that closes the shade and opens the canvas (from the lock
  screen it asks you to unlock first).

They appear the first time Voice Relay or the canvas opens after install, and disappear again when
you turn the switch off. To list apps for **Open an app**, the canvas
can see every installed app that has a launcher icon (see [PRIVACY.md](PRIVACY.md)).

### Ask the Page

Off by default, and only there while the built-in canvas is on. Turn on **Ask the Page**, and give
it an Anthropic API key when it asks (the same key as [Claude for cleanup](#claude-for-cleanup)).
Then:

1. Open the canvas and draw **?** (the shape whose action is Ask the Page). The canvas stays open
   and becomes a page.
2. Handwrite your question anywhere on it.
3. To show Claude part of the screen, draw a large closed loop around it: the loop becomes a
   **lens**, and the screen inside it lights up. Tap inside a loop to switch it between lens and
   plain ink. Up to 4 lenses at a time.

   Or tap the whole-screen button near the top-left corner to show Claude the entire screen
   instead: the dim lifts everywhere and a thin gold border runs round the edge. The lenses are
   not needed then (new loops stay ink, and lenses you lit wait for when you turn it off). It is
   for one question only: it turns itself off as soon as the question is sent.
4. Stop writing. After 2.5 seconds (a gold ring fills; more ink starts it over) the page is sent.
   The ring only fills once there is enough writing to be a question: a few dots, one dash or a
   small squiggle are not sent, and after the same pause the page says **Write a question first**.
   The marks stay on the page.

Rather type? Tap the keyboard button in the bottom-left corner of the page. A text field opens
above your keyboard (any keyboard, with swipe typing and voice input). Type the question and tap
Send; lenses you drew still go with it, and handwriting you also wrote goes too when it is enough
to be a question. While the field is open nothing is sent by itself. Back, a tap on the keyboard
button in the field, or (after an answer) a tap on the page puts the field away, keeping any ink.

The answer is written out on the page in gold cursive, letter by letter. **Copy** puts the question
and the answer on the clipboard. Write again to ask a follow-up in the same conversation; closing
the canvas ends it.

What goes to Anthropic is your handwriting, drawn as light strokes on black, or the question you
typed, as text, and, for each lit lens, the part of the screen inside the loop with everything
outside it blacked out, or, when you tapped the whole-screen button for that question, one picture
of the whole screen instead. Nothing else of the screen is sent. What you type is not stored or logged. To have something to light up, the accessibility service takes one picture
of the screen as the canvas opens (before the canvas dims it). That picture is kept only in memory,
never written to storage or the logs, and dropped when the canvas closes. Without Ask the Page on,
no picture is taken.

So Claude knows where you are, the question also carries one line of text naming the app under the
canvas and its screen title, such as `The reader is in the app "Modes and Routines"
(com.samsung.android.app.routines), on the screen "Driving".` The page's hint says **Asking about**
that app, so you can see it is shared. Only the app's name, package and window title are read,
never anything written in the app; they are kept in memory with the picture and dropped with it.
Opened from the Quick Settings tile, the canvas takes no picture and names no app, since the shade
could still be in the way. Each question is one request to Claude Sonnet 5.5 with a few small images,
billed to your Anthropic account.

## Settings

| Setting | Default | Notes |
|---|---|---|
| API key | none | Stored encrypted with an Android Keystore key. |
| Live transcription | off | Streams the audio to OpenAI's Realtime API (`gpt-live-transcribe`) while you talk, so the text is ready about 0.5 s after you stop, however long you spoke. About $0.017/min instead of $0.0045/min. If the stream fails, the same recording goes through the standard upload. |
| Transcription model | `gpt-transcribe` | The picker lists the speech models your key can use, plus **Other** for any model id. |
| Languages | empty (auto-detect) | Two-letter codes, such as `en, es`. Sent as hints so mixed-language dictation is recognized. |
| Vocabulary | empty | Names and terms to spell exactly, one per line or comma-separated; a whole list can be pasted in. Put the ones you say most first: the transcription model gets the first ones that fit about 800 characters (its hint is short), and the cleanup and grammar-fix instructions get all of them, with an instruction never to swap one for a word that sounds like it ("bun", not "bon"). |
| Cleanup service | OpenAI | **Claude, by Anthropic** sends cleanup and grammar fixes to Claude instead. Speech to text always stays on OpenAI. See [Claude for cleanup](#claude-for-cleanup). |
| Anthropic API key | none | Shown when Claude is the cleanup service, and under Ask the Page when it is on. One key for both. Stored encrypted like the OpenAI key. |
| Cleanup model | `gpt-6-luna`, or `claude-sonnet-5-5` for Claude | Also used for grammar fixes, with the lowest reasoning effort for speed. The picker lists the models the selected service's key can use. |
| Clean up transcript | on | Fixes grammar, punctuation, and filler words after transcription. |
| Cleanup prompt | Simple | **Dev** also turns spoken commands into shell commands; **Custom** is your own text. |
| Grammar fix prompt | built-in | Used by the long-press fix and the **Fix grammar** selection menu. "Use default" puts the built-in one back. |
| Wake word | off | The row's title shows the phrase. See [Wake word](#wake-word). |
| Wake phrase | hey claude | Any phrase the dictionary knows, or phones typed by hand or generated with AI. **Reset to default** puts "hey claude" back. |
| Sensitivity | 5 of 7 | Wake word detection threshold, 0.40 (1) to 0.10 (7). |
| Timers by voice | off | The second wake phrase, "hey timer": sets or cancels a timer by voice. Needs both API keys. See [Timers by voice](#timers-by-voice). |
| Timer phrase | hey timer | Same editor and checks as the wake phrase. |
| Timer model | Opus 5.5 | The Claude model that works out how long the timer runs. |
| Resume music after Claude | on | Presses play once Claude is done, if music was playing when the wake word fired. |
| Allow other apps to control Voice Relay | on | Whether Tasker, adb and other apps may send the [control intents](#turning-it-on-and-off-from-tasker). |
| Dim when idle | off | Fades the button and its pills while nothing is going on. See [The ring](#the-ring). |
| Show the arrow pad | off | See [The arrow pad](#the-arrow-pad). |
| Switch keyboard automatically | on | Switches to the arrows keyboard and back on its own. |
| Show the volume pill | off | See [Volume presets](#volume-presets). |
| Timer pill | on | See [Timer pill](#timer-pill). |
| Rename timers | off | Shown while the timer pill is on. A pencil and a microphone next to each timer's name, to type or say a new one. The microphone needs your OpenAI key. |
| Show the Glyph pill | on | Hidden anyway while Glyph is not installed, unless the built-in canvas is on. See [Glyph pill](#glyph-pill). |
| Use the built-in canvas | on | The pill opens Voice Relay's own Glyph canvas; adds the **Glyph** launcher icon and Quick Settings tile. See [The built-in Glyph canvas](#the-built-in-glyph-canvas). |
| Ask the Page | off | Shown while the built-in canvas is on. Draw ? and write a question; Claude answers on the page. See [Ask the Page](#ask-the-page). |

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
estimated cost for this month and since the last reset (tap it to reset). Timer requests by voice
are counted too: each recording as a dictation and each Claude request with the cleanups. **About > Open-source
licenses** shows the app's license and the notices for the software it includes.

## Privacy and security

- **Dictation:** your recording goes directly from the phone to OpenAI for transcription, and
  the transcript to OpenAI for cleanup (if on), or to Anthropic when Claude is the cleanup service.
- **Grammar fix:** the selected text, or the focused field's text, goes to OpenAI, or to Anthropic
  when Claude is the cleanup service.
- **No backend.** Requests go straight to OpenAI (and Anthropic, if you chose Claude) with your
  keys, with `store: false` on OpenAI cleanup and grammar-fix requests. No analytics, no accounts.
- **Ask the Page:** your handwriting or the question you typed, the circled parts of the screen
  (or the whole screen, when you tap the whole-screen button for that question), and the name and screen title of the app under the canvas (as text) go to Anthropic. The one picture of the screen it
  takes when the canvas opens stays in memory and is gone when the canvas closes.
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
  If it says it **cannot hear right now**, another app has the microphone or microphone access is
  off in Quick Settings.
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
./gradlew testDebugUnitTest assembleRelease            # gradlew.bat on Windows
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
Every merge to `main` runs `.github/workflows/release.yml`: a `feat:` or `fix:` commit cuts a
version; `chore:`, `docs:`, `ci:` and `refactor:` commits do not. The order is: semantic-release
works out the version in a dry run, the merged commit is tested and built as that version and
signed, and only then does semantic-release tag it and write the GitHub release, to which the
signed APK, `NOTICE` and `THIRD_PARTY_NOTICES.md` are attached. A failing test means no tag. Build artifacts are kept 3 days (30 for a release run, so its failed jobs can be re-run),
and a release deletes its own artifacts once the APK is attached. `versionName` is `0.31.0`, and it is owned by semantic-release, so never edit it by
hand. `versionCode` is derived from it by `scripts/version_code.sh`.

See [CHANGELOG.md](CHANGELOG.md) for what each release changed.

## Code map

| File | Role |
|---|---|
| `WhisperAccessibilityService.kt` | Floating button, gestures, and the dictation state machine |
| `ButtonRing.kt`, `ButtonRingView.kt` | The ring around the button: which state shows when (dictation, wake words, "hey timer", timers), its colours and timings, dimming when idle; and the view that draws it |
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
| `VolumePillView.kt`, `VolumeTrackView.kt`, `VTrackView.kt` | The pill above the button and the four-stop track; the V track drawing and touch it shares with the timer track |
| `TimerAssistant.kt`, `TimerVoice.kt`, `TimerVoiceFlow.kt` | "Hey timer": Claude's tools and the loop that runs them, when the speaker has stopped, the daily limit, and the glue to the mic, the network and the speaker |
| `Timers.kt`, `TimerController.kt` | The timers Voice Relay runs itself: the list and its rules, saving, alarms, ringing |
| `TimerPillView.kt`, `TimerTrackView.kt`, `TimerCards.kt`, `PillSkin.kt` | The timer pill, its track of times, its panel and minutes card; the look all pills share |
| `GlyphPillView.kt`, `GlyphGesture.kt` | The Glyph pill below the button, its tap and pull-down, and the sparkle |
| `glyph/` | The built-in Glyph canvas: the canvas, shape recognizer (`recognizer/`), actions, Shapes and settings, the launcher icon alias and `GlyphTileService.kt`, and Ask the Page (lenses, the screen picture, the images sent, the cursive answer) |
| `WakeWord.kt` | Wake word keywords (phrase to phones, variants, checks), sensitivity, and the listen, pause, and resume state machine |
| `WakeWordAi.kt` | Generate with AI: the prompt and schema for suggested pronunciations, and the checks on the answer |
| `WakeWordService.kt` | Wake word foreground service: mic, keyword spotter, opening Claude, resuming music |
| `MicHearing.kt` | Whether the wake word's mic hears anything (silenced by Android, or only zeros), for its notification |
| `WakeControlActivity.kt` | Invisible activity behind the Tasker `WAKE_ON` / `WAKE_OFF` / `WAKE_TOGGLE` / `TIMER_ON` / `TIMER_OFF` / `TIMER_TOGGLE` / `CLAUDE_VOICE` / `BUTTONS_*` intents, off when other apps may not control Voice Relay |
| `assets/wake/cmudict.txt.gzip` | CMUdict, to spell a typed wake phrase as the model's phones (`HH EY1 K L AO1 D`); built by `scripts/build_cmudict.py` |
| `MainActivity.kt`, `LicensesActivity.kt` | Settings screen (wiring and permission requests), and the open-source licenses screen |
| `SettingsUi.kt`, `SettingsCard.kt` | The settings screen's view builders (cards, rows, switch, radio and status rows) and what each card is given |
| `StatusCards.kt`, `SpeechCard.kt`, `CleanupCard.kt`, `WakeWordCard.kt`, `FloatingButtonsCard.kt`, `MoreCards.kt` | One settings card each: status and setup, OpenAI, cleanup and the grammar fix, wake word, floating buttons, and other apps, usage and about |
| `SettingsDialogs.kt`, `WakePhraseDialog.kt` | The settings screen's dialogs: keys, models, languages, vocabulary, prompts, and the wake phrase editor |
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
