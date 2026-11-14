# Song languages and public downloads

Downloads, YouTube Liked Music, offline Liked Songs and Spotify Liked Songs have
language chips, including Malayalam, Japanese, English, All and languages found
in the list. Counts cover the loaded songs. Selecting a language narrows the
song list; Play, Shuffle and row taps start that selection. A language selection
opens a fresh queue, excludes the old manual queue and does not load unfiltered
playlist continuations or AutoPlay recommendations. Normal queues retain the
existing behavior. Listen Together still follows the party's queue policy.

The song menu has **Song language**. A manual correction takes precedence over
detection and persists across restarts. **Unknown** can be an explicit correction;
**Automatic** follows detection again. Download aliases sharing one saved file
share the correction with the corresponding online song. Different recordings
with different IDs are not merged merely because their titles match.

Detection uses the bundled ML Kit language model at 0.85 confidence, with at least
60 letters of lyrics. Existing embedded lyrics are read first. Additional lyric
lookups respect the existing lyrics switch and selected providers, use two workers,
and stop when the page closes. Offline detection reads embedded lyrics without
contacting servers. Misses from online lookups are cached for a day. Downloads and
the player also reuse lyrics already fetched for tags or display. No audio is
analysed by this feature.
Japanese kana and Malayalam script in titles provide fallback hints. Latin titles,
artist names and Chinese characters alone are not enough to identify a song as
English or Japanese. Translated lyrics, mixed-language songs and title hints can
still be wrong; use the correction menu. This is classification of available text,
not recognition of the language being sung in an audio recording.

New installations and existing installations without an explicit storage
preference default to public downloads in **Music/PureBits**. An explicit previous
opt-out is retained. The existing compatible-format download policy is unchanged;
FLAC and other supported audio files are saved in their original containers.
Existing public **Music/BitChord** records remain usable.

**Export existing downloads**, in Downloads and download settings, also enables
public storage for future downloads. It copies private saved files, verifies their
SHA-256 digests, then publishes them. It remembers completed copies so retrying
does not duplicate them. Existing app copies remain in place to keep currently
playing tracks, offline likes and playlists valid; exporting therefore needs room
for a second copy. Original files are never deleted by this action.

Legacy WebM audio is copied unchanged to **Download/PureBits** because Android's
Music collection rejects WebM. Offline HLS/DASH packages that are not standalone
audio files stay private and are counted as skipped. Copy failures are reported
separately. Leaving the screen cancels the current export; completed copies are
kept, and an incomplete destination is removed. Android 8–9 asks for storage
permission. Android 10+ uses pending MediaStore entries without broad storage
permission.

Implementation references:

- [ML Kit language identification](https://developers.google.com/ml-kit/language/identification/android)
- [Android shared media storage](https://developer.android.com/training/data-storage/shared/media)

Device verification:

1. Open Downloads and Liked Songs with Japanese, Malayalam and English tracks.
   Let detection run, correct a romanized title, restart and verify the correction.
2. Filter Malayalam and test Play, Shuffle, a row tap and the end of the queue.
   Confirm Japanese songs and unfiltered continuations are not appended.
3. Download a FLAC and an AAC/M4A track. Open Music/PureBits in a file manager and
   play each in PureBits and another player.
4. Export older private downloads, then retry. Confirm the completed copies are
   counted as already exported, the originals still play and offline likes work.
5. Cancel an export, deny legacy permission, and retry after deleting a public copy.
   Confirm failures do not damage the private original or leave partial public files.

## Build verification (2026-11-14)

JDK 21: `:app:testDevDebugUnitTest :app:assembleDevDebug :app:assembleProdRelease`
completed successfully. The unit reports contain 1,419 tests, zero failures/errors
and one existing skipped test. Fifteen new cases cover language hints, correction
precedence and serialization, filtered queue navigation and AutoPlay policy, byte
integrity, cancellation, private path boundaries and legacy filename collisions.
Release lint and R8 completed with the existing Kotlin metadata/deprecation warnings.

Both artifacts retain version 2.3, code 43. The arm64 Dev APK is signed and its
signature was verified; it uses the separate `com.dev.bitchord` application ID.
The bundled language model and arm64 inference library were verified inside that
APK. The arm64 production APK uses `com.music.bitchord` and is unsigned; sign it
with the existing release key before installing over the production app. Copies
and SHA-256 checksums are under `build/outputs/PureBits-2.3-languages-downloads-*`.
The device checks above have not been performed in this session.
