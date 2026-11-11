# Direct USB background silence and intermittent gaps

Research and implementation snapshot: 10 November 2026, PureBits 2.1/code 41.

## Reported behavior

The user reports that switching to another app can make exclusive USB audio
silent while the player slider continues moving. Pause/play restores sound;
reconnecting the DAC is not required. Normal output with bit-perfect mode Off
does not show the same symptom. There are also occasional gaps of about one
second during streamed music. Local/downloaded playback has not yet been tested
for this new issue. Returning immediately to PureBits was not tested either.

These observations do not identify one root cause. A UI clock is not an acoustic
measurement; a source pause, audio-focus attenuation, USB callback stall, worker
descheduling and DAC-side silence can have different remedies. No phone was
connected to local ADB, and no live playback or acoustic test was performed here.

## References and findings

The [GitHub reference review](audio-engine-references.md) records current star
counts, licenses and relevant source paths. Mornye (7,830 stars) is the strongest
direct Android USB comparison; the existing driver already credits it. libusb
(6,193) and Google Oboe (4,132) are already used by the native modules. Oboe's
AAudio/OpenSL output is separate from the userspace USB driver. Tryptify (101)
provides an additional Media3/libusb implementation, including a stalled-pump
watchdog. Counts were checked on 10 November and can change.

Current Mornye source retains several old transfer behaviors already corrected
in PureBits. Replacing the driver would discard software-volume and failure
safeguards. Its explicit USB wake lock was compared with the existing ExoPlayer
wake lock rather than copied automatically. The reviewed `audio_engine` library
has only four stars and AGPL-3.0 licensing; no source or dependency was imported.

The Android lifecycle audit found:

- Audio lives in a `MediaLibraryService` with foreground media permissions and
  `WAKE_LOCK`. Both real ExoPlayers already use `C.WAKE_MODE_LOCAL`.
- Installed Media3 1.11.0 source confirms that its CPU wake lock stays acquired
  while ready/buffering with play intent, independent of Activity visibility or
  whether the sink uses AudioTrack. No extra wake lock was added.
- Activity visibility controls artwork and UI polling, not playback/gain. No
  `onPause`/`onStop` mute hook was found. Canvas/video-only players do not own audio
  focus. The audible player already delegates focus handling to ExoPlayer.
- A legitimate focus mute/duck must remain respected. At a user gain of −48 dB,
  a 0.2 player/focus multiplier produces approximately −62 dB. A lowered output
  may sound silent without being a lost USB stream. There is no evidence yet
  that this multiplier is the cause on this phone.
- Network/Wi-Fi behavior is not established by a CPU wake lock. Since the new
  one-second gaps have only been tested with streaming, source buffering remains
  a hypothesis. No blanket battery exemption or network-lock change was made.

