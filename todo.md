# XX-Calendar — Build Plan

Spec: [design.md](design.md). Teardown:
[design/google-calendar-teardown.md](design/google-calendar-teardown.md).
Target: Pixel 9 Pro (`caiman`), GrapheneOS, Android 17 / SDK 37.

**Status: feature-complete against the plan; unverified against a real
provider.** All twelve workstreams are implemented, and every finding from
[review.md](review.md) that could be fixed off-device is fixed: 326 JVM unit
tests green, `lint` green, `assembleDebug` and `assembleDebugAndroidTest`
green. The privacy claim is machine-proven — `aapt2 dump permissions` on the
built APK shows zero `INTERNET`; the CI gate fails the build if it ever
appears, and a local pre-push hook mirrors it while CI billing is broken.
What "unverified" means: the instrumented suite has never run on hardware, so
the provider layer's behavior against real CalendarProvider2 / DAVx⁵ data
(including whether the provider accepts `UID_2445` from a normal client on
import insert) is still evidence-free until it does.

Toolchain deltas from design §14: AGP 8.9.1, Gradle 8.11.1, compileSdk/
targetSdk 35 (environment baseline). Kotlin is 2.1.20, not 1.9.24, so the
Compose compiler is the matching Kotlin plugin rather than the standalone
1.5.14 pin — the family converged on one Kotlin. Java/jvmTarget is 17, not 1.8.
minSdk 26 is as specified. Robolectric is 4.13, not 4.12.2 — required for
compileSdk 35.

Built but device-gated, therefore AMBIGUOUS by plan:

- On-phone review of the sigil mock,
  [design/mock/sigil-mock.html](design/mock/sigil-mock.html). The mock's own
  verdict: six tiers collapse to ~4 perceptual groups at phone sizes. Decision
  pending owner.
- WS3's two open questions against real DAVx⁵ data (adb commands live in git
  history and the reports).
- Instrumented-suite runtime — `./gradlew connectedDebugAndroidTest`. The app
  is installed on a Pixel 6 under GrapheneOS; the suite has still never been
  pointed at it.

Known inert edges, shipped knowingly: widget text uses `FontFamily.Monospace`
because Glance/RemoteViews cannot load `res/font`, and the widgets stay on the
Ink ground whatever theme is synced. glance-appwidget pulls
`WAKE_LOCK`, `ACCESS_NETWORK_STATE`, and `FOREGROUND_SERVICE` transitively —
`INTERNET` is still absent.

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
adb shell am start -n com.piercingxx.calendar/.MainActivity
```

Copy `debug.keystore` from Nope-Mode before the first build, so all PiercingXX
sideloads keep one signing identity (design §14). Still not done: the keystore
was committed on 2026-08-23 and taken back out again — no signing material
lives in this repo, `*.keystore` is gitignored, and debug builds sign with
AGP's auto-generated `~/.android/debug.keystore`. Release signing reads an
optional, gitignored `keystore.properties`.

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
| 1 | `core/` + the sigil mock | nothing | done |
| 2 | Skeleton, theme, fonts, icon, permission gate | nothing | done |
| 3 | `CalendarRepository` — the provider layer | 2 | done |
| 4 | Schedule view | 1,2,3 | done |
| 5 | Day + Week time grids | 4 | done |
| 6 | Month grid + day peek | 4 | done |
| 7 | Detail sheet, editor, recurring writes | 3,4 | done |
| 8 | Reminders — reconciler, alarms, boot | 3 | done |
| 9 | Settings | 4,8 | done |
| 10 | `.ics` + JSON backup | 3,9 | done |
| 11 | Widgets, shortcuts, intent filters, theme sync | 4,6 | done |
| 12 | CI privacy gate + instrumented suite | 3,7 | done |

WS1 and WS2 are independent. So are WS5 and WS6 once WS4 lands.

---

## WS1 — `core/`, and the decision that gates everything

Pure JVM. No Android imports in this package, ever — that boundary is what makes
the recurrence logic testable (design §5).

- [x] `RRuleModel` — build/parse the five presets and custom rules (interval,
      by-day, by-month-day, nth-weekday, `UNTIL`/`COUNT`). Round-trip tests.
- [x] `ScopeResolver` — every row of design §6.3 as a plain data object
      describing the intended provider writes. **The priority suite.**
- [x] `TimeMath` — all-day UTC-midnight conversion tested at `UTC-11`, `UTC`,
      `UTC+13`, and across DST in both directions.
- [x] `SigilAssigner` — stable ordering, survives remove/re-add, honours
      overrides.
- [x] `AgendaGrouping` — day boundaries, multi-day and all-day placement.
- [x] **The mock.** A static render of a dense week and a dense month with six
      calendars in `▌ ▏ ░ ▒ ▓ ·` at their opacity tiers, on `#000000`, in
      JetBrains Mono. Look at it on the actual phone, at actual size.
      Built (`design/mock/sigil-mock.html`); on-phone review still pending.

