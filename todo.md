# XX-Calendar — Build Plan

Spec: [design.md](design.md). Teardown:
[design/google-calendar-teardown.md](design/google-calendar-teardown.md).
Target: Pixel 9 Pro (`caiman`), GrapheneOS, Android 17 / SDK 37.

**Status: nothing built.** The repo holds `design.md`, this file, and the
teardown.

---

## Read this before starting

**There is no network in this app.** DAVx⁵ owns the Google account and the
OAuth; XX-Calendar is a normal `ContentProvider` client and nothing else
(design D2, D3). The manifest does not declare `INTERNET`, and WS12 makes that a
CI gate rather than a claim. If a task ever seems to need a network call, the
task is wrong.

That single decision deletes most of what a calendar app usually is:

**1. There is no sync code.** No OAuth, no token refresh, no incremental sync
tokens, no conflict resolution, no retry policy, no `WorkManager`. DAVx⁵ writes
to the provider as a sync adapter; this app writes to it as a client, the
provider marks the row dirty, and DAVx⁵ pushes it. That is the whole
integration.

**2. There is no database.** `CalendarContract` is the system of record
(design D1). No Room, no entities, no DAOs, no migrations. The only persistent
app state is settings and the calendar→sigil map, in `DataStore`.

**3. There is no scheduling surface.** No guests, no RSVP, no invitations, no
tasks, no todos (teardown §3.4 and §3.7, both resolved to *nothing*). The `+`
button has one entry. An invitation from someone else renders as an ordinary
event.

### Where the real work actually is

Three places, and they are not the ones a calendar project usually front-loads:

| Risk | Where | Why |
|---|---|---|
| **The sigil scheme** | WS1 | Hue is the information channel a calendar normally uses, and the brand forbids it. If `▌ ▏ ░ ▒` does not read in a dense month grid, three views get rebuilt. |
| **Recurring-event edits** | WS7 | This/following/all writes are where calendar clients silently corrupt data. `ScopeResolver` exists so every case is a JVM test before it touches a real event. |
| **Reminder reliability** | WS8 | The one hard requirement (R7). Reconcile from the provider on a schedule; never chase broadcast events. |

Everything else is rendering.

### What the app does *not* fix

Hiding is not removing (design §4.5). Gmail's auto-added flights and hotels
still sync down and still exist in your Google account — this app declines to
render them. It makes the calendar quiet on this device; it does not clean the
account. Settings says so.

---

## Local setup

`local.properties` is gitignored and must exist before Gradle runs:

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew test assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.piercingxx.calendar/.ui.MainActivity
```

Copy `debug.keystore` from Nope-Mode before the first build, so all PiercingXX
sideloads keep one signing identity (design §14).

Vendor the brand tokens rather than retyping hexes:

```sh
curl -sL https://raw.githubusercontent.com/PiercingXX/piercingxx-branding/main/tokens/android-colors.xml \
  -o app/src/main/res/values/pxx_colors.xml
```

On-device, install DAVx⁵ and add the Google account **before WS3** — WS3's open
questions can only be answered against real synced data.

---

## Status at a glance

| WS | Scope | Blocks on | State |
|---|---|---|---|
| 1 | `core/` + the sigil mock | nothing | **start here** |
| 2 | Skeleton, theme, fonts, icon, permission gate | nothing | not started |
| 3 | `CalendarRepository` — the provider layer | 2 | not started |
| 4 | Schedule view | 1,2,3 | not started |
| 5 | Day + Week time grids | 4 | not started |
| 6 | Month grid + day peek | 4 | not started |
| 7 | Detail sheet, editor, recurring writes | 3,4 | not started |
| 8 | Reminders — reconciler, alarms, boot | 3 | not started |
| 9 | Settings | 4,8 | not started |
| 10 | `.ics` + JSON backup | 3,9 | not started |
| 11 | Widgets, shortcuts, intent filters, theme sync | 4,6 | not started |
| 12 | CI privacy gate + instrumented suite | 3,7 | not started |

WS1 and WS2 are independent. So are WS5 and WS6 once WS4 lands.

---

## WS1 — `core/`, and the decision that gates everything

Pure JVM. No Android imports in this package, ever — that boundary is what makes
the recurrence logic testable (design §5).

- [ ] `RRuleModel` — build/parse the five presets and custom rules (interval,
      by-day, by-month-day, nth-weekday, `UNTIL`/`COUNT`). Round-trip tests.
- [ ] `ScopeResolver` — every row of design §6.3 as a plain data object
      describing the intended provider writes. **The priority suite.**
- [ ] `TimeMath` — all-day UTC-midnight conversion tested at `UTC-11`, `UTC`,
      `UTC+13`, and across DST in both directions.
- [ ] `SigilAssigner` — stable ordering, survives remove/re-add, honours
      overrides.
- [ ] `AgendaGrouping` — day boundaries, multi-day and all-day placement.
- [ ] **The mock.** A static render of a dense week and a dense month with six
      calendars in `▌ ▏ ░ ▒ ▓ ·` at their opacity tiers, on `#000000`, in
      JetBrains Mono. Look at it on the actual phone, at actual size.

**Gate:** the mock reads, or design §7.1 gets revisited before WS4. This is the
cheapest possible moment to discover the scheme fails.

