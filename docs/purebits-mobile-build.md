# PureBits mobile build

PureBits builds the phone app (`:app`) and its required `:shared` and `:sharedUi`
libraries. The upstream `tv/` and `wearos/` directories are retained as reference
source but are not included in Gradle or built by CI.

TV depended on the phone's sources; the phone never depended on TV. Wear OS did
affect phone builds: each phone APK built and bundled a watch APK and compiled a
protocol file from `wearos/protocol`. PureBits removes that bundling, source path,
watch settings and listener, watch playback bridge, and watch-only dependencies.
The phone build no longer needs either directory. The shared player interface
still exposes an unavailable watch output, so existing player components remain
compatible. Google Cast and Android Auto continue to use their existing paths.

Use JDK 17 or 21 and an Android SDK with platform 37 and CMake 3.22.1. Local
verification used the installed JDK 21. The Android Studio bundled JDK 25 failed
with this project's Gradle 8.11.1 before configuration; set `JAVA_HOME` and the
IDE's Gradle JDK to 17 or 21. Point `sdk.dir` in `local.properties`, `ANDROID_HOME`,
or `ANDROID_SDK_ROOT` at the SDK.

```sh
./gradlew projects
./gradlew :app:testDevDebugUnitTest :app:assembleDevDebug
./gradlew :app:assembleProdRelease
```

On Windows use `gradlew.bat`. Release signing remains optional locally; provide
`keystore.properties` and its keystore to produce a signed release. Phone and
shared-module tasks must not include `:tv`, `:wearos`, or `bundleWearApk` tasks.

## Player and home changes

The large Top Picks shelf is removed from Home. Recents keeps its compact layout
and now starts below the header (or the sign-in banner when signed out).

The player has a **Search next source** button with a source icon above the lyric
strip. It searches for the same title, artist and recording length in eligible addons,
starting after the current addon in the configured order and wrapping to earlier
ones. It skips the current source and sources already attempted during this
play. Disabled addons and addons outside the current network/output quality
policy are excluded. Missing matches, failed lookups and dead streams advance
to the next eligible addon. Each source lookup has a 20-second limit.

The current stream keeps playing during the search and silent playback check.
A successful replacement keeps the queue item and playback position. Every
replacement has a separate audio cache entry, and automatic quality upgrades
cannot silently replace that manual choice. If all alternatives fail, the
current source stays selected. Moving to another song resets the attempted list.

Source switching applies to streamed audio on the phone, with playback loaded
and no transition in progress. It is unavailable for local files, downloads,
videos, locked party controls and Cast playback. New cache entries retain the
source ID across app restarts. Older addon cache entries without source details
must be cleared and replayed before the button can safely exclude their source.

Device checks still needed: try a known mismatched track with two addons, tap
Search next source repeatedly until exhausted, skip tracks during a search, and
test a failed addon URL. Check the portrait/landscape player and lyrics/queue transitions on
the actual phone. Unit tests cover lookup ordering, identity checks,
cancellation, failures, rendition isolation and source attribution.

The existing Android release workflow always creates a keystore from GitHub
Actions secrets. A fork needs those signing secrets for that workflow to pass;
the local unsigned fallback does not apply to an empty CI keystore. The Check
workflow builds a dev APK and can run without release-signing secrets.

## Local verification, 8 November 2026

With JDK 21, `projects` includes only `:app`, `:shared` and `:sharedUi`.
The combined `:app:testDevDebugUnitTest :app:assembleDevDebug
:app:assembleProdRelease` build completed successfully. The unit test report
contains 1,232 tests, zero failures/errors and one skipped test. Debug APKs and
unsigned production release APKs were generated for all three configured ABIs
and as universal APKs. No release signing key was available locally.

The debug APKs have no bundled watch APK, watch listener or watch-only native
libraries. The build graph has no TV/Wear OS/bundled-watch tasks. The existing
toolchain emits Kotlin metadata diagnostics from Android lint and R8 for newer
dependencies; these did not fail the build. No dependency/toolchain upgrade was
introduced as part of these changes.

There was no connected Android device. Compilation, tests and packaging were
verified; real addon network responses, playback and the final screen layout on
your phone still require the device checks above. Backend/VM validation is
covered separately in [the Oracle assessment](oracle-vm-hosting.md).

## Upstream merge recheck

The source-search control is labeled **Search next source**, with
**Searching next source…** shown during lookup. Search uses the configured
track-length tolerance, matching the upstream playback validation policy.
Long translated labels keep the source icon visible and leave room for lyrics
in the landscape player.

The upstream update introduced hardware-backed addon authentication. Addons
that require official BitChord app/signing verification may refuse a custom
PureBits or debug build. Plain addons can still work. Keeping or changing the
auth service URL does not grant a fork access to protected addons; their
operator must approve its credentials or signing identity. Build/unit checks
cannot verify acceptance by a remote addon. The upstream authentication checks
are preserved in this merge.
