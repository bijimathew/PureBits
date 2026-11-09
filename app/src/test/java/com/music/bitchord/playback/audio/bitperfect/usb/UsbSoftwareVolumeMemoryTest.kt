package com.music.bitchord.playback.audio.bitperfect.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UsbSoftwareVolumeMemoryTest {
    private val deviceA = "device-a"
    private val deviceB = "device-b"

    @Test fun firstConnectionUsesStartupLevelAndKeepsThatLevelAcrossSinkRecreation() {
        val disk = HashMap<String, Double>()
        val memory = memory(disk)
        assertEquals(-40.0, memory.level(deviceA), 0.0)
        memory.remember(deviceA, -25.0)
        repeat(5) { assertEquals(-25.0, memory.level(deviceA), 0.0) }
        assertEquals(-25.0, disk[deviceA]!!, 0.0)
    }

    @Test fun disconnectReconnectionReappliesStartupCeilingToRememberedHighLevel() {
        val disk = HashMap<String, Double>()
        val memory = memory(disk)
        memory.remember(deviceA, 0.0)
        assertEquals(0.0, memory.level(deviceA), 0.0)
        memory.reset(deviceA)
        assertEquals(-40.0, memory.level(deviceA), 0.0)
        assertEquals(0.0, disk[deviceA]!!, 0.0)
    }

    @Test fun processRestartAlsoCapsPersistedUnityEvenWithoutDisconnectEvent() {
        val disk = hashMapOf(deviceA to 0.0)
        val before = memory(disk)
        assertEquals(-40.0, before.level(deviceA), 0.0)
        before.remember(deviceA, -10.0)
        assertEquals(-10.0, before.level(deviceA), 0.0)
        val restarted = memory(disk)
        assertEquals(-40.0, restarted.level(deviceA), 0.0)
    }

    @Test fun quietAndMuteLevelsSurviveReconnectAndProcessRestart() {
        for (db in listOf(-60.0, -79.0, UsbSoftwareVolumePolicy.MUTE_DB)) {
            val disk = HashMap<String, Double>()
            val memory = memory(disk)
            memory.remember(deviceA, db)
            memory.reset(deviceA)
            assertEquals(db, memory.level(deviceA), 0.0)
            assertEquals(db, memory(disk).level(deviceA), 0.0)
        }
    }

    @Test fun devicesKeepIndependentLevelsAndResetOnlyTheDetachedDevice() {
        val disk = HashMap<String, Double>()
        val memory = memory(disk)
        memory.remember(deviceA, -15.0)
        memory.remember(deviceB, -60.0)
        memory.reset(deviceA)
        assertEquals(-40.0, memory.level(deviceA), 0.0)
        assertEquals(-60.0, memory.level(deviceB), 0.0)
        assertEquals(-15.0, disk[deviceA]!!, 0.0)
        assertEquals(-60.0, disk[deviceB]!!, 0.0)
    }

    @Test fun clearingAllSessionsDoesNotForgetConservativeStoredLevels() {
        val disk = HashMap<String, Double>()
        val memory = memory(disk)
        memory.remember(deviceA, -15.0)
        memory.remember(deviceB, -60.0)
        memory.reset()
        assertEquals(-40.0, memory.level(deviceA), 0.0)
        assertEquals(-60.0, memory.level(deviceB), 0.0)
    }

    @Test fun corruptPersistedAndRuntimeGainFailClosedToMute() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0)) {
            val disk = hashMapOf(deviceA to invalid)
            val memory = memory(disk)
            assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, memory.level(deviceA), 0.0)
            assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, memory.remember(deviceB, invalid), 0.0)
            assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, disk[deviceB]!!, 0.0)
            assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, memory.level(deviceB), 0.0)
        }
    }

    @Test fun failedPersistenceCannotCommitALouderSessionState() {
        var fail = false
        val disk = HashMap<String, Double>()
        val memory = UsbSoftwareVolumeMemory(
            read = { disk[it] },
            persist = { key, db ->
                if (fail) throw IllegalStateException("simulated preference write failure")
                disk[key] = db
            },
        )
        memory.remember(deviceA, -60.0)
        fail = true
        assertThrows(IllegalStateException::class.java) { memory.remember(deviceA, -5.0) }
        assertEquals(-60.0, memory.level(deviceA), 0.0)
        assertEquals(-60.0, disk[deviceA]!!, 0.0)
    }

    @Test fun failedPreferenceReadPropagatesInsteadOfAssumingFullOutput() {
        val memory = UsbSoftwareVolumeMemory(
            read = { throw IllegalStateException("simulated preference read failure") },
            persist = { _, _ -> },
        )
        assertThrows(IllegalStateException::class.java) { memory.level(deviceA) }
    }

    @Test fun concurrentControlAndReadsNeverCorruptOrCrossContaminateDeviceState() {
        val disk = ConcurrentHashMap<String, Double>()
        val memory = memory(disk)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        try {
            val futures = (0 until 4).map { worker ->
                pool.submit {
                    start.await()
                    try {
                        repeat(100) { i ->
                            val key = if (worker % 2 == 0) deviceA else deviceB
                            val db = if (key == deviceA) -60.0 else -50.0
                            memory.remember(key, db)
                            if (i % 7 == 0) memory.reset(key)
                            val actual = memory.level(key)
                            assertTrue(actual.isFinite())
                            assertEquals(db, actual, 0.0)
                        }
                    } catch (failure: Throwable) {
                        failures.add(failure)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(-60.0, memory.level(deviceA), 0.0)
            assertEquals(-50.0, memory.level(deviceB), 0.0)
            assertEquals(-60.0, disk[deviceA]!!, 0.0)
            assertEquals(-50.0, disk[deviceB]!!, 0.0)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun memory(disk: MutableMap<String, Double>) = UsbSoftwareVolumeMemory(
        read = { disk[it] }, persist = { key, db -> disk[key] = db },
    )
}
