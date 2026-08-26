# XX-Calendar — Remaining work

Spec: [design.md](design.md). Teardown:
[design/google-calendar-teardown.md](design/google-calendar-teardown.md).
Target: Pixel 9 Pro (`caiman`), GrapheneOS, Android 17 / SDK 37. Currently
installed on a Pixel 6 under GrapheneOS.

**Status: code-complete; ship gate is still the device suite.** Everything
below that could be done off-device has been done off-device (2026-08-26
pass): WS13–WS16 and 17.1 are landed with red-proofed regression tests
(409 / 0 JVM, `lint` green, `assembleDebug` and `assembleDebugAndroidTest`
green, debug APK still has no `INTERNET`, release signs with the recovered
family key). What remains open is exactly what this machine cannot see:
the instrumented suite has never executed against CalendarProvider2, and a
handful of provider behaviors (exception-URI acceptance from a normal client,
`UID_2445` on import insert, synced-row bookkeeping columns) are only
answerable on hardware. Those are listed under 17.2 and Device-gated.

Toolchain (deltas from design §14): AGP 8.9.1, Gradle 8.11.1, compileSdk /
targetSdk 35, Kotlin 2.1.20 with its Compose compiler plugin, Java/jvmTarget 17,
minSdk 26, Robolectric 4.13.

Known inert edges, shipped knowingly: widget text uses `FontFamily.Monospace`
because Glance/RemoteViews cannot load `res/font`; widgets stay on the Ink
ground whatever theme is synced. glance-appwidget pulls `WAKE_LOCK`,
`ACCESS_NETWORK_STATE`, and `FOREGROUND_SERVICE` transitively — `INTERNET` is
still absent.

---

## Read this before starting

**There is no network in this app.** DAVx⁵ owns the Google account and the
OAuth; XX-Calendar is a normal `ContentProvider` client and nothing else
(design D2, D3). The manifest does not declare `INTERNET`. If a task ever
seems to need a network call, the task is wrong.

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

| Risk | Why it was the work | Status |
|---|---|---|
| **Recurring-event edits** | This/Following/All writes are where calendar clients silently corrupt data. `ScopeResolver` was correct in JVM isolation; the UI never fed it the occurrence time, and the delete path talked to the wrong URI. | **Fixed off-device** (WS14); device suite pending |
| **Provider write contract** | A normal client must not write `SYNC_DATA*`. An update must not leave both `DTEND` and `DURATION`. `FakeCalendarProvider` accepted both, so green tests were not evidence. | **Fixed off-device** (WS13 + fake validators); device suite pending |
| **Reminder reliability (R7)** | Reconcile from the provider; never chase broadcasts. Exact-alarm denial has a Settings row; notification denial did not, and `POST_NOTIFICATIONS` was never requested. Hidden auto-added events could fire an alarm. | **Fixed off-device** (15.3 + WS16); device suite pending |
| **The sigil scheme** | Hue is forbidden. If `▌ ▏ ░ ▒` does not read in a dense month grid, three views get rebuilt. Mock exists; on-phone verdict is still pending. | **Open — owner decision** |

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
./gradlew testDebugUnitTest lint assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.piercingxx.calendar/.MainActivity
```

The R3 INTERNET gate is a local pre-push hook at `.githooks/pre-push`. It is
**not** active in a fresh clone until:

```sh
git config core.hooksPath .githooks
```

CI job `32654322807` died on a billing failure; private-repo Actions minutes
are billed. Until billing is fixed, the hook is the only gate, and only if
`core.hooksPath` is set.

Family signing identity: done (WS18). The shared keystore was recovered from
git history into gitignored `app/debug.keystore` + `keystore.properties`;
release builds sign with the family cert, verified by apksigner fingerprint.
No signing material is committed — `*.keystore` stays gitignored.

Vendor the brand tokens rather than retyping hexes:

```sh
curl -sL https://raw.githubusercontent.com/PiercingXX/piercingxx-branding/main/tokens/android-colors.xml \
  -o app/src/main/res/values/pxx_colors.xml
