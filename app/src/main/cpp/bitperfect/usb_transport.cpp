/*
 * Copyright (C) 2026 BitChord Project
 *
 * Adapted from Mornye (https://github.com/mornyeapp/Mornye),
 * rust_backend/crates/mobile/src/usb_audio/transport.rs,
 * Copyright 2026 zarzet.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

// A mixer-free USB Audio Class output: Android grants permission and opens
// the device; one worker thread here owns libusb, the claimed interfaces,
// up to sixteen isochronous OUT transfers and the explicit feedback transfer. No
// resampling or gain happens anywhere below the queue.
//
// Every raw libusb pointer belongs to the worker. Transfer buffers keep
// stable addresses until libusb acknowledges completion or cancellation,
// including on detach and error paths, before any memory is freed. Class
// control requests (the DAC's volume) are queued to the same worker rather
// than issued from another thread.
//
// Descriptor parsing and the choice of alternate setting happen in Kotlin
// (UsbDescriptors.kt); this side is handed the candidates in preference order
// and opens the first one the device accepts.

#include <android/log.h>
#include <jni.h>
#include <libusb.h>
#include <sys/resource.h>
#include <sys/time.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <future>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "usb_stream.h"
#include "usb_transfer_health.h"
#include "usb_pcm_queue.h"

using namespace usbaudio;
static_assert(kInterruptedEventPoll == LIBUSB_ERROR_INTERRUPTED, "libusb interrupted-poll value changed");

namespace {
void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

void check(int code) {
    if (code < 0) throw std::runtime_error(std::string("USB error ") + libusb_error_name(code));
}

struct Endpoint {
    uint8_t address = 0;
    uint8_t attributes = 0;
    size_t maxPacket = 0;
    int interval = 0;
    bool rateControl = false;
};

struct Candidate {
    int control = 0;
    int interface = 0;
    int alternate = 0;
    bool uac2 = false;
    int clock = 0;
    Endpoint data;
    bool hasFeedback = false;
    Endpoint feedback;
    Format format;
};

constexpr int kCandidateInts = 17;
const char *transferStatusName(libusb_transfer_status status) {
    switch (status) {
        case LIBUSB_TRANSFER_COMPLETED: return "completed";
        case LIBUSB_TRANSFER_ERROR: return "error";
        case LIBUSB_TRANSFER_TIMED_OUT: return "timed out";
        case LIBUSB_TRANSFER_CANCELLED: return "cancelled";
        case LIBUSB_TRANSFER_STALL: return "stalled";
        case LIBUSB_TRANSFER_NO_DEVICE: return "disconnected";
        case LIBUSB_TRANSFER_OVERFLOW: return "overflow";
    }
    return "unknown";
}

struct ControlRequest {
    bool input;
    uint8_t request;
    uint16_t value;
    uint16_t index;
    std::vector<uint8_t> data;
    std::chrono::steady_clock::time_point deadline;
    std::promise<std::vector<uint8_t>> reply;
};

// Diagnostic values only: never use these approximate, independently sampled
// fields to decide whether to play, change gain or reclaim the USB interface.
enum class WorkerStage { Opening, Events, QueueLock, Control, Cancel, Account, Paused, Prefill, Submit, Closing, Stopped };
int64_t monotonicMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

struct State {
    std::mutex mutex;
    std::condition_variable changed;
    std::vector<uint8_t> queueStorage;
    PcmByteQueue bytes;
    bool playing = false;
    bool closed = false;
    uint64_t frames = 0;
    uint64_t flush = 0;
    uint64_t flushed = 0;
    std::string error;
    std::deque<std::unique_ptr<ControlRequest>> controls;
    std::atomic<WorkerStage> workerStage{WorkerStage::Opening};
    std::atomic<int64_t> workerLoopAtMs{0};
    std::atomic<int64_t> workerLoops{0};
    std::atomic<int64_t> largestLoopGapMs{0};
    std::atomic<int64_t> paddingFrames{0};
    std::atomic<int64_t> transferFailures{0};
};

struct Slot {
    libusb_transfer *transfer = nullptr;
    std::vector<uint8_t> bytes;
    bool pending = false;
    bool submitted = false;
    uint64_t musicFrames = 0;
    uint64_t sequence = 0;

    Slot(int packets, size_t maxBytes) : bytes(static_cast<size_t>(packets) * maxBytes) {
        transfer = libusb_alloc_transfer(packets);
        require(transfer != nullptr, "USB transfer allocation failed");
    }
    ~Slot() {
        // Freed only after the worker has seen the completion or cancellation.
        if (transfer) libusb_free_transfer(transfer);
    }

    static void LIBUSB_CALL completed(libusb_transfer *transfer) {
        static_cast<Slot *>(transfer->user_data)->pending = false;
    }

    void submit(libusb_device_handle *handle, uint8_t address, const size_t *lengths, int count) {
        size_t total = 0;
        for (int i = 0; i < count; ++i) total += lengths[i];
        libusb_fill_iso_transfer(transfer, handle, address, bytes.data(), static_cast<int>(total), count,
                                 completed, this, 500);
        for (int i = 0; i < count; ++i) transfer->iso_packet_desc[i].length = static_cast<unsigned>(lengths[i]);
        check(libusb_submit_transfer(transfer));
        pending = true;
        submitted = true;
    }

    bool success() const {
        if (transfer->status != LIBUSB_TRANSFER_COMPLETED) return false;
        for (int i = 0; i < transfer->num_iso_packets; ++i) {
            const auto &packet = transfer->iso_packet_desc[i];
            if (packet.status != LIBUSB_TRANSFER_COMPLETED) return false;
            if ((transfer->endpoint & 0x80) == 0 && packet.actual_length != packet.length) return false;
        }
        return true;
    }

    TransferOutcome outcome() const {
        // Only packet-level losses in an otherwise completed transfer are
        // recoverable. Disconnects, timeouts, stalls and invalid transfers stop
        // immediately. Replaying the missed bytes would corrupt the timeline.
        if (transfer->status != LIBUSB_TRANSFER_COMPLETED) return TransferOutcome::Fatal;
        bool missed = false;
        for (int i = 0; i < transfer->num_iso_packets; ++i) {
            const auto &packet = transfer->iso_packet_desc[i];
            if (packet.actual_length > packet.length) return TransferOutcome::Fatal;
            if (packet.status != LIBUSB_TRANSFER_COMPLETED &&
                packet.status != LIBUSB_TRANSFER_ERROR && packet.status != LIBUSB_TRANSFER_TIMED_OUT) {
                return TransferOutcome::Fatal;
            }
            missed |= packet.status != LIBUSB_TRANSFER_COMPLETED || packet.actual_length != packet.length;
        }
        return missed ? TransferOutcome::PacketLoss : TransferOutcome::Complete;
    }

    void logFailure(uint64_t count, bool fatal) const {
        // Rate-limit recurring packet loss. Never log audio bytes or device
        // serial numbers, and use transfer statuses rather than error codes.
        if (!fatal && count != 1 && count % 128 != 0) return;
        int bad = 0;
        int first = -1;
        for (int i = 0; i < transfer->num_iso_packets; ++i) {
            const auto &packet = transfer->iso_packet_desc[i];
            if (packet.status != LIBUSB_TRANSFER_COMPLETED || packet.actual_length != packet.length) {
                ++bad;
                if (first < 0) first = i;
            }
        }
        if (first >= 0) {
            const auto &packet = transfer->iso_packet_desc[first];
            __android_log_print(ANDROID_LOG_WARN, "PureBitsUsb",
                                "Audio transfer %s; %d/%d missed packets; first=%d %s %u/%u bytes; failures=%llu",
                                transferStatusName(transfer->status), bad, transfer->num_iso_packets,
                                first, transferStatusName(packet.status), packet.actual_length, packet.length,
                                static_cast<unsigned long long>(count));
        } else {
            __android_log_print(ANDROID_LOG_WARN, "PureBitsUsb",
                                "Audio transfer %s; no packet failure reported; failures=%llu",
                                transferStatusName(transfer->status), static_cast<unsigned long long>(count));
        }
    }

    size_t actual() const { return transfer->iso_packet_desc[0].actual_length; }
};

class Session {
public:
    libusb_context *context = nullptr;
    libusb_device_handle *handle = nullptr;
    std::vector<int> claimed;
    Candidate chosen;
    int chosenIndex = -1;
    bool highSpeed = false;
    uint32_t interval = 0;
    std::vector<std::unique_ptr<Slot>> slots;
    std::unique_ptr<Slot> feedbackSlot;

    Session(int fd, const std::vector<Candidate> &candidates) {
        try {
            initialize(fd, candidates);
        } catch (...) {
            // A throwing constructor has no destructor. Release a partially
            // claimed device/context so Android can reacquire it after refusal.
            shutdown();
            throw;
        }
    }

    void initialize(int fd, const std::vector<Candidate> &candidates) {
        // NO_DEVICE_DISCOVERY is required on Android: the USB permission grant
        // is the file descriptor, not a scan of /dev.
        libusb_init_option option{};
        option.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
        check(libusb_init_context(&context, &option, 1));
        check(libusb_wrap_sys_device(context, static_cast<intptr_t>(fd), &handle));
        highSpeed = libusb_get_device_speed(libusb_get_device(handle)) >= LIBUSB_SPEED_HIGH;
        check(libusb_set_auto_detach_kernel_driver(handle, 1));
        std::string failure = "No supported USB endpoint";
        for (size_t i = 0; i < candidates.size(); ++i) {
            try {
                configure(candidates[i]);
            } catch (const std::exception &e) {
                failure = e.what();
                release();
                continue;
            }
            chosenIndex = static_cast<int>(i);
            const int packets = packetsPerTransfer();
            // Bound scheduled audio by time even for full-speed 8 ms packets.
            const int count = transferSlotCount(static_cast<uint64_t>(packets) * interval);
            for (int s = 0; s < count; ++s) slots.push_back(std::make_unique<Slot>(packets, chosen.data.maxPacket));
            if (chosen.hasFeedback) feedbackSlot = std::make_unique<Slot>(1, chosen.feedback.maxPacket);
            return;
        }
        throw std::runtime_error(failure);
    }

    ~Session() { shutdown(); }

    void shutdown() {
        drain();
        slots.clear();
        feedbackSlot.reset();
        release();
        if (handle) libusb_close(handle);
        handle = nullptr;
        if (context) libusb_exit(context);
        context = nullptr;
    }

    int packetsPerTransfer() const {
        return static_cast<int>(std::min<uint32_t>(std::max<uint32_t>(4000 / interval, 1), 32));
    }

    void control(bool input, uint8_t request, uint16_t value, uint16_t index, uint8_t *data, uint16_t size,
                 bool endpoint) {
        const uint8_t kind = (input ? 0x80 : 0) | 0x20 | (endpoint ? 2 : 1);
        const int n = libusb_control_transfer(handle, kind, request, value, index, data, size, 500);
        check(n);
        require(n == size, "Short USB control response");
    }

    void configure(const Candidate &c) {
        const auto &ep = c.data;
        require(ep.interval >= 1 && ep.interval <= 4 && ep.maxPacket > 0 && ep.maxPacket <= 3072,
                "Unsupported USB interval/packet size");
        const uint32_t period = (highSpeed ? 125u : 1000u) * (1u << (ep.interval - 1));
        const uint64_t maximumFrames = (static_cast<uint64_t>(c.format.sampleRate) * period + 999999) / 1000000;
        require(maximumFrames * c.format.channels * c.format.subslot <= ep.maxPacket,
                "USB endpoint bandwidth too small");
        for (int interface : {c.control, c.interface}) {
            if (std::find(claimed.begin(), claimed.end(), interface) != claimed.end()) continue;
            check(libusb_claim_interface(handle, interface));
            claimed.push_back(interface);
        }
        chosen = c;
        check(libusb_set_interface_alt_setting(handle, c.interface, 0));
        if (c.uac2) {
            const uint16_t index = static_cast<uint16_t>((c.clock << 8) | c.control);
            uint8_t rate[4];
            std::memcpy(rate, &c.format.sampleRate, 4);
            // A read-only clock already at the exact rate needs no SET_CUR.
            uint8_t current[4] = {};
            control(true, 1, 0x100, index, current, 4, false);
            if (std::memcmp(current, rate, 4) != 0) control(false, 1, 0x100, index, rate, 4, false);
            control(true, 1, 0x100, index, current, 4, false);
            require(std::memcmp(current, rate, 4) == 0, "USB clock rate could not be verified");
        }
        check(libusb_set_interface_alt_setting(handle, c.interface, c.alternate));
        if (!c.uac2 && ep.rateControl) {
            uint8_t rate[3] = {static_cast<uint8_t>(c.format.sampleRate), static_cast<uint8_t>(c.format.sampleRate >> 8),
                               static_cast<uint8_t>(c.format.sampleRate >> 16)};
            uint8_t current[3] = {};
            control(false, 1, 0x100, ep.address, rate, 3, true);
            control(true, 0x81, 0x100, ep.address, current, 3, true);
            require(std::memcmp(current, rate, 3) == 0, "USB clock rate could not be verified");
        }
        interval = period;
    }

    void events() {
        timeval timeout{0, 1000};
        const int result = libusb_handle_events_timeout(context, &timeout);
        // Android/POSIX signals may interrupt poll without harming any URB.
        // Keep the pending transfers alive; disconnect and other errors still
        // terminate the worker and cancel all transfers before release.
        if (!eventPollMayContinue(result)) check(result);
    }

    // Cancellation is asynchronous: keep every buffer and the context alive
    // until libusb acknowledges it, including after a detach or an error.
    void drain() {
        auto any = [this] {
            for (auto &slot : slots) if (slot->pending) return true;
            return feedbackSlot && feedbackSlot->pending;
        };
        for (auto &slot : slots) if (slot->pending) libusb_cancel_transfer(slot->transfer);
        if (feedbackSlot && feedbackSlot->pending) libusb_cancel_transfer(feedbackSlot->transfer);
        while (any()) {
            timeval timeout{0, 1000};
            libusb_handle_events_timeout(context, &timeout);
        }
    }

    void release() {
        if (!handle) return;
        if (chosen.alternate > 0) libusb_set_interface_alt_setting(handle, chosen.interface, 0);
        for (auto it = claimed.rbegin(); it != claimed.rend(); ++it) libusb_release_interface(handle, *it);
        claimed.clear();
        chosen = Candidate{};
    }

    void serve(ControlRequest &request) {
        if (std::chrono::steady_clock::now() > request.deadline) {
            request.reply.set_exception(std::make_exception_ptr(std::runtime_error("USB control request expired")));
            return;
        }
        try {
            control(request.input, request.request, request.value, request.index, request.data.data(),
                    static_cast<uint16_t>(request.data.size()), false);
            request.reply.set_value(std::move(request.data));
        } catch (...) {
            request.reply.set_exception(std::current_exception());
        }
    }

    void run(State &state) {
        const size_t frame = static_cast<size_t>(chosen.format.subslot) * chosen.format.channels;
        const int packets = packetsPerTransfer();
        PacketClock clock(chosen.format.sampleRate, interval);
        uint8_t marker = 0x05;
        auto lastFeedback = std::chrono::steady_clock::now();
        bool started = false;
        StartupPrefill prefill;
        const uint64_t scheduledUs = static_cast<uint64_t>(packets) * interval * slots.size();
        const size_t prefillBytes = static_cast<size_t>(
            (static_cast<uint64_t>(chosen.format.sampleRate) * scheduledUs + 999999) / 1000000) * frame;
        TransferHealth health;
        uint64_t failures = 0;
        uint64_t sequence = 0;
        uint64_t paddingFrames = 0;
        uint64_t musicFrames = 0;
        bool hadMusic = false;
        bool reportedPadding = false;
        auto lastPaddingReport = std::chrono::steady_clock::now();
        auto lastLoop = lastPaddingReport;
        auto lastGapReport = lastLoop;
        bool reportedGap = false;
        uint64_t largestGapUs = 0;
        int64_t largestLoopGapMs = 0;
        for (;;) {
            state.workerStage.store(WorkerStage::Events, std::memory_order_relaxed);
            events();
            const auto loopNow = std::chrono::steady_clock::now();
            const auto gapUs = std::chrono::duration_cast<std::chrono::microseconds>(loopNow - lastLoop).count();
            lastLoop = loopNow;
            state.workerLoopAtMs.store(std::chrono::duration_cast<std::chrono::milliseconds>(
                loopNow.time_since_epoch()).count(), std::memory_order_relaxed);
            state.workerLoops.fetch_add(1, std::memory_order_relaxed);
            largestLoopGapMs = std::max(largestLoopGapMs, static_cast<int64_t>(gapUs / 1000));
            state.largestLoopGapMs.store(largestLoopGapMs, std::memory_order_relaxed);
            state.workerStage.store(WorkerStage::QueueLock, std::memory_order_relaxed);
            std::unique_lock<std::mutex> lock(state.mutex);
            if (state.closed) break;
            // Read playing only under its mutex. A gap in this worker differs
            // from decoder/network starvation (the PCM padding diagnostic).
            if (state.playing && started && gapUs >= 50000) {
                largestGapUs = std::max(largestGapUs, static_cast<uint64_t>(gapUs));
                if (!reportedGap || loopNow - lastGapReport >= std::chrono::seconds(5)) {
                    size_t pending = 0;
                    for (const auto &slot : slots) if (slot->pending) ++pending;
                    __android_log_print(ANDROID_LOG_WARN, "PureBitsUsb",
                                        "Worker loop gap: %llu us; queued=%zu bytes; pending=%zu/%zu; media_frames=%llu",
                                        static_cast<unsigned long long>(largestGapUs), state.bytes.size(),
                                        pending, slots.size(), static_cast<unsigned long long>(state.frames));
                    largestGapUs = 0;
                    reportedGap = true;
                    lastGapReport = loopNow;
                }
            }
            if (!state.controls.empty()) {
                auto request = std::move(state.controls.front());
                state.controls.pop_front();
                lock.unlock();
                state.workerStage.store(WorkerStage::Control, std::memory_order_relaxed);
                serve(*request);
                state.workerStage.store(WorkerStage::QueueLock, std::memory_order_relaxed);
                lock.lock();
            }
            if (state.flush != state.flushed) {
                lock.unlock();
                state.workerStage.store(WorkerStage::Cancel, std::memory_order_relaxed);
                drain();
                state.workerStage.store(WorkerStage::QueueLock, std::memory_order_relaxed);
                lock.lock();
                state.bytes.clear();
                state.frames = 0;
                state.flushed = state.flush;
                for (auto &slot : slots) {
                    slot->musicFrames = 0;
                    slot->submitted = false;
                }
                if (feedbackSlot) feedbackSlot->submitted = false;
                clock = PacketClock(chosen.format.sampleRate, interval);
                marker = 0x05;
                started = false;
                prefill.reset();
                paddingFrames = musicFrames = 0;
                hadMusic = false;
                health.reset();
                state.changed.notify_all();
            }
            // Account for transfers that completed, playing or not, so a pause
            // does not hold back the frame count by what was in flight.
            state.workerStage.store(WorkerStage::Account, std::memory_order_relaxed);
            for (;;) {
                // Account in submission order even when completion callbacks
                // arrive together/out of order. The failure budget and playhead
                // must describe the stream timeline, not vector iteration order.
                Slot *slot = nullptr;
                for (auto &candidate : slots) {
                    if (candidate->submitted && (!slot || candidate->sequence < slot->sequence)) slot = candidate.get();
                }
                if (!slot || slot->pending) break;
                const auto outcome = slot->outcome();
                if (outcome != TransferOutcome::Complete) {
                    slot->logFailure(++failures, outcome == TransferOutcome::Fatal);
                    state.transferFailures.store(static_cast<int64_t>(failures), std::memory_order_relaxed);
                }
                require(health.accept(outcome, static_cast<uint64_t>(packets) * interval),
                        outcome == TransferOutcome::Fatal ? "USB audio transfer failed" : "USB packet loss persisted");
                // Lost isochronous packets cannot be retried. Advance past their
                // media frames rather than replay old samples after recovery.
                state.frames += slot->musicFrames;
                slot->musicFrames = 0;
                slot->submitted = false;
            }
            if (!state.playing) {
                // Resuming after a pause restarts the feedback watchdog.
                started = false;
                prefill.reset();
                paddingFrames = musicFrames = 0;
                hadMusic = false;
                state.workerStage.store(WorkerStage::Paused, std::memory_order_relaxed);
                state.changed.wait_for(lock, std::chrono::milliseconds(20));
                continue;
            }
            if (!started) {
                const auto now = std::chrono::steady_clock::now();
                const auto nowUs = std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count();
                if (!prefill.ready(state.bytes.size(), prefillBytes, static_cast<uint64_t>(nowUs))) {
                    // wait_for releases the queue lock, so the decoder can fill
                    // it. Without this, one 10 ms chunk was followed by ~54 ms
                    // of avoidable padding in the first scheduled transfer ring.
                    state.workerStage.store(WorkerStage::Prefill, std::memory_order_relaxed);
                    state.changed.wait_for(lock, std::chrono::milliseconds(1));
                    continue;
                }
                lastFeedback = now;
                started = true;
            }
            state.workerStage.store(WorkerStage::Submit, std::memory_order_relaxed);
            if (feedbackSlot) {
                if (!feedbackSlot->pending) {
                    const size_t got = std::min(feedbackSlot->actual(), feedbackSlot->bytes.size());
                    if (feedbackSlot->submitted && got > 0 && feedbackSlot->success() &&
                        clock.feedback(feedbackSlot->bytes.data(), got, highSpeed, interval)) {
                        lastFeedback = std::chrono::steady_clock::now();
                    }
                    const size_t length = chosen.feedback.maxPacket;
                    feedbackSlot->submit(handle, chosen.feedback.address, &length, 1);
                }
                require(std::chrono::steady_clock::now() - lastFeedback < std::chrono::seconds(2),
                        "USB feedback stopped or invalid");
            }
            for (auto &slot : slots) {
                if (slot->pending || slot->submitted) continue;
                size_t lengths[32];
                size_t total = 0;
                for (int i = 0; i < packets; ++i) {
                    lengths[i] = clock.next() * frame;
                    require(lengths[i] <= chosen.data.maxPacket, "USB feedback exceeded endpoint capacity");
                    total += lengths[i];
                }
                const size_t available = std::min(total, state.bytes.size()) / frame * frame;
                require(state.bytes.read(slot->bytes.data(), available) == available, "USB PCM queue lost frames");
                finishTransfer(slot->bytes.data(), total, available, chosen.format, marker);
                slot->musicFrames = available / frame;
                if (available > 0) hadMusic = true;
                if (hadMusic) {
                    musicFrames += available / frame;
                    paddingFrames += (total - available) / frame;
                    state.paddingFrames.fetch_add(static_cast<int64_t>((total - available) / frame),
                                                  std::memory_order_relaxed);
                }
                slot->sequence = sequence++;
                slot->submit(handle, chosen.data.address, lengths, packets);
            }
            const auto now = std::chrono::steady_clock::now();
            if (paddingFrames > 0 && (!reportedPadding || now - lastPaddingReport >= std::chrono::seconds(5))) {
                // Separate decoder/producer starvation from lost USB packets.
                // End-of-stream padding is normal too; this is diagnostic only.
                __android_log_print(ANDROID_LOG_INFO, "PureBitsUsb",
                                    "PCM queue padding: %llu silent / %llu media frames; queued=%zu bytes",
                                    static_cast<unsigned long long>(paddingFrames),
                                    static_cast<unsigned long long>(musicFrames), state.bytes.size());
                paddingFrames = musicFrames = 0;
                reportedPadding = true;
                lastPaddingReport = now;
            }
            state.changed.notify_all();
        }
        state.workerStage.store(WorkerStage::Cancel, std::memory_order_relaxed);
        drain();
    }
};

class Output {
    State state;
    std::thread worker;
    size_t frameBytes = 0;
    size_t capacity = 0;

    void throwIfFailed() {
        require(!state.closed, "USB output closed");
        if (!state.error.empty()) throw std::runtime_error(state.error);
    }

public:
    Format format;
    int chosen = -1;

    Output(int fd, std::vector<Candidate> candidates) {
        require(fd >= 0 && !candidates.empty() && candidates.size() <= 32, "Invalid USB device/configuration");
        std::promise<std::pair<int, Format>> opened;
        auto result = opened.get_future();
        worker = std::thread([this, fd, candidates = std::move(candidates), opened = std::move(opened)]() mutable {
            // Best effort; failure changes scheduling only, never audio data.
            const int priorityResult = setpriority(PRIO_PROCESS, 0, -16);
            const int priorityError = priorityResult == 0 ? 0 : errno;
            errno = 0;
            const int actualPriority = getpriority(PRIO_PROCESS, 0);
            const int readPriorityError = errno;
            __android_log_print(ANDROID_LOG_INFO, "PureBitsUsb",
                                "Worker priority: requested=-16 actual=%d set_result=%d set_errno=%d get_errno=%d",
                                actualPriority, priorityResult, priorityError, readPriorityError);
            std::unique_ptr<Session> session;
            try {
                // Android owns the descriptor and keeps it open until this
                // worker has joined.
                session = std::make_unique<Session>(fd, candidates);
                opened.set_value({session->chosenIndex, session->chosen.format});
            } catch (...) {
                state.workerStage.store(WorkerStage::Stopped, std::memory_order_relaxed);
                opened.set_exception(std::current_exception());
                return;
            }
            try {
                session->run(state);
            } catch (const std::exception &e) {
                std::lock_guard<std::mutex> lock(state.mutex);
                state.error = e.what();
                state.playing = false;
                state.bytes.clear();
                state.changed.notify_all();
            }
            // No post-error playback or hardware gain restoration: destruction
            // cancels every pending transfer before releasing the interfaces.
            state.workerStage.store(WorkerStage::Closing, std::memory_order_relaxed);
            session.reset();
            std::lock_guard<std::mutex> lock(state.mutex);
            for (auto &request : state.controls) {
                request->reply.set_exception(std::make_exception_ptr(std::runtime_error("USB output closed")));
            }
            state.controls.clear();
            state.changed.notify_all();
            state.workerStage.store(WorkerStage::Stopped, std::memory_order_relaxed);
        });
        try {
            auto [index, chosenFormat] = result.get();
            chosen = index;
            format = chosenFormat;
            frameBytes = static_cast<size_t>(format.subslot) * format.channels;
            capacity = queueCapacityBytes(format.sampleRate, frameBytes);
            require(capacity >= frameBytes, "Invalid USB queue capacity");
            std::lock_guard<std::mutex> lock(state.mutex);
            state.queueStorage.resize(capacity);
            require(state.bytes.reset(state.queueStorage.data(), capacity), "USB PCM queue initialization failed");
        } catch (...) {
            close();
            throw;
        }
    }

    ~Output() { close(); }

    // Nonblocking: takes what fits in the bounded queue (100 ms of frames).
    int write(const uint8_t *data, size_t size) {
        require(size <= 256 * 1024 && size % frameBytes == 0, "Invalid USB frame buffer");
        std::lock_guard<std::mutex> lock(state.mutex);
        throwIfFailed();
        const size_t n = std::min(size, capacity - std::min(capacity, state.bytes.size())) / frameBytes * frameBytes;
        require(state.bytes.write(data, n) == n, "USB PCM queue overflow");
        state.changed.notify_all();
        return static_cast<int>(n);
    }

    size_t capacityFrames() const { return capacity / frameBytes; }

    void setPlaying(bool playing) {
        std::lock_guard<std::mutex> lock(state.mutex);
        throwIfFailed();
        state.playing = playing;
        state.changed.notify_all();
    }

    // Cancels in-flight transfers, clears queued audio and resets the frame count.
    void flush() {
        std::unique_lock<std::mutex> lock(state.mutex);
        state.playing = false;
        state.flush++;
        state.changed.notify_all();
        const bool done = state.changed.wait_for(lock, std::chrono::seconds(2), [this] {
            return state.flushed == state.flush || !state.error.empty() || state.closed;
        });
        throwIfFailed();
        require(done, "USB cancellation timed out");
    }

    // Consumed media frames since the last flush, excluding inserted silence.
    // Includes unrecoverably missed packets so the playhead never repeats them.
    uint64_t frames() {
        std::lock_guard<std::mutex> lock(state.mutex);
        if (!state.error.empty()) throw std::runtime_error(state.error);
        return state.frames;
    }

    // Called by the service, independently of Media3's playback thread. Never
    // wait for the queue mutex: the write/frames calls may be blocked on it.
    // -1 means the queue snapshot was busy, not empty or paused.
    std::array<jlong, 10> diagnostics() {
        const auto tick = state.workerLoopAtMs.load(std::memory_order_relaxed);
        std::array<jlong, 10> values{
            static_cast<jlong>(state.workerStage.load(std::memory_order_relaxed)),
            tick == 0 ? -1 : std::max<int64_t>(0, monotonicMs() - tick),
            state.workerLoops.load(std::memory_order_relaxed),
            state.largestLoopGapMs.load(std::memory_order_relaxed),
            -1, -1, -1,
            state.paddingFrames.load(std::memory_order_relaxed),
            state.transferFailures.load(std::memory_order_relaxed),
            -1,
        };
        std::unique_lock<std::mutex> lock(state.mutex, std::try_to_lock);
        if (lock.owns_lock()) {
            values[4] = static_cast<jlong>(state.bytes.size() / frameBytes);
            values[5] = static_cast<jlong>(state.frames);
            values[6] = state.playing ? 1 : 0;
            values[9] = state.error.empty() ? 0 : 1;
        }
        return values;
    }

    std::vector<uint8_t> control(bool input, int request, int value, int index, std::vector<uint8_t> data) {
        auto pending = std::make_unique<ControlRequest>();
        pending->input = input;
        pending->request = static_cast<uint8_t>(request);
        pending->value = static_cast<uint16_t>(value);
        pending->index = static_cast<uint16_t>(index);
        pending->data = std::move(data);
        pending->deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
        auto reply = pending->reply.get_future();
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            throwIfFailed();
            require(state.controls.size() < 8, "USB control requests busy");
            state.controls.push_back(std::move(pending));
            state.changed.notify_all();
        }
        require(reply.wait_for(std::chrono::seconds(4)) == std::future_status::ready, "USB control request timed out");
        return reply.get();
    }

    void close() {
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            state.closed = true;
            state.changed.notify_all();
        }
        if (worker.joinable()) worker.join();
    }
};

void report(JNIEnv *env, const std::exception &error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}

std::mutex handlesMutex;
std::unordered_map<jlong, std::shared_ptr<Output>> handles;
jlong nextHandle = 1;

std::shared_ptr<Output> get(jlong id) {
    std::lock_guard<std::mutex> lock(handlesMutex);
    auto it = handles.find(id);
    require(it != handles.end(), "USB output handle closed");
    return it->second;
}

std::vector<Candidate> decode(const jint *v, jsize length) {
    require(length > 0 && length % kCandidateInts == 0, "Invalid USB candidate list");
    std::vector<Candidate> result;
    for (jsize at = 0; at < length; at += kCandidateInts) {
        const jint *c = v + at;
        Candidate candidate;
        candidate.control = c[0];
        candidate.interface = c[1];
        candidate.alternate = c[2];
        candidate.uac2 = c[3] != 0;
        candidate.clock = c[4];
        candidate.data.address = static_cast<uint8_t>(c[5]);
        candidate.data.attributes = static_cast<uint8_t>(c[6]);
        candidate.data.maxPacket = static_cast<size_t>(c[7]);
        candidate.data.interval = c[8];
        candidate.data.rateControl = c[9] != 0;
        candidate.hasFeedback = c[10] >= 0;
        candidate.feedback.address = static_cast<uint8_t>(c[10]);
        candidate.feedback.maxPacket = static_cast<size_t>(c[11]);
        candidate.format.sampleRate = static_cast<uint32_t>(c[12]);
        candidate.format.channels = c[13];
        candidate.format.subslot = c[14];
        candidate.format.bits = c[15];
        require(c[16] >= 0 && c[16] <= 3, "Invalid USB encoding");
        candidate.format.encoding = static_cast<Encoding>(c[16]);
        require(candidate.format.channels >= 1 && candidate.format.channels <= 2 && candidate.format.subslot >= 2 &&
                    candidate.format.subslot <= 4 && candidate.format.sampleRate > 0,
                "Invalid USB format");
        result.push_back(candidate);
    }
    return result;
}
} // namespace

#define JNI_USB(name) Java_com_music_bitchord_playback_audio_bitperfect_usb_UsbDirectNative_##name

extern "C" {
JNIEXPORT jlong JNICALL JNI_USB(open)(JNIEnv *env, jobject, jint fd, jintArray candidates) {
    try {
        const jsize length = env->GetArrayLength(candidates);
        std::vector<jint> values(static_cast<size_t>(length));
        env->GetIntArrayRegion(candidates, 0, length, values.data());
        auto output = std::make_shared<Output>(fd, decode(values.data(), length));
        std::lock_guard<std::mutex> lock(handlesMutex);
        const jlong id = nextHandle++;
        handles.emplace(id, std::move(output));
        return id;
    } catch (const std::exception &e) {
        report(env, e);
        return 0;
    }
}
JNIEXPORT jint JNICALL JNI_USB(chosen)(JNIEnv *env, jobject, jlong handle) {
    try { return get(handle)->chosen; } catch (const std::exception &e) { report(env, e); return -1; }
}
JNIEXPORT jint JNICALL JNI_USB(capacity)(JNIEnv *env, jobject, jlong handle) {
    try { return static_cast<jint>(get(handle)->capacityFrames()); } catch (const std::exception &e) { report(env, e); return 0; }
}
JNIEXPORT jint JNICALL JNI_USB(write)(JNIEnv *env, jobject, jlong handle, jobject buffer, jint offset, jint count) {
    try {
        auto *data = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
        const auto size = env->GetDirectBufferCapacity(buffer);
        require(data && offset >= 0 && count >= 0 && offset <= size && count <= size - offset, "Invalid PCM buffer");
        return get(handle)->write(data + offset, static_cast<size_t>(count));
    } catch (const std::exception &e) {
        report(env, e);
        return 0;
    }
}
JNIEXPORT void JNICALL JNI_USB(setPlaying)(JNIEnv *env, jobject, jlong handle, jboolean playing) {
    try { get(handle)->setPlaying(playing); } catch (const std::exception &e) { report(env, e); }
}
JNIEXPORT void JNICALL JNI_USB(flush)(JNIEnv *env, jobject, jlong handle) {
    try { get(handle)->flush(); } catch (const std::exception &e) { report(env, e); }
}
JNIEXPORT jlong JNICALL JNI_USB(frames)(JNIEnv *env, jobject, jlong handle) {
    try { return static_cast<jlong>(get(handle)->frames()); } catch (const std::exception &e) { report(env, e); return 0; }
}
JNIEXPORT jlongArray JNICALL JNI_USB(diagnostics)(JNIEnv *env, jobject, jlong handle) {
    try {
        const auto values = get(handle)->diagnostics();
        auto result = env->NewLongArray(static_cast<jsize>(values.size()));
        if (result) env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
        return result;
    } catch (const std::exception &) {
        // Closing concurrently is normal; diagnostics must not fail playback.
        return nullptr;
    }
}
JNIEXPORT jbyteArray JNICALL JNI_USB(control)(JNIEnv *env, jobject, jlong handle, jboolean input, jint request,
                                              jint value, jint index, jbyteArray data) {
    try {
        const jsize length = env->GetArrayLength(data);
        require(length >= 0 && length <= 1024, "Invalid USB control length");
        std::vector<uint8_t> bytes(static_cast<size_t>(length));
        env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte *>(bytes.data()));
        auto reply = get(handle)->control(input, request, value, index, std::move(bytes));
        auto result = env->NewByteArray(static_cast<jsize>(reply.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(reply.size()),
                                            reinterpret_cast<const jbyte *>(reply.data()));
        return result;
    } catch (const std::exception &e) {
        report(env, e);
        return nullptr;
    }
}
JNIEXPORT void JNICALL JNI_USB(close)(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<Output> output;
    {
        std::lock_guard<std::mutex> lock(handlesMutex);
        auto it = handles.find(handle);
        if (it == handles.end()) return;
        output = std::move(it->second);
        handles.erase(it);
    }
    output->close();
}
}
