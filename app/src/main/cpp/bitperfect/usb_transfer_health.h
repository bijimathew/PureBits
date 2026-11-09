/* SPDX-License-Identifier: LGPL-3.0-only */
#pragma once

// Policy shared by the libusb worker and host regression tests. No libusb or
// Android dependency: packet loss must not trigger an unattenuated route switch.
#include <stddef.h>
#include <stdint.h>

namespace usbaudio {

// Keep at most 64 ms scheduled on the bus. A full-speed bInterval=4 endpoint
// has one 8 ms packet per transfer, so it needs 8 slots rather than 16.
inline int transferSlotCount(uint64_t transferDurationUs) {
    if (transferDurationUs == 0) return 1;
    const uint64_t slots = 64000 / transferDurationUs;
    return static_cast<int>(slots < 1 ? 1 : (slots > 16 ? 16 : slots));
}

// Wait briefly for enough real PCM to prime the scheduled transfer ring. A
// deadline lets short end-of-stream buffers play without adding an EOS JNI API.
class StartupPrefill {
    bool waiting = false;
    uint64_t sinceUs = 0;

public:
    static constexpr uint64_t kWaitLimitUs = 20000;

    bool ready(size_t queuedBytes, size_t scheduledBytes, uint64_t nowUs) {
        if (queuedBytes >= scheduledBytes) {
            reset();
            return true;
        }
        if (!waiting) {
            waiting = true;
            sinceUs = nowUs;
        }
        return nowUs >= sinceUs && nowUs - sinceUs >= kWaitLimitUs;
    }

    void reset() { waiting = false; sinceUs = 0; }
};

enum class TransferOutcome { Complete, PacketLoss, Fatal };

class TransferHealth {
    uint64_t consecutiveLossUs = 0;

public:
    static constexpr uint64_t kLossLimitUs = 200000;

    bool accept(TransferOutcome outcome, uint64_t transferDurationUs) {
        if (outcome == TransferOutcome::Fatal) return false;
        if (outcome == TransferOutcome::Complete) {
            reset();
            return true;
        }
        // Guard the addition as well as zero/invalid durations. A broken worker
        // must stop rather than make the failure budget effectively unlimited.
        if (transferDurationUs == 0 || transferDurationUs >= kLossLimitUs ||
            consecutiveLossUs >= kLossLimitUs - transferDurationUs) return false;
        consecutiveLossUs += transferDurationUs;
        return true;
    }

    void reset() { consecutiveLossUs = 0; }
};

// Bound queued PCM to 100 ms, rounded to complete frames. In-flight USB slots
// add about 64 ms. A 256 KiB minimum previously held ~1.5 s of 16/44.1 stereo,
// delaying a software volume decrease applied before enqueueing.
inline size_t queueCapacityBytes(uint32_t sampleRate, size_t frameBytes) {
    if (sampleRate == 0 || frameBytes == 0) return 0;
    const uint64_t frames = (static_cast<uint64_t>(sampleRate) + 9) / 10;
    if (frames > static_cast<size_t>(-1) / frameBytes) return 0;
    return static_cast<size_t>(frames) * frameBytes;
}

} // namespace usbaudio
