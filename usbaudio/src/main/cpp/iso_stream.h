#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <thread>
#include <vector>

#include "libusb.h"
#include "ring_buffer.h"

class UacDevice;

/**
 * Isochronous OUT streaming engine.
 *
 * A fixed set of transfers (each carrying several iso packets) is kept in flight; every
 * completion callback refills the transfer from the ring buffer and resubmits it, so the
 * pipeline never drains as long as the producer keeps up. Packet sizes follow a fractional
 * accumulator seeded with the nominal rate and, for asynchronous endpoints with an explicit
 * feedback endpoint, continuously corrected by the DAC's feedback value.
 *
 * Underruns are filled with silence (and counted) rather than stalling the bus; pausing
 * keeps the transfers running silence-only so the DAC's clock stays locked (no resume pop).
 */
class IsoStream {
public:
    struct Config {
        uint8_t endpointAddress = 0;
        uint8_t feedbackEndpointAddress = 0; // 0 = none
        uint32_t rateHz = 0;
        int channels = 0;
        int subslotBytes = 0;
        int maxPacketSize = 0;
        int intervalCode = 1; // endpoint bInterval
        bool highSpeed = false;
        int ringBufferMs = 250;
    };

    IsoStream(UacDevice& device, Config config);
    ~IsoStream();

    IsoStream(const IsoStream&) = delete;
    IsoStream& operator=(const IsoStream&) = delete;

    /** Allocates and submits the transfer pipeline, starts the event thread. */
    bool start();
    /** Cancels everything and joins the event thread. Idempotent. */
    void stop();

    void pause() { paused_.store(true, std::memory_order_release); }
    void resume() { paused_.store(false, std::memory_order_release); }
    bool paused() const { return paused_.load(std::memory_order_acquire); }

    /**
     * Drops all audio buffered up to this moment (data written afterwards is kept).
     * The consumer bridges the cut with a short synthetic decay ramp — no click.
     */
    void flush();

    /** Producer side; returns bytes accepted (0 = ring full), -1 when the stream is dead. */
    int write(const uint8_t* data, size_t size);

    /** Frames of real audio handed to *completed* transfers (drives the playback position). */
    uint64_t playedFrames() const { return playedFrames_.load(std::memory_order_acquire); }
    /** Frames of real audio consumed from the ring (played + in flight). */
    uint64_t consumedFrames() const { return consumedFrames_.load(std::memory_order_acquire); }
    /** Frames still queued: ring + in flight. */
    uint64_t bufferedFrames() const;
    int xrunCount() const { return xruns_.load(std::memory_order_acquire); }
    /** False once the device vanished or the pipeline hit a fatal error. */
    bool alive() const { return !dead_.load(std::memory_order_acquire); }

private:
    struct TransferContext {
        IsoStream* stream = nullptr;
        libusb_transfer* transfer = nullptr;
        std::vector<uint8_t> buffer;
        uint32_t dataFrames = 0; // real (non-silence) frames in the submitted transfer
        bool inFlight = false;
    };

    /**
     * Consumer-side output shaping. Real PCM is passed through bit-exact; the only
     * synthesized audio is a ~2 ms ramp bridging silence↔data transitions, killing the
     * step-discontinuity click on skip/seek/pause/underrun.
     */
    enum class FillState {
        kPrefill,  // after start/flush: hold silence until the ring has enough audio
        kApproach, // synthetic ramp 0 → first pending sample
        kPlaying,  // bit-exact passthrough from the ring
        kDecay,    // synthetic ramp last sample → 0
        kSilence   // steady silence (paused or ran dry); resumes via kApproach
    };

    static void onTransferComplete(libusb_transfer* transfer);
    static void onFeedbackComplete(libusb_transfer* transfer);

    void fillAndSubmit(TransferContext& context);
    /** Fills `frames` frames at dst per the state machine; returns real data frames. */
    uint32_t fillFrames(uint8_t* dst, uint32_t frames);
    void handleFlushRequest();
    void beginApproach();
    void beginDecay(FillState after);
    int32_t readWireSample(const uint8_t* src) const;
    void writeWireSample(uint8_t* dst, int32_t s32top) const;
    bool submitFeedback();
    void markDead(const char* why);
    void eventLoop();

    /** Next packet size in frames, from the fractional accumulator. */
    uint32_t nextPacketFrames();

    UacDevice& device_;
    const Config config_;

    int frameBytes_ = 0;
    int packetsPerSecond_ = 0;
    uint32_t maxFramesPerPacket_ = 0;
    int packetsPerTransfer_ = 0;

    /** Q16.16 frames-per-packet, nominal and (when feedback is live) corrected. */
    uint64_t nominalRateQ16_ = 0;
    std::atomic<uint64_t> feedbackRateQ16_{0}; // 0 = no feedback yet
    uint64_t rateAccumulatorQ16_ = 0;          // event-thread only

    // ─── Fill state machine (event-thread only unless noted) ───────────────
    static constexpr int kMaxChannels = 8;
    FillState fillState_ = FillState::kPrefill;
    FillState decayTarget_ = FillState::kSilence;
    int32_t lastSample_[kMaxChannels] = {0};
    int32_t rampTarget_[kMaxChannels] = {0};
    uint32_t rampPos_ = 0;
    uint32_t rampFrames_ = 0;          // ~2 ms at the stream rate
    uint32_t prefillFrames_ = 0;       // ~40 ms of audio before leaving kPrefill
    uint32_t prefillWaitPackets_ = 0;  // deadline fallback so short tails still play
    uint32_t prefillDeadlinePackets_ = 0;
    std::atomic<bool> flushRequested_{false};
    std::atomic<uint64_t> discardUpToPos_{0}; // ring write position captured at flush()

    std::unique_ptr<RingBuffer> ring_;
    std::vector<std::unique_ptr<TransferContext>> transfers_;

    libusb_transfer* feedbackTransfer_ = nullptr;
    std::vector<uint8_t> feedbackBuffer_;
    bool feedbackInFlight_ = false;

    std::thread eventThread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> stopping_{false};
    // Streams begin paused: silence flows (locking the DAC's PLL, priming against pops)
    // without consuming the ring or counting xruns until resume().
    std::atomic<bool> paused_{true};
    std::atomic<bool> dead_{false};

    std::atomic<uint64_t> playedFrames_{0};
    std::atomic<uint64_t> consumedFrames_{0};
    std::atomic<uint64_t> inFlightFrames_{0};
    std::atomic<int> xruns_{0};
    std::atomic<int> pendingTransfers_{0};
};
