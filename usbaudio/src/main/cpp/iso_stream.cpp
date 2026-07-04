#include "iso_stream.h"

#include <algorithm>
#include <cinttypes>
#include <cstring>
#include <iterator>

#include "log.h"
#include "uac_device.h"

namespace {
constexpr int kTransfers = 8;
constexpr int kPacketsPerTransferTarget = 8;
constexpr unsigned kEventTimeoutUs = 100 * 1000;
// Synthetic silence↔data ramps: long enough to be clickless, short enough to be inaudible.
constexpr uint32_t kRampMs = 2;
// Audio buffered before playback starts after start/flush (absorbs bursty first buffers).
constexpr uint32_t kPrefillMs = 40;
// If some audio waits below the prefill threshold this long, start anyway (short tails).
constexpr uint32_t kPrefillDeadlineMs = 50;
}  // namespace

IsoStream::IsoStream(UacDevice& device, Config config)
    : device_(device), config_(config) {}

IsoStream::~IsoStream() {
    stop();
}

bool IsoStream::start() {
    if (running_.load()) return true;

    frameBytes_ = config_.channels * config_.subslotBytes;
    if (frameBytes_ <= 0 || config_.rateHz == 0 || config_.maxPacketSize <= 0) {
        device_.setLastError("IsoStream: invalid configuration");
        return false;
    }

    // Packets per second on the wire: 1000 frames (full speed) or 8000 µframes (high
    // speed), stretched by the endpoint's service interval (2^(bInterval-1)).
    const int base = config_.highSpeed ? 8000 : 1000;
    const int intervalShift = config_.intervalCode > 0 ? config_.intervalCode - 1 : 0;
    packetsPerSecond_ = base >> (intervalShift > 4 ? 4 : intervalShift);
    if (packetsPerSecond_ <= 0) packetsPerSecond_ = 1000;

    nominalRateQ16_ = (static_cast<uint64_t>(config_.rateHz) << 16) / packetsPerSecond_;
    maxFramesPerPacket_ = static_cast<uint32_t>(config_.maxPacketSize / frameBytes_);
    const uint32_t nominalFrames = static_cast<uint32_t>(nominalRateQ16_ >> 16) + 1;
    if (maxFramesPerPacket_ < nominalFrames) {
        device_.setLastError("IsoStream: wMaxPacketSize too small for rate");
        UA_LOGE("maxPacket=%d < needed %u frames × %d bytes", config_.maxPacketSize,
                nominalFrames, frameBytes_);
        return false;
    }

    packetsPerTransfer_ = kPacketsPerTransferTarget;
    const size_t ringBytes =
        static_cast<size_t>(config_.rateHz) * frameBytes_ * config_.ringBufferMs / 1000;
    ring_ = std::make_unique<RingBuffer>(ringBytes > 4096 ? ringBytes : 4096);

    rateAccumulatorQ16_ = 0;
    rampFrames_ = std::max<uint32_t>(16, config_.rateHz * kRampMs / 1000);
    prefillFrames_ = config_.rateHz * kPrefillMs / 1000;
    prefillDeadlinePackets_ =
        std::max<uint32_t>(1, packetsPerSecond_ * kPrefillDeadlineMs / 1000);
    prefillWaitPackets_ = 0;
    fillState_ = FillState::kPrefill;
    std::fill(std::begin(lastSample_), std::end(lastSample_), 0);
    flushRequested_.store(false);
    stopping_.store(false);
    dead_.store(false);
    running_.store(true);

    // Allocate the pipeline before spawning the event thread.
    const size_t packetBytes = static_cast<size_t>(maxFramesPerPacket_) * frameBytes_;
    transfers_.clear();
    for (int i = 0; i < kTransfers; ++i) {
        auto context = std::make_unique<TransferContext>();
        context->stream = this;
        context->buffer.resize(packetBytes * packetsPerTransfer_);
        context->transfer = libusb_alloc_transfer(packetsPerTransfer_);
        if (context->transfer == nullptr) {
            device_.setLastError("IsoStream: libusb_alloc_transfer failed");
            running_.store(false);
            return false;
        }
        transfers_.push_back(std::move(context));
    }

    if (config_.feedbackEndpointAddress != 0) {
        feedbackBuffer_.resize(4);
        feedbackTransfer_ = libusb_alloc_transfer(1);
        if (feedbackTransfer_ == nullptr) {
            device_.setLastError("IsoStream: feedback transfer alloc failed");
            running_.store(false);
            return false;
        }
    }

    for (auto& context : transfers_) {
        fillAndSubmit(*context);
        if (dead_.load()) {
            running_.store(false);
            return false;
        }
    }
    if (feedbackTransfer_ != nullptr && !submitFeedback()) {
        // Feedback is an optimization; fall back to nominal-rate pacing.
        UA_LOGW("Feedback endpoint submit failed; using nominal rate");
    }

    eventThread_ = std::thread([this] { eventLoop(); });
    UA_LOGI("IsoStream started: %u Hz × %dch × %dB, %d pkt/s, maxFrames/pkt=%u",
            config_.rateHz, config_.channels, config_.subslotBytes, packetsPerSecond_,
            maxFramesPerPacket_);
    return true;
}

