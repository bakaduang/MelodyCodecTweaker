#pragma once

#include "pcm_mono_core.h"

// Clang/GCC atomic builtins keep this code usable by the Android library and the freestanding
// host concurrency test. Every shared value below is accessed atomically; no ordinary C++ object
// is read concurrently with a write, and no atomic operation falls back to a runtime lock.
namespace melody::pcm {

template <typename T>
class AtomicValue {
public:
    static_assert(__atomic_always_lock_free(sizeof(T), nullptr));
    constexpr explicit AtomicValue(T initial = T{}) : value_(initial) {}
    AtomicValue(const AtomicValue&) = delete;
    AtomicValue& operator=(const AtomicValue&) = delete;

    T load(int order = __ATOMIC_SEQ_CST) const { return __atomic_load_n(&value_, order); }
    void store(T value, int order = __ATOMIC_SEQ_CST) { __atomic_store_n(&value_, value, order); }
    T fetchAdd(T value, int order) { return __atomic_fetch_add(&value_, value, order); }
    T fetchSubtract(T value, int order) { return __atomic_fetch_sub(&value_, value, order); }
    T fetchAnd(T value, int order) { return __atomic_fetch_and(&value_, value, order); }
    bool compareExchange(T& expected, T desired, int success, int failure) {
        return __atomic_compare_exchange_n(&value_, &expected, desired, false, success, failure);
    }

private:
    alignas(sizeof(T)) T value_;
};

// Writers serialize outside this registry. Each publication invalidates only its own slot.
// Readers take one bounded snapshot and never wait/retry: an unrelated track's publication and
// any number of telemetry readers cannot make a stable matching track fail its read.
template <size_t Capacity>
class TrackRegistry {
    struct Slot {
        AtomicValue<uint64_t> revision;
        AtomicValue<void*> key;
        AtomicValue<uint64_t> serial;
        AtomicValue<uint64_t> routeRevision;
        AtomicValue<int32_t> stream{-1};
        AtomicValue<uint32_t> sampleRate;
        AtomicValue<uint32_t> format;
        AtomicValue<uint32_t> channelMask;
        AtomicValue<uint32_t> flags;
        AtomicValue<int32_t> transfer{-1};
        AtomicValue<int32_t> selectedDeviceId;
        AtomicValue<bool> hasSharedBuffer{true};
        AtomicValue<uint8_t> routeEvidence{static_cast<uint8_t>(RouteEvidence::DefaultPolicy)};
        AtomicValue<size_t> routedCount;
        AtomicValue<int32_t> routedIds[kMaxDeviceIds];
    };

public:
    static constexpr size_t capacity = Capacity;

    void publish(size_t index, const Track& track) {
        if (index >= Capacity) return;
        Slot& slot = slots_[index];
        slot.revision.fetchAdd(1, __ATOMIC_SEQ_CST);
        slot.key.store(track.key);
        slot.serial.store(track.serial);
        slot.routeRevision.store(track.routeRevision);
        slot.stream.store(track.stream);
        slot.sampleRate.store(track.sampleRate);
        slot.format.store(track.format);
        slot.channelMask.store(track.channelMask);
        slot.flags.store(track.flags);
        slot.transfer.store(track.transfer);
        slot.selectedDeviceId.store(track.selectedDeviceId);
        slot.hasSharedBuffer.store(track.hasSharedBuffer);
        slot.routeEvidence.store(static_cast<uint8_t>(track.routeEvidence));
        slot.routedCount.store(track.routedCount);
        for (size_t i = 0; i < kMaxDeviceIds; ++i) slot.routedIds[i].store(track.routedIds[i]);
        slot.revision.fetchAdd(1, __ATOMIC_SEQ_CST);
    }

    bool read(void* key, Track* result) const {
        if (key == nullptr || result == nullptr) return false;
        for (size_t i = 0; i < Capacity; ++i) {
            if (slots_[i].key.load() == key) return readAt(i, result) && result->key == key;
        }
        return false;
    }

