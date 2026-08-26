# XX-Calendar

> It shows you your day. That is the whole pitch.

A cleanroom equivalent of Google Calendar. It syncs with Google — because that
is where the invitations arrive — and then refuses to do the other ninety
things Google Calendar does at you. No auto-added flight bookings, no
illustrated dinner events, no Goals, no "what's new" takeover, no Meet button
welded to every event.

<img src="docs/images/screenshot.png" width="270" alt="XX-Calendar on a Pixel 6, AMOLED Night">

```
package: com.piercingxx.calendar        minSdk 26 (Android 8)
version 0.1.0                           target/compileSdk 35
```

## How it syncs

DAVx⁵ owns the Google account and the OAuth. XX-Calendar is a normal client of
Android's calendar provider, which means it ships with **no `INTERNET`
permission** — machine-checkable rather than a promise:

```bash
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

Calendar read/write, notifications, boot-completed, exact alarms, and the
wake-lock trio Glance pulls in transitively. `INTERNET` is not on the list, and
a CI gate fails the build the day it is. No account, no telemetry. Any CalDAV
server works, including one on your own hardware.

## Defaults with a spine

Notification preview, heads-up alerts, and declined events are **off** by
default. Every switch in Settings switches something; the few that can't yet are
absent, not decorative. Launch reopens the view you closed, off the same
`default view` key the Settings row shows — one key, nothing to drift. The `+`
button has one entry. No guests, no RSVP, no tasks, no Goals, and none of
Google's *Reminders* entity; the reminder alarms this app schedules are local
notifications with a bundled chime.

Widgets stay dark whatever theme is active. Deliberate, not a bug: Glance paints
on the Ink ground and keeps doing so under Paper and Mist. Everything else
follows XX-Launcher's `xx.launcher.THEME_CHANGED` broadcast — pick once in the
launcher, the whole phone follows. There is no in-app picker.

## Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=$HOME/tools/jdk-21.0.12.1+1
./gradlew testDebugUnitTest lint assembleDebug assembleDebugAndroidTest
```

JDK 21 on Gradle 8.11.1, JVM target 17, AGP 8.9.1, Kotlin 2.1.20, Robolectric
4.13 for the provider fakes. Built for GrapheneOS. No Play Services.

## Status 🧪

Feature-complete against the plan and installed on a Pixel 6 under GrapheneOS.
326 unit tests green, `lint` clean, both debug builds green, APK provably free
of `INTERNET`.

**The provider layer is unproven against real DAVx⁵ data.** The instrumented
suites compile but have never run against the device, which is the difference
between "compiles" and "works". That, plus an on-phone review of the
[sigil mock](design/mock/sigil-mock.html), is the open list —
[review.md](review.md) and [todo.md](todo.md) have the detail.

## More

| Doc | What it is |
|---|---|
| [design.md](design.md) | The spec — architecture, data model, UI, build order |
| [todo.md](todo.md) | The build plan — workstreams and gates |
| [design/google-calendar-teardown.md](design/google-calendar-teardown.md) | Every Google Calendar feature, and why it stayed or went |

Layout and information architecture were reimplemented from published
screenshots and documentation. No Google source, assets, or branding are used —
see the teardown, §1. [LICENSE](LICENSE) — all rights reserved.