**Gate:** the mock reads, or design §7.1 gets revisited before WS4. This is the
cheapest possible moment to discover the scheme fails.

## WS2 — Skeleton

- [x] Gradle to design §14, with the toolchain deltas noted above. Kotlin
      2.1.20 and its Compose compiler plugin, minSdk 26.
- [x] `CalendarTheme` — tokens vendored from the branding repo, not retyped.
- [x] Space Mono + JetBrains Mono in `res/font/`. Tabular figures on.
- [x] Underlined-XX adaptive icon on an ink tile.
- [x] Permission gate — one screen, one button, no partial UI (design §10).
- [ ] `debug.keystore` from Nope-Mode — keystore was not available on this
      machine; signing uses the default `~/.android/debug.keystore`.

## WS3 — The provider layer, and the two open questions

- [x] `InstanceQuery` — `Instances.query()` over a range, off the main thread.
- [x] `CalendarRepository` — calendar list, load/save/delete event, reminders.
- [x] **`OpaqueColumns`** — every column not in design §6.2 read, held, written
      back untouched (D8). This protects R6 and it is easier to build now than
      to retrofit.
- [x] `ContentObserver` → invalidate the visible window.
- [x] Create a local calendar on first run when none is writable (§4.4).
- [ ] **Answer open question 1:** what marks a Gmail auto-added event once it
      has come through DAVx⁵? Inspect real synced rows. If there is no reliable
      marker, fall back to a per-calendar hide and record that in the spec.
      Needs a device with real synced data.
- [ ] **Answer open question 2:** measure `Instances` expansion cost over a
      month of a real, busy, heavily-recurring account. Same device gate.

**Gate:** both open questions answered on real data before WS4 builds on them.

## WS4 — Schedule view · first honest milestone

- [x] Infinite scroll, empty days skipped, `Nothing scheduled.` empty state.
- [x] Sigil + opacity per calendar. Past events at `shade`.
- [x] Current-time rule in signal white — one of only two full-white elements.
- [x] Top bar, `Today` button, mini-month picker, drawer with visibility
      toggles.
- [x] FAB with **one** action.

**Gate:** the app shows your real days. If it is not pleasant to look at here,
fix it here — not after three more views exist.

## WS5 — Day and Week

- [x] Time grid, hour rules at `line`, Space Mono hour labels.
- [x] Event blocks with the sigil bar; all-day pinned header row.
- [x] Drag to create, drag to move, edge-resize. 15-minute snap.
- [x] Week: seven columns, today's numeral inverted.

## WS6 — Month

- [x] 7×N grid, out-of-month at `shade`, today inverted.
- [x] Up to three chips per cell, then `+N`.
- [x] Day peek beneath the grid — the month stays visible.
- [x] Week-number gutter (S3).

## WS7 — Editor · the correctness work

- [x] Detail sheet: no guest section, no RSVP (teardown §3.4). Conferencing URL
      as text if present; attachment count only.
- [x] Editor per design §8.5. No location autocomplete, no title suggestion.
- [x] `RepeatBuilder` — presets plus custom.
- [x] **`ScopePrompt` + the three recurring writes.** Only for recurring events.
- [x] Refuse and explain on an unmodelled recurrence shape. Never guess.
- [x] Undo on delete.

**Gate:** `ScopeResolver` suite green *and* the instrumented round-trip green
before this merges. This is the one that corrupts data.

