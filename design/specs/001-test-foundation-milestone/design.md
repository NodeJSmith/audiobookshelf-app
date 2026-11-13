# Design: Test foundation for ABS+

**Date:** 2026-11-13
**Status:** ratified
**Mode:** sketch
**Prior art:** design/research/2026-11-13-capacitor-bridge-tests/prior-art.md

## Summary

ABS+ has no tests that run: no JS test setup, no Android test dependencies (the template unit test wouldn't compile), one iOS XCTest file in a test target nothing runs, and CI only builds. The bug that prompted this was drift across the JS↔native plugin bridge: the shared sleep-timer code started sending `setSleepTimer` a chapter index, Android's native side was updated to match, iOS still read the value as an end time in ms and silently did nothing. Nothing that ran could see it.

This milestone lays the foundation: a test harness on each of the three sides (JS, Android JVM, iOS XCTest), each running as a job or a step in an existing job under the CI workflow's `ci-complete` rollup so a failing test blocks the merge; a contract test that pins what `setSleepTimer`'s arguments mean on every side; a bridge-parity check that every plugin method and event the JS uses exists on both native sides; and a standing rule that code ABS+ adds or changes comes with tests. The harnesses are general-purpose: nothing in them is bridge-specific, so any module can be tested later.

Out of scope: backfilling tests for upstream code ABS+ hasn't touched, end-to-end tests on emulators or simulators, and codegen for the bridge. Coverage of the whole codebase is the expected follow-up milestone once ABS+ stops cherry-picking from upstream (see the Upstream surface stance); this milestone's harnesses are what that one builds on.

New homes:
- **JS bridge-argument logic** (what the shared code sends each platform for a plugin call): a fork-owned JS module, per D2.
- **Contract fixtures**: one shared fixture per pinned bridge call, read by the JS, JVM and XCTest suites, per D3.
- **Native argument interpretation** for pinned calls: small pure functions next to each platform's plugin, per D4.
- **Parity check and its platform-only list**: a script in CI plus a fork-owned list of methods and events that exist on one platform on purpose, per D5.
- **The changed-code-gets-tests rule**: the repo's review questions, per D8.
- **Testing guide** (where each side's tests live and how to run them): a short section in the repo's existing developer docs, beside the review questions it supports.

## Posture

- **Failure handling:** each test suite and check runs as a CI job, or a step in an existing job, under `ci-complete`, the required status check on master; a failing test blocks the merge. No retries or quarantine; a flaky test gets fixed or deleted.
- **Compatibility:** break freely; nothing outside ABS+ uses its code, so internal functions can be renamed or reshaped without deprecation paths.
- **Upstream surface:** minimal while ABS+ still cherry-picks from upstream. Tests, fixtures, scripts and CI are additive. Upstream code is edited only to pull logic a test needs into a pure function, one bridge call at a time; no reformatting or restructuring upstream code for testability. This stance ends when ABS+ stops cherry-picking from upstream, which is also the trigger for a coverage-backfill milestone.
- **Scope:** the foundation (three harnesses in CI, general enough for any module), the `setSleepTimer` contract, the bridge-parity check, and the changed-code-gets-tests rule. Backfilling untouched upstream code is out.
- **Rollout:** each issue ships as its own PR when green; no flags or staging.
- **Hardening:** project defaults; no performance budgets.
- **Altitude:** milestone; the issues will be picked up one at a time, after other ABS+ work has moved the code.

## Decisions

### D1: What does the JS harness test: plain modules only, or Vue components too?

**Situation:** The shared web layer is Nuxt 2 on Vue 2.7, with plugin calls made from inside large components (the audio player container is the one that drifted). JS tests can either import plain JS modules and assert their outputs, or mount Vue components with a Nuxt-aware setup (aliases, `$platform`, store, plugins) and drive them.

**Deciding factor:** catching bridge drift with the least harness machinery on an end-of-life framework.

| | A: Plain modules, minimal runner | A′: Plain modules now, runner able to mount Vue 2 components later | B: Components now |
|---|---|---|---|
| Catches the sleep-timer class of bug | Yes, once the argument logic lives in a module (D2) | Yes, same way | Yes, but through stubs of the child player's chapters and position, the data the logic computes from |
| Setup now | ~1-2 hours | ~2-4 hours | ~2-4 hours plus about a day for a shared stub of the ~40 Nuxt-injected globals and the first component test |
| Adding UI tests later | Runner rework | Write them | Already there |
| Survives a later Nuxt 3 / Vue 3 move | Unchanged | Runner config changes; module tests don't | Component tests rewritten |
| Upstream surface | Moves logic out of upstream components (D2) | Same | Test files only |
| What peers do | Official plugins test no JS at all; capawesome tests JS only through E2E | Same | unknown |

**Recommendation:** A′, because it costs an hour or two more than A, keeps component tests one step away instead of a rework, and doesn't spend a day of stubbing on logic that's easier to test as a module.
**Pick A instead if** setup time matters more than a later rework. **Pick B instead if** specific screens or interactions should be under test in this milestone.
**Reversibility:** easy (component tests can be added to the same runner later)
**Ratified:** Chose A′ (plain-module tests now, on a runner already able to mount Vue 2 components) over a minimal runner or component tests now, to keep UI tests one step away without paying for a stub harness yet, accepting a few hours more setup than the minimal runner, and that the JS tests run on one Node version while CI's two build jobs use different ones.

### D2: Where does the JS bridge-argument logic live?

**Situation:** Today the logic that turns a user's sleep-timer choice into the value each platform's native timer takes lives inside the audio player container component, as a small helper plus three call sites. To test it as a plain module (D1 A), it has to live somewhere importable without mounting the component.

**Deciding factor:** keeping upstream cherry-picks cheap while giving tests something to import.

| | A: Fork-owned bridge-arguments module | B: Leave it in the component |
|---|---|---|
| Testable as a plain module | Yes | No (needs D1 B) |
| Upstream surface | One-time edit to the component to call the module; later changes land in a fork-only file | None now; every later change edits the upstream component |
| Where the next bridge call's logic goes | Same module, one obvious home | Wherever its call site is |
| Reader load | One more file to find; named for what it does | Logic stays next to its use |

**Recommendation:** A, because it trades one edit to the upstream component for a fork-owned home that every later pinned bridge call reuses.
**Pick B instead if** D1 is B.
**Reversibility:** easy
**Ratified:** Chose a fork-owned bridge-arguments module over leaving the logic in the component, to make it testable as a plain module and give later bridge calls one home, accepting one edit to the upstream player component.

### D3: Should `setSleepTimer` mean the same thing on both platforms?

**Situation:** For a chapter sleep timer, Android's native side takes a chapter index (0 = the current chapter) and iOS's takes the stop chapter's end time in ms. The shared code branches per platform to send each one its own form. A contract test can either pin both encodings as they are, or the platforms can be made to agree first so there's one encoding to pin.

**Deciding factor:** pinning today's behavior without growing the native diff against upstream.

| | A: Keep per-platform encodings, pin both | B: Unify on the chapter index (change iOS) | C: Unify on end time in ms (change Android back) |
|---|---|---|---|
| Native code changed | None | iOS sleep timer and plugin | Android sleep timer, undoing the fork's index form |
| Upstream surface | None | Grows (iOS native) | Shrinks on Android, but loses what the index form gave (stopping at a chapter in a later audio file) |
| Contract the fixture expresses | Two expected values per case, one per platform | One | One |
| Shared-code branching | Stays | Goes | Goes |
| Risk without an iPhone to test on | None | Untested Swift behavior change | Android behavior change, testable locally |

**Recommendation:** A, because the contract test is what stops the drift; unifying is a behavior change with no user benefit today and, for B, an untestable one.
**Pick B instead if** multi-chapter timers on iOS across audio files become a feature you want. **Pick C instead if** the Android index form causes its own bugs.
**Reversibility:** easy (the fixture's per-platform expectations collapse to one if a later change unifies)
**Ratified:** Chose keeping the per-platform encodings and pinning both over unifying on either one, to stop the drift with no native behavior change, accepting that the shared code keeps its per-platform branch.

### D4: How do the native tests reach a pinned call's argument handling?

**Situation:** Each platform's plugin method reads the call's options (`time`, `isChapterTime`), converts them, and hands them to the player, which needs a running service. Tests on the JVM and in XCTest can't start the player. Peers that pin meaning (Cap-go's shared fixtures) move the interpretation into pure functions; testing the plugin method itself would need a fake plugin call and a fake player.

**Deciding factor:** pinning what each platform does with the value, with the smallest upstream edit.

| | A: Pure interpretation function per platform | B: Fake plugin call and fake player | C: Both |
|---|---|---|---|
| Pins what the value means | Yes | Yes | Yes |
| Pins the option keys and types read (glue) | No, unless the function takes the raw options | Yes | Yes |
| Upstream surface | Small: extract a function from the plugin method | Larger: seams for injecting a fake player into the plugin | Both |
| Harness machinery | None beyond the runner | Fakes for Capacitor's call object and the player service on each platform | Both |
| What peers do | Cap-go: pure functions fed by a shared fixture | unknown | unknown |

**Recommendation:** A, with the function taking the raw option values (the string `time` and the flag) so a type or parse change to the value still shows up as a failing test. A renamed key never reaches the function, so key renames stay unpinned.
**Pick B instead if** option-key drift (the JS renaming a key) turns out to bite, since only B pins the glue directly.
**Reversibility:** easy
**Ratified:** Chose a pure interpretation function per platform, taking the raw option values, over fakes of the plugin call and player, to pin what the value means with the smallest native edit, accepting that the one-line option-read glue stays unpinned.

**Contract boundary** (settled at the sketch-time challenge, by the user's answers):
- **What each side pins.** iOS's function pins the stop time in book seconds. Android's pins only the index encoding (0 = current chapter, N = chapter N): on Android, resolving the index to a stop point happens in the sleep-timer manager against live player state (chapter list, position, the near-chapter-end skip), and D6's behavior tests own that, not the contract.
- **Parse edge cases are in.** Fixture cases include an absent `time` and a decimal `time`, each expecting the platform's current outcome. Today that's Android defaulting an absent value to a 6-minute timer and throwing on a decimal (which crashes the app through the bridge), and iOS reporting failure on an absent value and accepting a decimal. Recording them changes no native behavior (D3) and makes the divergence visible.
- **The call's outcome is out.** The contract pins what the value means, not whether the call reports success. Known limitation: iOS reports success on a chapter timer whose stop point is already in the past, or when no playback session exists. A past stop point only arrives when the JS sends a wrong value, which the argument contract catches. A stop point in a later audio file is not a failure, since iOS re-arms the timer at the track change.

**Android test runtime** (settled at the sketch-time challenge): the Android pure functions, and the sleep-timer logic D6 tests, run under plain JUnit on the JVM, with no Android framework stubbing and no Robolectric; logic that reads the clock or other platform state takes it as an input. Chose this over Robolectric or default-value stubbing, because a stubbed framework returns silent zeros and nulls that hide failures and Robolectric tests code in place rather than the pure functions the Posture calls for, accepting that code touching Android classes directly stays untested.

### D5: What does the bridge-parity check compare?

**Situation:** The JS calls methods on five in-app plugins and listens for about 22 plugin events. Kotlin declares methods with an annotation and Swift in a method list. The platforms already differ on purpose (Cast is Android-only; the database plugin has 24 Kotlin methods against 17 Swift). The only peer tool (Cap-go's wiring check) compares plugin names, by regex.

**Deciding factor:** catching "added on one platform, forgotten on the other" without a check that cries wolf.

| | A: Method names | B: Methods and event names | C: Methods, events and option keys |
|---|---|---|---|
| Catches a method added on one side only | Yes | Yes | Yes |
| Catches an event JS listens for that a platform never emits | No | Yes | Yes |
| Catches a renamed option key | No | No | Yes |
| Parsing reliability | High (annotation and method-list declarations) | High for declarations; events are emitted from several native files | Low: keys are read via scattered `get…("key")` calls |
| Platform-only list to maintain | Methods | Methods and events | Methods, events and keys |

**Recommendation:** B, with one fork-owned list naming each method or event that exists on one platform on purpose and why; the check fails on anything missing that isn't on the list, and on list entries that no longer apply.
**Pick A instead if** event extraction proves too noisy to keep green. **Pick C instead if** a key rename bites.
**Reversibility:** easy
**Ratified:** Chose comparing method and event names against a fork-owned platform-only list (stale entries fail too) over methods only or adding option keys, to catch one-sided additions and missing events reliably, accepting that renamed option keys aren't caught, and neither are event payload fields (today's one divergence, iOS's sleep-timer-set event omitting the auto flag, is harmless since the JS treats it as false).

**Extraction floor** (settled at the sketch-time challenge): the check prints what it extracted per plugin and side, fails when a side extracts nothing for a plugin known to exist, and tests its parser against a small sample of each declaration syntax (Kotlin annotation, Swift method list, JS call, each native event-emit form). Chose this over printing alone, so a parser broken by a refactor or cherry-pick fails instead of passing on an empty comparison, accepting a small validator and parser tests to keep.

### D6: Which of ABS+'s existing native changes get tests in this milestone?

**Situation:** ABS+ already carries native changes upstream doesn't have: the sleep-timer fixes on both platforms (chapter-count timers, clearing a timer paused over an hour, shake re-arm), chunked playback-history storage, playback-control broadcasts, widget resume, and several crash fixes. The changed-code-gets-tests rule (D8) covers future changes; this is whether to backfill the existing ones now.

**Deciding factor:** testing the code most likely to regress, without turning the foundation milestone into a coverage push.

| | A: Sleep timer only | B: All fork-owned native logic | C: None; the rule covers what's touched next |
|---|---|---|---|
| Covers the area that already drifted once | Yes | Yes | Only when next touched |
| Size | One issue, bounded | Several issues across player, history, broadcasts | Zero |
| Needs extraction from upstream-shaped classes | Some (the sleep-timer managers) | More (history storage, broadcast receiver) | No |
| Value per effort | High: the timer logic is the most edited and has the subtlest behavior (pauses, expiry, chapter edges) | Mixed | n/a |

**Recommendation:** A, because the sleep timer is where both platforms have already diverged and been fixed twice; the rest gets tests the next time it changes.
**Pick B instead if** you'd rather pay for fork-owned coverage now than meet the rule piecemeal. **Pick C instead if** you want the milestone to be foundation only.
**Reversibility:** easy
**Ratified:** Chose backfilling tests for the sleep-timer logic only over all fork-owned native code or none, to cover the area that already drifted with one bounded issue, accepting that the other fork-owned changes wait for the rule.

### D7: Should CI report coverage?

**Situation:** Coverage growth is how you'd see the "coverage grows with divergence" plan working. Each side has its own tooling (a JS runner's coverage, a JVM coverage plugin, Xcode's coverage report), and a threshold would block merges on a number rather than a behavior.

**Deciding factor:** visibility without a gate that fails on a percentage.

| | A: No coverage reporting yet | B: Report-only, all three sides | C: Report-only, JS and JVM (skip iOS) |
|---|---|---|---|
| See coverage grow | No | Yes | Mostly (the iOS side is the smallest) |
| CI time and setup | None | A coverage step per job; iOS coverage adds macOS time | JS and JVM only; both cheap |
| Blocks merges | No | No | No |
| Meaningful today | n/a | Low: near-zero everywhere until tests exist | Same |

**Recommendation:** A, because the numbers start near zero and only become meaningful once the rule has run for a while; adding reporting later is a small change.
**Pick B or C instead if** watching the number move is part of what keeps the rule honest for you.
**Reversibility:** easy
**Ratified:** Chose no coverage reporting yet over report-only on all or two sides, to avoid CI cost for a number that starts near zero, accepting no visible measure of coverage growth until it's added later.

### D8: How is "changed code gets tests" enforced?

**Situation:** The standing rule is that code ABS+ adds or changes comes with tests for the behavior it changes. It can live as a review question the code reviewers answer on every PR, or as a CI heuristic that fails a PR touching source files without touching any test file.

**Deciding factor:** a rule that holds without blocking legitimate changes.

**Recommendation:** a review question in the repo's review questions, asking whether the change adds tests for the behavior it changes and naming the harness for each side. A file-matching CI check would fail on refactors, config and upstream cherry-picks, and train you to bypass it.
**Pick a CI heuristic instead if** reviews keep letting untested changes through.
**Reversibility:** easy
**Ratified:** Chose a review question over a file-matching CI check, to enforce the rule without failing refactors and cherry-picks, accepting that it relies on reviewers asking it.

### D9: Where do the iOS tests run in CI?

**Situation:** The iOS build already runs on a macOS runner, and macOS runner capacity has been the bottleneck (jobs cancelled after waiting for a runner). The job today only builds for a generic simulator destination; running XCTest needs the test action against a concrete simulator device, and because the test target is hosted in the app, each run boots a simulator and launches the app.

**Deciding factor:** fewest macOS jobs to schedule per PR.

**Recommendation:** run XCTest in the existing iOS job, after the build, so a PR waits for one macOS runner, not two. The `ci-complete` rollup already requires that job whenever iOS-relevant files change.
**Pick a separate job instead if** the combined job's runtime gets long enough that a test failure takes too long to surface.
**Reversibility:** easy
**Ratified:** Chose running XCTest in the existing iOS build job over a separate macOS job, to schedule one macOS runner per PR, accepting a longer single job.

**Simulator failures** (settled at the sketch-time challenge): the test step has its own timeout, so a hung simulator fails fast instead of using the job's whole budget. There are no automatic retries, per the Posture: a simulator that fails to boot or launch is handled by re-running the job by hand. Chose this over a retry limited to simulator-infrastructure failures, to avoid a heuristic that can misread a real test failure as infrastructure, accepting that a transient simulator failure blocks the merge until it's re-run.

## Assumed

- `ci-complete` is the CI workflow's rollup job, and requires each job to succeed when the path filter says the change needs it and to be skipped otherwise; it is the status check master's ruleset requires. Evidence: the CI workflow added in PR #4 (fold the builds into one workflow).
- master requires a pull request (ruleset, no bypass). Evidence: the repository's `master` ruleset.
- The iOS shared scheme already includes the unit-test target, and one XCTest file (player time utilities) exists. Evidence: the App scheme's test action; the AudiobookshelfUnitTests target. Whether it currently passes is unknown; the harness issue finds out.
- Android declares no test dependencies; the only test is the generator's template. Evidence: the app module's Gradle dependencies.
- The JS side is Nuxt 2.18 / Vue 2.7 with `@/` path aliases and no test runner. Evidence: package manifest and installed versions.
- Android's native `setSleepTimer` takes `time` as a chapter index for chapter timers (0 = current chapter); iOS's takes the stop chapter's end time in ms; the shared code sends each its own form through one helper. Evidence: the Android audio-player plugin and sleep-timer manager; the iOS audio-player plugin and sleep-timer extension; the audio player container's chapter-sleep helper.
- iOS reports a chapter sleep timer's remaining time divided by the playback rate. Evidence: the iOS sleep-timer extension's remaining-time getter.
- There is no Mac or iPhone available locally; iOS tests run only in macOS CI. Evidence: the maintainer's setup.

## Breakdown

**Done when:** on a PR that touches the shared web layer, Android and iOS code, `ci-complete` runs a JS suite, an Android JVM suite and an iOS XCTest suite and fails if any test fails; the `setSleepTimer` contract test passes on all three sides from one shared fixture; the parity check runs in CI and passes against its platform-only list; item 4's sleep-timer behavior tests run in the Android and iOS suites; and the review questions ask every PR whether it tests what it changes.

Applies to all: D7.

1. **Test harnesses in CI on all three sides**: a JS test runner for plain modules, Android JVM unit tests on D4's Android test runtime, as a step in the existing Android job (the template test replaced), and XCTest run in the iOS job with its own step timeout (D9), each wired into `ci-complete` (the rollup names every job it judges by hand, so a new job goes into its needs, its wanted/result inputs and its checks, or a red job won't block the merge), with the JS suite on its own path filter; one trivial real test per side proves the wiring, and the existing player-time XCTest runs. The iOS tests run as their own step after the build, without quiet output; the step writes the executed tests by name and the result to the job summary, and uploads the test result bundle when it fails, since the CI log is the only view of an iOS failure. The iOS project registers files by hand (no folder sync), so each new Swift file or resource is added to the project file explicitly, and every PR that adds iOS tests confirms they appear by name in that summary: a test missing from the target otherwise passes silently. Implements: D1, D9. After: none. Issue: #6
2. **Pin `setSleepTimer` with a shared contract fixture**: move the JS bridge-argument logic into a fork-owned module that takes the platform and the chapter data it computes from as explicit inputs (not component or global state, which would need the stub harness D1 avoided), extract each platform's interpretation of `time`/`isChapterTime` into a pure function taking the raw option values, and test all three against one fixture with per-platform expectations, within the contract boundary D4 sets. Each fixture case is one record running from the JS situation, to the exact option values sent to each platform as wire strings, to each platform's expected interpretation; the native suites use the record's sent values verbatim as their input, so the JS output and the native input can't drift apart while all three suites pass. The fixture lives outside the paths CI's filters exclude (design docs and markdown), and each suite's filter includes it, so a fixture-only change runs all three. Implements: D2, D3, D4. After: 1. Issue: #8
3. **Bridge-parity check**: a CI job that compares the plugin methods and events the JS uses with each platform's declarations, against a fork-owned platform-only list with a reason per entry, with D5's extraction floor. It gets its own path filter (the JS plugin wrappers and call sites, either native side's plugins, or the list) and is wired into `ci-complete` the same way as item 1's jobs. Implements: D5. After: none. Issue: #7
4. **Sleep-timer behavior tests on both platforms**: tests for the sleep-timer logic ABS+ changed (chapter-count stop points, which on Android include resolving a chapter index to a stop point, left out of D4's contract; clearing a timer paused over an hour; shake re-arm on Android), extracting pure logic where needed; the extracted logic takes the current time and any other platform state as inputs, so time-driven behavior is tested without sleeping or stubbing a clock. Implements: D6. After: 1, 2. Issue: #9
5. **Changed-code-gets-tests review question and a short testing guide**: add the review question and a brief note in the repo on where each side's tests live and how to run them. Implements: D8. After: 1. Issue: #10