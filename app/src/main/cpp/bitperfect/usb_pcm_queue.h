/* SPDX-License-Identifier: LGPL-3.0-only */
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace usbaudio {

// Fixed-storage PCM ring. The owner allocates once and serializes access;
// writes/reads never allocate, block, or move already queued samples.
class PcmByteQueue {
    uint8_t *bytes = nullptr;
    size_t capacity_ = 0;
    size_t head = 0;
    size_t used = 0;

    static void copy(uint8_t *to, const uint8_t *from, size_t count) {
        for (size_t i = 0; i < count; ++i) to[i] = from[i];
    }

public:
    bool reset(uint8_t *storage, size_t capacity) {
        if (!storage && capacity != 0) return false;
        bytes = storage;
        capacity_ = capacity;
        clear();
        return true;
    }

    size_t size() const { return used; }
    size_t capacity() const { return capacity_; }
    void clear() { head = 0; used = 0; }

    size_t write(const uint8_t *source, size_t count) {
        if (!source || !bytes) return 0;
        if (count > capacity_ - used) count = capacity_ - used;
        if (count == 0) return 0;
        const size_t tail = (head + used) % capacity_;
        const size_t first = count < capacity_ - tail ? count : capacity_ - tail;
        copy(bytes + tail, source, first);
        copy(bytes, source + first, count - first);
        used += count;
        return count;
    }

    size_t read(uint8_t *target, size_t count) {
        if (!target || !bytes) return 0;
        if (count > used) count = used;
        if (count == 0) return 0;
        const size_t first = count < capacity_ - head ? count : capacity_ - head;
        copy(target, bytes + head, first);
        copy(target + first, bytes, count - first);
        head = (head + count) % capacity_;
        used -= count;
        return count;
    }
};

} // namespace usbaudio