## WS8 — Reminders

- [x] `ReminderReconciler` — recompute the next 48h on boot, provider change,
      settings change, and a daily heartbeat. Diff and apply. Never chase
      individual events (design §4.3).
- [x] `AlarmScheduler` on `setExactAndAllowWhileIdle`.
- [x] `BootReceiver`, `ReminderReceiver`, notification channels.
- [x] Bundled chime `res/raw/xx_calendar.wav` on `reminders_v2` and
      `reminders_heads_up_v2`. Android freezes a channel's sound at creation,
      so shipping a sound means shipping a new channel id; the soundless v1
      ids are deleted in the same pass.
- [x] `canScheduleExactAlarms()` check → the one warn-coloured row in Settings.
- [x] Quiet defaults: content preview off, heads-up off, daily agenda off.
- [x] Never notify for a declined event.

## WS9 — Settings

- [x] The sixteen survivors, laid out as design §8.6.
- [x] `SettingsStore` on DataStore.
- [x] Sigil override per calendar.
- [x] Auto-added-event filter, using whatever WS3 found.
- [x] The honest sync row: last-changed timestamp plus an intent to DAVx⁵, and
      the sentence saying this app cannot see sync state.
- [x] `default view` doubles as the last-used view — the top-bar switcher
      writes the key, so launch reopens where you left off. One key, not a
      second one to drift out of sync.

## WS10 — Data

- [x] `.ics` export (RFC 5545) via SAF, all calendars or one.
- [x] `.ics` import with `UID` duplicate detection.
- [x] JSON backup/restore of settings + sigils only. Events are `.ics`.

## WS11 — Surfaces

- [x] Month and Schedule widgets in Glance. (Widget text is
      `FontFamily.Monospace` — Glance/RemoteViews cannot load `res/font`.)
- [x] App shortcuts: New event, Today.
- [x] Intent filters — be the system calendar handler (design §12).
- [x] `ThemeSyncReceiver` for the XX-Launcher broadcast
      (`xx.launcher.THEME_CHANGED`), as TxxT implements it. Exported and
      unguarded — the family contract carries no permission, and the worst a
      spoof buys is another valid ground. Seven named grounds plus Custom.
      There is no in-app picker; the launcher is the picker.

## WS12 — Gates

- [x] CI: `aapt2 dump permissions` fails the build if `INTERNET` appears.
      Verified on the built APK: zero `INTERNET`.
- [x] Instrumented: create/edit/delete round-trips, all three recurring scopes.
- [x] Instrumented: **opaque-column preservation** — load an event with every
      unmodelled column populated, change one field, assert the rest is
      byte-identical.
- [x] Instrumented: reminder reconciliation after a simulated boot.

All three instrumented suites are written and compile
(`assembleDebugAndroidTest` green) but have never run. The app is on a Pixel 6
now; the suite has not been pointed at it. See the device-gated list at the
top.

---

## v1 ships after WS12

Deferred, in rough order of appeal: `.ics` URL subscription (holidays without a
Google product) · year view · on-device natural-language quick add · secondary
timezone · Quick Settings tile · next-event widget.

---

## Post-build review — 2026-08-23

> Superseded and extended by [review.md](review.md), an independent pass
> over the same tree. Every item below is still open; review.md adds five
> P0 correctness findings the 247 green tests do not cover.
>
> **Resolution pass, later on 2026-08-23:** all five review P0s fixed with
> regression tests (the suite stood at 289 green that day; 326 now, after
> theme sync, last-view persistence and the reminder chime), and the
> Blocking/Should-do/Minor items below are ticked where the fix is provable
> off-device. What remains open is exactly what needs hardware or account
> access: CI billing, the instrumented suite run, and the on-phone sigil-mock
> verdict.

Verified on this machine: `./gradlew testDebugUnitTest assembleDebug` green,
247 tests / 0 failures, manifest declares six permissions and `INTERNET` is not
among them, no secrets or `.ics` in the tree, `.gitignore` correctly excludes
`local.properties`, `build/`, `.gradle/`, `*.apk`.

### Blocking

