# Google Calendar — Cleanroom Teardown

Every feature Google Calendar ships, what it is for, whether it earns its place
in XX-Calendar, and what it costs to keep.

**Status:** teardown for review. No spec, no decisions locked.
**Purpose:** you read the ledger, you overrule the recommendations you disagree
with, and the survivors become `design.md`.

Legend:

| Mark | Meaning |
|---|---|
| **KEEP** | Ships in v1, behaves broadly as Google's does |
| **KEEP±** | Ships in v1, but changed — the change is stated |
| **DEFER** | Good idea, not v1 |
| **PURGE** | Deliberately absent. This is the point of the project |
| **SYNC-ONLY** | Not rendered, but stored and round-tripped so sync does not corrupt data (§2.4) |

---

## 1. Cleanroom provenance

Same discipline as [Nope-Mode](https://github.com/PiercingXX/Nope-Mode) §1 and
[XX-Vitals](https://github.com/PiercingXX/xx-vitals) §1.

**What was studied:** published Google Calendar screenshots, Google's own
support documentation and Workspace release notes, the public Google Calendar
API v3 reference, the CalDAV/iCalendar RFCs (5545, 4791, 6638), and the AOSP
`CalendarContract` documentation.

**What was never opened:** the decompiled Google Calendar APK, any Google
Calendar source, the AOSP Calendar app source, or the source of any
GPL-licensed calendar client (Etar, Simple Calendar, DAVx⁵, Fossify Calendar).

Layout and information architecture — a month grid, a day column, a
bottom-sheet editor — are **ideas**. They are reimplemented here from
screenshots. The **expression** is replaced entirely: PiercingXX tokens,
monospace type, original vector icons, original name.

The data model is a different matter and is *safe*: events, recurrence,
attendees, and alarms come from **RFC 5545 (iCalendar)**, an open standard that
Google implements rather than owns. Modelling `RRULE`, `VALARM`, `ATTENDEE`,
and `TRANSP` is standards conformance, not copying.

### 1.1 What is deliberately *not* copied

| Google Calendar asset | Status here |
|---|---|
| "Google Calendar" name, the blue-31-on-white icon | Never used. App is **XX-Calendar**. |
| Google Sans / Product Sans | Never used. Space Mono + JetBrains Mono (§5). |
| The 11 named event colours (Tomato, Flamingo, Basil…) | Never used. See §5.1 — this is the hard one. |
| Google's icon set | Redrawn as original vectors. |
| Auto-generated event illustrations | Never used, never shipped, see §4. |
| "Goals", "Time Insights", "Speedy meetings" | Not implemented. Names are Google's; the features are purged anyway. |

### 1.2 Prior art

| Project | License | What was taken (behaviour only) |
|---|---|---|
| Google Calendar | proprietary | The IA: month / week / day / schedule views, the bottom-sheet quick editor, the drawer calendar list. |
| [Etar](https://github.com/Etar-Group/Etar-Calendar) | GPL-3.0 | Confirmation that a `CalendarContract`-backed client is a complete, viable architecture on Android. **Source deliberately not read — GPL would infect this repo.** |
| [DAVx⁵](https://github.com/bitfireAT/davx5-ospbo) | GPL-3.0 | Confirmation that Google CalDAV-over-OAuth works on a de-Googled device, and that a verified OAuth client is the thing that makes it painless. **Source not read.** Interop is via `CalendarContract`, which is arm's-length by design (§2.2). |
| RFC 5545 / 4791 / 6638 | open standard | The entire event, recurrence, alarm, and scheduling data model. |
| `CalendarContract` | AOSP, Apache-2.0 | The on-device storage and sync-adapter contract. Public docs only. |

---

## 2. The sync problem — read this before the ledger

"Syncs with Google Calendar" sounds like one requirement. It is three, and the
choice between them changes what the app *is*.

### 2.1 What is actually available on GrapheneOS

Google Calendar's own Android app requires Play Services. It is not an option.
That leaves three ways to get a Google account's events onto the device:

| Path | Mechanism | Needs |
|---|---|---|
| **Calendar API v3** | REST + OAuth 2.0, JSON | Your own Google Cloud OAuth client |
| **Google CalDAV** | RFC 4791 over HTTPS + OAuth 2.0 | Your own OAuth client (basic auth was killed in 2015) |
| **A sync adapter** | Another app owns the network; you read `CalendarContract` | DAVx⁵ installed |

Both direct paths need an OAuth client, and that is where the trap is.

### 2.2 The OAuth trap — the reason this is a real decision

Calendar scopes are classified **sensitive**. For an app using a personal Gmail
account, that produces a fork with no comfortable branch:

- **Consent screen in "Testing"** → refresh tokens **expire after 7 days**.
  You would re-authorise the app every week, forever. Disqualifying.
- **Consent screen in "In production", unverified** → refresh tokens are
  durable, but every auth shows the "Google hasn't verified this app" screen,
  cleared once via *Advanced → Go to XX-Calendar*. Google's docs list personal
  use as an exception to verification. Workable, mildly undignified.
- **Verified** → requires a justification write-up and an unlisted YouTube demo
  video, for an app with one user. Absurd for this project.
- **"Internal" user type** → no warning, no expiry, no cap — but requires a
  Google Workspace account, not a personal Gmail.

DAVx⁵ has already paid this cost: it ships a verified Google OAuth client, so
Google sync there is a login box and nothing else.

**Verify the production-unverified path on the day.** Google has moved these
goalposts repeatedly and this teardown is written in August 2026. Sources:
[OAuth 2.0 for Google APIs](https://developers.google.com/identity/protocols/oauth2),
[Sensitive scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification),
[Manage app audience](https://support.google.com/cloud/answer/15549945),
[Unverified apps](https://support.google.com/cloud/answer/7454865).

### 2.3 The three architectures

**A — Direct client.** XX-Calendar talks to Calendar API v3 itself. Room is the
local store. One app, no dependencies, full control of the sync loop and
therefore full control of *what syncs at all* — you can refuse to pull Gmail
auto-events at the wire. Costs: you own OAuth, token refresh, incremental sync
tokens, conflict resolution, and the unverified-app warning.

**B — Provider-backed.** XX-Calendar is a pure UI over Android's
`CalendarContract`. DAVx⁵ syncs Google. Zero networking code — the app can
plausibly ship **without the `INTERNET` permission**, which is the same
machine-checkable privacy claim TxxT makes. Works with any CalDAV server for
free, so a Radicale container on the Synology becomes a config change rather
than a project. Costs: a second app is mandatory, sync failures are debugged in
someone else's UI, and filtering happens after the junk has already landed
locally.

**C — Hybrid.** `CalendarContract` is the store (so DAVx⁵, or any other adapter,
works out of the box), plus an *optional* built-in Google sync adapter for
people who want one app. Strictly more work than A or B; defers nothing.

**RESOLVED 2026-08-23 — B.** It is the branch that matches every principle in
the brand guide at once — minimal, one job, local-first, no telemetry surface, and a
`INTERNET`-free manifest. It also makes the self-hosted future free instead of a
workstream. The cost is honest and small: DAVx⁵ is already the standard
GrapheneOS answer and you very likely have it installed. **A is the right pick
only if "one app, no dependencies" outranks everything else** — in which case
the 7-day-token trap is the first thing to prove, not the last.

### 2.4 Purging is not the same as deleting

This constrains the whole ledger, so it is stated once here.

A two-way sync makes the calendar a **shared mutable store**. If XX-Calendar
drops a field it does not render, the next push writes that field back as empty
and destroys it in Google — and in every other client. Google Calendar on the
web will happily re-add what you removed.

So there are two different verbs:

- **PURGE** — never rendered, never surfaced, never notified about. Server-side
  state is left exactly as found.
- **SYNC-ONLY** — parsed, stored in the local mirror, written back byte-identical
  on push, and never shown to you.

Every "annoying" Google feature that lives in event data — Meet links,
attachments, auto-added bookings — is **SYNC-ONLY, not deleted**. The app's job
is to not show you things, not to vandalise your account. Deletion, where you
want it, is an explicit action you take, never a side effect of the client
disagreeing with a field.

---

## 3. Feature inventory

### 3.1 Views and navigation

| # | Feature | Rec | Notes |
|---|---|---|---|
| V1 | **Month view** | KEEP | Grid, chips per day. The default in most people's heads. |
| V2 | **Week view** | KEEP | 7-column time grid. |
| V3 | **Day view** | KEEP | Single time column. |
| V4 | **Schedule / Agenda view** | KEEP± | A flat list of what is next. Strip the illustrations and the weather row (§3.8). This is the view that most deserves to be the *default*. |
| V5 | **3-Day view** | PURGE | Exists because phones are narrow. Day and Week already bracket it. |
| V6 | **Custom view (2–7 days / 2–4 weeks)** | PURGE | A settings-menu answer to a layout problem. |
| V7 | **Year view** | DEFER | Cheap, occasionally genuinely useful for planning. Not v1. |
| V8 | **Infinite scroll between periods** | KEEP | Vertical in month/schedule, horizontal in week/day. |
| V9 | **"Today" button** | KEEP | One tap home. Non-negotiable. |
| V10 | **Date jump / mini-month picker** | KEEP | Tap the header, get a compact picker. |
| V11 | **Search** | KEEP | Substring over title, location, description, attendees. Local-only, instant. |
| V12 | **Navigation drawer** | KEEP± | Calendar list with visibility toggles. Google also stuffs Settings, Help, Feedback, Trash and account-switching in here; keep it to calendars + Settings. |
| V13 | **Pull-to-refresh** | KEEP | Manual sync trigger with a visible result. |
| V14 | **Multi-account switching** | DEFER | One account until there are two. Architecture B gets this free later regardless. |
| V15 | **Landscape / tablet two-pane** | PURGE | Phone app. |
| V16 | **Print** | PURGE | — |
| V17 | **Offline mode** | KEEP | Not a feature. The local store *is* the app; the network is the optional part. |

### 3.2 The event model and the editor

| # | Feature | Rec | Notes |
|---|---|---|---|
| E1 | **Title** | KEEP | |
| E2 | **Start / end datetime** | KEEP | |
| E3 | **All-day toggle** | KEEP | |
| E4 | **Per-event timezone** | KEEP | Two fields (start tz, end tz) as RFC 5545 allows. Rendered only when it differs from the device. |
| E5 | **Location (free text)** | KEEP | Plain string. |
| E6 | **Location autocomplete / Maps lookup** | PURGE | Sends your typing to Google as you type. A text field is a text field. |
| E7 | **Description / notes** | KEEP | Plain text. Google's HTML is rendered as text, round-tripped intact. |
| E8 | **Calendar assignment** | KEEP | Which calendar owns the event. |
| E9 | **Event colour override** | KEEP± | The mechanism survives; the *hue* does not. See §5.1. |
| E10 | **Availability (busy / free)** | KEEP | `TRANSP`. One line in the editor, matters for invitations. |
| E11 | **Visibility (default / public / private)** | SYNC-ONLY | Meaningless on a single-user calendar; destructive to drop. |
| E12 | **Attachments (Drive)** | SYNC-ONLY | Rendered as a count, if at all. No Drive integration, ever. |
| E13 | **Google Meet / conferencing link** | KEEP± | **Never generated.** When an incoming invitation has one, show the URL as tappable text. Google welding a Meet button onto every event you create is the single most-cited annoyance and it is a *creation* behaviour, not a display one. |
| E14 | **Title autocomplete / smart suggestions** | PURGE | Predictive text for your own life. Also a keystroke-by-keystroke network call. |
| E15 | **Natural-language quick add** | DEFER | Genuinely good when it works. Must be **fully on-device** or not at all. Post-v1. |
| E16 | **Drag to create** | KEEP | Long-press-drag on the time grid. |
| E17 | **Drag to move / resize** | KEEP | The reason a time grid beats a list. |
| E18 | **Duplicate event** | KEEP | Cheap, constantly useful. |
| E19 | **Delete event** | KEEP | With undo (E21). |
| E20 | **Copy to another calendar** | DEFER | |
| E21 | **Undo (snackbar)** | KEEP | Anything destructive gets a 5-second undo. |
| E22 | **Working location (home / office)** | PURGE | A status-broadcast feature for people with coworkers. |
| E23 | **Out of office** | DEFER | A real event type with auto-decline behaviour. Only earns its place alongside invitations (§3.4). |
| E24 | **Focus time** | PURGE | An event with a marketing name. Make an event. |
| E25 | **Appointment schedules / booking pages** | PURGE | Requires a public web surface. Antithetical. |

### 3.3 Recurrence

| # | Feature | Rec | Notes |
|---|---|---|---|
| R1 | **Presets** (daily, weekly, monthly, yearly, weekdays) | KEEP | Covers the overwhelming majority. |
| R2 | **Custom rule builder** (interval, by-day, by-month-day, nth-weekday) | KEEP | Full `RRULE`. Skimping here breaks real calendars. |
| R3 | **End condition** (never / on date / after N) | KEEP | `UNTIL` / `COUNT`. |
| R4 | **Exceptions** — a modified or deleted instance | KEEP | `EXDATE` / `RECURRENCE-ID`. **The classic source of calendar data corruption.** Test-first territory. |
| R5 | **Edit scope prompt** — this / this-and-following / all | KEEP | Ugly, unavoidable, correct. |
| R6 | **`RDATE`** (explicit extra dates) | SYNC-ONLY | Rarely authored by hand; must survive. |

### 3.4 Guests and invitations

**RESOLVED 2026-08-23: none of it.** XX-Calendar is a personal planner that
shows meetings other people made. Invitations sync in and render as ordinary
events; the app never participates in scheduling. This deletes the entire
scheduling surface and is the single largest simplification in the spec.

| # | Feature | Rec | Notes |
|---|---|---|---|
| G1 | **See the guest list on an incoming invite** | PURGE | *Resolved 2026-08-23: personal planner.* Attendee rows are left untouched in the provider. |
| G2 | **RSVP** (yes / no / maybe) | PURGE | *Resolved 2026-08-23.* An invitation renders as an ordinary event. You answer it wherever you read your mail. |
| G3 | **Invite guests to your own events** | PURGE | *Resolved 2026-08-23.* |
| G4 | **Guest permissions** (modify / invite others / see list) | SYNC-ONLY | |
| G5 | **"Find a time" / suggested times** | PURGE | Needs everyone's free-busy. Not a personal-calendar feature. |
| G6 | **Room / resource booking** | PURGE | Workspace. |
| G7 | **Email the guests** | PURGE | This is a mail client's job. |
| G8 | **RSVP nag notifications** | PURGE | "You haven't responded to…" is nagging, and it is the *notification*, not the RSVP, that is the annoyance. |
| G9 | **"Did you attend?" / post-event prompts** | PURGE | |

### 3.5 Notifications and alerts

Where a calendar earns trust or becomes noise. Every default here is
deliberately quieter than Google's.

| # | Feature | Rec | Notes |
|---|---|---|---|
| N1 | **Per-event notification, multiple** | KEEP | `VALARM`. Up to 5, as Google allows. |
| N2 | **Per-calendar default notification** | KEEP | |
| N3 | **All-day default notification time** | KEEP | Defaults to a specific hour the day before, not 00:00. |
| N4 | **Email notifications** | PURGE | The client has no business sending mail. Google-side email alarms are SYNC-ONLY. |
| N5 | **Snooze from the notification** | KEEP | |
| N6 | **Notification content preview** | KEEP± | **Off by default**, matching TxxT's posture. The lock screen says "Event in 10 min", not the title. |
| N7 | **"Time to leave" / traffic-aware alerts** | PURGE | Requires continuous location plus a traffic service. Two network dependencies for a feature that guesses. |
| N8 | **Daily agenda notification** | KEEP± | One notification, at a time you set, off by default. The genuinely useful one. |
| N9 | **Notifications for declined events** | PURGE | Never. If you said no, you said no. |
| N10 | **Notifications for auto-added events** | PURGE | Moot under §3.8. |
| N11 | **Quick-response from notification** | DEFER | Depends on G3. |
| N12 | **Full-screen / heads-up alerts** | KEEP± | Opt-in per calendar. Never the default. |
| N13 | **Persistent ongoing-event notification** | PURGE | |

### 3.6 Calendars, sharing, and import/export

| # | Feature | Rec | Notes |
|---|---|---|---|
| C1 | **Multiple calendars, per-calendar visibility** | KEEP | |
| C2 | **Create / rename / delete a calendar** | KEEP± | Local calendars in v1. Creating a *Google* calendar is a sync-write; DEFER under architecture B. |
| C3 | **Per-calendar colour** | KEEP± | See §5.1. |
| C4 | **Share a calendar with a person** | PURGE | Configure it on the web the once-a-decade you need it. |
| C5 | **Subscribe to an `.ics` URL** | DEFER | The clean, standards-based way to get holidays without Google's holiday product (§3.8). Strong post-v1 candidate. |
| C6 | **Import `.ics`** | KEEP | One file picker. Escape hatches are load-bearing for a local-first tool. |
| C7 | **Export `.ics`** | KEEP | Full export, all calendars. Same reason. |
| C8 | **JSON backup / restore** | KEEP | Family convention — TxxT and XX-Launcher both ship it. |
| C9 | **Trash / bin with 30-day recovery** | PURGE | Undo (E21) covers the real case. |
| C10 | **Google's "Interesting Calendars"** (sports, TV, moon phases) | PURGE | Content, in a calendar. |

### 3.7 Tasks, Reminders, and Goals

| # | Feature | Rec | Notes |
|---|---|---|---|
| T1 | **Google Tasks integration** | PURGE | *Resolved 2026-08-23: purged entirely, no local todo model either.* A separate product on a separate API rendered in a calendar grid. Tasks and events have different shapes; merging them is why the "+" button has five entries. Ours has one. |
| T2 | **Reminders** | PURGE | Google itself deprecated and folded these into Tasks. Do not resurrect. |
| T3 | **Goals** ("Run 3× a week", auto-scheduled) | PURGE | Auto-inserted events that nag and reschedule themselves. The purest example of the thing this project exists to avoid. |
| T4 | **Birthdays calendar** (auto-built from Contacts) | PURGE± | A read-only calendar you cannot edit, populated by data you did not put there. Purge the auto-calendar; a birthday is a yearly all-day event and the model already handles it. |
| T5 | **Holidays calendar** | KEEP± | Actually useful. Ship it as a bundled `.ics` per region, or via C5 — **not** as a Google-managed calendar. Off by default. |

### 3.8 Google-injected content — the annoyance layer

This section is the reason the project exists. Recommendation is PURGE across
the board; the argument is per-row.

| # | Feature | Rec | Notes |
|---|---|---|---|
| A1 | **Events auto-added from Gmail** — flights, hotels, restaurants, deliveries, bills, ticketed events | PURGE | Your inbox writing to your calendar. Under architecture A they are never pulled; under B they arrive and are filtered at render by their `eventType`/source. **SYNC-ONLY, never deleted server-side** (§2.4). |
| A2 | **Auto-generated event illustrations** | PURGE | The stock photograph of a plate of pasta because you typed "dinner". |
| A3 | **Weather row in Schedule view** | PURGE | XX-Launcher already has a weather widget. A calendar is not a weather app. |
| A4 | **"What's new" / feature-announcement takeovers** | PURGE | Never. No interstitials of any kind, for any reason. |
| A5 | **Promotional cards and upsells** | PURGE | |
| A6 | **Google Assistant integration** | PURGE | |
| A7 | **Time Insights** (analytics on your own meetings) | PURGE | Telemetry with a dashboard. |
| A8 | **Smart suggestions / "you might want to…"** | PURGE | |
| A9 | **Auto-decline outside working hours** | PURGE | Depends on Working Hours, which is Workspace. |
| A10 | **In-app rating / feedback prompts** | PURGE | |
| A11 | **Crash reporting, analytics, telemetry** | PURGE | Absent by construction. Under architecture B, absent by *manifest* — no `INTERNET` permission. |

### 3.9 Workspace-only features

Listed for completeness. All PURGE — none apply to a personal Gmail account and
each carries UI weight.

Working hours · Time Insights · Room booking · Speedy meetings (25/50-min
defaults) · Appointment schedules · Focus time · Out-of-office auto-decline ·
Shared external free-busy · Delegate access · Admin-managed calendars ·
Colleague schedule search.

### 3.10 Settings

Google Calendar ships roughly sixty settings across General and per-calendar
screens. The purge list above deletes most of them outright. What remains:

| # | Setting | Rec | Notes |
|---|---|---|---|
| S1 | **Start day of week** | KEEP | |
| S2 | **Default view on open** | KEEP | Schedule as the shipped default. |
| S3 | **Show week numbers** | KEEP | Cheap; some people need it badly. |
| S4 | **Show declined events** | KEEP± | Default **off**. Google defaults it on. |
| S5 | **Dim past events** | KEEP | Free with the opacity ramp. |
| S6 | **Default event duration** | KEEP | 30 / 60 minutes. |
| S7 | **Default notification time** | KEEP | |
| S8 | **12/24-hour time** | KEEP± | Follows the system. No in-app override — XX-Launcher's convention. |
| S9 | **Secondary timezone** | DEFER | |
| S10 | **"Ask to update timezone while travelling"** | PURGE | A prompt that appears at airports. |
| S11 | **Alternate calendar** (lunar, Hijri…) | PURGE | |
| S12 | **Theme / background preset** | KEEP | The seven named presets, plus theme sync from XX-Launcher (§5.2). |
| S13 | **Font, text size** | KEEP | Family convention. |
| S14 | **Density / display compactness** | KEEP± | Two options, not five. |
| S15 | **Backup / restore** | KEEP | C8. |
| S16 | **Sync frequency / manual sync** | KEEP | Under B, a deep-link to DAVx⁵ plus a visible last-sync timestamp. |

### 3.11 Surfaces beyond the app

| # | Feature | Rec | Notes |
|---|---|---|---|
| W1 | **Month widget** | KEEP | XX-Launcher is text-only and widget-driven; a calendar with no widget is half-installed. |
| W2 | **Schedule / agenda widget** | KEEP | Probably the more useful of the two. |
| W3 | **"At a glance" / next-event widget** | DEFER | |
| W4 | **Quick Settings tile** | DEFER | Nope-Mode establishes the pattern. |
| W5 | **App shortcuts** (long-press → New event, Today) | KEEP | Two lines of XML. |
| W6 | **Wear OS companion** | PURGE | |
| W7 | **Handle `.ics` files and `content://` calendar intents from other apps** | KEEP | Being the system's calendar handler is the whole point of replacing one. |
| W8 | **Theme-sync broadcast from XX-Launcher** | KEEP | TxxT already implements this receiver. Family consistency. |

---

## 4. The annoyance ledger — ranked

The specific behaviours that make Google Calendar tiring, worst first. If v1
fixes only these, it has justified itself.

1. **Gmail writes to your calendar** (A1) — events you never created, that you
   cannot bulk-remove, that notify you.
2. **Notification volume** (N7, N8, N9, N10, G8) — declined events, unresponded
   invites, traffic guesses, auto-added bookings.
3. **The "+" button has five entries** (T1, T2, T3, E23, E24, E25) — five
   pseudo-types layered onto one real one.
4. **Meet welded to every event** (E13) — a conferencing link on your dentist
   appointment.
5. **Illustrations and weather** (A2, A3) — decoration in an information
   display.
6. **Interstitials** (A4, A5, A10) — a full-screen takeover between you and
   your day.
7. **Goals reschedule themselves** (T3) — software that moves your commitments.
8. **Birthdays you cannot edit** (T4) — a read-only calendar from Contacts.
9. **Settings sprawl** (§3.10) — sixty toggles because no default has a spine.
10. **Play Services required** — the reason none of the above can simply be
    turned off on this device.

---

## 5. Brand application

Source of truth:
[`piercingxx-branding`](https://github.com/PiercingXX/piercingxx-branding) —
`BRAND-GUIDE.md` §3 and `tokens/colors.json`. Per the new-project checklist,
`tokens/android-colors.xml` is vendored into `res/values/` and updated by
re-copying. No hardcoded hexes.

### 5.1 The colour problem — the one real conflict

A calendar's primary information channel is hue. Google ships 11 named event
colours and a colour per calendar. The brand guide forbids exactly this: **one
accent, and pure white is reserved** (§3.1). This is XX-Vitals' ring-colour
problem (§9.1 there) but sharper, because calendar colour is *user-assigned and
semantically load-bearing* — it is how you tell work from personal at a glance
in a dense month grid.

**RESOLVED 2026-08-23 — Option 1.** The brand guide is not amended; the sigil
scheme stands. Recorded here with the alternatives it beat.

**Option 1 — Sigil + opacity tier (CHOSEN).** Hue is replaced by a
leading monospace sigil and a position on the white ramp. Monospace makes the
sigil column free — it costs one character cell and aligns perfectly by
construction.

```
Mon 24                             Tue 25
▌ 09:00  standup                   ▏ 11:00  dentist
▌ 14:00  design review             ░ 19:30  dinner — mum
▏ 18:00  gym

  ▌  work        text 90%
  ▏  personal    strong 80%
  ░  family      muted 50%
  ▒  subscribed  shade 25%
```

Selection and "now" are the only Signal-white elements on screen — the current
time rule, and the selected event as an inverted block (white ground, ink text),
exactly as the guide prescribes for strong emphasis. Four to six calendars read
apart cleanly. Beyond that, any scheme fails, hue included.

**Option 2 — Claim a product signal colour.** §3.4 permits one reserved accent
per product. It *replaces* white rather than joining it, so it buys one hue, not
eleven. Doesn't solve multi-calendar; would need Option 1 anyway.

**Option 3 — Write the exception into the guide.** BRAND-GUIDE.md closes with:
*"If a project needs to break a rule, the rule loses only after the break is
written down here."* A calendar is the strongest candidate in the family for
that break — a documented, bounded amendment (say, a five-tint set at reduced
saturation, calendars only, never chrome). It is a change to the brand system,
not to this app, so it is your call and not a spec decision.

Option 1 is recommended because it is the only one that needs no amendment and
it is genuinely better on AMOLED black. It is also the riskiest to be wrong
about, because a month grid is unforgiving — build a static mock of a dense
week in WS1 and look at it before committing.

### 5.2 Everything else

| Element | Treatment |
|---|---|
| Ground | `ink` `#000000`. The seven named presets available (§3.3 of the guide). |
| Grid lines, hour rules | `line` `#1AFFFFFF` |
| Past events | `shade` 25% — S5 is free |
| Body text ceiling | `text` `#E6FFFFFF` 90% — never full white |
| Current-time rule, selection, FAB | `signal` `#FFFFFF` — the only full-white things |
| Selected day / active tab | Inverted: white block, ink text |
| Sync status | Glyph-first: `✓` ok, `→` syncing, `⚠` stale, `✗` failed |
| Rare colours | Unused |
| Display type | Space Mono — dates, day numerals, times |
| Body type | JetBrains Mono — titles, list rows, editor |
| Weight | Light/regular. Bold is a scalpel. |
| Figures | **Tabular throughout.** A time column that reflows is the tell. Free with mono. |
| Fonts | Shipped in `res/font/`. Never system-dependent. |
| Icon | Underlined-XX logomark on an ink tile |
| Theme sync | Receiver for the XX-Launcher broadcast (W8) |

In-app copy uses the calm product register; README and commits use the dry
maker register. Empty state is one line: `Nothing scheduled.`

---

## 6. The proposed v1 cut

Assuming every recommendation above stands:

**Ships:** Schedule / Day / Week / Month · search · full event editor with
recurrence and exceptions · guest list and RSVP on incoming invitations ·
notifications (quiet defaults, daily agenda, no email) · multiple calendars with
sigil identity · `.ics` import/export · JSON backup · two widgets · seven theme
presets · Google sync via the chosen architecture.

**Absent by design:** 3-day and custom views · Tasks · Reminders · Goals ·
Gmail auto-added events · illustrations · weather · Meet generation ·
location autocomplete · title suggestions · time-to-leave · birthdays calendar ·
interesting calendars · trash · sharing · Workspace everything · analytics ·
interstitials.

That is roughly **thirty features kept out of a hundred and twenty catalogued**,
and the app is more useful, not less — which is the thesis.

**T1, Tasks — resolved 2026-08-23: purged, with no local replacement.** It was
the most arguable line in the ledger and it lost cleanly. The `+` button has one
entry.

## 7. Decisions — resolved 2026-08-23

| # | Question | Ruling |
|---|---|---|
| 1 | Sync architecture | **B** — `CalendarContract` + DAVx⁵. No `INTERNET` permission. |
| 2 | Colour | **Sigil + opacity tier.** Brand guide unamended. |
| 3 | Tasks | **Purged entirely.** No local todo model. |
| 4 | Invitations | **None.** Personal planner; invites render as ordinary events. |
| 5 | Line-item overrides | None taken. Every other recommendation in §3 stands. |

Rulings 1 and 4 compound: with no networking and no scheduling, XX-Calendar is a
renderer and editor over the platform calendar provider and nothing else. That
is a much smaller app than this teardown started out describing.

The spec is [design.md](../design.md); the build plan is [todo.md](../todo.md).
