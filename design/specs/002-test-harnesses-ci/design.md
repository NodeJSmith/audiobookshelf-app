# Design: Test harnesses in CI on all three sides

**Date:** 2026-11-13
**Status:** ratified
**Mode:** sketch
**Issues:** #6
**Parent:** design/specs/001-test-foundation-milestone/design.md
**Prior art:** design/research/2026-11-13-capacitor-bridge-tests/prior-art.md

## Summary

ABS+ has no tests that run. This change adds a test harness on each side, each gated by the CI rollup `ci-complete` in `.github/workflows/ci.yml`, so a failing test blocks the merge. It is item 1 of the test-foundation milestone (parent ledger above). Items #7–#10 build on it.

- **JS:** a test runner for plain modules (parent D1, A′), able to mount Vue 2 components, in a new `js-tests` job in `ci.yml` on Node 24 (the version the `ios` job uses; Vitest 3 supports it, see Assumed), with its own path filter (D10) and the install command D7 sets. Runner choice is D1 here. Its dependencies, root `vitest.config.mjs` and `test` script go where D2 says. JS tests live in a new `test/js/` directory (D10 explains why not `test/`). The first real test exercises the `getDownloadItem` getter in `store/globals.js`. D3 decides whether a component mount is proven now.
- **Android JVM:** plain JUnit on the JVM (parent D4's Android test runtime: no Robolectric, no framework stubbing). Added as `testImplementation` in `android/app/build.gradle`, with `junitVersion` from `android/variables.gradle`. The tests run as their own `testDebugUnitTest` step in the existing `android` job, after `npx cap sync` and before the keystore install and `assembleDebug`; the job (about 5 minutes on the last run) stays well inside its `timeout-minutes: 30`. The template `android/app/src/test/java/com/getcapacitor/myapp/ExampleUnitTest.java` is replaced by a real Kotlin test of an existing pure function with no `android.*` calls (JVM unit tests run against the stub `android.jar`, and `returnDefaultValues` is not set, so any framework call throws) under `android/app/src/test/java/com/audiobookshelf/app/`. The builder picks the first qualifying function and records it under Calls made during the build.
- **iOS XCTest:** runs in the existing `ios` job (parent D9) as its own step after the build, without `-quiet`. The step writes each executed test's name and the result to `$GITHUB_STEP_SUMMARY`. D9 sets its timeout, the job budget and when the `.xcresult` bundle is uploaded. How the build and test steps split is D4. D6 makes the test target compile (deployment target and Podfile). The existing `ios/App/AudiobookshelfUnitTests/Shared/player/util/PlayerTimeUtilsTests.swift` must run and appear by name in that summary, and D8 makes the step fail if it didn't. D5 covers what happens if it fails.
- **`ci-complete` wiring:** D10 lists every touch point a new job needs.

Out of scope: any test of sleep-timer or bridge logic (#8, #9), the parity check (#7), the review question and testing guide (#10), coverage reporting (parent D7), and the instrumented-test template under `android/app/src/androidTest/` (nothing runs it; left untouched).

## Posture

- **Failure handling:** a failing test fails its job and `ci-complete` blocks the merge. No retries or quarantine. A hung simulator fails at the test step's own timeout; a transient simulator failure is re-run by hand (parent D9).
- **Compatibility:** break freely; nothing outside ABS+ uses its code.
- **Upstream surface:** minimal. Test files, test config and CI are additive. Upstream-owned files (`package.json`, `package-lock.json`, `android/app/build.gradle`, `ci.yml`'s existing jobs, and, per D6, `ios/App/Podfile`, `ios/App/Podfile.lock` and the test target's build settings in `ios/App/App.xcodeproj/project.pbxproj`) get only the lines the harness needs. No upstream code is refactored for this issue.
- **Scope:** the wiring plus one trivial real test per side, and the existing XCTest running. Nothing more is tested yet.
- **Rollout:** one PR, merged when `ci-complete` is green.
- **Hardening:** project defaults; no coverage reporting (parent D7), no performance budgets.
- **Altitude:** build; one session builds this issue from the current code.

## Decisions

### D1: Which JS test runner?

**Situation:** The shared web layer is Nuxt 2.18 on Vue 2.7.16. Its modules are ES modules (`export const getters = …`) that import each other through Nuxt's `@/` and `~/` aliases (e.g. `store/index.js`, `plugins/init.client.js`). Nothing runs JS tests today. Parent D1 asks for a runner that tests plain modules now and can mount Vue 2 components later without rework. Upstream's own Nuxt 2 web client, in the `audiobookshelf` server repo, uses Cypress component tests, mounted through Nuxt's webpack config.

**Deciding factor:** least machinery to run plain ES-module tests now, with Vue 2 mounting one step away.

| | A: Vitest 3.x + `@vitejs/plugin-vue2` | B: Jest + `babel-jest` + `@vue/vue2-jest` | C: Cypress component testing (upstream's choice) |
|---|---|---|---|
| ES modules and `@/`/`~/` aliases | Native ESM; two alias lines in config | Needs a Babel transform for ESM and a `moduleNameMapper` for the aliases | Handled by Nuxt's own webpack config |
| Vue 2 component mounting later | Plugin already configured; add `@vue/test-utils@1` + jsdom | Add `vue2-jest`; Jest 29 is its last supported major | Built in, with a real browser |
| Plain-module tests | First-class | First-class | Awkward: it's a component and browser runner |
| Install weight in every job's `npm ci` | Small (Vite + Vitest) | Medium (Jest + Babel presets) | Large: the Cypress binary (~500 MB) downloads on every `npm ci` unless `CYPRESS_INSTALL_BINARY=0` is set in each job |
| CI time | Seconds | Seconds | A browser launch per run |
| Node versions | 3.x runs on CI's Node versions (see Assumed) | Any | Any |
| Maintenance outlook | Active; plugin-vue2 is end-of-life with Vue 2 but its peer range covers Vite 3–7 | Active, but the Vue 2 transform is frozen | Active |
| What peers do | Not used by upstream | Not used by upstream | Upstream's Nuxt 2 client |

**Recommendation:** A, because it runs the repo's ES modules and aliases with almost no config, and the Vue 2 plugin keeps component mounting a dependency-add away. It departs from upstream's Cypress choice: Cypress fits screen tests, which parent D1 deferred, not the plain-module tests this milestone needs.
**Pick B instead if** you'd rather stay on the runner Nuxt 2's own docs describe. **Pick C instead if** cherry-picking upstream's client component tests, or running them in a real browser, is something you expect to want.
**Reversibility:** easy (tests of plain modules port between runners with small edits)
**Ratified:** Chose Vitest 3.x with `@vitejs/plugin-vue2` over Jest or Cypress component testing, to run the repo's ES modules and aliases with minimal config and keep Vue 2 mounting a dependency-add away, accepting a departure from upstream's Cypress setup and a Vue 2 plugin that is end-of-life.

### D2: Where do the JS test dependencies and config live?

**Situation:** Adding a runner means new devDependencies, a lockfile update, a config file and a `test` script. `package.json` and `package-lock.json` are upstream-owned files, and an upstream dependency bump already conflicts in `package-lock.json` whenever ABS+'s lock differs, as it does since the Capacitor 7.6.10 bump. The alternative is a fork-owned test package with its own manifest and lockfile.

**Deciding factor:** keeping upstream cherry-picks cheap without making the harness awkward to use.

| | A: Root `package.json` devDependencies + root config | B: Separate `test/package.json` with its own lockfile |
|---|---|---|
| Upstream file edits | A few lines in `devDependencies` and `scripts`; lock entries for the new packages | None |
| Cherry-pick conflicts | `package-lock.json` conflicts on upstream bumps, which already happen; `package.json` conflicts only if upstream edits the same lines | None from the harness |
| Running tests | `npm test` from the root; one install | `npm --prefix test ci` and then a run in `test/`; two installs locally and in CI |
| Resolving app imports (`vue`, `date-fns`) | Same `node_modules` | Resolved up the tree from root `node_modules`, so the root install is still needed |
| Dependency updates | One lockfile for Dependabot or Renovate | Two |
| Convention | Standard | Unusual; the next reader has to learn it |

**Recommendation:** A, because the extra conflict surface is small next to the lockfile drift that already exists, and B doubles installs and adds a layout every later issue has to learn.
**Pick B instead if** `package.json` cherry-picks from upstream turn out to be frequent enough that even devDependency lines conflict.
**Reversibility:** easy
**Ratified:** Chose root `package.json` devDependencies, config and `test` script over a separate test package, to keep one install and the standard layout, accepting a few fork-only lines in upstream's `package.json` and more `package-lock.json` drift.

### D3: Is component mounting proven now, or only configured?

**Situation:** Parent D1 chose a runner "already able to mount Vue 2 components" so later UI tests are just written, not set up. That ability can be proven now with one smoke test that mounts a small leaf component (`components/ui/` has candidates such as `ToggleSwitch.vue`), or just configured (the Vue plugin in the runner config) and left unexercised until a real component test arrives.

**Deciding factor:** whether parent D1's "able to mount later" is checked or only assumed.

| | A: Configure and prove it with one smoke mount | B: Configure the plugin only |
|---|---|---|
| Evidence that mounting works | A passing test | None until the first component test |
| Extra devDependencies | `@vue/test-utils@1`, `jsdom` | None |
| Test kept long-term | One smoke test of a leaf component; it breaks only if that component's markup changes in a way its assertion checks | None |
| Cost if mounting turns out broken later | None; found now | Paid by whoever writes the first component test |

**Recommendation:** A, because parent D1 was ratified on mounting being one step away, and an untested config is a claim, not a step. The smoke test mounts a leaf component that needs no Nuxt globals; if no leaf mounts without them, it uses a minimal `.vue` file under `test/js/`.
**Pick B instead if** you'd rather keep this PR's dependencies to the runner alone and accept finding out later.
**Reversibility:** easy
**Ratified:** Chose proving component mounting with one smoke mount over configuring the plugin only, to verify parent D1's "able to mount later" now, accepting two more devDependencies and one smoke test to keep.

### D4: How does the iOS job build and then run the tests?

**Situation:** The `ios` job runs on `macos-15-intel`, where macOS runner time is the bottleneck. Its build step is `xcodebuild -quiet … -destination 'generic/platform=iOS Simulator' build`, about 25 minutes of job time on the last run. XCTest has to run against a concrete simulator. The app-hosted test target needs the app built too. Parent D9 wants the tests in their own step after the build.

**Deciding factor:** fewest macOS minutes per PR while keeping build and test as separate steps.

| | A: Build step becomes `build-for-testing`, then a `test-without-building` step | B: Keep the `build` step; add an `xcodebuild test` step |
|---|---|---|
| Compiles per run | Once (app and test bundle together) | Twice: generic-destination build, then a rebuild for the concrete simulator (partly incremental through the shared derived data) |
| Added macOS time | The test bundle compile plus the test run | A second app build plus the test run |
| Does the build step still prove the app builds? | Yes; `build-for-testing` builds the app | Yes, unchanged |
| A broken test target fails | The build step | The test step |
| Edits to the existing step | Action and destination change | None |

**Recommendation:** A, because it compiles once on the runner pool that's scarce, and the build step still fails on an app that doesn't build. The `build-for-testing` step drops `-quiet` too, since it now also fails on a broken test target and its log is the only way to see which target failed and why. The simulator is chosen at runtime: the job resolves one available iPhone simulator's UDID with `xcrun simctl list devices available -j`, logs it, and passes `-destination "id=$UDID"`, along with the same `-derivedDataPath`, scheme, configuration, SDK and build-setting overrides, to both `build-for-testing` and `test-without-building`. A hard-coded device name would fail with "Unable to find a destination" whenever the unpinned `macos-15-intel` image changes its simulator set, and pinning Xcode with `xcode-select` only defers that until the pinned Xcode leaves the image (chosen at the sketch-time challenge).
**Pick B instead if** you want the existing build step byte-for-byte unchanged, so a broken test target can never mark "build" red.
**Reversibility:** easy
**Ratified:** Chose turning the build step into `build-for-testing` (without `-quiet`) followed by a `test-without-building` step, both on a simulator resolved at runtime by UDID, over keeping the build and adding `xcodebuild test`, to compile once on the scarce macOS runners and survive runner-image changes, accepting that a broken test target now fails the build step.

### D5: What if the existing `PlayerTimeUtilsTests` fails when it first runs?

**Situation:** Nothing has run the one XCTest file since it was written in 2022. Its assertions match `PlayerTimeUtils`' current seek-back thresholds (`ios/App/Shared/player/util/PlayerTimeUtils.swift:27-45`), and its clock offsets (2, 12, 62, 302, 1802 s) sit well inside their buckets, so timing is a low risk. Its inline comments ("should seek back 5s") are stale; its assertions are not. The real hidden dependency is `isAutoRewindDisabled()`, which reads `Database.shared.getDeviceSettings()` (Realm, inside the app-hosted test process; `disableAutoRewind` defaults to `false` in `ios/App/Shared/models/DeviceSettings.swift:12`). A Realm init failure or a persisted setting in the host would make every case wrong or crash the test process. The parent ledger's Posture says a flaky test gets fixed or deleted. Fixing upstream player code is out of this issue's scope.

**Deciding factor:** getting the harness green without silently changing player behavior.

**Recommendation:** if a failure is the test's fault (stale expectations against behavior that's correct, or host state such as the Realm device setting leaking in), fix the test and record a build-time call. Making the test independent of persisted device settings (such as `disableAutoRewind`) is a permitted test fix. If it shows the player code is wrong, stop and ask before touching `PlayerTimeUtils`.
**Pick "delete the failing case" instead if** it's the test's fault and fixing it would need clock injection into upstream code.
**Reversibility:** easy
**Ratified:** Chose fixing a test- or host-state-caused failure as a recorded build-time call (including making it independent of persisted device settings) and stopping to ask on a code-caused one over deleting failing cases, to get the harness green without silently changing player behavior, accepting that a real `PlayerTimeUtils` bug pauses the build for a decision.

### D6: How does the iOS test target get to compile on its first run?

**Situation:** `PlayerTimeUtilsTests` does `@testable import Audiobookshelf`. The test target's deployment target is iOS 13.0 while the app and every pod are 14.0, and Swift won't import a module built for a higher minimum. The test target also has no Pods search paths, so "missing required module 'RealmSwift'" is the usual result for an app-hosted test target outside the Podfile. Each failure found by trial costs a ~25-minute macOS run, and a Podfile change needs a `Podfile.lock` regenerated through `refresh-pod-lock.yml` before `pod install --deployment` passes.

**Deciding factor:** fewest failed macOS round trips before the PR goes green.

| | A: Fix both up front | B: Deployment target up front, Podfile only if needed | C: Discover both on the first run |
|---|---|---|---|
| Failed ~25 min iOS runs expected | Likely none from these causes | One, if the missing-module error appears (likely) | One or two |
| Lock refresh round trips | One, planned before the first iOS run | One, only if needed | One, after a failed run |
| Upstream surface | 2 pbxproj lines + a 3-line nested Podfile target + lock | 2 pbxproj lines; Podfile only if proven | Same as A or B, decided late |
| Risk of an unneeded change | The Podfile block, if pods resolve without it (unlikely) | None | None |
| What peers do | CocoaPods' `pod init` template nests test targets with `inherit! :search_paths` | Same | Same |

**Recommendation:** A, because the deployment-target error is verified and the missing-module error is the standard outcome; one planned lock refresh is cheaper than a failed run followed by the same refresh.
**Pick B instead if** you want the Podfile untouched until a run proves the block is needed. **Pick C instead if** you'd rather not touch `project.pbxproj` build settings until a run shows the error.
**Reversibility:** easy
**Ratified:** Chose setting the test target's deployment target to 14.0 and adding a nested `target 'AudiobookshelfUnitTests' do inherit! :search_paths end` to the Podfile, with `Podfile.lock` regenerated before the first iOS run, over fixing only the deployment target or discovering both by trial, to avoid failed macOS runs, accepting two more fork-only edits to upstream iOS files.

### D7: How does the `js-tests` job install dependencies?

**Situation:** Every existing job installs with `npm ci --omit=optional` (`.github/workflows/ci.yml`), added in bf92d18b to skip fsevents. Vite's rollup and esbuild ship their Linux binaries as optional dependencies, so that flag makes Vitest fail with "Cannot find module @rollup/rollup-linux-x64-gnu". The only optional packages with install scripts in today's lock are two darwin-only `fsevents` copies, which npm skips on ubuntu anyway.

**Deciding factor:** a `js-tests` install that works on the first push, with the fewest moving parts.

**Recommendation:** plain `npm ci` in `js-tests`, with a one-line comment that the other jobs' `--omit=optional` exists for darwin-only fsevents. The other jobs keep the flag; they never run Vitest. Peers (capacitor-plugins) install with plain `npm install`.
**Pick "keep the flag and install the platform binaries explicitly" instead if** every job's install line must be identical, accepting a platform-package list to keep in sync with Vite and Vitest bumps.
**Reversibility:** easy
**Ratified:** Chose plain `npm ci` in `js-tests` over keeping `--omit=optional` with explicit platform binaries or copying the existing step, to install Vitest correctly on the first push with nothing to maintain, accepting that `js-tests`' install line differs from the other jobs'.

### D8: Does CI fail when a test silently stops running?

**Situation:** `ci-complete` treats each job's `success` as proof the tests ran. In the hand-registered iOS project, an upstream merge that touches `project.pbxproj` or the scheme can drop `PlayerTimeUtilsTests` from the target, and the job then runs zero tests and goes green. Android's `testDebugUnitTest` (Gradle 8.11) also passes when no tests are found. Vitest exits 1 when it finds no test files as long as `passWithNoTests` stays off.

**Deciding factor:** a gating harness that turns red when its tests stop running, with the least added machinery.

| | A: Assert it ran on every side | B: Assert on iOS only | C: Keep the human-read summary |
|---|---|---|---|
| Catches iOS test dropped from target | Yes | Yes | Only if someone reads it |
| Catches Android source-set misconfig | Yes (JUnit XML testcase count) | No | No |
| JS | Vitest default already fails on no files | Same | Same |
| Added machinery | Two checks | One check | None |
| Where the risk sits | Spread evenly | Where files are registered by hand and upstream merges land | n/a |
| What peers do | capacitor-plugins asserts nothing (and runs no XCTest) | Same | Matches peers |

**Recommendation:** B, because iOS is where silent drop-out is realistic, and one required-name check turns "must appear by name" into a failing step.
**Pick A instead if** you want the same guarantee on every side before #8/#9 add real Android tests. **Pick C instead if** you will read the summary on every PR that touches the iOS project.
**Reversibility:** easy
**Ratified:** Chose failing the iOS test step unless `PlayerTimeUtilsTests` executed (and keeping Vitest's `passWithNoTests` off) over asserting on every side or relying on the human-read summary, to make the iOS invariant enforce itself, accepting that an Android source-set misconfiguration still passes green until real Android tests arrive.

### D9: How long can the iOS test step run, and what does a failed run leave behind?

**Situation:** The tests are app-hosted, so the test step boots a simulator and launches the full app (Capacitor WebView, Realm) before injecting the bundle; host crashes and wedged simulator boots are the classic hangs. The Posture relies on the step's own timeout, but the job's `timeout-minutes: 45` (`.github/workflows/ci.yml`) is shared with a build of about 25 minutes (run 43491635540). If the step timeout is too large, the job timeout fires first, and an upload gated on `if: failure()` doesn't run on a cancelled job.

**Deciding factor:** a hang fails inside the job budget and leaves an artifact, without retry-like machinery the Posture ruled out.

| | A: Size the timeouts, upload on any non-success | B: A plus boot step, per-test timeouts, simulator and crash logs | C: Leave the numbers to the build |
|---|---|---|---|
| Hang fails inside the job budget | Yes: step `timeout-minutes: 15`; job `timeout-minutes` raised from 45 to 55 | Yes | Depends on the builder |
| Artifact after a timeout or cancel | `.xcresult` via `if: failure() \|\| cancelled()` | `.xcresult`, simulator log, DiagnosticReports | Only on plain failure |
| Tells a hang from a slow boot from a host crash | Partly | Yes | No |
| Added machinery | Two numbers and an `if:` | A pre-boot step, `-test-timeouts-enabled`, two more uploads | None |
| What peers do | capacitor-plugins: job-level 30-min timeout only, no artifacts | Not seen | Matches peers |

**Recommendation:** A, with the test step at `timeout-minutes: 15` and the job raised from 45 to 55. The last run's job took ~24.5 minutes, and D4's `build-for-testing` adds the test bundle compile, so 45 would leave a 15-minute step under 5 minutes of headroom; 55 keeps a hang inside the step timeout with room for a slow build. This works because the Posture already relies on the step timeout, so giving it a value that fits the budget is what makes the stance true; B's diagnostics can be added if hangs recur.
**Pick B instead if** the first runs show host crashes or hangs the `.xcresult` doesn't explain. **Pick C instead if** you'd accept a hang that leaves no artifact.
**Reversibility:** easy
**Ratified:** Chose a test-step `timeout-minutes: 15`, the `ios` job's `timeout-minutes` raised from 45 to 55, and uploading the `.xcresult` whenever the step doesn't succeed (including on cancel) over adding boot and crash-log diagnostics or leaving it to the build, to make a hang fail inside the budget with evidence, accepting that a host crash may still be hard to tell from a hang.

### D10: When does `js-tests` run, and what wiring does it need?

**Situation:** A new path-filtered job takes six edits in `.github/workflows/ci.yml`, not three: a `changes` output, a `paths-filter` entry, the job's `if:`, plus `ci-complete`'s `needs` entry, its `*_WANTED`/`*_RESULT` env pair, and its `check` call. The job's `if:` and its `*_WANTED` expression are hand-copied twins; if they diverge, a job that runs when "not wanted" fails `ci-complete`. The `android` and `ios` filters are `'**'` minus exclusions, so a PR touching only JS tests would start the ~25-minute macOS build.

**Deciding factor:** `js-tests` runs exactly when JS could be affected, with the fewest wasted macOS minutes.

| | A: Dedicated filter; exclude JS tests from native filters | B: Dedicated filter; native filters unchanged | C: Reuse android/ios outputs, no new filter |
|---|---|---|---|
| `js-tests` runs on every JS-relevant change | Yes | Yes | Yes (any non-docs change) |
| JS-test-only PR starts the macOS build | No | Yes | Yes |
| Edits to upstream-owned filters | Two exclusion lines | None | None |
| Reads like the existing filters | Yes | Yes | Needs an `\|\|` of two outputs in both `if:` and `*_WANTED` |
| What peers do | unknown | unknown | unknown |

**Recommendation:** A, because it matches the existing filter style and keeps JS-test-only PRs off the macOS runner, D4's own deciding factor, for two lines.
**Pick B instead if** you want the existing filters byte-for-byte unchanged. **Pick C instead if** you want no new `changes` output.
**Reversibility:** easy
**Ratified:** Chose a dedicated `js` filter (`'**'` minus `ios/**`, `android/**`, `**/*.md`, `design/**`, and the workflow-only files the `android` filter already excludes), all six touch points wired with the job's `if:` and `JS_WANTED` written as the same expression, and `!test/js/**` added to the `android` and `ios` filters, over leaving the native filters unchanged or reusing their outputs, to keep JS-test-only PRs off the macOS runner, accepting two fork-only exclusion lines in upstream's filters, and that a change to the root `vitest.config.mjs` or `package.json` still starts the native builds (inherent: they share the install). Only `test/js/**` is excluded, so the shared contract fixture #8 adds elsewhere under `test/` still triggers the native jobs.

## Assumed

- `ci-complete` judges each job by hand: listed in `needs`, a `*_WANTED`/`*_RESULT` env pair, and a `check` call; expected `success` when wanted, `skipped` otherwise. Path filters use `predicate-quantifier: every` with `'**'` minus exclusions. Evidence: `.github/workflows/ci.yml` (`changes` and `ci-complete` jobs).
- Android declares no test dependencies; `junitVersion = '4.13.2'` exists unused, and the template unit test is Java JUnit 4. Evidence: `android/app/build.gradle` dependencies; `android/variables.gradle`; `android/app/src/test/java/com/getcapacitor/myapp/ExampleUnitTest.java`.
- The Android Gradle build needs `npx cap sync` first (it applies `capacitor.build.gradle` and depends on `:capacitor-cordova-android-plugins`), so the test step runs after sync. Evidence: `android/app/build.gradle` (`apply from: 'capacitor.build.gradle'`, `project(':capacitor-cordova-android-plugins')`).
- The App scheme's test action includes `AudiobookshelfUnitTests`, an app-hosted bundle (`TEST_HOST` = the Audiobookshelf app). Evidence: `ios/App/App.xcodeproj/xcshareddata/xcschemes/App.xcscheme`; `ios/App/App.xcodeproj/project.pbxproj` (TEST_HOST/BUNDLE_LOADER).
- The Podfile declares no block for the test target (`ios/App/Podfile:28-34`), and the test target's `IPHONEOS_DEPLOYMENT_TARGET` is 13.0 while the app target and `platform :ios` are 14.0 (`ios/App/App.xcodeproj/project.pbxproj:791,813` vs `:746,770`; `ios/App/Podfile:3`). D6 settles both. `Podfile.lock` is regenerated with the existing `refresh-pod-lock.yml` workflow (no Mac is available locally), and CI's `pod install --deployment` fails until the regenerated lock is committed. Evidence: `.github/workflows/refresh-pod-lock.yml`; `.github/workflows/ci.yml` (`pod install --deployment`).
- The iOS project registers files by hand, so a new Swift test file is added to `project.pbxproj` explicitly. A test missing from the target otherwise passes silently (D8 guards the one existing test). Evidence: `ios/App/App.xcodeproj/project.pbxproj` (explicit `PBXFileReference` entries, no synchronized folders); parent Breakdown item 1.
- Vitest 3.x supports Node `^18 || ^20 || >=22`, and `@vitejs/plugin-vue2` 2.3.x peers on `vue ^2.7` and `vite ^3–^7`. The newest Vitest needs Node ≥22.12. Evidence: `npm view vitest@3 engines`, `npm view @vitejs/plugin-vue2 peerDependencies`.
- The JS modules use `@/` and `~/` aliases for the repo root. Evidence: `store/index.js`, `plugins/init.client.js`, `mixins/bookshelfCardsHelpers.js`.
- No Mac or iPhone is available locally; iOS tests run only in macOS CI. Evidence: parent ledger, Assumed.

## Build

- [x] Implementation and tests committed
- [x] Docs: none needed (the testing guide is #10)
- [ ] Ship-time challenge

**Calls made during the build:**
- Android test subject is `AudioTrack` (`data/AudioTrack.kt`: `startOffsetMs`/`durationMs`/`endOffsetMs`, `getBookChapter()`, `relPath`): the first class under `data/` with no `android.*` imports, and its members are Kotlin-only arithmetic. Verified locally: `testDebugUnitTest` runs 3 tests, 0 failures.
- Component smoke mount uses `components/ui/ToggleSwitch.vue`: a leaf with no Nuxt globals, so no minimal `.vue` fixture was needed.
- Plain-module tests run in Vitest's default node environment; only the component test opts into jsdom with a per-file `// @vitest-environment jsdom` comment, so later plain-module tests stay fast.
- `jsdom` is `^26.1.0`, not the latest 31: 31 requires Node ≥22.22/≥24.15, while 26 supports Node ≥18, so a contributor's older Node 22 or 24 still runs the tests.
- `package-lock.json` re-resolves a few existing packages (`postcss` 8.5.3→8.5.32, `debug` 4.4.0→4.4.3, top-level `yaml` 1.x→2.x with 1.x nested under its consumers, and other patch bumps): Vite and Vitest require newer `postcss` and `debug` than the lock pinned, and npm keeps one hoisted copy. All are in-range semver bumps; CI's `npm run generate` covers the web build. `package.json`'s existing dependency order is kept as upstream has it.
- iOS test step passes `-parallel-testing-enabled NO`: the scheme marks the bundle parallelizable, which makes xcodebuild clone simulators. With four tests that adds boot time and another hang mode for no gain.
- The iOS summary reads results with `xcrun xcresulttool get test-results tests` (Xcode 16+) instead of parsing xcodebuild's log, whose test-line format differs between serial and parallel runs. If the bundle can't be read, the step fails with its own error.
- D8's check requires a `PlayerTimeUtilsTests` test to have *passed*, not just appeared, so skipping every case (e.g. `XCTSkip`) also fails the step.
- `js-tests` has `timeout-minutes: 10`; the ledger set none, and the job runs in about a minute.
- No D5 failure handling was needed yet; the first macOS run decides.