    bool readAt(size_t index, Track* result) const {
        if (index >= Capacity || result == nullptr) return false;
        const Slot& slot = slots_[index];
        const uint64_t revision = slot.revision.load();
        if ((revision & 1U) != 0) return false;
        Track track;
        track.key = slot.key.load();
        if (track.key == nullptr) return false;
        track.serial = slot.serial.load();
        track.routeRevision = slot.routeRevision.load();
        track.stream = slot.stream.load();
        track.sampleRate = slot.sampleRate.load();
        track.format = slot.format.load();
        track.channelMask = slot.channelMask.load();
        track.flags = slot.flags.load();
        track.transfer = slot.transfer.load();
        track.selectedDeviceId = slot.selectedDeviceId.load();
        track.hasSharedBuffer = slot.hasSharedBuffer.load();
        track.routeEvidence = static_cast<RouteEvidence>(slot.routeEvidence.load());
        track.routedCount = slot.routedCount.load();
        if (track.routedCount > kMaxDeviceIds) return false;
        for (size_t i = 0; i < track.routedCount; ++i) track.routedIds[i] = slot.routedIds[i].load();
        // Sequentially consistent data and revision operations give one common order: a field
        // from another publication necessarily places an odd/different revision between checks.
        if (slot.revision.load() != revision) return false;
        *result = track;
        return true;
    }

private:
    Slot slots_[Capacity];
};

class CounterReferences {
public:
    // beginRead always reserves one reference, even when it reports a writer. The caller must
    // endRead in either case. Keeping those brief rejected reservations visible lets endWrite
    // finish immediately without waiting for them or erasing references that will exit later.
    bool beginRead() { return (state_.fetchAdd(1, __ATOMIC_ACQUIRE) & kWriter) == 0; }
    void endRead() { state_.fetchSubtract(1, __ATOMIC_RELEASE); }
    bool tryBeginWrite() {
        uint64_t expected = 0;
        return state_.compareExchange(expected, kWriter, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED);
    }
    void endWrite() { state_.fetchAnd(~kWriter, __ATOMIC_RELEASE); }

private:
    static constexpr uint64_t kWriter = uint64_t{1} << 63;
    AtomicValue<uint64_t> state_;
};

// Mixing never depends on telemetry ownership. Pins use a fixed number of lock-free atomic
// operations, not a lock or a retry loop. A pinned bucket cannot be recycled; its token also rejects
// a delayed reader after recycling, even if a numeric generation is used again later.
template <size_t Capacity>
class GenerationCounters {
    static_assert(Capacity > 0 && Capacity <= 256);
    struct Bucket {
        CounterReferences references;
        AtomicValue<uint64_t> token;
        AtomicValue<uint64_t> generation;
        AtomicValue<uint64_t> mixedFrames;
        AtomicValue<uint64_t> lastMixMs;
        AtomicValue<uint64_t> unsupportedFrames;
        AtomicValue<uint64_t> routeSkippedFrames;
        AtomicValue<uint64_t> invalidBuffers;
    };

public:
    class Pin {
    public:
        Pin() = default;
        Pin(const Pin&) = delete;
        Pin& operator=(const Pin&) = delete;
        Pin(Pin&& other) noexcept : bucket_(other.bucket_) { other.bucket_ = nullptr; }
        ~Pin() { if (bucket_ != nullptr) bucket_->references.endRead(); }
        explicit operator bool() const { return bucket_ != nullptr; }

        void record(const MixResult& result, uint64_t nowMs) const {
            if (bucket_ == nullptr) return;
            switch (result.outcome) {
                case MixOutcome::Mixed: {
                    bucket_->mixedFrames.fetchAdd(result.frames, __ATOMIC_RELAXED);
                    uint64_t previous = bucket_->lastMixMs.load(__ATOMIC_RELAXED);
                    // Keep a real, nondecreasing mix timestamp. Two attempts bound telemetry
                    // work even with many simultaneous tracks; a lost race may only leave an
                    // earlier timestamp from this same generation, never block or skip audio.
                    for (unsigned attempt = 0; attempt < 2 && previous < nowMs; ++attempt) {
                        if (bucket_->lastMixMs.compareExchange(previous, nowMs,
                                __ATOMIC_RELAXED, __ATOMIC_RELAXED)) break;
                    }
                    break;
                }
                case MixOutcome::Unsupported:
                    bucket_->unsupportedFrames.fetchAdd(result.frames, __ATOMIC_RELAXED); break;
                case MixOutcome::RouteSkipped:
                    bucket_->routeSkippedFrames.fetchAdd(result.frames, __ATOMIC_RELAXED); break;
                case MixOutcome::InvalidBuffer:
                    bucket_->invalidBuffers.fetchAdd(1, __ATOMIC_RELAXED); break;
                case MixOutcome::Inactive: break;
            }
        }

