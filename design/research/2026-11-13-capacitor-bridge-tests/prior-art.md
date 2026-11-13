---
topic: "Testing the JS↔native bridge contract in Capacitor apps"
date: 2026-11-13
status: Draft
---

# Prior Art: Testing the JS↔native bridge contract in Capacitor apps

## The Problem

A Capacitor app's shared JS calls native plugin methods that are written twice, once in Kotlin and once in Swift. The call's shape (`setSleepTimer({ time, isChapterTime })`) can stay identical while its meaning drifts: ABS+ changed `time` to mean a chapter index on Android while iOS still read it as an end time in ms, and iOS resolved `success: true` and did nothing. Builds and type checks can't see that kind of drift. The question is how comparable projects catch it, and how much machinery they spend on it.

## How We Do It Today

No tests run. CI builds the APK (ubuntu) and an iOS simulator app (macOS) and nothing else. Android declares no test dependencies (the template `ExampleUnitTest` wouldn't compile). iOS has one real XCTest file (`PlayerTimeUtilsTests`) in a test target the shared scheme already includes. The plugins are hand-written, registered by name from JS with no TypeScript definitions, and the platforms already differ on purpose (Cast is Android-only; `AbsDatabase` has 24 Kotlin methods vs 17 Swift). A root `REVIEW.md` asks reviewers the bridge-parity questions.

## Patterns Found

### Pattern 1: Build-and-lint only; test the bridge by hand
**Used by**: ionic-team/capacitor-plugins (the official plugins); most capawesome plugins.
**How it works**: CI proves each native side compiles (`xcodebuild build` on macOS, `gradlew build test` on ubuntu) and the TS builds and lints. The test directories are the generator's stubs (`2 + 2 == 4`, empty XCTest). Behavior across the bridge is checked by hand in an example app; unit consistency is a docs convention ("prefer identical units").
**Strengths**: Near-zero upkeep; fits a no-Mac maintainer.
**Weaknesses**: Wouldn't catch the motivating bug. A green `verify` says nothing about the bridge.
**Example**: https://github.com/ionic-team/capacitor-plugins/blob/main/share/package.json
**Read**: capacitor-plugins `.github/workflows/ci.yml`, `share/package.json`, the share plugin's Android and iOS test files; capawesome `packages/file-picker`.

### Pattern 2: Names-only wiring check
**Used by**: Cap-go (capacitor-updater, capacitor-mute).
**How it works**: A regex script in CI extracts the plugin's registered name from JS, the Kotlin/Java `@CapacitorPlugin(name)` and the Swift `jsName`, and fails on a mismatch. Extending it to method lists is straightforward but not something they do.
**Strengths**: Cheap; runs on Linux.
**Weaknesses**: Names only; never argument names, types or units.
**Example**: https://github.com/Cap-go/capacitor-mute/blob/main/scripts/check-capacitor-plugin-wiring.mjs
**Read**: capacitor-updater `scripts/check-capacitor-plugin-wiring.mjs`.

### Pattern 3: Shared JSON fixtures run by JUnit and XCTest
**Used by**: Cap-go/capacitor-updater.
**How it works**: One fixture file of `{id, input, expect}` cases per behavior. Each platform has a small runner that feeds `input` to its own pure function and asserts `expect`. The logic under test is pulled out of the plugin class into pure functions, so no `PluginCall`, WebView or player is needed.
**Strengths**: Pins meaning and units on both platforms against one source of truth; fast JVM tests on ubuntu, XCTest on macOS CI; the fixture doubles as documentation.
**Weaknesses**: Needs the argument-to-meaning logic extracted into testable functions; doesn't cover the glue (`call.getString("time")` key and type) unless the function takes the raw options; their README explicitly excludes the bridge itself.
**Example**: https://github.com/Cap-go/capacitor-updater/tree/main/native-contract-tests
**Read**: its README, `core.json`, the JUnit runner, `package.json` scripts and workflow job names. Swift runner: secondary (listed in the README, not opened).

### Pattern 4: Emulator/simulator E2E (Maestro)
**Used by**: capawesome-team/capacitor-plugins (opt-in per package).
**How it works**: Build the example app, boot an Android emulator (Linux, KVM) or iOS simulator (macOS runner), run Maestro YAML flows against a mock server.
**Strengths**: Exercises the real bridge and WebView.
**Weaknesses**: Slow, flaky-prone, heavy setup; the flow read only asserts visible UI, so catching the sleep-timer bug would need flows that assert native state.
**Example**: https://github.com/capawesome-team/capacitor-plugins/blob/main/.github/workflows/ci.yml
**Read**: that workflow's e2e jobs, `packages/live-update` example and its `.maestro/smoke-test.yaml`.

### Pattern 5: Single-schema codegen (Pigeon, React Native Codegen)
**Used by**: Flutter Pigeon; React Native TurboModules.
**How it works**: A typed spec generates the caller and each platform's native interface and argument parsing; a non-conforming implementation fails to compile.
**Strengths**: Removes hand-written argument parsing; compile-time signature drift detection.
**Weaknesses**: Doesn't encode meaning (an `int` going from ms to index still compiles); no Capacitor generator exists.
**Example**: https://github.com/flutter/packages/tree/main/packages/pigeon
**Read**: Pigeon README and `platform_tests/README.md`; RN "What is Codegen" docs.

## Anti-Patterns

- Treating template stub tests as coverage: the official plugins' `gradlew test` passes on `2 + 2 == 4`.
- Relying on a units convention in docs with no executable check.

## Gaps

- No Capacitor-specific schema-to-TS/Kotlin/Swift generator found.
- No tool compares argument names or types between JS and native `call.getX(...)` usage.
- No app-level (non-plugin) project found that tests its own in-app Capacitor plugins; all evidence is from plugin repos.
- Cap-go's Swift contract runner, and generic cross-platform contract-test writeups, were not opened.

## Relevance to Us

The official ecosystem's answer is "don't test the bridge", which is exactly the posture that let this bug through, so it's the baseline to beat rather than copy. Pattern 3 is the only one that pins the *meaning* of an argument, which is what drifted, and it runs within the no-Mac constraint (JVM on ubuntu, XCTest in the existing macOS job). Its cost is extracting the argument-handling logic into pure functions, which edits upstream native files: a small, per-method cherry-pick cost. Pattern 2 is cheap and catches a different, real failure (a method added on one platform only), but would not have caught this bug. Patterns 4 and 5 are more machinery than a solo fork maintainer should carry: E2E is slow and flaky, and codegen still misses meaning drift.

## Recommendation

Adopt Pattern 3 for the bridge calls whose meaning matters, starting with `setSleepTimer`, with the fixture also consumed by a JS test of what the shared code sends. Add Pattern 2 extended to method and event names, with an explicit list of intended platform-only methods. Skip E2E and codegen. Coverage of native logic beyond the bridge (Pattern 1's gap) is a separate, later choice.

## Sources

### Reference implementations
- https://github.com/ionic-team/capacitor-plugins — official plugins; build-and-lint CI, stub tests
- https://github.com/capawesome-team/capacitor-plugins — opt-in Maestro E2E
- https://github.com/Cap-go/capacitor-updater/tree/main/native-contract-tests — shared JSON fixtures, JUnit + XCTest
- https://github.com/Cap-go/capacitor-mute/blob/main/scripts/check-capacitor-plugin-wiring.mjs — names-only wiring check
- https://github.com/flutter/packages/tree/main/packages/pigeon — schema codegen
- https://github.com/daangn/metabridge — generic WebView bridge codegen (not opened)

### Documentation & standards
- https://capacitorjs.com/docs/plugins/creating-plugins — plugin workflow and units convention
- https://github.com/ionic-team/capacitor-docgen — reads TS definitions only
- https://reactnative.dev/docs/the-new-architecture/what-is-codegen — RN codegen
