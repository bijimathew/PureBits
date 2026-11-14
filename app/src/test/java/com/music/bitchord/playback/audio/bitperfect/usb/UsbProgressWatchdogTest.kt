package com.music.bitchord.playback.audio.bitperfect.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbProgressWatchdogTest {
    @Test fun acceptedAudioWithoutCompletionsTimesOutAtTheBoundary() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(4_410, 100)
        watchdog.setPlaying(true, 100)
        assertFalse(watchdog.observe(0, 2_099))
        assertTrue(watchdog.observe(0, 2_100))
    }

    @Test fun normalStartupAndUsbQueueLatencyDoNotTriggerTheWatchdog() {
        val watchdog = UsbProgressWatchdog()
        watchdog.setPlaying(true, 0)
        watchdog.onWritten(4_410, 0)
        assertFalse(watchdog.observe(0, 20 + 164))
        assertFalse(watchdog.observe(441, 200))
    }

    @Test fun ongoingPartialCompletionProgressRenewsTheDeadline() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(10_000, 0)
        watchdog.setPlaying(true, 0)
        assertFalse(watchdog.observe(1_000, 1_900))
        assertFalse(watchdog.observe(2_000, 3_800))
        assertFalse(watchdog.observe(2_000, 5_799))
        assertTrue(watchdog.observe(2_000, 5_800))
    }

    @Test fun acceptingMoreInputCannotHideAStalledConsumer() {
        val watchdog = UsbProgressWatchdog()
        watchdog.setPlaying(true, 0)
        watchdog.onWritten(1_000, 0)
        watchdog.onWritten(1_000, 1_000)
        watchdog.onWritten(0, 1_999)
        assertTrue(watchdog.observe(0, 2_000))
    }

    @Test fun repeatedPlayNotificationsCannotKeepAStalledConsumerAlive() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(1_000, 0)
        watchdog.setPlaying(true, 0)
        watchdog.setPlaying(true, 1_000)
        watchdog.setPlaying(true, 1_999)
        assertTrue(watchdog.observe(0, 2_000))
    }

    @Test fun anEmptyProducerAndZeroWritesDoNotCountAsAUsbStall() {
        val watchdog = UsbProgressWatchdog()
        watchdog.setPlaying(true, 0)
        watchdog.onWritten(0, 10_000)
        assertFalse(watchdog.observe(0, 100_000))
        watchdog.onWritten(100, 100_100)
        assertFalse(watchdog.observe(0, 102_099))
        assertTrue(watchdog.observe(0, 102_100))
    }

    @Test fun aDrainedQueueCanWaitForTheNetworkWithoutAUsbFailure() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(1_000, 0)
        watchdog.setPlaying(true, 0)
        assertFalse(watchdog.observe(1_000, 500))
        assertFalse(watchdog.observe(1_000, 60_000))
        watchdog.onWritten(100, 60_100)
        assertFalse(watchdog.observe(1_000, 62_099))
        assertTrue(watchdog.observe(1_000, 62_100))
    }

    @Test fun pausesPreservePendingFramesWithoutTimingOut() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(1_000, 0)
        watchdog.setPlaying(true, 0)
        watchdog.setPlaying(false, 1_000)
        assertFalse(watchdog.observe(0, 100_000))
        watchdog.setPlaying(true, 100_100)
        assertFalse(watchdog.observe(0, 102_099))
        assertTrue(watchdog.observe(0, 102_100))
    }

    @Test fun inFlightCompletionsDuringPauseAreAccountedBeforeResume() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(1_000, 0)
        watchdog.setPlaying(true, 0)
        watchdog.setPlaying(false, 10)
        assertFalse(watchdog.observe(1_000, 100))
        watchdog.setPlaying(true, 20_000)
        assertFalse(watchdog.observe(1_000, 30_000))
    }

    @Test fun flushStartsANewFrameClockAndRequiresANewPlaySignal() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(1_000, 0)
        watchdog.setPlaying(true, 0)
        assertTrue(watchdog.observe(0, 2_000))
        watchdog.reset()
        watchdog.onWritten(100, 0)
        assertFalse(watchdog.observe(0, 10_000))
        watchdog.setPlaying(true, 10_000)
        assertFalse(watchdog.observe(0, 11_999))
        assertTrue(watchdog.observe(0, 12_000))
    }

    @Test fun aBackwardsClockRebasesInsteadOfProducingASpuriousFailure() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(1_000, 1_000)
        watchdog.setPlaying(true, 1_000)
        assertFalse(watchdog.observe(0, 2_500))
        assertFalse(watchdog.observe(0, 100))
        assertFalse(watchdog.observe(0, 2_099))
        assertTrue(watchdog.observe(0, 2_100))
    }

    @Test fun clocksNearLongMaxDoNotOverflowTheElapsedTimeCalculation() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(1_000, Long.MAX_VALUE - 2_000)
        watchdog.setPlaying(true, Long.MAX_VALUE - 2_000)
        assertFalse(watchdog.observe(0, Long.MAX_VALUE - 1))
        assertTrue(watchdog.observe(0, Long.MAX_VALUE))
    }

    @Test fun invalidFrameCountsAreRejectedInsteadOfCorruptingProgress() {
        val watchdog = UsbProgressWatchdog()
        assertThrows(IllegalArgumentException::class.java) { watchdog.onWritten(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { watchdog.observe(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { watchdog.observe(1, 0) }
        watchdog.onWritten(100, 0)
        assertFalse(watchdog.observe(50, 0))
        assertThrows(IllegalArgumentException::class.java) { watchdog.observe(49, 0) }
    }

    @Test fun acceptedFrameOverflowIsRejected() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(Long.MAX_VALUE, 0)
        assertThrows(IllegalArgumentException::class.java) { watchdog.onWritten(1, 0) }
    }

    @Test fun invalidTimeoutAndClockAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { UsbProgressWatchdog(0) }
        assertThrows(IllegalArgumentException::class.java) { UsbProgressWatchdog(-1) }
        val watchdog = UsbProgressWatchdog()
        assertThrows(IllegalArgumentException::class.java) { watchdog.onWritten(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { watchdog.setPlaying(true, -1) }
        assertThrows(IllegalArgumentException::class.java) { watchdog.observe(0, -1) }
    }

    @Test fun firstCallbackAfterProcessSuspensionLetsTheWorkerResume() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(7_232, 0)
        watchdog.setPlaying(true, 0)
        assertFalse(watchdog.observe(0, 10))
        // The Media3 and native worker threads were both suspended for ~3 s.
        // Media3 happens to resume first, seeing the old completion counter.
        assertFalse(watchdog.observe(0, 2_893))
        assertFalse(watchdog.observe(2_822, 2_900))
        assertFalse(watchdog.observe(7_232, 3_000))
    }

    @Test fun processSuspensionGraceStillDetectsAWorkerThatDoesNotRecover() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(7_232, 0)
        watchdog.setPlaying(true, 0)
        assertFalse(watchdog.observe(0, 10))
        assertFalse(watchdog.observe(0, 68_475))
        assertFalse(watchdog.observe(0, 70_474))
        assertTrue(watchdog.observe(0, 70_475))
    }

    @Test fun aWriteCanBeTheFirstCallbackAfterSuspension() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(7_232, 0)
        watchdog.setPlaying(true, 0)
        assertFalse(watchdog.observe(0, 10))
        watchdog.onWritten(100, 3_000)
        assertFalse(watchdog.observe(0, 3_001))
        watchdog.onWritten(0, 4_000)
        assertFalse(watchdog.observe(0, 4_999))
        assertTrue(watchdog.observe(0, 5_000))
    }

    @Test fun regularCallbacksCannotDisguiseAStallAsProcessSuspension() {
        val watchdog = UsbProgressWatchdog()
        watchdog.onWritten(7_232, 0)
        watchdog.setPlaying(true, 0)
        for (time in 100L..1_900L step 100L) {
            watchdog.onWritten(0, time)
            watchdog.setPlaying(true, time)
            assertFalse(watchdog.observe(0, time))
        }
        assertTrue(watchdog.observe(0, 2_000))
    }
}