```

On-device, install DAVx⁵ and add the Google account before claiming the
provider layer works. WS3's open questions and the instrumented suite both
need real synced rows.

---

## Status at a glance

| WS | Scope | State |
|---|---|---|
| 1 | `core/` + the sigil mock | done (on-phone mock verdict still pending) |
| 2 | Skeleton, theme, fonts, icon, permission gate | done (`POST_NOTIFICATIONS` requested; family keystore recovered) |
| 3 | `CalendarRepository` — the provider layer | done off-device (WS13); local-insert proof device-gated |
| 4 | Schedule view | done |
| 5 | Day + Week time grids | done (recurring drag routes to the editor) |
| 6 | Month grid + day peek | done (peek taps open detail with occurrence begin) |
| 7 | Detail sheet, editor, recurring writes | done off-device (WS14); device suite pending |
| 8 | Reminders | done off-device (`POST_NOTIFICATIONS` + WS16 filters) |
| 9 | Settings | done (visible rows drive behavior) |
| 10 | `.ics` + JSON backup | landed off-device; `UID_2445` insert unproven on hardware |
| 11 | Widgets, shortcuts, intent filters, theme sync | done (`.ics` VIEW imports the handed-over URI) |
| 12 | CI privacy gate + instrumented suite | written; CI billing dead; suite has never run |
| **13** | **Provider writes that will fail on a real device** | **landed off-device; insert proof device-gated** |
| **14** | **Occurrence identity and recurring scope** | **landed off-device** |
| **15** | **Surfaces that lie or do not work** | **landed off-device** |
| **16** | **Reminders that match the visible calendar** | **landed off-device** |
| **17** | **Honest tests, then the device suite** | **fake taught (17.1 done); 17.2 open — ship gate** |
| **18** | **Release machinery** | **keystore recovered + hook active; CI billing open (user)** |

v1 ships after WS13–17 are green on a device with DAVx⁵ data. WS18 is required
before any APK leaves this machine.

---

## Suggested order

1. ~~**WS13**~~ — done off-device (2026-08-26).
2. ~~**WS14**~~ — done off-device, including the review-pass fixes
   (All-events anchor delta, dragged-instance identity, refused-insert
   surfacing).
3. ~~**WS15**~~ — done off-device.
4. ~~**WS16**~~ — done off-device.
5. **WS17** — 17.1 (fake rules) done; **17.2 is now the only thing between
   this tree and a ship decision**: `./gradlew connectedDebugAndroidTest` on
   the Pixel 6. Treat the first red run as evidence, not a formality.
6. **WS18 + device-gated evidence** — keystore and hook are done; CI billing,
   WS3's two open questions, and the on-phone sigil-mock verdict remain.

---

## WS13 — Provider writes that will fail on a real device

The JVM suite is green because `FakeCalendarProvider` `putAll`s any column and
never validates. CalendarProvider2 does not.

### 13.1 Do not write `SYNC_DATA*` as a normal client

- [x] Add `Events.SYNC_DATA1` through `Events.SYNC_DATA10` to
      `OpaqueColumns.NON_PRESERVED_COLUMNS`
      (`app/src/main/java/com/piercingxx/calendar/calendar/OpaqueColumns.kt`).
      `_SYNC_ID` / `DIRTY` / `UID_2445` are already stripped. `CUSTOM_APP_*`
      stay — those are app-writable and carry conferencing URIs.
- [x] Confirm `HeldValues.mergeInto` does **not** put stripped keys. Absence
      on update is what preserves DAVx⁵ href/etag; cloning them onto a new
      exception or continuation row is what `verifyNoSyncColumns` rejects.
- [x] Cover it: load a fake row with `SYNC_DATA1` populated, save a title-only
      edit, assert the update `ContentValues` has no `SYNC_DATA*` keys. Same
      for a This-instance exception insert and a This-and-following
      continuation insert (`RecurrenceEditor.kt`).

`loadEvent` queries `Events.CONTENT_URI` with a null projection
(`CalendarRepository.kt`), so `OpaqueColumns.capture` currently holds every
preservable column. DAVx⁵ stores href/etag in `SYNC_DATA*`. Net effect today:
title-only edits of synced events, This-instance exception inserts, and
This-and-following continuation inserts are expected to throw
`IllegalArgumentException("Only sync adapters may write to " + column)`.

### 13.2 Never leave both DTEND and DURATION on an update

- [x] `EventDraft.writeModeledInto`
      (`CalendarRepository.kt`) currently writes `DURATION` **or** `DTEND` and
      never `putNull`s the unused one. CalendarProvider2 merges the update onto
      the existing row and, for a normal client, does not scrub.
      `validateEventData` then throws `"Cannot have both DTEND and DURATION in
      an event"`.
- [x] Always write both: `put` the one in use, `putNull` the other.
- [x] All-day recurring Google/DAVx⁵ rows typically carry `DURATION=P1D`.
      `EditorState.buildDraft` always emits an exclusive `DTEND` for all-day.
      Follow the provider recurrence shape (`DURATION`, `DTEND` null) for
      repeating all-day, not the single-event exclusive end. (Landed as
      repository-side normalization to `P<n>D`; `buildDraft` itself unchanged.)
- [x] Tests for: adding a repeat to a timed single event; removing a repeat;
      saving an all-day recurring row that arrived with `DURATION=P1D`.

