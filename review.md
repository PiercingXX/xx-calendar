# XX-Calendar — Independent Review · 2026-08-23

> Every P0 below is fixed off-device; remaining work lives in
> [todo.md](todo.md). This file is kept as the 2026-08-23 record.

Read alongside [todo.md](todo.md). Everything below was checked against the
tree at `a7449bf`, not inferred from the plan. Where this contradicts
todo.md's own "Post-build review", this document is the later reading — its
items are all still open and are carried forward here.

## What I actually ran

```
./gradlew testDebugUnitTest lint     # BUILD SUCCESSFUL, 1m41s
```

- **247 tests, 0 failures, 0 errors, 0 skipped.** The count in README/todo.md
  is accurate.
- **`lint` passes** and is not currently wired into CI.
- Manifest declares six permissions; `INTERNET` is not among them.
- `.gitignore` correctly excludes `local.properties`, `build/`, `*.apk`,
  `*.ics`. No secrets in the tree.

So the build-health claims hold. The problem is what the green tests are
testing.

---

## P0 — Correctness. These are wrong against a real provider

### 1. `Instances.DTSTART` is read where `Instances.BEGIN` was meant

`app/src/main/java/com/piercingxx/calendar/calendar/InstanceQuery.kt:75`

```kotlin
startMillis = longOr(Instances.DTSTART) ?: 0L,
```

In `CalendarContract.Instances`, `BEGIN`/`END` are the *occurrence* times.
`DTSTART`/`DTEND` are inherited from `EventsColumns` and carry the *series*
start. CalendarProvider2 returns `Events.dtstart` unchanged for every
expanded row. So every occurrence of a recurring event reports the start of
the first occurrence.

The `endMillis` line below it falls back to `BEGIN`/`END` only when `DTEND`
is null — which is exactly the recurring case, since recurring rows carry
`DURATION` instead of `DTEND`. Net effect on a recurring event: **correct end
time, wrong start time**, on the same instance.

This is not a rendering nit. `repository.instances()` is the single source
for Schedule, Day, Week, Month, both widgets, and
`ReminderReconciler.reconcileLocked` — so reminders for recurring events are
planned off the series start too.

Same file, line 21:

```kotlin
const val DEFAULT_SORT_ORDER: String = "${Instances.DTSTART} ASC"
```

should sort by `Instances.BEGIN`, or recurring occurrences interleave wrong.

**Why the suite is green.** `FakeCalendarProvider.occurrenceRow()`
(`app/src/test/.../FakeCalendarProvider.kt:345`) writes
`row[Events.DTSTART] = start` *and* `row[Instances.BEGIN] = start` for each
generated occurrence. The double was built to match the implementation
rather than the platform, so the bug is invisible to all 247 tests. Fixing
the fake is part of fixing the bug — otherwise the regression test can't
fail.

- [ ] Read `Instances.BEGIN`/`Instances.END` for instance extent; keep
      `DTSTART` only where the series anchor is genuinely wanted.
- [ ] Sort by `Instances.BEGIN ASC`.
- [ ] Make `FakeCalendarProvider` emit `BEGIN`/`END` per occurrence while
      leaving `DTSTART`/`DTEND` at the series values, as the real provider
      does. Add a test that a weekly event's third occurrence reports the
      third occurrence's start.

### 2. Calendar visibility toggles do nothing

The drawer writes `Calendars.VISIBLE` (`CalendarDrawer.kt:78` →
`repository.setVisible`), and Settings has the same toggle. But
`InstanceQuery.query` passes `selection = null`
(`InstanceQuery.kt:62`), and no screen post-filters on `isVisible` — the only
reads of that field are the two toggle rows that render it.

Hiding a calendar therefore changes nothing in Schedule, Day, Week, Month,
or either widget. WS4's "drawer with visibility toggles" box is checked, but
the toggle is inert everywhere it matters.

- [ ] Add `Calendars.VISIBLE=1` to the `Instances` selection (the standard
      client filter), or filter by `calendarId` against the visible set.
- [ ] Test: two calendars, one hidden, assert its instances are absent.

### 3. A "this and following" split drops the tail's EXDATEs

`RecurrenceEditor.kt:214-215` builds the continuation row with
`rdate = null, exdate = null`.

`ScopeResolver.resolveDelete(ThisInstance)` deliberately resolves to
`DeleteInstanceUri` so the *provider* writes EXDATE, rather than inserting a
cancelled exception row (documented at `ScopeResolver.kt`, and asserted in
`RecurringScopeRoundTripTest.delete_thisInstance_lets_the_provider_write_exdate`).
That choice means tail exclusions live in the parent's EXDATE string — not
in exception rows.