        bool snapshot(Counters* result) const {
            if (bucket_ == nullptr || result == nullptr) return false;
            result->generation = bucket_->generation.load(__ATOMIC_RELAXED);
            result->mixedFrames = bucket_->mixedFrames.load(__ATOMIC_RELAXED);
            result->lastMixMs = bucket_->lastMixMs.load(__ATOMIC_RELAXED);
            result->unsupportedFrames = bucket_->unsupportedFrames.load(__ATOMIC_RELAXED);
            result->routeSkippedFrames = bucket_->routeSkippedFrames.load(__ATOMIC_RELAXED);
            result->invalidBuffers = bucket_->invalidBuffers.load(__ATOMIC_RELAXED);
            return true;
        }

    private:
        friend class GenerationCounters;
        explicit Pin(Bucket* bucket) : bucket_(bucket) {}
        Bucket* bucket_ = nullptr;
    };

    // Only the serialized configuration writer calls this method. A failed allocation disables
    // telemetry for this publication only; a later full configuration can retry. PCM stays enabled.
    uint64_t adoptGeneration(uint64_t generation) {
        if (hasGeneration_ && activeGeneration_ == generation && activeToken_ != 0) return activeToken_;
        if (!hasGeneration_ || activeGeneration_ != generation) {
            hasGeneration_ = true;
            activeGeneration_ = generation;
            activeToken_ = 0;
        }
        if (nextEpoch_ > (UINT64_MAX - (Capacity - 1)) / Capacity) return 0;
        for (size_t offset = 0; offset < Capacity; ++offset) {
            const size_t index = (nextIndex_ + offset) % Capacity;
            Bucket& bucket = buckets_[index];
            if (!bucket.references.tryBeginWrite()) continue;
            const uint64_t token = nextEpoch_++ * Capacity + index;
            bucket.token.store(token, __ATOMIC_RELAXED);
            bucket.generation.store(generation, __ATOMIC_RELAXED);
            bucket.mixedFrames.store(0, __ATOMIC_RELAXED);
            bucket.lastMixMs.store(0, __ATOMIC_RELAXED);
            bucket.unsupportedFrames.store(0, __ATOMIC_RELAXED);
            bucket.routeSkippedFrames.store(0, __ATOMIC_RELAXED);
            bucket.invalidBuffers.store(0, __ATOMIC_RELAXED);
            // A reader that saw kWriter still owns a temporary reference until it backs out.
            // Preserve those references instead of storing zero, which could underflow on exit.
            bucket.references.endWrite();
            activeToken_ = token;
            nextIndex_ = (index + 1) % Capacity;
            return token;
        }
        return 0;
    }

    Pin pin(uint64_t token) {
        if (token == 0) return Pin{};
        Bucket& bucket = buckets_[token % Capacity];
        const bool readable = bucket.references.beginRead();
        if (!readable || bucket.token.load(__ATOMIC_RELAXED) != token) {
            bucket.references.endRead();
            return Pin{};
        }
        return Pin{&bucket};
    }

private:
    Bucket buckets_[Capacity];
    // Writer-only bookkeeping; neither audio nor snapshot readers access these fields.
    uint64_t activeGeneration_ = 0;
    uint64_t activeToken_ = 0;
    uint64_t nextEpoch_ = 1;
    size_t nextIndex_ = 0;
    bool hasGeneration_ = false;
};

}  // namespace melody::pcm