The insert path is fine. Updates of existing provider rows are not.

### 13.3 Create a local calendar on first run, for real

Design §4.4 and the checked WS3 box require this. It is not wired.

- [x] `LocalCalendarBootstrap.ensureWritableCalendar` has **zero production
      call sites** — only `ProviderFixture` and unit tests. Call it from
      `AppShell` once calendar permission is granted.
- [x] A fresh install without DAVx⁵ cannot save: the editor leaves
      `calendarId == 0` and disables Save.
- [ ] Prove the insert on device. The insert now uses the documented
      `CALLER_IS_SYNCADAPTER=true` + `ACCOUNT_NAME`/`ACCOUNT_TYPE=LOCAL`
      query-param form (the local-calendar exception, not general sync-adapter
      impersonation), pinned by `LocalCalendarBootstrapTest`. Whether a given
      DAVx⁵/Google build accepts it without extra quirks is only answerable on
      hardware.

---

## WS14 — Occurrence identity and recurring scope

`ScopeResolver` is the one piece that is actually correct. Everything around
it feeds it the series anchor, or talks to the wrong URI.

### 14.1 Thread the tapped occurrence into detail and editor

- [x] Every view navigated with only `eventId` (`MainActivity.kt`,
      `DayScreen.kt`, `WeekScreen.kt`). `EditorScreen` then built
      `InstanceRef(original.eventId, original.draft.startMillis)` off the
      **parent row's DTSTART**. `DetailSheet` did the same on delete.
- [x] Thread `CalendarInstance.startMillis` (and parent vs exception id) from
      the tap through `detail/{eventId}?start=` into both `DetailSheet` and
      `EditorScreen`, and pass that as `InstanceRef.instanceStartMillis`.
      Schedule taps, Day/Week grid blocks, all-day rows, and Month peek all
      carry the tapped begin now.
- [x] Prefill the editor from the occurrence, not the series anchor.
- [x] Tapping occurrence #3 of a weekly series showed the first
      occurrence's times; This-instance / This-and-following applied to
      occurrence #1. Fixed: the UI supplies the occurrence time everywhere the
      data exists locally. An "All events" save that changes times now applies
      as an anchor delta (GCal-like), so the series head is not truncated —
      regression-tested in `AllEventsOccurrenceShiftTest`.

### 14.2 Stop deleting `Events.CONTENT_URI/{millis}`

- [x] `RecurrenceEditor.deleteInstanceUri` deleted
      `content://com.android.calendar/events/{instanceStartMillis}`.
      CalendarProvider2 matches `events/#` as `EVENTS_ID` and calls
      `deleteEventInternal(ContentUris.parseId(uri), …)` — that id is an event
      `_ID`, not an instance begin. An epoch-millis path segment will not
      match a real row (no-op) or, in the absurd collision, delete a random
      event. It does **not** write EXDATE.
- [x] Insert a canceled exception (`Events.CONTENT_EXCEPTION_URI` +
      `ORIGINAL_INSTANCE_TIME` + `STATUS_CANCELED`) — the path AOSP Calendar's
      `DeleteEventHelper` uses. Done; a refused insert now surfaces as a
      failure outcome instead of a false success. Whether CalendarProvider2
      accepts this URI from a normal client (AOSP's own helper builds it with
      `CALLER_IS_SYNCADAPTER=true`) is device-gated — see 17.2 notes.
- [x] Replace the unit test that only asserted the URI string
      (`RecurrenceEditorTest`). Tests now assert the exception row's fields and
      observe the occurrence disappearing from expansion
      (`RecurrenceEditorInstanceDeleteTest`); the instrumented suite asserts
      the same against the real provider.

The 2026-08-23 EXDATE-split on This-and-following
(`RecurrenceEditor.splitRecurrenceDates`) is fixed in source. End-to-end it
still depends on this delete actually writing an exclusion.

### 14.3 Keep UNTIL on a This-and-following split

- [x] `ScopeResolver` set `remainingRule = rule.copy(end = EndCondition.Never)`
      (`ScopeResolver.kt`). Design §6.3 says the new row carries "the remaining
      rule." COUNT is correctly refused; UNTIL was just thrown away. A
      `FREQ=WEEKLY;UNTIL=20261231T000000Z` series split in October became an
      infinite continuation.
- [x] Keep the original `EndCondition.Until` on `remainingRule`. Leave COUNT
      as a refusal.
- [x] Update `ScopeResolverTest` — it asserted the Never reset; it now asserts
      UNTIL is preserved and COUNT still refused.
- [x] Watch `EditorScreen`: an "All events" save with changed times now
      applies as an anchor delta rather than re-anchoring to the tapped slot,
      so no accidental papering-over and no series-head truncation.

