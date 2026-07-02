#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

/**
 * Lock-free single-producer/single-consumer byte ring.
 * Producer: the JNI write path (ExoPlayer's playback thread).
 * Consumer: the libusb event thread filling isochronous packets.
 *
 * Positions are monotonically increasing byte counts; the index into the
 * backing store is position % capacity.
 */
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity) : buffer_(capacity) {}

    size_t capacity() const { return buffer_.size(); }

    size_t availableToRead() const {
        return static_cast<size_t>(
            writePos_.load(std::memory_order_acquire) - readPos_.load(std::memory_order_acquire));
    }

    size_t availableToWrite() const { return capacity() - availableToRead(); }

    /** Copies up to `size` bytes in; returns the number accepted (0 when full). */
    size_t write(const uint8_t* data, size_t size) {
        const uint64_t writePos = writePos_.load(std::memory_order_relaxed);
        const uint64_t readPos = readPos_.load(std::memory_order_acquire);
        const size_t space = capacity() - static_cast<size_t>(writePos - readPos);
        const size_t toWrite = size < space ? size : space;
        if (toWrite == 0) return 0;

        const size_t index = static_cast<size_t>(writePos % capacity());
        const size_t first = std::min(toWrite, capacity() - index);
        std::memcpy(buffer_.data() + index, data, first);
        if (toWrite > first) {
            std::memcpy(buffer_.data(), data + first, toWrite - first);
        }
        writePos_.store(writePos + toWrite, std::memory_order_release);
        return toWrite;
    }

    /** Copies up to `size` bytes out; returns the number read. */
    size_t read(uint8_t* out, size_t size) {
        const uint64_t readPos = readPos_.load(std::memory_order_relaxed);
        const uint64_t writePos = writePos_.load(std::memory_order_acquire);
        const size_t available = static_cast<size_t>(writePos - readPos);
        const size_t toRead = size < available ? size : available;
        if (toRead == 0) return 0;

        const size_t index = static_cast<size_t>(readPos % capacity());
        const size_t first = std::min(toRead, capacity() - index);
        std::memcpy(out, buffer_.data() + index, first);
        if (toRead > first) {
            std::memcpy(out + first, buffer_.data(), toRead - first);
        }
        readPos_.store(readPos + toRead, std::memory_order_release);
        return toRead;
    }

    /** Consumer-side discard of everything currently buffered. */
    void clear() {
        readPos_.store(writePos_.load(std::memory_order_acquire), std::memory_order_release);
    }

private:
    std::vector<uint8_t> buffer_;
    std::atomic<uint64_t> writePos_{0};
    std::atomic<uint64_t> readPos_{0};
};
