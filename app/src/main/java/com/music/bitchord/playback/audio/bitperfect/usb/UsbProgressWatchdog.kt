package com.music.bitchord.playback.audio.bitperfect.usb

/**
 * Detects a USB completion clock that stopped while accepted PCM is still pending.
 *
 * Owned by the playback thread. [onWritten] receives accepted frame deltas;
 * [observe] receives the transport's cumulative completed media frames since
 * [reset]. Producer starvation is deliberately different from a stalled USB
 * consumer: an empty queue, a pause or silence in the recording does not trip it.
 * This class neither changes gain nor selects/restarts an output.
 */
internal class UsbProgressWatchdog(private val timeoutMs: Long = 2_000L) {
    init {
        require(timeoutMs > 0L) { "USB progress timeout must be positive" }
    }

    private var acceptedFrames = 0L
    private var completedFrames = 0L
    private var playing = false
    // Primitive sentinels avoid boxing elapsed times on the audio write path.
    private var waitingSinceMs = -1L
    private var lastNowMs = -1L

    fun onWritten(frames: Long, nowMs: Long) {
        require(frames >= 0L && frames <= Long.MAX_VALUE - acceptedFrames) { "Invalid USB accepted frame count" }
        updateClock(nowMs)
        acceptedFrames += frames
        armIfNeeded(nowMs)
    }

    fun setPlaying(playing: Boolean, nowMs: Long) {
        updateClock(nowMs)
        if (this.playing != playing) {
            this.playing = playing
            waitingSinceMs = -1L
        }
        // An idempotent play notification must not keep a wedged stream alive.
        armIfNeeded(nowMs)
    }

    /** True only when queued media received no completion progress for the full timeout. */
    fun observe(playedFrames: Long, nowMs: Long): Boolean {
        require(playedFrames >= completedFrames && playedFrames <= acceptedFrames) { "Invalid USB completed frame count" }
        updateClock(nowMs)
        if (playedFrames > completedFrames) {
            completedFrames = playedFrames
            waitingSinceMs = -1L
        }
        armIfNeeded(nowMs)
        val since = waitingSinceMs
        if (since < 0L) return false
        return nowMs - since >= timeoutMs
    }

    /** Flush/new transport: its frame clock and pending PCM both start from zero. */
    fun reset() {
        acceptedFrames = 0L
        completedFrames = 0L
        playing = false
        waitingSinceMs = -1L
        lastNowMs = -1L
    }

    private fun armIfNeeded(nowMs: Long) {
        if (!playing || acceptedFrames == completedFrames) {
            waitingSinceMs = -1L
        } else if (waitingSinceMs < 0L) {
            waitingSinceMs = nowMs
        }
    }

    private fun updateClock(nowMs: Long) {
        require(nowMs >= 0L) { "Invalid USB progress clock" }
        val previous = lastNowMs
        if (previous >= 0L && nowMs < previous && waitingSinceMs >= 0L) {
            // elapsedRealtime is monotonic in production. Rebase a replacement
            // or test clock instead of subtracting across a backwards jump.
            waitingSinceMs = nowMs
        }
        lastNowMs = nowMs
    }
}