void IsoStream::stop() {
    if (!running_.load()) return;
    stopping_.store(true);

    for (auto& context : transfers_) {
        if (context->inFlight) {
            libusb_cancel_transfer(context->transfer);
        }
    }
    if (feedbackTransfer_ != nullptr && feedbackInFlight_) {
        libusb_cancel_transfer(feedbackTransfer_);
    }

    if (eventThread_.joinable()) {
        eventThread_.join();
    }

    for (auto& context : transfers_) {
        libusb_free_transfer(context->transfer);
        context->transfer = nullptr;
    }
    transfers_.clear();
    if (feedbackTransfer_ != nullptr) {
        libusb_free_transfer(feedbackTransfer_);
        feedbackTransfer_ = nullptr;
    }
    running_.store(false);
    UA_LOGI("IsoStream stopped (played=%" PRIu64 " frames, xruns=%d)",
            playedFrames_.load(), xruns_.load());
}

void IsoStream::flush() {
    if (!ring_) return;
    // The consumer thread discards exactly the bytes present now — anything the producer
    // writes after this call is the new stream and survives. It also bridges the cut with
    // a decay ramp instead of a hard step (the source of the audible click on skips).
    discardUpToPos_.store(ring_->writePosition(), std::memory_order_release);
    flushRequested_.store(true, std::memory_order_release);
    // In-flight audio still plays out (a few ms); consumedFrames() already includes it,
    // which is exactly what the sink snapshots as its flush base.
}

int IsoStream::write(const uint8_t* data, size_t size) {
    if (dead_.load(std::memory_order_acquire)) return -1;
    if (!running_.load(std::memory_order_acquire) || !ring_) return -1;
    return static_cast<int>(ring_->write(data, size));
}

uint64_t IsoStream::bufferedFrames() const {
    const uint64_t ringFrames = ring_ ? ring_->availableToRead() / frameBytes_ : 0;
    return ringFrames + inFlightFrames_.load(std::memory_order_acquire);
}

uint32_t IsoStream::nextPacketFrames() {
    const uint64_t feedback = feedbackRateQ16_.load(std::memory_order_acquire);
    const uint64_t rate = feedback != 0 ? feedback : nominalRateQ16_;
    rateAccumulatorQ16_ += rate;
    uint32_t frames = static_cast<uint32_t>(rateAccumulatorQ16_ >> 16);
    rateAccumulatorQ16_ &= 0xFFFF;
    if (frames > maxFramesPerPacket_) {
        // Bogus feedback value; clamp and drop the excess debt.
        frames = maxFramesPerPacket_;
        rateAccumulatorQ16_ = 0;
    }
    return frames;
}

int32_t IsoStream::readWireSample(const uint8_t* src) const {
    switch (config_.subslotBytes) {
        case 2:
            return static_cast<int32_t>(static_cast<int16_t>(src[0] | (src[1] << 8))) << 16;
        case 3:
            return static_cast<int32_t>(src[0] | (src[1] << 8) | (src[2] << 16)) << 8;
        default:
            return static_cast<int32_t>(
                src[0] | (src[1] << 8) | (src[2] << 16) |
                (static_cast<uint32_t>(src[3]) << 24));
    }
}