Relevant primary documentation:
[Media3 background playback](https://developer.android.com/media/media3/session/background-playback),
[Android audio focus](https://developer.android.com/media/optimize/audio-focus),
and the [Mornye USB bridge](https://github.com/mornyeapp/Mornye/blob/45df9860661e9c4e731ef26a60c44b2b5d8cf125/android/app/src/main/kotlin/com/zarz/mornye/UsbDirectPlayback.kt).

## Implemented changes

1. **Interrupted event polls retry.** The pinned libusb 1.0.27 POSIX backend can
   return `LIBUSB_ERROR_INTERRUPTED` when `poll()` is interrupted by a signal.
   The old worker passed that result through its generic fatal check. It now
   continues polling without discarding pending transfers. Other negative event
   errors remain fatal. This is a confirmed code defect, not proof that the
   user's app-switch event produced that error. See
   [the pinned libusb backend](https://github.com/libusb/libusb/blob/v1.0.27/libusb/os/events_posix.c)
   and [asynchronous transfer documentation](https://libusb.sourceforge.io/api-1.0/group__libusb__asyncio.html).
2. **Stalled completion guard.** A new independently implemented watchdog tracks
   accepted PCM frame deltas against cumulative native completion progress.
   After two seconds with queued media and no completion progress while playing,
   the direct transport stops through the existing fail-closed error path.
   It does not automatically switch to Android, replay stale data or reset gain.
   Pauses, a drained queue, source/network starvation and normal startup latency
   do not trigger it. Continued writes cannot postpone the deadline; real
   completion progress can. Timing fields are primitive values, with no clock
   boxing/allocation on the audio write path.
3. **Player and gain diagnostics.** Event-only service logs record ready/buffering
   state, play intent/reason, suppression, public player volume, software gain and
   buffer positions/duration. The transport separately records the actual
   player/focus multiplier, user dB, combined target dB and accepted/completed
   frames on volume/play/pause/failure changes, plus a bounded health sample every
   five seconds. Public `ExoPlayer.volume` alone does not expose the internal
   focus multiplier.
4. **Native diagnostics.** Logs now include requested versus actual worker
   priority, rate-limited scheduling gaps above 50 ms, pending/queued work and
   packet status/actual/expected lengths. Existing PCM-padding diagnostics remain.
   These distinguish producer gaps from USB packet loss or worker starvation.

The software-volume policy, ramp/mute behavior, sample-rate negotiation, 100 ms
queue and 64 ms transfer limit are unchanged. No vendor DSP/volume/EQ commands
were added. This work does not rewrite the app or replace a driver based on stars.

The watchdog can identify missing completion progress. It cannot detect a DAC
that acknowledges every USB transfer successfully but produces no audible sound.
Likewise, the native interrupted-poll fix cannot be claimed to solve every app
switch or network gap without runtime evidence from the affected phone.

## Verification

The native event-poll/transfer-health host test passed, including interrupted
polls that preserve the stream and other errors that must still fail. The native
ARM64 library compiled and linked. The new progress-watchdog suite has 15 tests:
stalls, genuine progress, repeated writes/play, pause/resume, drained network
gaps, startup, flush, clock rollback, invalid counters and overflow.

The full coordinated `:app:testDevDebugUnitTest :app:assembleDevDebug` build passed
with **1,397 tests, zero failures/errors and one skipped test**. The native
libraries built for arm64-v8a, armeabi-v7a and x86_64. Both native host test
executables passed, and the watchdog's final compiled timing fields were checked
to be primitive `long` values. The final Gradle process exit code was zero;
nonfatal existing SDK XML/deprecation warnings remain. Whitespace/conflict checks
pass. No hardware acceptance is claimed for the new background-silence issue.

Fresh signed diagnostic APK (version 2.1/code 41):
`build/outputs/PureBits-2.1-usb-background-debug.apk`. It installs as **PureBits Dev**,
package `com.dev.bitchord`, so production PureBits settings are separate. Normal
ABI/universal Gradle APKs remain under `app/build/outputs/apk/dev/debug/`.
The copied ARM64 APK's v2 signature was verified; SHA-256:
`289DA2E686C3F030825615995DFE292F84C03F433682D93706BDCC91FEAA5D02`.

Build logs: `build/purebits-usb-background-validation.log` and
`build/purebits-usb-background-final-check.log`. Existing local commands are in
[purebits-mobile-build.md](purebits-mobile-build.md).

Changed runtime files:

- `app/src/main/cpp/bitperfect/usb_transport.cpp`
- `app/src/main/cpp/bitperfect/usb_transfer_health.h`
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- `app/src/main/java/com/music/bitchord/playback/audio/bitperfect/usb/UsbDirectTransport.kt`
- New `app/src/main/java/com/music/bitchord/playback/audio/bitperfect/usb/UsbProgressWatchdog.kt`

The native transfer-health tests were extended, and 15 new JVM watchdog tests
were added. The research report, this report and the build-document link were
added/updated. Pre-existing generated release-file changes were left in place.
No commit or push was performed.

## Next phone test

Use the diagnostic build with Direct USB and software volume at a low chosen
level. Start with a downloaded/local track, then repeat with the affected stream.
Switch first to a non-audio app, then separately to an app that actually requests
audio focus. Test screen-off separately. Do not raise volume to diagnose silence.

If silence/gaps recur, return to PureBits and immediately use the playing song's
three-dot menu → **Copy log**, preferably before pause/play changes the state.
The log now includes player/focus/gain/USB progress and source-buffer clues, without
stream URLs or audio samples in these new diagnostic lines. Copy again after
pause/play restores sound, and record whether returning alone restored it.

The app's Copy log contains the Kotlin service/transport entries; native
`PureBitsUsb` diagnostics are in logcat and need ADB (wireless ADB or a USB hub
when the phone's port is occupied by the IEM):

```text
adb logcat -v threadtime PureBitsUsb:I AUDIO_OUT:D '*:S'
```

Do not collect all applications' logs or send credentials. Existing app log
exports may contain source/track metadata; review them before sharing.

Interpretation:

| Evidence | What it narrows down |
| --- | --- |
| Actual multiplier falls, combined dB becomes much quieter/muted | Player/focus gain behavior; preserve focus policy while checking restoration |
| Player enters buffering and source buffer empties | Source/network stall, not established as a USB driver fault |
| Accepted frames remain pending, completion clock stops | USB completion/worker stall; watchdog stops safely |
| Native worker-gap warnings grow | Scheduling/CPU starvation hypothesis gains evidence |
| Native PCM-padding grows while source remains ready | Decoder/producer scheduling or short supply to native queue |
| Normal multiplier, advancing successful completions, but silence | Requires DAC/route/firmware investigation; software counters cannot prove acoustic output |
