# XX-Calendar — Design Specification

Android app for GrapheneOS. A calendar that renders your days and then leaves
you alone.

A cleanroom equivalent of Google Calendar. It is a renderer and editor over the
platform calendar provider — DAVx⁵ owns the Google account and the network, so
this app ships with **no `INTERNET` permission** and no way to phone anywhere.
Everything Google Calendar does *at* you is absent by construction.

**Status:** specification. Nothing built.
**Teardown:** [design/google-calendar-teardown.md](design/google-calendar-teardown.md) — the full 120-feature ledger and the reasoning behind every cut.
**Build plan:** [todo.md](todo.md) — workstreams, gates, and the order to do them in.
**Target:** Pixel 9 Pro (`caiman`), GrapheneOS, Android 17 / SDK 37.
**Sync:** DAVx⁵ → Google, via `CalendarContract`. Not this app's problem.

---

## 1. Cleanroom provenance

See [teardown §1](design/google-calendar-teardown.md#1-cleanroom-provenance) for
the full statement, the not-copied table, and the prior-art licences. The short
version:

**Studied:** published screenshots, Google's support docs, the Calendar API v3
reference, RFCs 5545 / 4791 / 6638, and the AOSP `CalendarContract`
documentation.

**Never opened:** the Google Calendar APK or source, the AOSP Calendar app
source, or the source of Etar, Simple Calendar, Fossify Calendar, or DAVx⁵ —
all GPL-3.0, all of which would infect this repo.

The event model is **RFC 5545**, an open standard Google implements rather than
owns. Modelling `RRULE`, `EXDATE`, `VALARM` and `TRANSP` is conformance, not
copying. The layout is reimplemented from screenshots as an *idea*; the
expression is entirely PiercingXX.

---

## 2. Requirements

**R1.** Render a personal calendar in four views — Schedule, Day, Week, Month —
re-skinned to the PiercingXX brand.
**R2.** Read and write events through Android's `CalendarContract`. The provider
is the system of record; this app never holds a second copy of an event.
**R3.** **No `INTERNET` permission.** The manifest must not declare it, and that
must be machine-checkable in the built APK.
**R4.** Sync with Google Calendar happens through a third-party sync adapter
(DAVx⁵). The app must be *useful and correct* with no sync adapter installed at
all, against purely local calendars.
**R5.** Never nag. No auto-added events, no illustrations, no interstitials, no
promotional surface, no RSVP prompts, no telemetry.
**R6.** Never corrupt data it does not render. Columns the app ignores are
preserved byte-identical on write.
**R7.** Notifications fire reliably — including across reboot and Doze — or the
app is worthless. This is the one hard reliability requirement.
**R8.** No Google Play Services. Nothing may depend on GMS.
**R9.** Recurrence is correct. `RRULE`, `EXDATE`, and per-instance exceptions
round-trip without drift.

### Non-goals

- Guests, invitations, RSVP, scheduling of any kind (teardown §3.4, resolved).
- Tasks, reminders, goals, todos (teardown §3.7, resolved).
- Multi-account switching, calendar sharing, `.ics` URL subscription in v1.
- Conferencing. No link is ever generated; an inbound one renders as text.
- Location autocomplete, title suggestion, natural-language parsing in v1.
- Tablet layouts, landscape two-pane, Wear OS, print.
- Telemetry, crash reporting, analytics — absent by manifest, not by policy.

---

## 3. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **`CalendarContract` is the system of record. No Room table for events.** | R2. The provider already stores events, recurrence, reminders and attendees, already enforces the sync-adapter contract, and already expands recurrences (§4.2). A local mirror would be a second copy to keep coherent for no gain, and would be the most likely source of the data corruption R9 forbids. |
| D2 | **DAVx⁵ owns Google. This app owns none of it.** | Teardown §2, resolved. Calendar scopes are *sensitive*; a personal-Gmail OAuth client either re-authorises every 7 days or ships an unverified-app warning. DAVx⁵ has already paid that cost with a verified client. Delegating it buys R3 outright. |
| D3 | **No `INTERNET` permission — the privacy claim is machine-checkable.** | R3. Same posture as TxxT. `aapt2 dump permissions` is the proof, and it is stronger than any statement in a README. It also makes R5 partly structural: an app with no network cannot fetch a promotional card. |
| D4 | **Compose, not Views.** | Departs from Nope-Mode D4 and XX-Launcher, following the precedent XX-Vitals set on 2026-08-15. A month grid, a scrolling time grid, and drag-to-move/resize are custom-draw and gesture work either way; Compose is materially less code for both. Rest of the toolchain is unchanged (§15). |
| D5 | **Google Calendar's layout, PiercingXX's skin.** | Copying layout is safe (idea); copying palette, type and icons is not (expression). Re-skinning is the brand-correct *and* legally-cleanest answer at once. |
| D6 | **Hue is replaced by a sigil + opacity tier, and the brand guide is not amended.** | Teardown §5.1, resolved. A leading monospace sigil costs one character cell, aligns by construction, and reads better than tint on AMOLED black. Signal white is reserved for the now-rule and the selection, as §3.1 of the guide requires. |
| D7 | **Reminders are scheduled by this app via `AlarmManager`, reconciled from the provider.** | R7. `ACTION_EVENT_REMINDER` broadcasts from the provider are not dependable enough to hang the one hard reliability requirement on. Reconciliation-not-event-chasing is the Nope-Mode §7.1 pattern and it applies unchanged. |
| D8 | **Ignored columns are read, held, and written back unchanged.** | R6. A two-way sync makes the calendar a shared mutable store — dropping a field you do not render destroys it in Google and in every other client. The app refuses to *display* things; it never vandalises the account. |
| D9 | **App-local state is `DataStore`, not Room.** | The only persistent app state is settings and the calendar-ID → sigil map. A database for two dozen key-value pairs is ceremony. Backup/restore (§9) is a JSON dump of exactly this. |
| D10 | **Schedule is the default view.** | It is the view that answers "what is next", which is the question a calendar is actually opened to answer. Google defaults to Month, which answers "what shape is this fortnight" — a rarer question. |
| D11 | **The `+` button has one entry.** | Teardown §3.7 and §3.4, resolved. There is one object type in this app: an event. Every pseudo-type Google layered on top is gone, so the menu that disambiguated them is gone too. |
| D12 | **Quiet defaults, and privacy leaks are opt-in.** | TxxT's posture, applied here: notification content preview off, declined events hidden, heads-up alerts off, daily agenda off. Each is one toggle away; none is the shipped default. |

---

## 4. Platform mechanics

### 4.1 The sync path, and where this app sits in it

```
   Google Calendar
         ▲
         │  CalDAV over OAuth 2.0   ← DAVx⁵'s verified client, DAVx⁵'s problem
         ▼
      DAVx⁵                          ← a sync adapter, registered with the OS
         ▲
         │  ContentProvider, as a sync adapter (CALLER_IS_SYNCADAPTER)
         ▼
  CalendarContract                   ← AOSP. The system of record.
         ▲
         │  ContentResolver, as a normal client
         ▼
   XX-Calendar                       ← this app. No network. No account.
```

XX-Calendar is a **normal client**, never a sync adapter. It does not set
`CALLER_IS_SYNCADAPTER`, which means the provider marks its writes dirty and
DAVx⁵ picks them up on the next sync. That is the entire integration, and it is
the reason this app has no sync code.

**Consequence to internalise:** the app cannot make sync happen and cannot
report why it failed. Sync state is DAVx⁵'s UI. What this app shows is the last
time the provider's data changed, plus a deep-link into DAVx⁵ (§8.6). Pretending
to more than that would be lying in a status line.

### 4.2 `Instances` does the hard part

The provider exposes three relevant tables:

| Table | What it holds |
|---|---|
| `Calendars` | one row per calendar — name, account, colour, visibility, sync flags |
| `Events` | one row per event *definition*, including `RRULE`, `RDATE`, `EXDATE`, and `ORIGINAL_ID`/`ORIGINAL_INSTANCE_TIME` for exceptions |
| `Instances` | a **generated, queryable expansion** of recurring events over a time range |

`Instances.query()` over `[start, end)` returns the expanded occurrences with
exceptions and deletions already applied. Every view in this app is one
`Instances` query over the visible window.

This removes the single largest source of bugs in a calendar client. R9 does not
mean "write a recurrence expander"; it means "author `RRULE`/`EXDATE` correctly
and let the provider expand them". The remaining risk is concentrated in
*editing* a recurring event, which is §6.3 and is where the tests go.

`Instances` is a generated table — the provider expands lazily and the first
query over a fresh range can be slow. Query the visible window plus one period
of margin, off the main thread, and let a `ContentObserver` invalidate.

### 4.3 Reminders and the reliability requirement

The provider stores `Reminders` rows against events. It also broadcasts
`ACTION_EVENT_REMINDER`. That broadcast is not a foundation to build R7 on: its
delivery is implementation-dependent, it does not survive every reboot path, and
it gives no control over Doze behaviour.

Instead (D7), reconcile:

1. On boot, on provider change, on settings change, and on a daily heartbeat,
   query `Instances` joined to `Reminders` for the next 48 hours.
2. Compute the set of alarms that *should* exist.
3. Diff against what is scheduled, and set/cancel the difference through
   `AlarmManager.setExactAndAllowWhileIdle`.
4. Never chase individual events. Recompute the whole window; it is small.

This is Nope-Mode §7.1's pattern verbatim, and it is correct for the same
reason: a reconciler converges after a missed signal, an event-chaser does not.

**Exact alarms need permission.** `USE_EXACT_ALARM` is granted at install for an
app whose primary purpose is calendaring, which this is. `SCHEDULE_EXACT_ALARM`
is the user-granted fallback. Check `canScheduleExactAlarms()` at startup and
say so plainly if it is denied — a calendar that silently stops reminding is
worse than one that admits it cannot.

### 4.4 Working with no sync adapter

R4. With DAVx⁵ absent, the provider still works and still offers local
calendars. The app must:

- Create a local calendar (`ACCOUNT_TYPE_LOCAL`) on first run if no writable
  calendar exists, so a fresh install is immediately usable.
- Never render a "not connected" nag. A purely local calendar is a legitimate
  end state, not a setup step someone abandoned.
- Surface DAVx⁵ in Settings as an option, once, and never again.

### 4.5 What arrives from Google that we do not want

Under this architecture the junk syncs down before it can be filtered — DAVx⁵
pulls whatever the account holds. Filtering is therefore at **render**, not at
the wire, and per D8 it is never a deletion.

| Arrival | Treatment |
|---|---|
| Gmail auto-added events (flights, hotels, deliveries, bills) | Hidden at render. Rows untouched. Recognised by their source calendar and event metadata; the rule is a settings toggle, default on. |
| Birthdays calendar | Hidden by default in the drawer, like any other calendar. Not special-cased. |
| Meet / conferencing links | Rendered as tappable text in the detail sheet. Never generated. |
| Attachments | Shown as a count. No opening, no Drive. |
| Attendees | Not rendered at all (teardown §3.4, resolved). Rows untouched. |
| Declined events | Hidden by default (S4). |

**The honest caveat:** hiding is not removing. The rows exist, they count
against sync, and they are visible in every other client. The app makes your
calendar quiet on this device; it does not clean your Google account. Saying so
in Settings is better than implying otherwise.

---

## 5. Architecture

```
┌──────────────────────────────────────────────────────────┐
│  ui/                                                      │
│    schedule/  day/  week/  month/   ← four Instances views│
│    editor/     detail/                                    │
│    settings/   drawer/                                    │
│    theme/      tokens, type, sigils                       │
├──────────────────────────────────────────────────────────┤
│  calendar/    CalendarRepository                          │
│                 queryInstances(range)                     │
│                 loadEvent / saveEvent / deleteEvent       │
│                 calendars(), reminders()                  │
│               RecurrenceEditor   ← the dangerous one, §6.3│
│               OpaqueColumns      ← D8 passthrough         │
├──────────────────────────────────────────────────────────┤
│  core/        pure JVM, no Android, fully testable        │
│                 RRuleModel, ScopeResolver, TimeMath,      │
│                 SigilAssigner, AgendaGrouping             │
├──────────────────────────────────────────────────────────┤
│  alarm/       ReminderReconciler, AlarmScheduler,         │
│               BootReceiver, ProviderObserver              │
├──────────────────────────────────────────────────────────┤
│  settings/    SettingsStore (DataStore), BackupJson       │
├──────────────────────────────────────────────────────────┤
│  widget/      MonthWidget, ScheduleWidget                 │
└──────────────────────────────────────────────────────────┘
             │
             ▼   ContentResolver (normal client, not a sync adapter)
      CalendarContract
```

`core/` holds everything that can be tested on a JVM with no device: recurrence
rule construction, the this/following/all scope resolver, timezone arithmetic,
sigil assignment, and agenda day-grouping. That boundary is what makes R9
testable, so it is drawn deliberately and defended.

---

## 6. Data model

### 6.1 What lives where

| Data | Home |
|---|---|
| Events, recurrence, exceptions, reminders, attendees | `CalendarContract` — the provider. Never duplicated. |
| Calendar list, colours, visibility | `CalendarContract.Calendars` |
| Calendar → sigil assignment | `DataStore`, keyed by `Calendars._ID` + account |
| Settings (§8.6) | `DataStore` |
| Nothing else | — |

There is no app database. D1.

### 6.2 Columns the app writes

`Events`: `TITLE`, `DTSTART`, `DTEND`, `DURATION`, `ALL_DAY`, `EVENT_TIMEZONE`,
`EVENT_END_TIMEZONE`, `EVENT_LOCATION`, `DESCRIPTION`, `CALENDAR_ID`,
`EVENT_COLOR_KEY`, `AVAILABILITY`, `RRULE`, `RDATE`, `EXDATE`, `ORIGINAL_ID`,
`ORIGINAL_INSTANCE_TIME`, `ORIGINAL_ALL_DAY`.

`Reminders`: `MINUTES`, `METHOD` (`METHOD_ALERT` only — never `METHOD_EMAIL`).

**Everything else is opaque (D8).** Read on load, held in the editor's state
untouched, written back on save. `ACCESS_LEVEL`, `GUESTS_CAN_*`,
`HAS_ATTENDEE_DATA`, `ORGANIZER`, `CUSTOM_APP_URI`, and every `SYNC_DATA*`
column falls in this bucket. The editor must not be able to clear a column it
does not have a field for.

### 6.3 Editing a recurring event — the dangerous operation

Three scopes, three different writes. This table is the spec; get it wrong and
R9 is violated silently.

| Scope | Operation |
|---|---|
| **This instance only** | Insert a new `Events` row with `ORIGINAL_ID` = the parent, `ORIGINAL_INSTANCE_TIME` = the instance start, carrying the edits. The provider treats it as an exception and suppresses the generated instance. Do **not** hand-edit `EXDATE`. |
| **This and following** | Set `UNTIL` on the parent's `RRULE` to just before this instance, then insert a *new* recurring event starting at this instance with the edited fields and the remaining rule. Both rows exist afterwards. |
| **All events** | Update the parent row in place. |
| **Delete: this instance** | Insert an exception row with `STATUS_CANCELED`, or delete the instance URI and let the provider write `EXDATE`. Pick one and never mix them. |
| **Delete: this and following** | `UNTIL` on the parent. |
| **Delete: all** | Delete the parent; the provider cascades. |

`ScopeResolver` in `core/` computes the intended write as a plain data object
with no Android dependency, so every row above is a JVM unit test. That is the
point of the boundary.

**Never offer a scope prompt for a non-recurring event.** Google does not, and
the prompt is disorienting.

### 6.4 All-day events and timezones

All-day events are stored in **UTC midnight** with `ALL_DAY = 1`, per the
provider contract. Getting this wrong shifts events by a day for anyone east or
west of UTC, and it is the second-most-common calendar bug after recurrence.
`TimeMath` in `core/` owns the conversion in both directions and is tested at
`UTC-11`, `UTC`, `UTC+13`, and across a DST boundary.

Per-event timezones (`EVENT_TIMEZONE`, `EVENT_END_TIMEZONE`) are stored and
honoured, and rendered in the detail sheet **only when they differ** from the
device zone. There is no travel prompt (S10, purged).

---

## 7. Design tokens

Google Calendar's layout, PiercingXX's skin (D5).

**Source of truth:**
[`piercingxx-branding`](https://github.com/PiercingXX/piercingxx-branding) —
`BRAND-GUIDE.md` §3 and `tokens/colors.json`. Per the new-project checklist,
colours are **imported from `tokens/`, never retyped**. XX-Calendar vendors
`tokens/android-colors.xml` into `res/values/` and updates it by re-copying, so a
brand change is a file swap rather than a hunt through composables.

```
Core
  ink             #000000            ground, AMOLED black
  signal          #FFFFFF            THE accent — reserved (§7.1)

Emphasis (inverts)
  emphasis_bg     #FFFFFF            selected day, active view tab, FAB
  emphasis_fg     #000000            its text

Neutral
  ink_raised      #09090B            bottom sheets, the editor surface
  graphite        #131316            drawer, settings panels
  slate           #18181B            text fields, wells

White-opacity ramp — this carries ALL hierarchy
  line            #1AFFFFFF   10%    grid lines, hour rules, day cell borders
  shade           #40FFFFFF   25%    past events, out-of-month days, disabled
  muted           #80FFFFFF   50%    secondary text, weekday headers
  strong          #CCFFFFFF   80%    labels, glyphs, day numerals
  text            #E6FFFFFF   90%    event titles, body — the ceiling for type

Status — glyph-first (✓ → ⚠ ✗), colour only when it needs attention
  ok              #E6FFFFFF   90%    ✓ — not a colour
  info            #80FFFFFF   50%    → — not a colour
  warn            #FDBA74            ⚠   stale sync, exact-alarm denied
  error           #FF6767            ✗   write failed
```

### 7.1 Calendar identity without hue (D6)

A calendar's primary information channel is hue, and the guide forbids it: one
accent, and pure white is reserved. Hue is replaced by a **leading monospace
sigil plus a position on the white ramp**. Monospace makes the sigil column
free — one character cell, aligned by construction.

```
  ▌  tier 1   text    90%
  ▏  tier 2   strong  80%
  ░  tier 3   muted   50%
  ▒  tier 4   shade   25%
  ▓  tier 5   muted   50%
  ·  tier 6   shade   25%
```

`SigilAssigner` in `core/` allocates sigils in a stable order on first sight of
a calendar and persists the mapping (§6.1). Assignment is overridable per
calendar in Settings. Six is the ceiling; past that no scheme works, hue
included.

Signal white `#FFFFFF` appears **twice per screen at most**: the current-time
rule, and the selected element as an inverted block — white ground, ink text —
exactly as guide §3.1 prescribes for strong emphasis. Nothing else on any screen
is full white, including event titles, which sit at `text` 90%.

Rare colours (guide §3.6) are unused. Rare Green on a fully-clear day is the one
plausible future call. Not in v1.

**This is the riskiest decision in the spec** and it is a decision about a dense
month grid, which is unforgiving. WS1 produces a static mock of a busy week and
a busy month before any of it is built on top of.

### 7.2 Type

Monospace is the identity. Both faces are **shipped in `res/font/`**, per the
checklist — never system-dependent.

| Role | Face | Size |
|---|---|---|
| Day numerals, month header, times | Space Mono | 32 / 20 / 13 sp |
| Event titles, list rows, editor | JetBrains Mono | 15 / 14 sp |
| Weekday headers, labels | JetBrains Mono | 11 sp, letter-spaced |

**Weight is light.** Regular by default; bold is a scalpel. Left-aligned,
generous line-height, no centred paragraphs.

**Tabular figures throughout.** A time column whose digits reflow is the single
most obvious tell of an unconsidered calendar UI, and monospace fixes it for
free.

---

## 8. UI

Four views, one editor, one detail sheet, one drawer, one settings screen. That
is the whole app.

### 8.1 Chrome — every screen

- **Top bar:** month/year in Space Mono, tappable → mini-month picker. `Today`
  button appears only when not on today. Search glyph. Overflow → view switcher.
- **No bottom navigation.** Four views on a five-tab bar is Google's answer to
  having eleven things to show. We have four, and they belong in the switcher.
- **FAB:** `+`, signal white block, ink glyph. **One action: new event.** (D11)
- **Drawer:** calendar list with sigil, name, and a visibility toggle. Settings
  at the bottom. Nothing else — no account switcher, no Trash, no Help, no
  Feedback.

### 8.2 Schedule — the default view (D10)

A flat, infinitely-scrolling list of what is next. Days that hold nothing are
skipped, not rendered as empty rows.

```
MON 24 AUG
▌ 09:00 – 09:15   standup
▌ 14:00 – 15:00   design review
▏ 18:00 – 19:00   gym

TUE 25 AUG
▏ 11:00 – 11:45   dentist
░ 19:30           dinner — mum

THU 27 AUG
▌ all day         release freeze
```

No illustrations (A2). No weather row (A3). Past events at `shade` 25%. The
current-time position is marked with a signal-white rule when today is on
screen.

Empty state, one line: `Nothing scheduled.`

### 8.3 Day and Week

A time grid. Hour rules at `line` 10%, labelled in Space Mono at `muted`.
Current time is a signal-white rule with a filled dot at the leading edge — the
only full-white element in the view.

Events are blocks: `ink_raised` fill, a 2dp leading bar carrying the calendar's
sigil tier, title at `text` 90%, time at `muted` 50% when the block is tall
enough to hold it.

- **Drag to create** — long-press on empty grid, drag to size.
- **Drag to move** — long-press an event, drag. Snaps to 15 minutes.
- **Resize** — drag the block's top or bottom edge.
- **All-day events** pin to a fixed header row above the grid.

Week is the same grid, seven columns, with the day numerals in the header
inverted (white block, ink text) for today.

### 8.4 Month

A 7×N grid. Day numerals in Space Mono at `strong` 80%; out-of-month days at
`shade` 25%; today's numeral inverted.

Each cell holds up to three event chips — sigil plus truncated title at 11sp —
then `+N` at `muted`. Tapping a day opens that day beneath the grid in a
Schedule-style list rather than navigating away, so the month stays visible.

Week numbers (S3) render in a leading gutter column at `shade` when enabled.

### 8.5 The event editor and detail sheet

**Detail sheet** — a bottom sheet on `ink_raised`. Title, time, calendar sigil
and name, location, description, reminders. A conferencing URL, if the event
arrived with one, as tappable text. Attachment count, if any. Actions: edit,
duplicate, delete. **No guest section, no RSVP controls** (teardown §3.4).

**Editor** — a full screen, not a sheet. Fields, in order:

```
title
─────────────────────────────
all-day                    ▢
starts     Mon 24 Aug  09:00
ends       Mon 24 Aug  09:15
timezone   (only if ≠ device)
repeats    does not repeat  →
─────────────────────────────
calendar   ▌ work           →
location
description
─────────────────────────────
notify     10 minutes before
           + add notification
busy / free                 →
```

No location autocomplete (E6). No title suggestions (E14). Both are network
features in an app with no network, so both are structurally impossible, which
is the tidiest form of "purged".

**Repeat** opens a rule builder: the five presets, then `custom…` for interval,
by-day, by-month-day, nth-weekday, and the end condition. Saving a change to a
recurring event raises the scope prompt (§6.3) — and only then.

### 8.6 Settings

The surviving sixteen (teardown §3.10). One screen, sectioned.

```
VIEW
  default view            schedule
  start day of week       monday
  week numbers                  ▢
  show declined events          ▢     ← off, unlike Google
  dim past events               ▣
  density                 comfortable

EVENTS
  default duration        30 min
  default notification    10 min before
  all-day notification    18:00, day before

NOTIFICATIONS
  show event title on lock screen ▢    ← off
  daily agenda                    ▢    ← off
  heads-up alerts                 ▢    ← off
  ⚠ exact alarms are denied — reminders will be late    [fix]

CALENDARS
  ▌ work            visible ▣
  ▏ personal        visible ▣
  ░ family          visible ▢
  hide auto-added events          ▣    ← Gmail bookings (§4.5)

APPEARANCE
  background        amoled night
  font              jetbrains mono
  text size         ──────●───

SYNC
  last change   2 min ago
  Google sync is handled by DAVx⁵.      [open DAVx⁵]

DATA
  import .ics
  export .ics
  backup to JSON
  restore from JSON
```

The `⚠ exact alarms` row is present only when the permission is denied, and it
is the only warn-coloured thing in the app.

---

## 9. Backup, import, export

Family convention — TxxT and XX-Launcher both ship JSON backup, and a
local-first tool without an escape hatch is a trap.

| Operation | Scope |
|---|---|
| **Export `.ics`** | RFC 5545, all visible calendars or one, via SAF file picker |
| **Import `.ics`** | Into a chosen calendar. Duplicate detection by `UID`. |
| **Backup JSON** | Settings and the sigil map. **Not events** — those belong to the provider and are exported as `.ics`. |
| **Restore JSON** | Settings and sigils only |

Splitting the two is deliberate: events have a standard interchange format and
should use it; app settings do not.

---

## 10. Failure modes

| Failure | Behaviour |
|---|---|
| No calendar provider access (permission denied) | A single screen explaining what the app needs and a button to grant. No partial UI. |
| Exact-alarm permission denied | App works. Reminders are scheduled inexactly and may be minutes late. Warned in Settings (§8.6), stated plainly, never nagged. |
| No writable calendar exists | Create a local one on first run (§4.4). |
| DAVx⁵ absent | Nothing. Local calendars work. No nag. |
| DAVx⁵ present but failing | Not detectable from here, and not claimed. The sync row shows when data last changed, which is honest. |
| Provider write fails | `✗` toast, editor stays open with the edit intact. Never discard a user's typing on an error. |
| Recurring edit hits an unmodelled shape | Refuse the write and say so, rather than guessing. A silently-wrong recurrence is worse than a rejected edit. |
| Enormous `Instances` range | Query the window plus one period, off-thread, `ContentObserver`-invalidated. Never query a year. |
| Boot, app update, timezone change, DST | `ReminderReconciler` runs. It converges by construction (§4.3). |

---

## 11. Testing

`core/` is pure JVM and carries the burden:

- `RRuleModel` — build and parse every preset plus custom rules; round-trip.
- `ScopeResolver` — **the priority suite.** Every row of §6.3, asserted as the
  exact intended provider operations.
- `TimeMath` — all-day UTC-midnight conversion at `UTC-11`, `UTC`, `UTC+13`;
  across a DST boundary in both directions; per-event timezone rendering rules.
- `SigilAssigner` — stable across restarts, survives a calendar being removed
  and re-added, respects overrides.
- `AgendaGrouping` — day boundaries, multi-day events, all-day placement.

Instrumented, against a real provider:

- Create / edit / delete round-trips, including all three recurring scopes.
- **Opaque-column preservation (D8)** — load an event with every unmodelled
  column populated, save with one field changed, assert every other column is
  byte-identical. This is the test that protects R6.
- Reminder reconciliation after a simulated boot.

Machine-checkable privacy claim, in CI:

```sh
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk \
  | grep -q 'android.permission.INTERNET' && exit 1 || exit 0
```

R3 is not a promise in a README. It is a build gate.

---

## 12. Manifest

```
uses-permission  READ_CALENDAR
uses-permission  WRITE_CALENDAR
uses-permission  POST_NOTIFICATIONS
uses-permission  RECEIVE_BOOT_COMPLETED
uses-permission  USE_EXACT_ALARM
uses-permission  SCHEDULE_EXACT_ALARM      ← fallback path

NOT DECLARED     INTERNET                  ← R3, D3, gated in CI
NOT DECLARED     ACCESS_*_LOCATION
NOT DECLARED     GET_ACCOUNTS
NOT DECLARED     READ_CONTACTS
```

Components: `MainActivity`, `EditorActivity`, `SettingsActivity`,
`BootReceiver`, `ReminderReceiver`, `MonthWidget`, `ScheduleWidget`,
`ThemeSyncReceiver` (XX-Launcher broadcast, as TxxT implements it).

Intent filters: `VIEW` on `content://com.android.calendar/time`, `INSERT` on
`vnd.android.cursor.dir/event`, `VIEW`/`EDIT` on
`vnd.android.cursor.item/event`, and `text/calendar` for `.ics`. Being the
system's calendar handler is the point of replacing one.

---

## 13. Package layout

```
com.piercingxx.calendar
├── core/       RRuleModel, ScopeResolver, TimeMath,        ← pure JVM
│               SigilAssigner, AgendaGrouping
├── calendar/   CalendarRepository, OpaqueColumns,
│               RecurrenceEditor, InstanceQuery
├── alarm/      ReminderReconciler, AlarmScheduler,
│               BootReceiver, ReminderReceiver, ProviderObserver
├── settings/   SettingsStore (DataStore), BackupJson, IcsCodec
├── ui/
│   ├── schedule/   ScheduleScreen
│   ├── day/        DayScreen, TimeGrid
│   ├── week/       WeekScreen
│   ├── month/      MonthScreen, MonthGrid, DayPeek
│   ├── editor/     EditorScreen, RepeatBuilder, ScopePrompt
│   ├── detail/     DetailSheet
│   ├── drawer/     CalendarDrawer
│   ├── settings/   SettingsScreen
│   └── theme/      CalendarTheme, tokens, typography, sigils
└── widget/     MonthWidget, ScheduleWidget
```

---

## 14. Build

```
AGP 8.5.0 · Kotlin 1.9.24 · compileSdk 34 · minSdk 26 · targetSdk 34
Java/jvmTarget 1.8 · buildConfig · Compose compiler 1.5.14
compose-bom 2024.06.00 · material3 · activity-compose · navigation-compose
datastore-preferences 1.1.1 · coroutines 1.7.3
glance-appwidget 1.1.0
junit 4.13.2 · mockk 1.13.10 · robolectric 4.12.2 · espresso 3.5.1
```

Deltas from the Nope-Mode / XX-Launcher baseline:

| Change | Reason |
|---|---|
| `minSdk 24 → 26` | `java.time` without `coreLibraryDesugaring`. A calendar is nothing but date arithmetic; carrying a desugaring shim through it is the wrong trade. 26 is old enough to cost nothing. |
| `+ Compose` | D4, following XX-Vitals. Kotlin stays 1.9.24, so compiler 1.5.14 is the matching pin — no toolchain upgrade. |
| `+ DataStore` | D9. Replaces Room, which the other apps use. |
| `− Room` | D1. There is no app database. |
| `− Retrofit / OkHttp / WorkManager` | D2/D3. No network, nothing to schedule against one. |
| `− viewBinding` | Nothing left to bind. |
| `+ Glance` | Widgets in Compose rather than `RemoteViews`. |

`applicationId` / `namespace`: `com.piercingxx.calendar`. **Debug keystore
copied from Nope-Mode** so sideloads keep one signing identity across the
family.

---

## 15. Build order

Provider first. Every screen is a query against it, so the query layer and the
recurrence writes are proven before anything renders.

| WS | Scope | Depends on |
|---|---|---|
| 1 | `core/` — `RRuleModel`, `ScopeResolver`, `TimeMath`, `SigilAssigner`, `AgendaGrouping`, all unit-tested. Plus a **static mock of a dense week and month** to validate the sigil scheme (§7.1) | — |
| 2 | Android skeleton, `CalendarTheme`, fonts, tokens, icon, permission gate | — |
| 3 | `CalendarRepository` — `Instances` query, calendar list, `ContentObserver`, **opaque-column passthrough** | 2 |
| 4 | Schedule view — the default, and the first honest milestone | 1,2,3 |
| 5 | Day and Week time grids, current-time rule, drag/move/resize | 4 |
| 6 | Month grid + day peek | 4 |
| 7 | Detail sheet, editor, `RepeatBuilder`, **`ScopePrompt` + recurring writes** | 3,4 |
| 8 | `ReminderReconciler`, `AlarmScheduler`, boot receiver, notification channels | 3 |
| 9 | Settings, `SettingsStore`, sigil overrides, auto-added-event filter | 4,8 |
| 10 | `.ics` import/export, JSON backup/restore | 3,9 |
| 11 | Widgets (month, schedule), app shortcuts, `ThemeSyncReceiver`, intent filters | 4,6 |
| 12 | CI privacy gate (§11), instrumented round-trip suite | 3,7 |
| — | **v1 ships here.** Everything below is additive. | |
| 13 | *(later)* `.ics` URL subscription (C5) — holidays without a Google product | 10 |
| 14 | *(later)* Year view (V7) | 6 |
| 15 | *(later)* On-device natural-language quick add (E15) | 7 |

**WS1 is where the project's riskiest decision gets tested.** The sigil scheme
either reads in a dense month grid or it does not, and finding out in WS6 means
rebuilding three views. Draw the mock, look at it, then commit.

**WS4 is the first honest milestone.** After it the app shows your real days,
with no editor and no reminders. If Schedule-on-black-in-monospace is not
pleasant to look at, that is the moment to learn it.

**WS7 is the load-bearing correctness work.** Recurring-event editing is where
calendar clients corrupt data, and §6.3 exists so it can be tested before it
touches a real event. Do not ship WS7 without the `ScopeResolver` suite green.

---

## 16. Deferred

Year view · `.ics` URL subscription · natural-language quick add · secondary
timezone · multi-account · copy-to-calendar · quick-response from notification ·
Quick Settings tile · next-event widget · out-of-office.

## 17. Open questions

1. **Auto-added-event detection.** §4.5 hides Gmail bookings by their source and
   metadata, but the exact signal DAVx⁵ surfaces through CalDAV needs
   confirmation against a real account — Google's CalDAV representation of these
   is not documented. If there is no reliable marker, the fallback is a
   per-calendar hide, which is coarser. **Resolve in WS3, on real data.**
2. **`Instances` performance on a busy account.** The lazy expansion cost over a
   month of a heavily-recurring calendar is unmeasured. Measure in WS3 before
   building three views on the assumption it is fast.
3. **Sigil ceiling.** Six is asserted, not tested. WS1's mock decides.