void IsoStream::writeWireSample(uint8_t* dst, int32_t s32top) const {
    const uint32_t u = static_cast<uint32_t>(s32top);
    switch (config_.subslotBytes) {
        case 2:
            dst[0] = static_cast<uint8_t>(u >> 16);
            dst[1] = static_cast<uint8_t>(u >> 24);
            break;
        case 3:
            dst[0] = static_cast<uint8_t>(u >> 8);
            dst[1] = static_cast<uint8_t>(u >> 16);
            dst[2] = static_cast<uint8_t>(u >> 24);
            break;
        default:
            dst[0] = static_cast<uint8_t>(u);
            dst[1] = static_cast<uint8_t>(u >> 8);
            dst[2] = static_cast<uint8_t>(u >> 16);
            dst[3] = static_cast<uint8_t>(u >> 24);
    }
}

void IsoStream::beginApproach() {
    uint8_t firstFrame[kMaxChannels * 4];
    if (ring_->peek(firstFrame, frameBytes_) < static_cast<size_t>(frameBytes_)) {
        fillState_ = FillState::kSilence;
        return;
    }
    for (int ch = 0; ch < config_.channels && ch < kMaxChannels; ++ch) {
        rampTarget_[ch] = readWireSample(firstFrame + ch * config_.subslotBytes);
    }
    rampPos_ = 0;
    prefillWaitPackets_ = 0;
    fillState_ = FillState::kApproach;
}

void IsoStream::beginDecay(FillState after) {
    for (int ch = 0; ch < kMaxChannels; ++ch) rampTarget_[ch] = lastSample_[ch];
    rampPos_ = 0;
    decayTarget_ = after;
    fillState_ = FillState::kDecay;
}

void IsoStream::handleFlushRequest() {
    if (!flushRequested_.exchange(false, std::memory_order_acq_rel)) return;
    // Discard exactly the audio that existed when flush() was called; bytes the producer
    // wrote afterwards belong to the new stream and stay queued behind the prefill gate.
    const uint64_t upTo = discardUpToPos_.load(std::memory_order_acquire);
    const uint64_t readPos = ring_->readPosition();
    if (upTo > readPos) {
        ring_->skip(static_cast<size_t>(upTo - readPos));
    }
    switch (fillState_) {
        case FillState::kPlaying:
        case FillState::kApproach:
            beginDecay(FillState::kPrefill);
            break;
        case FillState::kDecay:
            decayTarget_ = FillState::kPrefill;
            break;
        default:
            fillState_ = FillState::kPrefill;
    }
    prefillWaitPackets_ = 0;
}