## WS2 — Skeleton

- [ ] Gradle to design §14. Kotlin 1.9.24, Compose compiler 1.5.14, minSdk 26.
- [ ] `CalendarTheme` — tokens vendored from the branding repo, not retyped.
- [ ] Space Mono + JetBrains Mono in `res/font/`. Tabular figures on.
- [ ] Underlined-XX adaptive icon on an ink tile.
- [ ] Permission gate — one screen, one button, no partial UI (design §10).
- [ ] `debug.keystore` from Nope-Mode.

## WS3 — The provider layer, and the two open questions

- [ ] `InstanceQuery` — `Instances.query()` over a range, off the main thread.
- [ ] `CalendarRepository` — calendar list, load/save/delete event, reminders.
- [ ] **`OpaqueColumns`** — every column not in design §6.2 read, held, written
      back untouched (D8). This protects R6 and it is easier to build now than
      to retrofit.
- [ ] `ContentObserver` → invalidate the visible window.
- [ ] Create a local calendar on first run when none is writable (§4.4).
- [ ] **Answer open question 1:** what marks a Gmail auto-added event once it
      has come through DAVx⁵? Inspect real synced rows. If there is no reliable
      marker, fall back to a per-calendar hide and record that in the spec.
- [ ] **Answer open question 2:** measure `Instances` expansion cost over a
      month of a real, busy, heavily-recurring account.

**Gate:** both open questions answered on real data before WS4 builds on them.

## WS4 — Schedule view · first honest milestone

- [ ] Infinite scroll, empty days skipped, `Nothing scheduled.` empty state.
- [ ] Sigil + opacity per calendar. Past events at `shade`.
- [ ] Current-time rule in signal white — one of only two full-white elements.
- [ ] Top bar, `Today` button, mini-month picker, drawer with visibility
      toggles.
- [ ] FAB with **one** action.

**Gate:** the app shows your real days. If it is not pleasant to look at here,
fix it here — not after three more views exist.

## WS5 — Day and Week

- [ ] Time grid, hour rules at `line`, Space Mono hour labels.
- [ ] Event blocks with the sigil bar; all-day pinned header row.
- [ ] Drag to create, drag to move, edge-resize. 15-minute snap.
- [ ] Week: seven columns, today's numeral inverted.

## WS6 — Month

- [ ] 7×N grid, out-of-month at `shade`, today inverted.
- [ ] Up to three chips per cell, then `+N`.
- [ ] Day peek beneath the grid — the month stays visible.
- [ ] Week-number gutter (S3).

## WS7 — Editor · the correctness work

- [ ] Detail sheet: no guest section, no RSVP (teardown §3.4). Conferencing URL
      as text if present; attachment count only.
- [ ] Editor per design §8.5. No location autocomplete, no title suggestion.
- [ ] `RepeatBuilder` — presets plus custom.
- [ ] **`ScopePrompt` + the three recurring writes.** Only for recurring events.
- [ ] Refuse and explain on an unmodelled recurrence shape. Never guess.
- [ ] Undo on delete.

**Gate:** `ScopeResolver` suite green *and* the instrumented round-trip green
before this merges. This is the one that corrupts data.

## WS8 — Reminders

- [ ] `ReminderReconciler` — recompute the next 48h on boot, provider change,
      settings change, and a daily heartbeat. Diff and apply. Never chase
      individual events (design §4.3).
- [ ] `AlarmScheduler` on `setExactAndAllowWhileIdle`.
- [ ] `BootReceiver`, `ReminderReceiver`, notification channels.
- [ ] `canScheduleExactAlarms()` check → the one warn-coloured row in Settings.
- [ ] Quiet defaults: content preview off, heads-up off, daily agenda off.
- [ ] Never notify for a declined event.

## WS9 — Settings

- [ ] The sixteen survivors, laid out as design §8.6.
- [ ] `SettingsStore` on DataStore.
- [ ] Sigil override per calendar.
- [ ] Auto-added-event filter, using whatever WS3 found.
- [ ] The honest sync row: last-changed timestamp plus an intent to DAVx⁵, and
      the sentence saying this app cannot see sync state.

## WS10 — Data

- [ ] `.ics` export (RFC 5545) via SAF, all calendars or one.
- [ ] `.ics` import with `UID` duplicate detection.
- [ ] JSON backup/restore of settings + sigils only. Events are `.ics`.

## WS11 — Surfaces

- [ ] Month and Schedule widgets in Glance.
- [ ] App shortcuts: New event, Today.
- [ ] Intent filters — be the system calendar handler (design §12).
- [ ] `ThemeSyncReceiver` for the XX-Launcher broadcast, as TxxT implements it.

## WS12 — Gates

- [ ] CI: `aapt2 dump permissions` fails the build if `INTERNET` appears.
- [ ] Instrumented: create/edit/delete round-trips, all three recurring scopes.
- [ ] Instrumented: **opaque-column preservation** — load an event with every
      unmodelled column populated, change one field, assert the rest is
      byte-identical.
- [ ] Instrumented: reminder reconciliation after a simulated boot.

---

## v1 ships after WS12

Deferred, in rough order of appeal: `.ics` URL subscription (holidays without a
Google product) · year view · on-device natural-language quick add · secondary
timezone · Quick Settings tile · next-event widget.
