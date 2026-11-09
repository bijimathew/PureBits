# Direct USB software volume investigation and validation

Date: 9 November 2026. Applies to this PureBits checkout, version 2.0 (code 40).

## Result and limits

Direct USB now has Auto, Hardware Volume and Safe Software Volume modes. Auto
uses software volume for every DAC, including the Bunny DSP, without hardware
volume writes. Explicit Hardware mode retains standard UAC Feature Unit control;
failed initialization falls back to software. A failed hardware control during
playback stops the stream, because the resulting per-channel state is uncertain.

The Android debug app builds and its unit tests pass. The user now reports the
stuttering is gone, after previously reporting that the volume spikes stopped.
Their screenshots report Direct USB/Exclusive/Software, −48 dB attenuation,
PCM24 at 44.1 kHz and no resampling. No Android phone was connected to local ADB
during validation; exact HyperOS/API, acoustic levels, firmware behavior and
long-term background/key handling have not been independently measured. The
user's playback result and software tests do not establish an acoustic safety
limit or a guarantee against every firmware transient.

## Follow-up: stuttering with normal pitch

The user reports that volume no longer spikes, but Direct USB has gaps/stutters
that make playback feel slower. Pitch stays normal. The other two selections
play ordinary audio without an apparent output change.

A scheduling regression in the first software-volume integration was reproduced
in the sink tests: the volume write gate accepts at most 10 ms per call, while
the sink yielded after one positive short write. Media3's approximately 10 ms
renderer retry interval then left almost no scheduling margin. A 4,096-frame
44.1 kHz block needed ten sink calls; at 96 kHz it needed five. The native driver
filled producer gaps with silence and counted only real media toward the playhead,
explaining the stutter and apparent slowdown without requiring a clock-rate change.

The sink now drains consecutive positive writes in one call until the bounded
input chunk is consumed or the transport reports zero space. Player start happens
after that initial drain. The 10 ms gain staging remains, preserving partial-write
safety and volume response. There is no additional resampling or amplification.
Regression tests verify one-call 44.1/16 and 96/24 output, startup after buffering,
zero-space backpressure, exact continuation and idle resume.

Native changes replace the allocating per-byte deque with a preallocated PCM ring.
Before the first submission, a bounded startup prefill gives the producer up to
20 ms to supply the scheduled ring's music; short clips still start at that deadline.
The 100 ms queue and at-most-64 ms transfer budget stay unchanged. Rate-limited
`PureBitsUsb` "PCM queue padding" logs distinguish producer starvation from
isochronous packet errors. End-of-stream padding can be normal. The existing
packet-loss limits, fail-closed handling and software-volume policy remain intact.

The other modes have different eligibility:

- Built-in AAudio Exclusive requires a built-in wired headphone/analog line
  output and an exclusive grant. USB earphones do not satisfy that requirement.
  Opening can no longer silently ask AAudio for default device 0 under this label.