uint32_t IsoStream::fillFrames(uint8_t* dst, uint32_t frames) {
    const bool paused = paused_.load(std::memory_order_acquire);
    const int channels = std::min(config_.channels, kMaxChannels);
    uint32_t dataFrames = 0;
    uint32_t remaining = frames;
    uint8_t* cursor = dst;

    while (remaining > 0) {
        switch (fillState_) {
            case FillState::kPrefill: {
                const uint32_t ringFrames =
                    static_cast<uint32_t>(ring_->availableToRead() / frameBytes_);
                if (!paused && ringFrames > 0) {
                    if (ringFrames >= prefillFrames_ ||
                        prefillWaitPackets_ >= prefillDeadlinePackets_) {
                        beginApproach();
                        continue;
                    }
                    ++prefillWaitPackets_;
                }
                std::memset(cursor, 0, static_cast<size_t>(remaining) * frameBytes_);
                return dataFrames;
            }

            case FillState::kSilence: {
                if (!paused && ring_->availableToRead() >= static_cast<size_t>(frameBytes_)) {
                    beginApproach();
                    continue;
                }
                std::memset(cursor, 0, static_cast<size_t>(remaining) * frameBytes_);
                return dataFrames;
            }

            case FillState::kApproach: {
                while (remaining > 0 && rampPos_ < rampFrames_) {
                    for (int ch = 0; ch < channels; ++ch) {
                        const int32_t value = static_cast<int32_t>(
                            static_cast<int64_t>(rampTarget_[ch]) * (rampPos_ + 1) /
                            (rampFrames_ + 1));
                        writeWireSample(cursor + ch * config_.subslotBytes, value);
                        lastSample_[ch] = value;
                    }
                    cursor += frameBytes_;
                    --remaining;
                    ++rampPos_;
                }
                if (rampPos_ >= rampFrames_) fillState_ = FillState::kPlaying;
                continue;
            }

            case FillState::kPlaying: {
                if (paused) {
                    beginDecay(FillState::kSilence);
                    continue;
                }
                const size_t got =
                    ring_->read(cursor, static_cast<size_t>(remaining) * frameBytes_);
                const uint32_t gotFrames = static_cast<uint32_t>(got / frameBytes_);
                if (gotFrames > 0) {
                    const uint8_t* lastFrame = cursor + (gotFrames - 1) * frameBytes_;
                    for (int ch = 0; ch < channels; ++ch) {
                        lastSample_[ch] = readWireSample(lastFrame + ch * config_.subslotBytes);
                    }
                    dataFrames += gotFrames;
                    remaining -= gotFrames;
                    cursor += static_cast<size_t>(gotFrames) * frameBytes_;
                }
                if (remaining > 0) {
                    // Ran dry mid-stream (or the track just drained): bridge with a decay
                    // ramp instead of a hard step to zero.
                    xruns_.fetch_add(1, std::memory_order_relaxed);
                    beginDecay(FillState::kSilence);
                    continue;
                }
                return dataFrames;
            }

            case FillState::kDecay: {
                while (remaining > 0 && rampPos_ < rampFrames_) {
                    for (int ch = 0; ch < channels; ++ch) {
                        const int32_t value = static_cast<int32_t>(
                            static_cast<int64_t>(rampTarget_[ch]) *
                            (rampFrames_ - 1 - rampPos_) / rampFrames_);
                        writeWireSample(cursor + ch * config_.subslotBytes, value);
                        lastSample_[ch] = value;
                    }
                    cursor += frameBytes_;
                    --remaining;
                    ++rampPos_;
                }
                if (rampPos_ >= rampFrames_) fillState_ = decayTarget_;
                continue;
            }
        }
    }
    return dataFrames;
}

void IsoStream::fillAndSubmit(TransferContext& context) {
    libusb_transfer* transfer = context.transfer;
    uint8_t* cursor = context.buffer.data();
    uint32_t dataFrames = 0;
    int totalBytes = 0;

    handleFlushRequest();

    for (int p = 0; p < packetsPerTransfer_; ++p) {
        const uint32_t frames = nextPacketFrames();
        const size_t bytes = static_cast<size_t>(frames) * frameBytes_;
        if (bytes > 0) {
            dataFrames += fillFrames(cursor, frames);
        }
        transfer->iso_packet_desc[p].length = static_cast<unsigned>(bytes);
        cursor += bytes;
        totalBytes += static_cast<int>(bytes);
    }

    context.dataFrames = dataFrames;
    if (dataFrames > 0) {
        consumedFrames_.fetch_add(dataFrames, std::memory_order_release);
        inFlightFrames_.fetch_add(dataFrames, std::memory_order_release);
    }

    libusb_fill_iso_transfer(transfer, device_.handle(), config_.endpointAddress,
                             context.buffer.data(), totalBytes, packetsPerTransfer_,
                             &IsoStream::onTransferComplete, &context, 0);

    const int rc = libusb_submit_transfer(transfer);
    if (rc != LIBUSB_SUCCESS) {
        context.inFlight = false;
        if (dataFrames > 0) {
            inFlightFrames_.fetch_sub(dataFrames, std::memory_order_release);
        }
        device_.setLastError(std::string("iso submit: ") + libusb_error_name(rc));
        markDead("submit failed");
        return;
    }
    context.inFlight = true;
    pendingTransfers_.fetch_add(1, std::memory_order_release);
}