`migrateTailExceptions()` re-points exception rows (`ORIGINAL_ID`) at the new
series, which is correct and careful. But there are no exception rows for
deleted instances, and the EXDATEs that do hold them are left on the
truncated parent (where they now sit past `UNTIL`, i.e. dead) and are never
copied to the continuation.

**Repro:** weekly series → delete occurrence #5 (this instance only) → edit
occurrence #3 with "this and following". Occurrence #5 comes back.

The two mechanisms are individually tested and their interaction is not.

- [ ] Split the parent's EXDATE/RDATE at `splitMillis`: entries before stay
      on the parent, entries at or after move to the continuation.
- [ ] Add the delete-then-split interaction test to
      `RecurringScopeRoundTripTest`.

### 4. Every setting except the two theme enums is written and never read

`SettingsStore` persists eighteen values. Outside `SettingsStore.kt`,
`BackupJson.kt`, and `SettingsScreen.kt`, the only files that reference it at
all are `MainActivity.kt` (import only — it never constructs one) and
`ThemeSyncReceiver.kt` (writes `background`). `CalendarTheme` and `Type.kt`
are fully static: `AppBackground`, `AppFont`, and `textSizeScale` are never
consulted.

todo.md admits this for the APPEARANCE rows. It is in fact **all eighteen**,
including the behavioural ones:

| Setting | Claimed | Actual |
|---|---|---|
| `showDeclined` | declined hidden by default | declined always render; the only declined check in the tree is in `ReminderPlanner` |
| `hideAutoAdded` | the app's headline feature | `AutoAddedDetector.isLikelyAutoAdded` has **zero production call sites** |
| `autoAddedFilterMode` | metadata vs per-calendar | inert |
| `startDayOfWeek`, `weekNumbers` | month/week layout | inert |
| `defaultView` | which view opens | inert |
| `dimPast`, `density` | rendering | inert |
| `defaultDurationMin`, `defaultNotificationMin` | editor defaults | inert |
| `allDayNotification` | 18h lead is hardcoded in `ReminderPlanner` | inert |
| `headsUp`, `lockScreenTitle`, `dailyAgenda` | quiet-by-default notifications | inert — quiet because unimplemented, and turning them on does nothing |
| `background`, `font`, `textSizeScale` | appearance | inert (known) |

This is the gap between "Settings renders sixteen survivors" and "Settings
controls sixteen survivors". WS9's box is checked for the former.

- [ ] Decide per row: wire it, or hide it. A switch that silently does
      nothing is worse than an absent one, and it is the one thing this
      project's README explicitly promises not to do.
- [ ] `hideAutoAdded` + `AutoAddedDetector` first — it is the feature the
      README leads with ("No auto-added flight bookings"). Note this is
      also gated on WS3's open question 1, which is still unanswered.
- [ ] `showDeclined` second — the reconciler already has the predicate;
      the views just need it.

### 5. `.ics` import duplicate detection can't see its own imports

`IcsCodec.uidOf(eventId, calendarId)` synthesises `"$id-$cal@xx-calendar"` on
export, discarding the row's real `UID_2445`. Import dedupes against
`readKnownUids()`, which reads `UID_2445` from the provider — but the import
insert path never writes `UID_2445` (it is in `OpaqueColumns.NON_PRESERVED_COLUMNS`).

Consequences:
- Importing the same file twice creates duplicates.
- Exporting an event that came from Google loses its server UID, so the file
  no longer round-trips against any other client.
- Export→import on the same device does not dedupe.

Only events DAVx⁵ synced down are covered — which is the case the tests
exercise.

- [ ] Export the row's real `UID_2445` when present; fall back to the
      synthetic UID only when it is null.
- [ ] Write `UID_2445` on import insert. Verify on-device whether
      CalendarProvider2 accepts it from a non-sync-adapter client; if it
      refuses, keep a local UID→eventId map and say so in Settings.
- [ ] Test: import the same file twice, assert zero new rows the second time.

---

## P1 — Ship blockers that are not code

Carried from todo.md's own review; all still open and all confirmed.

- [ ] **CI has never run.** Run `32654322807` died in two seconds on a
      billing failure. The R3 gate — this repo's central claim — is
      unenforced on every push. Fix billing, or mirror the
      `aapt2 dump permissions` check into a local pre-push hook so the gate
      exists somewhere.