### 14.4 Recurring drag-move must not rewrite the series

- [x] `moveTimedEvent` (`GridWrites.kt`) loaded the parent and rewrote
      `DTSTART` in place, with no scope prompt. `TimeGrid.kt` documented this:
      "recurring events shift the series." Dragging one block of a weekly
      event moved every occurrence.
- [x] Resize already refused recurring rows (`GridWrites.kt`). Move now
      refuses the same way and sends the user through the editor + §6.3
      prompt, carrying the dragged occurrence's begin so the prompt targets
      the dragged instance.

### 14.5 Calendar changes on a repeating event are silently dropped

- [x] `EventFieldEdits` had no `calendarId` (`EditorState.kt`). Recurring
      saves went through `diffEdits` → `ScopeResolver` →
      `RecurrenceEditor.appliedTo`, which cannot move the event. Non-recurring
      `directSave` writes the full draft and works.
- [x] Add `calendarId` to `EventFieldEdits` and copy it in `appliedTo` /
      exception / split inserts.

---

## WS15 — Surfaces that lie or do not work

### 15.1 Month peek taps are inert

- [x] `MonthScreen`'s chrome passed no `onEventClick`. Month is a first-class
      view and the last-view persistence target; you could not open or edit an
      event from it (`MainActivity.kt`).
- [x] Pass the same navigation as Schedule/Day/Week, including the instance
      start (DayPeek taps carry the tapped instance now, not a first-match
      lookup).

### 15.2 All-day detail times drift west of UTC

- [x] `detailTimeText` (`DetailSheet.kt`) converted all-day `DTSTART` with the
      **device** zone, then converted exclusive `DTEND` with UTC. AgendaGrouping
      and `TimeMath` correctly use UTC for all-day dates so they do not drift.
- [x] West of UTC, an all-day June 10 event (stored `2026-06-10T00:00Z`)
      rendered as June 9 in the sheet, and a multi-day span could disagree with
      itself on the start vs end date.
- [x] Use `TimeMath.storageToAllDayDate` for both ends, matching
      `EditorForm.fromLoaded`. The sheet now renders the tapped occurrence's
      times when one was supplied.

### 15.3 Request `POST_NOTIFICATIONS`

- [x] The permission gate (`MainActivity.kt`) requested only `READ_CALENDAR`
      and `WRITE_CALENDAR`. `POST_NOTIFICATIONS` is in the manifest and never
      requested.
- [x] On API 33+ (target 35; the Pixel 6 install is well past 33)
      `ReminderReceiver` returns without posting if the runtime grant is
      missing. Exact-alarm denial has a Settings warn row; notification denial
      was silent.
- [x] Request `POST_NOTIFICATIONS` with the calendar grant (same single
      dialog; below API 33 it is auto-granted), and surface a Settings warn
      row when it is denied, matching the exact-alarm row.

### 15.4 EditorActivity ignores CalendarContract extras and `onNewIntent`

- [x] Exported INSERT/EDIT/VIEW handler, `singleTask`. `onCreate` read only
      `intent.data.lastPathSegment` as an event id and never
      `CalendarContract.EXTRA_EVENT_BEGIN_TIME` /
      `EXTRA_EVENT_END_TIME` / `EXTRA_EVENT_ALL_DAY`.
- [x] There was no `onNewIntent`, so a second INSERT while the editor was up
      kept the first form. Other apps' "add to calendar" intents opened a blank
      next-half-hour event.
- [x] Parse the standard extras into `EditorScreen(initialStartMillis=…)`,
      with `onNewIntent` resetting form state from the new intent.
      (`parseEditorIntent` is JVM-tested in `EditorIntentTest`.)
- [x] Feed `SettingsStore` into `CalendarTheme` here — matching
      `SettingsActivity`, so text size/density apply.

### 15.5 All-day reminder label lies about "same day"

- [x] `allDayLabel` (`SettingsScreen.kt`) printed `"day before"` whenever
      `daysBefore <= 1`. The preset list includes
      `AllDayNotification(hourOfDay = 18, daysBefore = 0)` and
      `…(hourOfDay = 8, daysBefore = 0)`, which fire on the event's own
      calendar date. Those two stops were labelled wrong.
- [x] Branch `0 -> "same day"`, `1 -> "day before"`, else `"N days before"`.

### 15.6 Week view ignores start-of-week

- [x] `WeekWindowState` was hard-anchored to ISO Monday (`mondayOf`). Settings
      `startDayOfWeek` was wired for Month and the mini-month picker only. A
      user who sets Sunday still saw Monday-first week columns.
- [x] Parameterize `WeekWindowState` with the setting, like `MonthScreen`;
      the MainActivity call site passes the live setting.