- Android native USB requires Android 14+ **and** exposed BIT_PERFECT mixer
  attributes for the route/format. That OEM HAL feature is optional, as described
  in [AOSP's preferred mixer attributes documentation](https://source.android.com/docs/core/audio/preferred-mixer-attr).
  Android version alone does not establish support. No connected Android host was
  available to query this Xiaomi's actual attributes.

Settings, the mode picker and Audio Pipeline now explain absent wired outputs,
USB permission, missing native support, format rejection and exclusive refusal.
Unsupported non-direct modes can keep their existing normal Android fallback;
their labels no longer imply the requested exclusive route engaged. Old rejection
reasons are cleared on mode changes. Device/API eligibility is separate from a
successful track-format negotiation and stream open.

Host gain/copy benchmarks processed 60 seconds of 44.1 kHz stereo in about
26–33 ms across 16/24-bit and partial-write cases on the Windows JDK. This is not
an Android CPU measurement, but it found no reason to weaken or bypass attenuation.
The reproducible sink starvation bug is fixed, and the user has confirmed that
the replacement APK no longer stutters on their phone.

## Screenshot follow-up: an unrelated fallback warning

The successful-playback screenshot also showed "Fallback: Direct playback
unavailable for active USB device". That text belongs to the separate ordinary
AudioTrack negotiation in `AudioOutputStatus`, not the active native USB driver.
The pipeline now suppresses ordinary-sink fallback diagnostics while an exclusive
transport is active. They remain visible when normal Android output is actually
in use. This is a display-only change; no gain, buffering or routing code changed.

The screenshot's "USB Device Capability: PCM 24-bit / 96 kHz" is Android's reported
maximum capability, separate from the actual 24-bit/44.1 kHz output. "Bit-exact:
No — USB software attenuation" is correct at −48 dB: sample scaling changes the
PCM, while USB ownership remains exclusive. Increasing volume to unity is not
required to make exclusive playback work.

## Investigation

The user connected the real Bunny DSP to the Windows computer during this task.
Read-only standard USB descriptor queries confirmed:

| Property | Connected unit |
| --- | --- |
| Product name (Windows) | TANCHJIM BUNNY DSP |
| VID:PID | `31B2:1112` |
| Device revision | `bcdDevice 0101` (1.01) |
| USB audio | UAC1, full-speed |
| Playback | Stereo PCM; 16-bit in 2-byte slots or 24-bit in 3-byte slots |
| Advertised playback rates | 44,100 / 48,000 / 96,000 Hz on both playback alternates |
| Playback endpoint | Interface 2, OUT `0x01`, adaptive (`0x09`), 1 ms interval; sample-frequency control advertised |
| Maximum packet | 384 bytes (16-bit alternate); 576 bytes (24-bit alternate) |
| Capture | Separate interface 1, mono 16-bit, 44.1/48 kHz; not a playback format |

The playback chain is output terminal 8 → Feature Unit 7 → mixer 6 → USB input
terminal 5. Feature Unit 7 advertises channel volume, but `UsbVolume.feature`
deliberately cannot follow an ambiguous mixer. The actual parser returns no
trusted hardware volume for this playback chain; capture Feature Unit 2 must
not be selected instead. This explains why the old hardware-only default could
refuse direct playback and why allowing fixed full-scale output was unsuitable.
It does **not** establish which event caused the user's audible volume spikes.

All six actual-descriptor regression cases pass against the app's parser and
transport planner. Exact 16/24-bit plans at all three advertised playback rates
exist without changing descriptor parsing. A usable plan is not proof that the
Android host/device accepts opening, clock controls or sustained streaming.

The reusable collector is `tools/Get-UsbAudioDescriptors.ps1`:

```powershell
.\tools\Get-UsbAudioDescriptors.ps1 -VendorId 0x31B2 -ProductId 0x1112
```

It uses the existing Windows hub driver and only standard read queries described
by [Microsoft's USB descriptor API](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/usbioctl/ni-usbioctl-ioctl_usb_get_descriptor_from_node_connection).
There was no audio playback, hardware-volume read/write, vendor/DSP command,
reset or driver replacement. Raw device/configuration/combined descriptors and
capture summary are in ignored `build/usb-diagnostics/`. The saved test fixture
contains descriptor bytes with device string indices removed; it contains no
serial strings or Windows device-instance paths. OS/HyperOS/API and actual audio
behavior still need the Android phone.

| Question | Finding in this checkout before the change |
| --- | --- |
| Device detection | `UsbDirectDevices` chooses an attached Android USB device with an AudioStreaming interface. Android permission is required; raw descriptors are cached per attachment. |
| Format selection | `UsbDescriptors` parses UAC1/UAC2 interfaces, clocks, endpoints, sample containers and advertised rates. `UsbDirectTransport.plans` ranks actual descriptor formats; native opening verifies supported clock changes. No Bunny format or VID/PID was assumed. |
| Hardware volume | `UsbVolume.feature` follows an unambiguous playback terminal chain to a writable master or all-channel Feature Unit. `Volume.open` reads signed 1/256 dB ranges/current values, lowers startup to at most −40 dB and verifies write readback. |
| Reliability of hardware gain | Readback checks protocol agreement; it cannot prove the audible gain. We have no measured evidence that Bunny hardware-volume commands cause the reported loud jumps. |
| Volume representation | Hardware volume was stored as dB in an attachment-local map and exposed as a normalized slider through `DacVolume`. It did not scale direct USB PCM. |
| Physical keys | Existing activity/dialog handlers call `DacVolume`; the Media3 session exposes remote volume for background controls. These paths are reused for software volume. No global key interception service was added. |
| Startup order | Native USB starts paused with an empty queue. Kotlin initializes volume before any write/play. The old fixed-volume override could admit unattenuated PCM when no usable hardware control existed. |
| PCM processing | `BitPerfectAudioSink` converts decoder integer/Float32 PCM into little-endian integer endpoint containers, optionally using the existing limited-DAC converter. Normal EQ, spatial processing and ReplayGain are bypassed on this exclusive sink. Its player-volume callback previously did nothing. |
| Shutdown | Successful releases attempted to restore the original hardware level before the worker was closed. On failures restoration was skipped. Restoring old gain while queued audio can still play is inappropriate for this safety-focused path. |
| Transport errors | One failed isochronous packet aborted the native worker. Routing then continued through Android's normal sink, which has independent gain. Native clock errors could be swallowed after the last buffer. |
| Reporting | Exclusive output and source conversion exactness were reported, but there was no direct software attenuation state. Some pipeline rows reflected the inactive normal AudioTrack/DSP. |

The native changes proposed in [issue #1485](https://github.com/kushagrasinghx/BitChord/issues/1485)
were absent from the local starting source. Its packet-loss findings are relevant;
its restoration to old/0 dB hardware levels is deliberately not adopted.
[Issue #1295](https://github.com/kushagrasinghx/BitChord/issues/1295) describes track-boundary
hardware attenuation; [#1296](https://github.com/kushagrasinghx/BitChord/issues/1296) and
[#1297](https://github.com/kushagrasinghx/BitChord/issues/1297) describe background stuttering
and buffering. These reports were still open when inspected. They are separate
observations, not proof of the cause of this user's maximum-volume symptom.

The Android AudioTrack "Direct: Rejected" result belongs to Android's native
USB path, not this libusb transport. This change does not alter Android's decision
to reject an AudioTrack direct configuration.

[Resonāda's public documentation](https://github.com/resonada/resonada)
is a functional reference for exclusive ownership and software attenuation. No
proprietary binaries were decompiled or implementation copied. The
[Bunny EQ register map](https://github.com/vzpyr/bunnyeq#register-map) describes a
separate HID DSP volume register, `0x66`, with per-side attenuation. PureBits does
not send that vendor protocol or modify persistent DSP/EQ registers.

## Implemented signal path

```text
decoder PCM
  -> existing format conversion / optional DAC-limit conversion
  -> raw sink history (for idle suspend/resume)
  -> USB PCM write gate: software user gain × player mute/focus multiplier
  -> bounded native queue
  -> claimed isochronous USB endpoint
```

`UsbPcmWriteGate` is the only Kotlin entry for PCM to native `write`. It owns a
reusable direct staging buffer, processes each staged byte once, and retains
processed tails across zero/partial native writes. Sink/history buffers stay raw;
reopening after a long pause applies the current gain once. Seek/flush discards
pending staged bytes without resetting gain to unity. Malformed frames, changed
retry buffers or invalid native counts fail instead of sending unchecked bytes.

`UsbSoftwareGain` supports signed 16-bit, packed 24-bit, 32-bit and Float32 PCM.
The USB endpoint currently receives packed integer PCM after the existing decoder
conversion. Channels share one frame gain; integer arithmetic scales in Double
and rounds/clamps without integer overflow. Gain changes ramp over 10 ms; mute
and invalid gain requests produce actual zero samples. Unity integer PCM takes
an unchanged-byte path. No sample-rate conversion is added. DoP/native DSD plans
are excluded from this PCM gain path; existing DSD-to-PCM decoding remains.

Media3 mute and audio-focus ducking are a separate multiplier. They never overwrite
the user's saved DAC gain. The multiplier is established before the first write
and reapplied when reopening the device.

## Volume policy and controls

- Slider travel is linear in dB from −80 to 0 dB, with zero reserved for exact
  silence (stored as a finite −120 dB mute sentinel). A 50% position is −40 dB;
  this percentage is not an acoustic loudness percentage.
- A new connection, service/process session, output-mode change or output failure
  starts at the quieter of saved dB and −40 dB. Saved unity never automatically
  becomes a startup value. Saved mute stays muted.
- Within one healthy USB session the chosen level survives tracks, seeks,
  pause/resume, idle release/reopen and format changes.
- Persisted software gain is keyed by real device vendor/product/revision. Two
  physically identical devices can share this model key; each new session still
  gets the conservative startup ceiling. No serial number is logged or required.
- Software volume keys move in 1 dB steps. The existing HUD, player slider,
  settings slider and session volume use the same control state.
- Above −20 dB, output is clamped until an explicit foreground confirmation.
  Repeated/held keys cannot authorize the increase. A later decrease cancels a
  pending increase. Confirmation expires at a real session boundary.
- These dB thresholds are digital precautions, not calibrated hearing limits.
  Actual acoustic output depends on the IEM, its DSP state and the recording.

Auto uses software on all DACs because descriptor validity/readback alone cannot
establish acoustic reliability. Hardware is an explicit option for compatible
devices. There is no full-scale fixed-volume bypass. Switching into/out of direct
USB, or changing its volume mode, pauses playback so another route does not
automatically start at its unrelated system level.

Every direct USB error, denied permission, missing device or unsupported format
stops rather than redirecting to AudioTrack. This is an intentional behavior
change: choose another output and check its volume before pressing play. A direct
stream's failure policy remains latched even if the preference changes while an
error is being reported. Stream/addon recovery does not retry tagged USB output
failures as a different music source.

No hardware volume restoration is attempted on release or errors. Software mode
leaves hardware volume untouched. Explicit hardware mode can leave the DAC at a
quieter setting for other applications; inspect their volume separately rather
than compensating with an automatic 0 dB write.

## Native resilience and reporting

The driver uses up to 16 isochronous transfers, limited to 64 ms in flight, plus
a frame-aligned 100 ms queue and at most 10 ms of Kotlin staging. This replaces a
256 KiB minimum queue that could hold about 1.5 seconds of 16/44.1 stereo. Volume
changes affect newly queued PCM; already queued/transmitted audio has bounded
latency. Host scheduling and DAC buffering add device-dependent delay.

Isolated packet errors in completed transfers can be skipped. Consecutive affected
media reaches a 200 ms limit and stops. Whole-transfer errors, disconnects, stalls,
timeouts and invalid feedback remain fatal. Completion accounting follows
submission order; failures do not replay stale samples. Fatal shutdown discards
queued PCM and acknowledges cancellations before releasing interfaces. Constructor
failure also cleans libusb handles, context and claimed interfaces. Diagnostic
logs report format, real vendor/product IDs and rate-limited transfer status;
they do not dump audio or serial numbers.

Audio Pipeline reports actual exclusive transport, device, output rate/depth,
hardware/software volume, software attenuation, conversion and sample exactness.
Attenuated PCM does not get the bit-perfect indicator. Unity can remain exact
only when format conversion and other processing also preserve the samples.
EQ/ReplayGain/spatial settings are shown as bypassed on the exclusive sink rather
than implying they processed that stream.

## Automated validation

With JDK 21, Android SDK 37 and CMake 3.22.1:

```powershell
$env:JAVA_HOME = 'C:\Users\mathe\.jdks\jbr-21.0.11'
$env:GRADLE_USER_HOME = 'C:\Users\mathe\.gradle'
& "$env:JAVA_HOME\bin\java.exe" '-Duser.home=C:\Users\mathe' `
  -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain `
  :app:testDevDebugUnitTest :app:assembleDevDebug --console=plain
```

Final result including the six actual-device fixture cases: **BUILD SUCCESSFUL**
in 20 seconds for the latest display-only correction, 1,382 tests in 122 suites,
zero failures/errors, one skipped. The preceding buffering-fix build took
1 minute 8 seconds and passed the same suite.
The native libraries built for arm64-v8a, armeabi-v7a and x86_64. The dependency-free
native transfer-health tests also compiled/linked as a Windows host executable
and ran with exit code 0. Existing Kotlin/deprecation diagnostics remain nonfatal.
`git diff --check` passes. No production release rebuild was requested for this task.

Fresh installable phone APK:
`build/outputs/PureBits-2.0-usb-display-fix-arm64-debug.apk` (version 2.0/code 40,
`com.dev.bitchord`, PureBits Dev). Other configured ABI and universal debug APKs
remain under `app/build/outputs/apk/dev/debug/`, along with the original Gradle
ARM64 output name. Debug and production are separate packages and have separate
settings. The signed debug app can still be refused by official-only addons;
use a local file or an addon that accepts this app when testing USB.

The ARM64 APK's signature was verified with the installed Android `apksigner`
(v2 signature, one signer). Its SHA-256 is
`D1D1BA26C19BEF1393AAD8784CB89F003851DCA720AF8602E6099590185DA3B9`.
The preceding buffering-fix APK remains at
`build/outputs/PureBits-2.0-usb-fix-arm64-debug.apk`; the latest revision changes
only the pipeline's unrelated fallback-warning visibility.

Native host regression command with the locally installed NDK (no separate MSVC
installation needed for this dependency-free test):

```powershell
$usbNdkBin = 'C:\Users\mathe\AppData\Local\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin'
& "$usbNdkBin\clang++.exe" --target=x86_64-pc-windows-msvc -std=c++17 `
  -ffreestanding -fno-exceptions -fno-rtti -fno-stack-protector -O2 `
  -I app/src/main/cpp/bitperfect -c `
  app/src/main/cpp/bitperfect/tests/usb_transfer_health_test.cpp `
  -o build/usb_transfer_health_test.obj
& "$usbNdkBin\ld.lld.exe" -flavor link /entry:main /subsystem:console /nodefaultlib `
  /out:build/usb_transfer_health_test.exe build/usb_transfer_health_test.obj
& .\build\usb_transfer_health_test.exe
# Exit code 0 means success.
```

Latest log: `build/purebits-usb-pipeline-display-validation.log`. The buffering-fix
log is `build/purebits-usb-stutter-validation.log`; the earlier initial-volume
log is `build/purebits-usb-validation-final.log`. Detailed JUnit results:
`app/build/reports/tests/testDevDebugUnitTest/index.html`.

Tests exercise dB/percentage mappings, all supported PCM encodings, full-scale
positive/negative values, invalid values, clipping bounds, exact unity, stereo
ramps/mute, partial writes/retries, persistence/concurrency, startup policy,
hardware-initialization fallback, UI synchronization/high-volume confirmation,
mute-before-open, focus ducking, pause/seek/track/rate changes, direct failure
without normal fallback and fatal clock failure at end-of-stream. Native tests
cover queue/transfer time budgets, isolated/sustained loss and fatal outcomes.
These are simulated controls/transports, not an end-to-end Android USB test.

## Physical-device verification

1. Keep the IEMs **out of your ears**. Stop playback in other players and close
   applications owning the USB interface. Do not reset DSP volume/EQ or send a
   test tone. Begin with ordinary music and no listening exposure.
2. Install the ARM64 debug APK; choose USB DAC (Direct Driver) and Auto or Safe
   Software Volume. Connect the Bunny DSP and grant USB permission. Use a local
   16/44.1 FLAC first, with "Convert for limited DACs" off.
3. Before starting music, set the app's software slider to zero. Start playback
   with IEMs still out. Confirm Audio Pipeline says Direct USB/Exclusive/Software
   and muted. Any audible output at zero, route surprise, error or gain jump is
   a failed test: stop and unplug; do not try a louder setting.
4. Only after mute is verified, increase very gradually from the minimum audible
   digital setting, still out of ears. Check both channels, volume-down/mute,
   HUD/slider agreement and the phone's physical keys. Do not authorize high
   output or test unity with the IEMs being worn. A trusted measurement setup is
   preferable before listening tests.
5. Repeat mute/low-level checks across seek, next/previous, pause/resume, a pause
   longer than 30 seconds, background/lock/unlock, audio-focus loss, service restart
   and reconnect. New sessions must be at −40 dB or quieter, never restored unity.
6. Test only formats actually enumerated from this unit. Confirm 44.1 stays
   44.1 kHz when supported and conversion is off. Check reported depth/rate after
   supported format changes. Unsupported formats must stop silently.
7. At mute/low level, unplug during playback and deny/regrant permission. Playback
   must stop without switching to the phone speaker. Deliberately selecting
   Android output must pause and require another play action; check its separate
   system volume before resuming.
8. Collect Android logs/USB descriptors and record exact phone model, HyperOS
   version and Android API before claiming hardware acceptance. Android-only
   descriptor/clock/control behavior may differ from Windows. Do not inject
   physical USB faults or force full-scale output to test the error policy.

The user reports working, stutter-free playback, and screenshots report active
exclusive Bunny output on the Xiaomi. Acceptance still open: independently
measured gain/mute, long background sessions and real media-session volume keys.
Software correctness cannot prevent every possible firmware-originated transient.

## Changed source files

- Settings/control: `data/settings/AppSettings.kt`,
  `playback/audio/bitperfect/DacVolume.kt`,
  `ui/components/DacVolumeHud.kt`, `ui/screens/SettingsSheet.kt`.
- Routing/lifecycle: `playback/PlaybackService.kt`,
  `playback/audio/bitperfect/BitPerfectAudioSink.kt`, `BitPerfectOutput.kt`,
  `BitPerfectRoutingSink.kt`, `BitPerfectTransport.kt`, `ExclusiveTransport.kt`,
  new `BitPerfectAvailability.kt`.
- USB: `playback/audio/bitperfect/usb/UsbDirectDevices.kt`,
  `UsbDirectTransport.kt`, new `UsbSoftwareGain.kt`, `UsbSoftwareVolumePolicy.kt`,
  `UsbSoftwareVolumeMemory.kt`, `UsbDirectVolumeSelection.kt`, `UsbPcmWriteGate.kt`.
- Native: `app/src/main/cpp/bitperfect/usb_transport.cpp`, new
  `usb_transfer_health.h` and `tests/usb_transfer_health_test.cpp`.
- Native follow-up: new `usb_pcm_queue.h` and `tests/usb_pcm_queue_test.cpp`.
- Reporting: `ui/components/AudioPipelineDialog.kt`; 20 Android `strings.xml`
  catalogues have the volume choices/warnings (all supported app locales plus
  existing Traditional Chinese/Hong Kong catalogues). New
  `ui/components/BitPerfectAvailabilityText.kt` maps capability reasons to
  translated explanations.
- Tests: `BitPerfectAudioSinkTest.kt`, `DacVolumeTest.kt`, new
  `UsbSoftwareGainTest.kt`, `UsbPcmWriteGateTest.kt`,
  `UsbSoftwareVolumeMemoryTest.kt`, `UsbDirectVolumeSelectionTest.kt`,
  `BunnyUsbDescriptorsTest.kt` and its actual descriptor fixture,
  `BitPerfectAvailabilityTest.kt`, `ui/components/BitPerfectAvailabilityTextTest.kt`.
- Diagnostics/documentation: `tools/Get-UsbAudioDescriptors.ps1`, this report and
  the link from `docs/purebits-mobile-build.md`.

Java/Kotlin paths above are relative to
`app/src/main/java/com/music/bitchord/`; JVM tests are under the matching
`app/src/test/java/` tree. Pre-existing root version and generated release-file
changes were left in place. The compact Home and Search next source features
remain in this fork; TV/Wear OS remain excluded. No commit or push was performed.
