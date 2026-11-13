# REVIEW.md — ABS+ (repo root)

## Plugin Bridge Parity
The shared Vue code calls native plugins (`plugins/capacitor/` names them) that are implemented
twice: `android/app/src/main/java/com/audiobookshelf/app/plugins/` and `ios/App/App/plugins/`.
When a call's arguments change, or what a value means (an index vs a time, ms vs seconds, `0` as
"current"), does the receiving method on *both* platforms accept the new form? Read the native
method on each side. Watch for one that resolves `success: true` while ignoring the value.

## Events Across the Bridge
When JS starts listening for a plugin event (`addListener('on...')`) or one is renamed, does each
platform's plugin still emit an event under that exact name, with the payload fields JS reads?

## Time Units Across the Bridge
Does a time value crossing the bridge mean the same thing on both sides: seconds or ms, book time
or real time? iOS reports a chapter sleep timer's remaining time divided by the playback rate
(`ios/App/Shared/player/AudioPlayerSleepTimer.swift`). Does JS arithmetic that adds it to a book
position convert it first?

## The Other Platform's Copy
A fix to player behavior on one platform (sleep timer, progress sync, chapters, downloads): does
the other platform's implementation (`ios/App/Shared/player/` vs
`android/app/src/main/java/com/audiobookshelf/app/player/` and `managers/`) have the same flaw?
If it's deliberately left, does the commit say so?

## Upstream Cherry-Pick Surface
ABS+ still cherry-picks fixes from advplyr/audiobookshelf-app. Does the change edit upstream files
beyond what it needs (reformatting, renames, moved code), or could it be additive or applied at
build time instead, as `.github/workflows/ios-testflight.yml` does for iOS branding?
