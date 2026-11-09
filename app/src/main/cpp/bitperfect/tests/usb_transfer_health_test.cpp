// Host regression tests. No runtime/OS dependency; any C++17 compiler suffices:
//   c++ -std=c++17 -I.. usb_transfer_health_test.cpp -o usb_transfer_health_test
//   ./usb_transfer_health_test
#include "usb_transfer_health.h"

using namespace usbaudio;

static int failures = 0;
#define EXPECT(condition) do { if (!(condition)) ++failures; } while (false)

static void isolatedMissesDoNotEndAStream() {
    TransferHealth health;
    for (int i = 0; i < 10000; ++i) {
        EXPECT(health.accept(TransferOutcome::PacketLoss, 4000));
        EXPECT(health.accept(TransferOutcome::Complete, 4000));
    }
}

static void sustainedLossStopsWithinTheMediaBudget() {
    const uint64_t durations[] = {4000, 8000};
    for (uint64_t duration : durations) {
        TransferHealth health;
        for (uint64_t i = duration; i < TransferHealth::kLossLimitUs; i += duration) {
            EXPECT(health.accept(TransferOutcome::PacketLoss, duration));
        }
        EXPECT(!health.accept(TransferOutcome::PacketLoss, duration));
    }
}

static void fatalLossNeverContinues() {
    TransferHealth health;
    EXPECT(!health.accept(TransferOutcome::Fatal, 4000));
    EXPECT(!health.accept(TransferOutcome::PacketLoss, 0));
    EXPECT(!health.accept(TransferOutcome::PacketLoss, static_cast<uint64_t>(-1)));
}

static void flushStartsANewLossBudget() {
    TransferHealth health;
    for (int i = 0; i < 49; ++i) EXPECT(health.accept(TransferOutcome::PacketLoss, 4000));
    health.reset();
    EXPECT(health.accept(TransferOutcome::PacketLoss, 4000));
}

static void volumeLatencyQueueIsBoundedAndFrameAligned() {
    EXPECT(queueCapacityBytes(44100, 4) == 17640);
    EXPECT(queueCapacityBytes(48000, 6) == 28800);
    EXPECT(queueCapacityBytes(192000, 8) == 153600);
    EXPECT(queueCapacityBytes(1, 4) == 4);
    EXPECT(queueCapacityBytes(0, 4) == 0);
    EXPECT(queueCapacityBytes(44100, 0) == 0);
    EXPECT(queueCapacityBytes(44100, static_cast<size_t>(-1)) == 0);
}

static void wireQueueHasATimeBudgetAcrossUsbIntervals() {
    EXPECT(transferSlotCount(4000) == 16);  // 32 high-speed 125 us packets.
    EXPECT(transferSlotCount(8000) == 8);   // One full-speed bInterval=4 packet.
    EXPECT(transferSlotCount(1000) == 16);  // Never allocate more than 16 slots.
    EXPECT(transferSlotCount(64000) == 1);
    EXPECT(transferSlotCount(0) == 1);
    EXPECT(transferSlotCount(static_cast<uint64_t>(-1)) == 1);
    const uint64_t durations[] = {1000, 2000, 4000, 8000};
    for (uint64_t duration : durations) {
        EXPECT(static_cast<uint64_t>(transferSlotCount(duration)) * duration <= 64000);
    }
}

int main() {
    isolatedMissesDoNotEndAStream();
    sustainedLossStopsWithinTheMediaBudget();
    fatalLossNeverContinues();
    flushStartsANewLossBudget();
    volumeLatencyQueueIsBoundedAndFrameAligned();
    wireQueueHasATimeBudgetAcrossUsbIntervals();
    return failures;
}