### 15.7 `.ics` VIEW filter claims a file it will not open

- [x] The `text/calendar` VIEW filter was registered as "being the handler."
      A VIEW of an `.ics` URI only opened Settings and toasted "pick the file
      from there," discarding the URI the OS already handed over
      (`MainActivity.kt`).
- [x] Parse `intent.data` through the existing bounded reader +
      `IcsCodec.parse` / `IcsExchange.insertDrafts`, reusing the Settings
      target-calendar picker. The VIEW filter stays because it now tells the
      truth. (Known residual: a mid-import configuration change cancels the
      import coroutine; partial rows persist with no toast — S3, tracked.)

---

## WS16 — Reminders that match the visible calendar

- [x] Views and widgets run `InstanceFilters` (declined + auto-added). The
      reconciler (`ReminderReconciler.kt`) queried raw `repository.instances`
      and only applied the declined skip inside `ReminderPlanner`.
- [x] `hideAutoAdded` defaults to true. A Gmail-booking row the detector
      hides in the UI could still fire an alarm.
- [x] Apply the same `InstanceFilters.apply(...)` in the reconciler before
      `ReminderPlanner.plan`, with a settings-read failure falling back to the
      pre-filter behavior.
- [x] Test: an auto-added instance in the next 48h is absent from the
      planned alarm set when the setting is on (`ReminderReconcilerFilterTest`).

---

## WS17 — Honest tests, then the device suite

### 17.1 Teach the fake the three provider rules

`FakeCalendarProvider` expansion is DAILY/WEEKLY + INTERVAL/UNTIL only,
ignores BYDAY, and never subtracts EXDATE/RDATE. Combined with 14.2, JVM
tests of "delete this instance" cannot observe a resurrected occurrence.

- [x] Honor EXDATE (and RDATE) when expanding.
- [x] Reject `SYNC_DATA*` writes from a normal client, matching
      `verifyNoSyncColumns` — widened to `_SYNC_ID`, `DIRTY`, `MUTATORS`,
      `DELETED`, `ORIGINAL_SYNC_ID`, `LAST_SYNCED` as well. `UID_2445` and
      `CUSTOM_APP_*` stay allowed pending the device probe.
- [x] Refuse rows that carry both DTEND and DURATION, matching
      `validateEventData`.
- [x] Keep the documented "not Google's expansion" limit for FREQ=MONTHLY /
      YEARLY — do not pretend to be CalendarProvider2's expander. Do pretend
      to be its write validator. (COUNT is still ignored during fake
      expansion — documented limit; the instrumented suite exercises the real
      expander.)

The 2026-08-23 note still applies: fix the fake as part of fixing the bug,
otherwise the regression test cannot fail.

### 17.2 Run the instrumented suite on the Pixel 6

The suites (13 tests) compile and have never been executed against
CalendarProvider2:

- `EventRoundTripTest` — create / edit / delete
- `OpaqueColumnPreservationTest` — unmodelled columns survive a title edit
- `RecurringScopeRoundTripTest` — This / Following / All, including
  delete-then-split EXDATE
- `ReminderReconciliationAfterBootTest`

```sh
./gradlew connectedDebugAndroidTest
```

- [ ] Run it **after** WS13 and WS14 have a real chance of passing. They now
      do — this is the next action and the ship gate. A first red run is the
      actual evidence, not a formality.
