# Android exclusive USB audio references

Research snapshot: 10 November 2026. GitHub stars, license metadata and last-push
dates were checked through the public GitHub repository API. Stars describe
adoption, not measured DAC compatibility or hearing safety.

## Shortlist

| Project | Stars | Published license | What it actually provides | Fit for PureBits |
| --- | ---: | --- | --- | --- |
| [Mornye](https://github.com/mornyeapp/Mornye) | 7,830 | LGPL-3.0; Rust workspace specifies LGPL-3.0-only | Android player with a userspace direct USB driver, plus separate Android native USB and AAudio output paths | Closest established reference; the current BitChord/PureBits USB implementation already credits it. Compare lifecycle changes rather than replace blindly. |
| [libusb](https://github.com/libusb/libusb) | 6,193 | LGPL-2.1 | Cross-platform low-level USB transfer library with Android support | Already the transport dependency. It is not a decoder, music player, gain controller or complete UAC engine. |
| [Google Oboe](https://github.com/google/oboe) | 4,132 | Apache-2.0 | Android C++ audio abstraction over AAudio/OpenSL ES | Strong Android audio scheduling/error-handling reference. Its exclusive endpoint is not a userspace USB host driver and is not a drop-in replacement for the current direct USB path. |
| [Tryptify](https://github.com/tryptz/Tryptify) | 101 | GPL-3.0 repository; native driver declares GPL-3.0-or-later | Android Kotlin/Media3 player with actual C++ libusb UAC1/UAC2 source | Useful second implementation for sink/driver diagnostics and gain/stream ownership patterns. Smaller adoption than Mornye; not independently hardware-tested here. |
| [minervarr/audio_engine](https://github.com/minervarr/audio_engine) | 4 | AGPL-3.0 | Android Java/C++17 playback library, including a libusb USB output and desktop backends | Architecturally relevant engine interface, but not a well-starred or proven drop-in component. Any future copied integration needs deliberate license handling. |

Additional projects checked: [hiresTI](https://github.com/yelanxin/hiresTI) has
190 stars and GPL-3.0, but is a Linux desktop TIDAL client, not an Android USB
service. [truesample-player](https://github.com/devang-altf4/truesample-player)
has 3 stars and GPL-3.0; it is a much smaller experimental reference. Neither is
evidence of a mature Android replacement. All seven repositories were unarchived
when queried. No proprietary Resonāda or USB Audio Player PRO code was used.

The API metadata can be reproduced with
`https://api.github.com/repos/OWNER/REPOSITORY`; counts will change.

## Concrete patterns worth comparing

Mornye's [Android USB bridge](https://github.com/mornyeapp/Mornye/blob/main/android/app/src/main/kotlin/com/zarz/mornye/UsbDirectPlayback.kt)
owns a dedicated audio-priority `HandlerThread` and an explicit partial wake
lock for direct USB. Its resume path fills before starting, acquires the lock,
then pumps at 5 ms intervals. Pause/close release the lock. Filling can process
several chunks and checks native queue room before copying. These are useful
comparisons for a custom sink that bypasses Android's normal AudioTrack path.
The current upstream driver also retains older transport behaviors; importing
the whole driver would lose some PureBits software-volume and error safeguards.

Tryptify's [Media3 libusb sink](https://github.com/tryptz/Tryptify/blob/main/app/src/main/java/tf/monochrome/android/audio/usb/LibusbAudioSink.kt)
documents a stalled-iso-pump watchdog: native opening can succeed while completion
callbacks stop progressing. It compares actual driver frame progress and elapsed
time, rather than assuming accepted writes prove output. Its recovery redirects
to normal Android output. That redirection must not be copied into PureBits'
safety-focused direct mode because the other route has independent gain.

Its [native driver](https://github.com/tryptz/Tryptify/blob/main/app/src/main/cpp/usb/libusb_uac_driver.cpp)
uses a 1 MiB ring and four transfers with eight packets each. Buffer sizes must be
converted to media time at the actual format: 1 MiB is about six seconds of
16/44.1 stereo. Enlarging an already-attenuated queue that far would delay volume
down/mute, so this constant is not a suitable fix to paste into PureBits.

Oboe's [full guide](https://github.com/google/oboe/blob/main/docs/FullGuide.md)
emphasizes high-priority audio callbacks, nonblocking real-time work, buffer
priming and safe close/reopen after disconnection. Ordinary threads can be
preempted and jitter; larger buffers trade latency for resilience. Exclusive
mode must be verified rather than assumed from a request. These are design
lessons; swapping libusb for Oboe would change the output mechanism.

## Dependency state

PureBits' `app/src/main/cpp/CMakeLists.txt` currently pins libusb 1.0.27. The
[latest release checked](https://github.com/libusb/libusb/releases/tag/v1.0.30)
was 1.0.30. Its [changelog](https://github.com/libusb/libusb/blob/master/ChangeLog)
includes an Android `get_usbfs_fd()` intermittent-failure fix, removal-race fixes
and descriptor parsing hardening. This warrants a separate compatibility review;
it does not establish the cause of silence in an already-open stream.

The [official Android integration notes](https://github.com/libusb/libusb/blob/master/android/README)
describe obtaining USB permission in Java and passing the open file descriptor to
native `libusb_wrap_sys_device`, matching this app's basic permission model.

## Application to the reported 2.1 symptoms

App switching followed by silence with an advancing playhead does not, by itself,
prove that decoding is too slow or that a different implementation language will
fix it. Distinguish player/focus mute, transport worker scheduling, USB completion
failure and producer/network starvation with actual state/clock/queue logs.

For the occasional one-second gap, test a local file separately from streamed
addon audio. A USB queue underrun and a source download pause can sound alike.
Record queue padding, successful/failed transfer completions, worker scheduling
gaps, requested/applied gain, audio-focus changes and whether the app is backgrounded.

The immediate useful integration is the missing lifecycle/diagnostic pattern that
matches measured evidence. Mornye is the strongest direct-USB comparison by both
relevance and adoption; Oboe is the stronger platform-audio reference. There is no
verified highly starred Android USB driver library here that can simply replace
the current engine and guarantee correct output on the Bunny DSP.

This research changed documentation only. It did not import third-party engine
code, modify DAC registers, change gain policy, replace native dependencies or
perform playback/hardware verification. Implementation and validation belong to
the associated background-playback investigation.
