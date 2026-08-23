# XX-Calendar

Your calendar, on your own hardware, without the parts that nag.

A cleanroom equivalent of Google Calendar. It syncs with Google — because that
is where the invitations arrive — and then refuses to do the other ninety
things Google Calendar does at you. No auto-added flight bookings, no
illustrated dinner events, no Goals, no "what's new" takeover, no Meet button
welded to every event.

## How it syncs

DAVx⁵ owns the Google account and the OAuth. XX-Calendar is a normal client of
Android's calendar provider, which means it ships with **no `INTERNET`
permission** — the manifest does not declare it, and the build fails if it ever
does:

```
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

No account, no network, no telemetry, no analytics. Any CalDAV server works,
including one on your own hardware.

**Defaults with a spine.** Notification content preview, heads-up alerts, the
daily agenda, and declined events are all **off** by default. There are no
guests, no RSVP, no tasks, no reminders, no goals. The `+` button has one entry.

**Status:** built. All twelve planned workstreams are implemented — 247 JVM
unit tests green, `assembleDebug` and `assembleDebugAndroidTest` green, and the
built APK provably declares no `INTERNET` permission (the CI gate fails the
build if it ever appears). Three items stay device-gated — on-phone review of
the [sigil mock](design/mock/sigil-mock.html), WS3's open questions on real
DAVx⁵ data, and the instrumented suite runtime. Details in
[todo.md](todo.md).

| Doc | What it is |
|---|---|
| [design.md](design.md) | The spec — architecture, data model, UI, build order |
| [todo.md](todo.md) | The build plan — workstreams and gates |
| [design/google-calendar-teardown.md](design/google-calendar-teardown.md) | Every Google Calendar feature, and why it stayed or went |

Built for GrapheneOS. No Play Services.

---

Layout and information architecture were reimplemented from published
screenshots and documentation. No Google source, assets, or branding are used —
see the teardown, §1.
