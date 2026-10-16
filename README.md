# doobs

A small Android assistant that sets alarms and reminders from speech. It replaces
the deprecated Google Assistant for the handful of things that were actually
worth asking out loud.

No wakeword and no always-on microphone: doobs listens only when you summon it,
parses what you said on-device, and hands the result to the clock app.

Speech-to-text is the platform's, so whether it leaves the phone is the
platform's call, not doobs'. doobs asks the platform which on-device models are
installed and prefers those, falling back to the network only when the device
has nothing that fits — see [Language packs](#language-packs).

## What it understands

```
set an alarm for 7:30
set an alarm for seven thirty am
set an alarm for 0730                      -- four digits means 24-hour time
wake me up at quarter to eight
set an alarm for 6 am to go running        -- labelled alarm
set an alarm for noon / midnight

remind me at 6 to take the bins out
remind me to call the dentist at 9 am
remind me in 20 minutes to check the oven  -- becomes a labelled timer
remind me in half an hour to stretch
remind me to look at the oven in 10 minutes
set a reminder for 5 pm to call mum
```

Politeness is ignored (`hey doobs`, `please`, `could you`). Anything outside the
grammar is refused out loud rather than guessed at — see
`app/src/test/kotlin/dev/doobs/parse/CommandParserTest.kt` for the exact
contract.

**A bare hour means the next time that hour comes round.** At 9:15am, "set an
alarm for seven" is 7pm; "set an alarm for eleven" is 11am. Say "am"/"pm", or
"in the morning"/"tonight", to be explicit.

## Design

Three decisions carry the whole app:

- **Nothing owns state.** Alarms go to the clock app via `ACTION_SET_ALARM`,
  relative reminders to its timer via `ACTION_SET_TIMER`, both with
  `EXTRA_SKIP_UI`. So there is no database, no exact-alarm permission, no
  boot receiver, no foreground service, no OEM doze fights — and you edit
  everything afterwards in the UI you already know.
- **Parsing is rule-based and offline** (`parse/`). Speech recognisers are
  inconsistent about numbers, so everything is folded to digits
  (`"seven thirty"` → `"7 30"`) before any pattern matching. Pure Kotlin, no
  Android types, so it is tested on the JVM with no device or Robolectric.
- **One UI, three doors.** The launcher icon, `ACTION_ASSIST`, and the assistant
  role all land in `AssistActivity`, which starts listening on open. The
  `VoiceInteractionSession` exists only to hand off to that activity and close.

`compileSdk`/`targetSdk` 35, `minSdk` 26, no third-party dependencies at all.

## Building

Everything lives in a container; the host needs only `podman`.

```sh
./dev image      # build the toolchain image (JDK 21, Android SDK 35, Gradle 8.11.1)
./dev build      # assemble app/build/outputs/apk/debug/app-debug.apk
./dev test       # parser unit tests
./dev check      # tests + lint
./dev gradle …   # anything else
./dev shell      # poke around inside
./dev clean      # drop outputs and the cache volumes
```

The container runs as your UID (`--userns=keep-id`) so build outputs are yours,
not root's. Gradle and SDK caches live in the named volumes `doobs-gradle` and
`doobs-android`.

## Getting it on a phone

Either transport works, and `./dev devices` is how you check what adb can see.

```sh
./dev install    # build + install
./dev run        # build + install + fire ACTION_ASSIST
./dev logcat     # tail doobs' own log tag
```

The SDK, JDK and Gradle never touch the host. adb is the one exception, and
only for USB — see below.

### Testing without speaking

Debug builds accept a typed transcript, which is the only way to exercise the
parser and the clock hand-off from a script — no microphone, and it works even
with the phone locked:

```sh
adb shell am start -n dev.doobs/.AssistActivity \
  -e transcript "'remind me in 20 minutes to check the oven'"
adb logcat -d -s doobs:V
```

The inner quotes matter: `adb shell` re-splits arguments on the device, so
without them only the first word arrives. **This creates real alarms and
timers** — they are not a dry run.

### Over USB — uses the host's adb

This is the one place the host is not kept clean, and it is not by choice: a
**rootless container cannot open `/dev/bus/usb` device nodes**. Passing
`--device=/dev/bus/usb --group-add=keep-groups` is not enough — the group
credential udev requires does not survive into the container (verified on crun:
the group shows up in `id` inside the container, and the node is still
unreadable). So `./dev` shells out to a host `adb` for USB and keeps the
container for everything else. `DOOBS_ADB=host|container` overrides the choice.

Enable Developer options → USB debugging, plug in, and accept the "Allow USB
debugging?" prompt — until you do, the phone lists as `unauthorized`.

**You also have to be in the `adbusers` group.** Arch's `android-udev` rules
give the phone to `root:adbusers` with `other::---`, and that group starts out
empty, so by default *nobody* can reach the phone:

```sh
sudo usermod -aG adbusers "$USER"
```

Group changes only reach new processes. Arch ships no `sg`, so either start a
fresh shell or pipe a command through `newgrp`, which works non-interactively:

```sh
newgrp adbusers <<< './dev install'
```

A stale `adb` server started before the group change keeps its old credentials
and will keep showing nothing — `adb kill-server` first. If `./dev devices` is
still empty:

```sh
id | grep adbusers                      # are you in it, in THIS shell?
ls -l /dev/bus/usb/*/* | grep adbusers  # did udev tag the phone?
lsusb | grep -i android\\\|pixel\\\|debug  # "charging + debug" = interface is exposed
```

A phone plugged in as "charging only" exposes no adb interface at all; if
`lsusb` says `charging + debug`, the interface is there and the problem is
permissions.

### Over wireless debugging

Needs no root and no udev at all. On the phone: Developer options → Wireless
debugging. Then, once:

```sh
./dev pair 192.168.1.50:41234 123456   # "Pair device with pairing code"
./dev connect 192.168.1.50:5555        # the main wireless debugging port
```

`connect` remembers the address in `.dev-device` (gitignored) and everything
afterwards targets it; `./dev usb` forgets it again. `--network=host` is what
lets the container reach the phone.

The container's adb runs its own server on port 5038 so it neither piggybacks
on nor fights a host adb server on the usual 5037.

## Making it the assistant

1. `./dev install`
2. Settings → Apps → Default apps → **Digital assistant app** → doobs
3. Grant the microphone permission the first time it opens.

The assist gesture (long-press power, or the corner swipe) then opens doobs.

If doobs does not appear in that list, the culprit is almost always
`res/xml/interaction_service.xml` or the service declarations it points at —
that's the one piece of platform plumbing that varies by OEM and Android
version. `./dev logcat` plus `adb logcat -s VoiceInteractionManagerService` will
say why.

`DoobsRecognitionService` is a deliberate stub: the platform requires an
assistant to name a `RecognitionService` in its own package, but doobs has no
recogniser of its own — it asks the system's via `SpeechRecognizer` like any
other app. A side effect is that doobs shows up under Settings → Voice input.
**Don't pick it there**; it refuses every request.

## Language packs

Starting the recognizer means committing up front to a language and to
offline-or-not, and both can be wrong in ways that fail instantly — before the
microphone opens. So `Ears` does not guess once; it asks the platform what is
installed, then walks a ladder of ways to ask.

**A locale is not a language tag.** This Pixel's default is
`en-IL-u-mu-celsius` — English, Israel, show temperatures in Celsius. Handed
over whole it gets `ERROR_LANGUAGE_NOT_SUPPORTED` immediately, so extensions
are stripped first.

**Neither is a bare language.** `en` fails too, and SODA says why:

```
W/SodaLPDirGenerator: Returning no LP, as MDD does not support locale: en.
```

On-device recognition wants a full region tag, and there is no `en-IL` model in
existence. What this phone actually has is `en-US` — which no amount of
reasoning from the locale would ever produce. So on API 33+ `Models` asks
outright, via `checkRecognitionSupport`:

```
installed = [en-US]
supported = [… en-CA, en-IN, en-AU, en-GB, en-IE, en-SG …]   ← no en-IL
pending   = []
```

An installed model that matches the locale's *language* goes to the top of the
ladder, ahead of anything derived from the locale itself:

| # | language | offline |
|---|---|---|
| 1 | `en-US` — because the phone says it has it | yes |
| 2 | `en-IL` | yes |
| 3 | `en` | yes |
| 4 | recognizer's default | yes |
| 5–8 | the same four | no — network allowed |

Rung 1 wins, and speech never leaves the phone. Where no model is installed but
one is supported, `triggerModelDownload` asks for it and the user is told to
try again shortly; on pre-33 phones none of this exists and the ladder runs as
before, guessing from the locale.

The first summon in a fresh process spends up to 300ms waiting for that report
before planning, because the answer changes the entire ladder; later summons
read a cached copy and pay nothing. The budget exists so a recognizer that
never calls back cannot cost us the microphone.

The ladder climbs only on *setup* errors — `LANGUAGE_NOT_SUPPORTED`,
`LANGUAGE_UNAVAILABLE`, `SERVER_DISCONNECTED`, `SERVER`,
`CANNOT_CHECK_SUPPORT` — never on "you didn't speak", which would make silence
thrash through every rung instead of saying so. Keying on the category rather
than specific codes matters more than it looks: the same rung returns different
codes on different runs, so a fix keyed to "rung 2 returns 12" is a coin flip.
An earlier version retried only on language errors, hit `SERVER_DISCONNECTED`
from the dead offline recognizer, and stopped one rung short of the attempt
that worked.

`./dev logcat` prints the full ladder and which rung won on every summon.

## Known gaps

- "remind me in the morning" (no hour) is refused rather than guessed at 8am.
- "tomorrow" is dropped; the clock app already picks the next occurrence, so it
  is right for alarms and wrong for anything further out than a day.
- Reminders are alarms and timers, so they have a clock app's UX, not a
  to-do list's. If that chafes, the fix is a real reminder store —
  `AlarmManager` plus notifications — behind the same `Actions` seam.
- English only.
- Summoning doobs from a locked phone refuses rather than listening. The
  keyguard draws above our sheet, so listening there would mean an open
  microphone behind a screen showing no sign of it. The refusal is a toast,
  since only toasts draw over the keyguard.
- Speech leaves the phone only when no on-device model fits — see
  [Language packs](#language-packs).
