# Bunny DSP fixture

`bunny-dsp-31b2-1112-uac1.bin` was captured from the user's connected TANCHJIM
BUNNY DSP on Windows on 9 November 2026 using
`tools/Get-UsbAudioDescriptors.ps1`. It combines the 18-byte device descriptor
and 303-byte configuration descriptor, matching Android's raw-descriptor layout.
The device descriptor's manufacturer/product/serial string indices are zeroed;
there are no string descriptors, serial strings or host device-instance IDs.

This particular unit is VID/PID `31B2:1112`, revision `0101`. The fixture records
advertised capabilities, not proof of successful Android streaming or audible
volume behavior. It must not be used to justify vendor-register writes or an
acoustic safety claim. See `docs/usb-direct-software-volume.md` for findings and
the hardware test plan.