- [ ] **CI is not running.** The first push (`4397ac2`) triggered run
      `32654322807`, which died in two seconds: *"the job was not started
      because recent account payments have failed or your spending limit needs
      to be increased."* Private-repo Actions minutes are billed. The R3 gate —
      this repo's central claim — is therefore unenforced on every push.
      ~~Fix billing, or mirror the `aapt2 dump permissions` check into a local
      pre-push hook so the gate exists somewhere.~~ Billing still needs the
      account owner; the mirror now exists — `.githooks/pre-push` (active via
      `core.hooksPath`) builds the debug APK and fails the push if `INTERNET`
      appears. CI itself remains dead until billing is fixed.
- [x] **Font licensing.** ~~JetBrains Mono and Space Mono ship as `.ttf` under
      `res/font/`. Both are OFL 1.1, which requires the license text and
      copyright notice travel with the fonts. `LICENSE` currently asserts
      "Copyright (c) 2026 PiercingXX / All rights reserved" over the whole
      tree, fonts included. Add `third-party/OFL.txt` for both faces and a
      NOTICE line.~~ Done: `third-party/OFL.txt` carries the full OFL 1.1 text
      with both families' copyright notices, `NOTICE` lists the bundled fonts,
      and LICENSE carves them out of "all rights reserved".
- [x] **The local-setup command above is wrong.** ~~It reads
      `am start -n com.piercingxx.calendar/.ui.MainActivity`; the manifest
      declares `.MainActivity`. Copy-pasting it fails.~~ Fixed above.

### Should do

- [x] Release build type has no `signingConfig` and `isMinifyEnabled = false` —
      ~~there is no path to a shippable release APK.~~ Resolved half-way, on
      purpose: release now signs via an optional `keystore.properties`
      (gitignored; falls back to the local debug key when absent). The shared
      signing identity is *not* fixed — the committed `app/debug.keystore` was
      taken back out, so debug builds carry this machine's auto-generated key
      (see WS2). R8 stays off until the instrumented suite has run on a
      device; enabling minification blind would trade one unknown for another.
      The first real release build should confirm the storeFile path resolves
      as given.
- [ ] Run the instrumented suite. All three suites guard exactly the failure
      modes this plan names as the real risk — recurring-scope writes, opaque
      column preservation, reminder reconciliation after boot. That they
      compile is not evidence. (The suites grew with the review fixes: the
      delete-then-split EXDATE interaction now has an end-to-end case in
      `RecurringScopeRoundTripTest`, waiting on hardware.)
- [x] `sourceCompatibility`, `targetCompatibility` and `jvmTarget` are all
      ~~`1.8` under AGP 8.9 and JDK 17.~~ Bumped to 17.
- [x] Add `./gradlew lint` to the CI job. Done; lint is green (0 errors).
- [x] Add `distributionSha256Sum` to `gradle-wrapper.properties`. Done;
      verified by deleting the wrapper dist and re-downloading.

### Minor

- [ ] WS3's two open questions remain unanswered, which means the auto-added
      event filter in Settings ships on an unverified assumption about what
      marks a Gmail-injected row. (The filter itself is now wired —
      `hideAutoAdded` drives `AutoAddedDetector` at every instance-consumption
      site — but the assumption under it is still untested against real
      DAVx⁵ data.)
- [x] README says "no guests, no RSVP, no tasks, no reminders, no goals" one
      paragraph away from a full reminder subsystem. ~~It means Google's
      *Reminders* entity; a reader will not parse that.~~ Fixed: the sentence
      now names Google's Reminders entity explicitly and points at the local
      reminder alarms as the different thing they are.
- [x] APPEARANCE rows render and do nothing. ~~Hide them until a second theme
      exists, or render them visibly disabled.~~ Superseded by review P0 #4's
      full pass: every visible Settings row now controls behavior; the rows
      that could not be honestly wired (`dimPast`, `dailyAgenda`, and the
      `background`/`font` pickers) are hidden, their keys still persist and
      round-trip through backup. APPEARANCE keeps `text size`. The `background`
      picker stays hidden for a second reason now — WS11's theme sync makes the
      launcher the picker for the whole family, so an in-app one would be a
      second source of truth.
