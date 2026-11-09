// Host regression tests (C++17, no Android/libusb/runtime dependency).
#include "usb_pcm_queue.h"
#include "usb_transfer_health.h"

using namespace usbaudio;

static int failures = 0;
#define EXPECT(condition) do { if (!(condition)) ++failures; } while (false)

static void partialWritesAndWrapKeepSamplesInOrder() {
    uint8_t storage[8], result[16], first[] = {1, 2, 3, 4, 5, 6};
    uint8_t second[] = {7, 8, 9, 10, 11, 12};
    PcmByteQueue queue;
    EXPECT(queue.reset(storage, sizeof(storage)));
    EXPECT(queue.write(first, sizeof(first)) == 6);
    EXPECT(queue.read(result, 4) == 4);
    for (int i = 0; i < 4; ++i) EXPECT(result[i] == i + 1);
    EXPECT(queue.write(second, sizeof(second)) == 6);
    EXPECT(queue.write(second, 1) == 0);
    EXPECT(queue.size() == 8);
    EXPECT(queue.read(result, sizeof(result)) == 8);
    for (int i = 0; i < 8; ++i) EXPECT(result[i] == i + 5);
    EXPECT(queue.read(result, 1) == 0);
}

static void flushDropsOldSamplesAndInvalidStorageCannotAcceptAudio() {
    uint8_t storage[8], result[8], data[] = {1, 2, 3, 4};
    PcmByteQueue queue;
    EXPECT(queue.write(data, 4) == 0);
    EXPECT(!queue.reset(nullptr, 4));
    EXPECT(queue.reset(storage, sizeof(storage)));
    EXPECT(queue.write(data, 4) == 4);
    queue.clear();
    EXPECT(queue.size() == 0);
    EXPECT(queue.read(result, sizeof(result)) == 0);
    EXPECT(queue.write(data, 4) == 4);
    EXPECT(queue.read(result, sizeof(result)) == 4);
    for (int i = 0; i < 4; ++i) EXPECT(result[i] == data[i]);
}

static void sustainedStreamingNeverDropsOrRepeatsQueuedBytes() {
    static uint8_t storage[153600], input[2048], result[2048];
    const uint32_t rates[] = {44100, 48000, 96000, 192000};
    const size_t frameBytes[] = {4, 6, 8};
    for (uint32_t rate : rates) {
        for (size_t frame : frameBytes) {
            const size_t capacity = queueCapacityBytes(rate, frame);
            PcmByteQueue queue;
            EXPECT(capacity <= sizeof(storage));
            EXPECT(queue.reset(storage, capacity));
            uint64_t produced = 0, consumed = 0;
            for (size_t turn = 0; turn < 4000; ++turn) {
                const size_t count = (turn * 73 + 127) % sizeof(input);
                for (size_t i = 0; i < count; ++i) input[i] = static_cast<uint8_t>((produced + i) % 251);
                produced += queue.write(input, count);
                const size_t read = queue.read(result, (turn * 41 + 211) % sizeof(result));
                for (size_t i = 0; i < read; ++i) EXPECT(result[i] == (consumed + i) % 251);
                consumed += read;
                EXPECT(queue.size() == produced - consumed);
                EXPECT(queue.size() <= capacity);
            }
            while (queue.size() > 0) {
                const size_t read = queue.read(result, sizeof(result));
                for (size_t i = 0; i < read; ++i) EXPECT(result[i] == (consumed + i) % 251);
                consumed += read;
            }
            EXPECT(produced == consumed);
        }
    }
}

static void startupDoesNotTurnOneChunkIntoALongSilencePrefix() {
    StartupPrefill prefill;
    // Actual Bunny 16/44.1: first 10 ms contains 1,764 bytes, whereas its
    // sixteen 4 ms transfers require ~11,292 bytes. Original worker sent
    // roughly 54 ms of avoidable zero padding immediately after that chunk.
    const size_t initial = 441 * 4;
    const size_t scheduled = ((44100u * 64000u + 999999u) / 1000000u) * 4;
    EXPECT(!prefill.ready(initial, scheduled, 1000000));
    EXPECT(!prefill.ready(initial, scheduled, 1005000));
    EXPECT(prefill.ready(4096 * 4, scheduled, 1010000));
}

static void shortClipAndPauseNeverLeavePrefillWaitingForever() {
    StartupPrefill prefill;
    EXPECT(!prefill.ready(4, 12000, 0));
    EXPECT(!prefill.ready(4, 12000, 19999));
    EXPECT(prefill.ready(4, 12000, 20000));
    prefill.reset(); // Pause, seek or transport flush.
    EXPECT(!prefill.ready(4, 12000, 20001));
    EXPECT(prefill.ready(4, 12000, 40001));
}

int main() {
    partialWritesAndWrapKeepSamplesInOrder();
    flushDropsOldSamplesAndInvalidStorageCannotAcceptAudio();
    sustainedStreamingNeverDropsOrRepeatsQueuedBytes();
    startupDoesNotTurnOneChunkIntoALongSilencePrefix();
    shortClipAndPauseNeverLeavePrefillWaitingForever();
    return failures;
}
