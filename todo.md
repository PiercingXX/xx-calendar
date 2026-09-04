# XX-Calendar — Remaining work

**2026-09-04.** Historical WS1–18 live in git (`3f96986` and parents). This file
is what is left. Do not re-litigate DAVx⁵, `CalendarContract`, or `INTERNET`.

Package: `com.piercingxx.calendar`  
Target: Pixel 9 Pro (`caiman`), GrapheneOS. Currently also dogfooded on a Pixel 6.  
Spec: [design.md](design.md). Teardown: [design/google-calendar-teardown.md](design/google-calendar-teardown.md).

```
Status: views/editor/reminders/ics/widgets exist off-device. Chrome /
tap-to-create / ORIGINAL_ID insert fix landed (`33ddc89`). Ship gate is
the instrumented suite on a real CalendarProvider2 + DAVx⁵.
```

There is **no network** in this app. DAVx⁵ owns Google. This process is a
normal provider client. No Room events table.

---

## Locked now (2026-09-04)

| ID | Decision |
|---|---|
| S1 | **Sigils collapse to four perceptual tiers.** The six-glyph mock reads as ~4 on a phone (`▏` dies ≤13px, `░≈▒`, `·` vanishes). Rebuild `SigilTier` + month/week/schedule/widget chips. Update design §7.1. |
| S2 | Chrome / create / `ORIGINAL_ID` batch **landed** (`33ddc89`, 2026-09-04). Do 17.2 against this tree, not the pre-fix one. |

Still locked from design: no `INTERNET`, DAVx⁵ sync, `CalendarContract` SoR,
AlarmManager reconcile, DataStore-only app state, family theme sync (widgets
stay Ink + system monospace — known).

---

## Landed — chrome / create / ORIGINAL_ID (`33ddc89`)

Chrome date jumps across views, Schedule `focusDate`, month pager follow,
tap-empty-grid create, month double-tap / peek create, `CalendarRepository`
omit-null `ORIGINAL_ID` / `ORIGINAL_INSTANCE_TIME` (blank-editor save NPE),
plus the new month/schedule tests.

- [x] Focused commit on `main` (`33ddc89`).
- [ ] `./gradlew testDebugUnitTest lint assembleDebug assembleDebugAndroidTest` green (re-run after this push if not already).
- [ ] README test count → current (~448 with this batch, was documented 409).
- **Accept:** creating an event from a blank editor no longer crashes in
  the fake **or** on the Pixel. Do not run 17.2 against parents of `33ddc89`.

---

## Ship gate — 17.2

`./gradlew connectedDebugAndroidTest` on a Pixel with DAVx⁵ + a Google
account. Thirteen tests, never run against CalendarProvider2.

| Class | What it proves |
|---|---|
| `EventRoundTripTest` (3) | create / title edit / delete |
| `OpaqueColumnPreservationTest` (1) | unmodeled columns survive a title edit |
| `RecurringScopeRoundTripTest` (8) | This / Following / All edit + delete, including `CONTENT_EXCEPTION_URI` |
| `ReminderReconciliationAfterBootTest` (1) | alarms after simulated boot |

Record while it runs:

- [ ] `UID_2445` on a normal-client import insert — if refused: local UID map + Settings line.
- [ ] `Events.CONTENT_EXCEPTION_URI` without impersonating a sync adapter — if refused: fallback insert or honest error on delete-this-instance.
- [ ] Real DAVx⁵/`SYNC_DATA*` / auto-added row shape (feeds WS3 Q1).
- [ ] Synced-row bookkeeping columns (`version`, …) — widen `NON_PRESERVED_COLUMNS` if the provider rejects.
- [ ] Local calendar bootstrap insert (`CALLER_IS_SYNCADAPTER` + `ACCOUNT_TYPE=LOCAL` only on that path).
- **Accept:** 13/13 green **or** a written fallback for each red, then a second green run. Manual smoke: single + recurring (all three scopes), `.ics` import, reminder after reboot.

Do not enable R8/minify until this is green.

---

## Sigils — four tiers

- [ ] Pick four glyphs that still read at month-cell size (mock:
  [design/mock/sigil-mock.html](design/mock/sigil-mock.html)).
- [ ] `SigilAssigner` / `SigilTier` emit four, not six.
- [ ] Month, week, schedule, day chip, widgets all use the new set.
- [ ] design §7.1 rewritten; JVM tests updated.
- **Accept:** dense month with ≥8 calendars is readable at arm's length on
  the Pixel. Signal white still reserved for now + selection.

---

## WS3 — still device-only

- [ ] Auto-added flights/hotels: inspect real DAVx⁵ rows. If no marker,
  fall back to per-calendar hide (do not ship a lying “hidden” filter).
- [ ] `Instances` cost on one busy month — note if the query window is enough.
- **Accept:** written note in this file or `design/`. Heuristic stays
  fail-closed.

---

## Known leftovers (not ship blockers unless they bite 17.2)

| Item | State |
|---|---|
| Search icon | Inert, deferred |
| `dimPast` / `dailyAgenda` / in-app font-bg pickers | Keys persist, rows hidden |
| ICS S3 | Process death mid-import can leave partial rows with no toast |
| Split series | Non-atomic by design; UI must keep offering recovery |
| Widgets | System monospace, Ink only |
| CI | Actions billing dead; R3 gate is `.githooks/pre-push` if `core.hooksPath` is set |
| `design.md` header | Still says “nothing built” — fix when convenient |

- [ ] ICS S3 toast (optional pre-ship).
- [ ] Restore CI billing or write “hook-only” as permanent.
- [ ] After 17.2: `assembleRelease` smoke; minify decision.

---

## Post-v1 (do not block)

Search, year view, `.ics` URL subscribe, NL quick-add, secondary TZ, QS tile,
next-event widget, wire hidden `dimPast` / `dailyAgenda`.

---

## Stop conditions

- `INTERNET` in the APK → reject.
- Writing `SYNC_DATA*` as a normal client → reject.
- Leaving both `DTEND` and `DURATION` on an update → reject.
- Hue instead of sigils → reject.
- Claiming ship without 17.2 or a written fallback → reject.

---

## Setup

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
git config core.hooksPath .githooks
./gradlew testDebugUnitTest lint assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

DAVx⁵ + Google account on the device before claiming the provider layer.