- [ ] **Font licensing.** JetBrains Mono and Space Mono ship as `.ttf` under
      `res/font/`. Both are OFL 1.1: the license text and copyright notice
      must travel with them. `LICENSE` asserts "All rights reserved" over the
      whole tree, fonts included. Add `third-party/OFL.txt` and a NOTICE line.
- [ ] **No release path.** `release` has no `signingConfig` and
      `isMinifyEnabled = false`. Compounded by WS2's open box — `debug.keystore`
      was never copied from Nope-Mode, so current sideloads carry a different
      signing identity from the rest of PiercingXX. Changing that identity
      later means uninstall-and-lose-state on the phone.
- [ ] **Run the instrumented suite.** All three suites guard exactly the
      failure modes above. P0 items 1 and 3 are precisely what
      `RecurringScopeRoundTripTest` and a real provider would have caught.
      That they compile is not evidence.

---

## P2 — Should do

- [ ] Add `./gradlew lint` to the CI job. It passes today; keep it that way.
      Lint's current findings worth acting on: `ExportedReceiver`
      (`ThemeSyncReceiver` is exported with no permission — any installed app
      can set your background; add a signature permission or accept it
      explicitly), `OldTargetApi`, `DataExtractionRules`, `AutoboxingStateCreation`.
- [ ] `sourceCompatibility` / `targetCompatibility` / `jvmTarget` are all
      `1.8` under AGP 8.9 and JDK 17. Deprecated now, removed in AGP 9. Bump
      to 17.
- [ ] Add `distributionSha256Sum` to `gradle-wrapper.properties`.
- [ ] `ReminderReconciler`'s trigger-matrix KDoc says `ensureObserving` is
      "intended to be called from MainActivity … until that one-liner lands
      the observer stays off". It landed — `MainActivity.kt:114`. Stale
      comment on the app's one hard requirement; delete it.
- [ ] `RecurrenceEditor` passes `parent.opaque` into the *new* continuation
      row. `_SYNC_ID` / `UID_2445` / `ORIGINAL_SYNC_ID` are excluded by
      `NON_PRESERVED_COLUMNS`, but `SYNC_DATA1..10` and `CUSTOM_APP_*` are
      not. Confirm on-device that DAVx⁵ tolerates a fresh row carrying the
      parent's sync payload.
- [ ] Compiler warnings, all trivial: four unnecessary `!!`
      (`IcsCodec.kt:334`, `DetailSheet.kt:151,287,292`), three unused
      parameters (`TimeGrid.kt:721`, `EditorState.kt:318`,
      `ScopePrompt.kt:20` — `title` unused in the *scope prompt* is worth a
      look, not just a delete), one unused variable
      (`ScheduleWidget.kt:136`).

---

## P3 — Docs, and the honesty gap

The README's tone is its main asset, which makes overstatement expensive.

- [ ] **README/todo.md say "built" and "all twelve workstreams are
      implemented."** With P0 #4 unresolved that is not true of WS9, and with
      #1–#3 it is not true of WS3/WS7. Restate as "feature-complete against
      the plan; unverified against a real provider" until the instrumented
      suite has run once.
- [ ] todo.md's local-setup block reads
      `am start -n com.piercingxx.calendar/.ui.MainActivity`. The manifest
      declares `.MainActivity`. Copy-pasting it fails.
- [ ] README: "no guests, no RSVP, no tasks, no reminders, no goals" sits one
      paragraph from a full reminder subsystem. It means Google's *Reminders
      entity*. One clause fixes it.
- [ ] README: "declined events are off by default" — see P0 #4. Either wire
      it or drop the sentence.
- [ ] WS3's two open questions are still unanswered, so the auto-added filter
      ships on an unverified assumption — and currently on no implementation
      at all.
- [ ] On-phone review of [design/mock/sigil-mock.html](design/mock/sigil-mock.html).
      The mock's own verdict is that six tiers collapse to ~4 perceptual
      groups at phone size. That decision gates three views and is still
      pending.

---

## Suggested order

1. **P0 #1** — one-line class of fix, largest blast radius, and it invalidates
   the fake provider that hides it. Do this before trusting any view.
2. **P0 #2** — small, and it makes the app usable with more than one calendar.
3. **Run the instrumented suite on a device** (P1). With #1 and #2 fixed, this
   is the first real evidence the provider layer works, and it is what would
   surface #3.
4. **P0 #3** — needs the device loop from step 3 to verify.
5. **P0 #4** — largest by volume; mostly wiring, and each row is independent.
   Hiding the rows you don't wire is a legitimate first pass.
6. **P1 licensing and CI billing** — cheap, and both are ship blockers.
7. **P0 #5**, then P2/P3.