- [ ] Record what CalendarProvider2 does with `UID_2445` on a normal-client
      import insert (WS10's remaining unknown). If it refuses, keep a local
      UID→eventId map and say so in Settings. (The fake deliberately allows
      `UID_2445` until this is answered either way.)
- [ ] Record what a DAVx⁵ row's `SYNC_DATA*` / auto-added markers actually
      look like (feeds WS3 open question 1).
- [ ] NEW (2026-08-26 review): confirm a normal-client insert into
      `Events.CONTENT_EXCEPTION_URI` is accepted (AOSP's own
      `DeleteEventHelper` builds it with `CALLER_IS_SYNCADAPTER=true`). If
      refused, fall back to a plain Events insert carrying
      `ORIGINAL_ID`/`ORIGINAL_INSTANCE_TIME`/`STATUS_CANCELED`, or the narrow
      sync-adapter form.
- [ ] NEW (2026-08-26 review): watch whether synced-row edits trip provider
      rejection on bookkeeping columns (`version` et al.) that the fake does
      not model; widen `OpaqueColumns.NON_PRESERVED_COLUMNS` if so.

Do not enable R8 until this is green.

---

## WS18 — Release machinery

- [x] **Family signing identity.** The shared Nope-Mode keystore was recovered
      from git history (byte-identical blob in Nope-Mode, xx-launcher, and
      this repo's own history — sha256 `dc794ef4…`, alias `androiddebugkey`)
      and restored to gitignored `app/debug.keystore` + `keystore.properties`.
      `assembleRelease` signs with the family cert (apksigner SHA-256
      `ed57aa0a…de13`, not the machine-local debug key). No new key was
      generated, so no uninstall-and-lose-state. `isMinifyEnabled = false`
      stays until 17.2 is green.
- [x] **Activate the INTERNET gate in this clone.**
      `git config core.hooksPath .githooks` is set; README documents it as an
      activate-in-your-clone step. The hook rebuilds if stale and rejects any
      push whose APK gains `INTERNET`.
- [ ] **Restore CI billing**, or accept that the local hook is the only R3
      enforcement. Run `32654322807` died in two seconds: *"the job was not
      started because recent account payments have failed or your spending
      limit needs to be increased."* Private-repo Actions minutes are billed.
      The workflow itself (`.github/workflows/ci.yml`) already runs
      `testDebugUnitTest`, `lint`, `assembleDebug`, `assembleDebugAndroidTest`,
      and the `aapt2 dump permissions` gate. **Owner action — cannot be done
      from this machine.**
- [x] Confirm the first real `assembleRelease` resolves `storeFile` as given
      (done with the recovered key; see above).

---

## Device-gated evidence (cannot close off this machine)

- [ ] **On-phone review of the sigil mock**,
      [design/mock/sigil-mock.html](design/mock/sigil-mock.html). The mock's
      own verdict: six tiers collapse to ~4 perceptual groups at phone sizes.
      Decision pending owner. If it does not read, design §7.1 gets revisited
      — this is still the cheapest moment to discover the scheme fails, even
      after three views exist.
- [ ] **WS3 open question 1:** what marks a Gmail auto-added event once it
      has come through DAVx⁵? Inspect real synced rows. If there is no
      reliable marker, fall back to a per-calendar hide and record that in
      the spec. The filter itself is wired (`hideAutoAdded` drives
      `AutoAddedDetector` at every instance-consumption site except the
      reconciler — WS16); the assumption under it is still untested.
- [ ] **WS3 open question 2:** measure `Instances` expansion cost over a
      month of a real, busy, heavily-recurring account.
- [ ] **`UID_2445` on import insert**, as part of 17.2. CalendarContract
      lists it as writable by apps *and* sync adapters (it is not in
      `Events.SYNC_WRITABLE_COLUMNS`), so the write should stick on AOSP
      CalendarProvider2. Still unproven on this device.

---

## Docs honesty

README currently says "326 unit tests" and points at [review.md](review.md)
and this file as the open list. The 2026-08-23 P0s in review.md are closed
off-device; this file is the current list.

- [x] README test count: current green count (409 as of the 2026-08-26 pass),
      not 326.
- [x] README "open list" points here first. review.md stays as the
      2026-08-23 record, with a one-line pointer at the top that its P0s are
      fixed and remaining work lives in todo.md.
- [x] Do not claim the pre-push hook is active via `core.hooksPath` until
      WS18's config step is done. (Done in this clone; README phrases it as an
      activate-in-your-clone instruction.)
- [x] Do not restate "all twelve workstreams are implemented" as a ship
      claim. Feature-complete against the *plan* is still true; correct
      against a real provider is not, until 17.2 is green.

---

## Prior P0s (2026-08-23) — closed off-device

Checked against the tree after `bfe15db` and the follow-up commits. Do not
re-open these unless a regression test goes red.

| # | Finding | Disposition |
|---|---|---|
| 1 | `Instances.DTSTART` read where `BEGIN` was meant | **Fixed.** `InstanceQuery` sorts by `Instances.BEGIN` and reads `BEGIN`/`END`. Fake now leaves series `DTSTART` on the row and puts occurrence times in `BEGIN`/`END`. `InstanceQueryTest` asserts a weekly third occurrence reports the third start. |
| 2 | Calendar visibility toggles did nothing | **Fixed.** `InstanceQuery` selection is `Calendars.VISIBLE=1`. Test: hidden calendar instances are absent from results. |
| 3 | This-and-following dropped tail EXDATEs | **Fixed in source.** `RecurrenceEditor.splitRecurrenceDates` partitions RDATE/EXDATE at `splitMillis`. End-to-end still depends on 14.2 actually writing an exclusion, and on 17.2. |
| 4 | Every setting written and never read | **Fixed for that complaint.** Visible switches drive behavior (`InstanceFilters`, theme scale/density, editor defaults, reminder policy, last-used view, week numbers / start-of-week on month). Hidden keys (`dimPast`, `dailyAgenda`, `background`, `font`) stay persisted and are honestly hidden. Remaining gaps are WS15/WS16, not "nothing is read." |
| 5 | `.ics` import duplicate detection could not see its own imports | **Fixed in source.** Export uses `event.uid` (`UID_2445`) when present; import writes `Events.UID_2445` on insert; test: importing the same file twice inserts zero rows the second time. Unproven on device — 17.2. |

Also closed off-device from that pass: font licensing (`third-party/OFL.txt` +
`NOTICE`, LICENSE carves the fonts out), local-setup activity class name,
Java 17, `lint` in CI, `distributionSha256Sum` on the wrapper, README
"reminders" wording.

---

## Historical workstreams (WS1–WS12)

The original build. Left here so the map of the tree is still in one file.
Unchecked boxes below are the honest remainder, also listed above.

### WS1 — `core/`, and the decision that gates everything

Pure JVM. No Android imports in this package, ever — that boundary is what
makes the recurrence logic testable (design §5).

- [x] `RRuleModel` — build/parse the five presets and custom rules (interval,
      by-day, by-month-day, nth-weekday, `UNTIL`/`COUNT`). Round-trip tests.
- [x] `ScopeResolver` — every row of design §6.3 as a plain data object
      describing the intended provider writes. **The priority suite.**
      14.3 landed: UNTIL survives a following-split; COUNT still refused.
- [x] `TimeMath` — all-day UTC-midnight conversion tested at `UTC-11`, `UTC`,
      `UTC+13`, and across DST in both directions.
- [x] `SigilAssigner` — stable ordering, survives remove/re-add, honours
      overrides.
- [x] `AgendaGrouping` — day boundaries, multi-day and all-day placement.
- [x] **The mock.** Built (`design/mock/sigil-mock.html`).
- [ ] On-phone review of the mock, at actual size. See Device-gated.

### WS2 — Skeleton

- [x] Gradle to design §14, with the toolchain deltas noted above.
- [x] `CalendarTheme` — tokens vendored from the branding repo, not retyped.
- [x] Space Mono + JetBrains Mono in `res/font/`. Tabular figures on.
      OFL 1.1 text in `third-party/OFL.txt`; `NOTICE` lists both families.
- [x] Underlined-XX adaptive icon on an ink tile.
- [x] Permission gate — one screen, one button, no partial UI (design §10).
      15.3 landed: `POST_NOTIFICATIONS` rides the same dialog, with a Settings
      warn row on denial.
- [x] `debug.keystore` from Nope-Mode — recovered from git history; see WS18.

### WS3 — The provider layer

- [x] `InstanceQuery` — `Instances.query()` over a range, off the main
      thread. Reads `BEGIN`/`END`; filters `Calendars.VISIBLE=1`.
- [x] `CalendarRepository` — calendar list, load/save/delete event,
      reminders.
- [x] **`OpaqueColumns`** — every column not in design §6.2 read, held,
      written back untouched (D8). 13.1 landed: `SYNC_DATA1..10` stripped.
- [x] `ContentObserver` → invalidate the visible window.
- [x] Create a local calendar on first run when none is writable (§4.4) —
      wired from `AppShell` post-grant; insert uses the documented
      sync-adapter local-account form. On-device proof: 13.3 / 17.2.
- [ ] Open question 1 — auto-added marker through DAVx⁵. Device-gated.
- [ ] Open question 2 — `Instances` expansion cost. Device-gated.

### WS4 — Schedule view · first honest milestone

- [x] Infinite scroll, empty days skipped, `Nothing scheduled.` empty state.
- [x] Sigil + opacity per calendar. Past events at `shade`.
- [x] Current-time rule in signal white — one of only two full-white
      elements.
- [x] Top bar, `Today` button, mini-month picker, drawer with visibility
      toggles (now consumed by `InstanceQuery`).
- [x] FAB with **one** action.

### WS5 — Day and Week

- [x] Time grid, hour rules at `line`, Space Mono hour labels.
- [x] Event blocks with the sigil bar; all-day pinned header row.
- [x] Drag to create, drag to move, edge-resize. 15-minute snap.
      14.4 landed: recurring moves refuse and route through the editor's
      §6.3 prompt with the dragged occurrence's begin.
- [x] Week: seven columns, today's numeral inverted.
      15.6 landed: the grid follows `startDayOfWeek`.

### WS6 — Month

- [x] 7×N grid, out-of-month at `shade`, today inverted.
- [x] Up to three chips per cell, then `+N`.
- [x] Day peek beneath the grid — the month stays visible.
      15.1 landed: peek taps open the tapped instance.
- [x] Week-number gutter (S3).

### WS7 — Editor · the correctness work

- [x] Detail sheet: no guest section, no RSVP (teardown §3.4). Conferencing
      URL as text if present; attachment count only. 15.2 landed: all-day
      times read in UTC on both ends, and the sheet shows the tapped
      occurrence's times.
- [x] Editor per design §8.5. No location autocomplete, no title suggestion.
- [x] RepeatBuilder — presets plus custom.
- [x] **`ScopePrompt` + the three recurring writes.** WS14 landed: occurrence
      identity threaded from every tap; delete-this-instance writes a canceled
      exception (not `events/{millis}`); UNTIL survives a following-split;
      calendar moves ride scoped saves; All-events time edits shift the anchor
      instead of re-anchoring.
- [x] Refuse and explain on an unmodelled recurrence shape. Never guess.
- [x] Undo on delete.

**Gate, still in force:** `ScopeResolver` suite green *and* the instrumented
round-trip green (17.2) before calling this done. This is the one that
corrupts data. The JVM side of that gate is green; 17.2 is the open half.

### WS8 — Reminders

- [x] `ReminderReconciler` — recompute the next 48h on boot, provider
      change, settings change, and a daily heartbeat. Diff and apply. Never
      chase individual events (design §4.3). WS16 landed: the reconciler
      applies `InstanceFilters` (declined + auto-added) before planning.
- [x] `AlarmScheduler` on `setExactAndAllowWhileIdle`.
- [x] `BootReceiver`, `ReminderReceiver`, notification channels.
- [x] Bundled chime `res/raw/xx_calendar.wav` on `reminders_v2` and
      `reminders_heads_up_v2`. Android freezes a channel's sound at creation,
      so shipping a sound means shipping a new channel id; the soundless v1
      ids are deleted in the same pass.
- [x] `canScheduleExactAlarms()` check → the one warn-coloured row in
      Settings. 15.3 landed: notification-denial warn row alongside it.
- [x] Quiet defaults: content preview off, heads-up off, daily agenda off
      (the last is hidden, not wired — documented).
- [x] Never notify for a declined event.

### WS9 — Settings

- [x] The sixteen survivors, laid out as design §8.6. Every *visible* row
      controls behavior. Hidden: `dimPast`, `dailyAgenda`, `background`,
      `font` — keys still persist and round-trip through backup. The
      `background` picker stays hidden because WS11's theme sync makes the
      launcher the picker for the whole family.
- [x] `SettingsStore` on DataStore.
- [x] Sigil override per calendar.
- [x] Auto-added-event filter, using whatever WS3 found — wired, assumption
      unproven. WS16 landed it in the reconciler too; the marker assumption
      itself stays device-gated (WS3 open question 1).
- [x] The honest sync row: last-changed timestamp plus an intent to DAVx⁵,
      and the sentence saying this app cannot see sync state.
- [x] `default view` doubles as the last-used view — the top-bar switcher
      writes the key, so launch reopens where you left off.
- [x] 15.5 (all-day label) and 15.6 (week start on the week grid) — landed.

### WS10 — Data

- [x] `.ics` export (RFC 5545) via SAF, all calendars or one. Uses
      `UID_2445` when present.
- [x] `.ics` import with `UID` duplicate detection; writes `UID_2445` on
      insert. Unproven on device — 17.2.
- [x] JSON backup/restore of settings + sigils only. Events are `.ics`.

### WS11 — Surfaces

- [x] Month and Schedule widgets in Glance. (Widget text is
      `FontFamily.Monospace` — Glance/RemoteViews cannot load `res/font`.)
- [x] App shortcuts: New event, Today.
- [x] Intent filters — be the system calendar handler (design §12).
      INSERT/EDIT/VIEW event registered with extras + `onNewIntent` (15.4);
      `.ics` VIEW now actually imports the handed-over file (15.7).
- [x] `ThemeSyncReceiver` for the XX-Launcher broadcast
      (`xx.launcher.THEME_CHANGED`), as TxxT implements it. Exported and
      unguarded — the family contract carries no permission, and the worst a
      spoof buys is another valid ground. Seven named grounds plus Custom.
      There is no in-app picker; the launcher is the picker.

### WS12 — Gates

- [x] CI workflow written: `aapt2 dump permissions` fails the build if
      `INTERNET` appears. Verified on the built APK: zero `INTERNET`.
- [ ] CI itself has never completed a run. See WS18.
- [x] Instrumented tests written: create/edit/delete round-trips, all three
      recurring scopes, opaque-column preservation, reminder reconciliation
      after a simulated boot.
- [ ] Instrumented tests executed on hardware. See 17.2.

---

## v1 ships after WS13–17 are green on device

Deferred, in rough order of appeal: `.ics` URL subscription (holidays without
a Google product) · year view · on-device natural-language quick add ·
secondary timezone · Quick Settings tile · next-event widget.