void IsoStream::onTransferComplete(libusb_transfer* transfer) {
    auto* context = static_cast<TransferContext*>(transfer->user_data);
    IsoStream* self = context->stream;

    context->inFlight = false;
    self->pendingTransfers_.fetch_sub(1, std::memory_order_release);

    const uint32_t dataFrames = context->dataFrames;
    if (dataFrames > 0) {
        self->inFlightFrames_.fetch_sub(dataFrames, std::memory_order_release);
    }

    switch (transfer->status) {
        case LIBUSB_TRANSFER_COMPLETED:
            if (dataFrames > 0) {
                self->playedFrames_.fetch_add(dataFrames, std::memory_order_release);
            }
            break;
        case LIBUSB_TRANSFER_CANCELLED:
            return; // shutting down; do not resubmit
        case LIBUSB_TRANSFER_NO_DEVICE:
            self->markDead("device gone");
            return;
        default:
            // Isolated iso errors happen (bus glitches); resubmit unless we are stopping.
            UA_LOGW("iso transfer status=%d", transfer->status);
            break;
    }

    if (!self->stopping_.load(std::memory_order_acquire) && !self->dead_.load()) {
        self->fillAndSubmit(*context);
    }
}

bool IsoStream::submitFeedback() {
    libusb_fill_iso_transfer(feedbackTransfer_, device_.handle(),
                             config_.feedbackEndpointAddress, feedbackBuffer_.data(),
                             static_cast<int>(feedbackBuffer_.size()), 1,
                             &IsoStream::onFeedbackComplete, this, 0);
    feedbackTransfer_->iso_packet_desc[0].length = static_cast<unsigned>(feedbackBuffer_.size());
    const int rc = libusb_submit_transfer(feedbackTransfer_);
    feedbackInFlight_ = rc == LIBUSB_SUCCESS;
    return feedbackInFlight_;
}

void IsoStream::onFeedbackComplete(libusb_transfer* transfer) {
    auto* self = static_cast<IsoStream*>(transfer->user_data);
    self->feedbackInFlight_ = false;

    if (transfer->status == LIBUSB_TRANSFER_CANCELLED) return;
    if (transfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
        self->markDead("device gone (feedback)");
        return;
    }

    if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        const int length = transfer->iso_packet_desc[0].actual_length;
        const uint8_t* data = self->feedbackBuffer_.data();
        uint64_t rateQ16 = 0;
        if (length >= 4) {
            // High-speed: Q16.16 frames per µframe.
            const uint32_t raw = data[0] | (data[1] << 8) | (data[2] << 16)
                | (static_cast<uint32_t>(data[3]) << 24);
            rateQ16 = raw;
        } else if (length == 3) {
            // Full-speed: Q10.14 frames per frame → shift to Q16.16.
            const uint32_t raw = data[0] | (data[1] << 8) | (data[2] << 16);
            rateQ16 = static_cast<uint64_t>(raw) << 2;
        }
        if (rateQ16 != 0) {
            // Sanity window: ±25% of nominal, otherwise ignore the sample.
            const uint64_t nominal = self->nominalRateQ16_;
            if (rateQ16 > nominal - nominal / 4 && rateQ16 < nominal + nominal / 4) {
                self->feedbackRateQ16_.store(rateQ16, std::memory_order_release);
            }
        }
    }

    if (!self->stopping_.load(std::memory_order_acquire) && !self->dead_.load()) {
        self->submitFeedback();
    }
}

void IsoStream::markDead(const char* why) {
    if (!dead_.exchange(true)) {
        UA_LOGE("IsoStream dead: %s", why);
    }
}

void IsoStream::eventLoop() {
    timeval timeout{0, kEventTimeoutUs};
    while (true) {
        const bool stopping = stopping_.load(std::memory_order_acquire);
        const int pending = pendingTransfers_.load(std::memory_order_acquire);
        if (stopping && pending == 0 && !feedbackInFlight_) break;
        libusb_handle_events_timeout(device_.context(), &timeout);
    }
}
