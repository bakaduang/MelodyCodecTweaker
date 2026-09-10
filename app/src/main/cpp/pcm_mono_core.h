#pragma once

#include <stddef.h>
#include <stdint.h>

// This file deliberately has no Android, JNI, allocator, or platform STL dependencies. The same
// buffer/policy code is exercised by the host executable in src/test/cpp/pcm_mono_core_test.cpp.
namespace melody::pcm {

constexpr uint32_t kPcm16 = 1;
constexpr uint32_t kPcm8 = 2;
constexpr uint32_t kPcm32 = 3;
constexpr uint32_t kPcm8_24 = 4;
constexpr uint32_t kPcmFloat = 5;
constexpr uint32_t kPcm24Packed = 6;
constexpr uint32_t kStereoMask = 3;
constexpr int32_t kMusicStream = 3;
constexpr uint32_t kCompressedOffloadFlag = 0x10;
constexpr uint32_t kHwAvSyncFlag = 0x40;
constexpr size_t kMaxDeviceIds = 16;
constexpr size_t kMaxReleaseBytes = 1024 * 1024;
constexpr uint64_t kMaxLeaseMs = 4000;

// Android 16 AudioTrack::Buffer, not a view of the AudioTrack object's private fields.
struct AudioBuffer {
    size_t frameCount;
    size_t sizeBytes;
    void* raw;
    uint32_t sequence;
};

enum class RouteEvidence : uint8_t {
    // Only used until a routing callback or a device-selection change has been seen.
    DefaultPolicy,
    Unknown,
    Observed,
};

struct Track {
    void* key = nullptr;
    uint64_t serial = 0;
    uint64_t routeRevision = 0;
    int32_t stream = -1;
    uint32_t sampleRate = 0;
    uint32_t format = 0;
    uint32_t channelMask = 0;
    uint32_t flags = 0;
    int32_t transfer = -1;
    int32_t selectedDeviceId = 0;
    bool hasSharedBuffer = true;
    RouteEvidence routeEvidence = RouteEvidence::DefaultPolicy;
    size_t routedCount = 0;
    int32_t routedIds[kMaxDeviceIds]{};
};

struct Configuration {
    bool enabled = false;
    uint64_t expiresAtMs = 0;
    uint64_t generation = 0;
    uint64_t revision = 0;
    size_t deviceCount = 0;
    int32_t deviceIds[kMaxDeviceIds]{};
};

// With a null sp<IMemory>, Android 16 resolves DEFAULT to CALLBACK or SYNC. Both release the
// client proxy buffer; we do not need to interpret the unrelated wp<IAudioTrackCallback> ABI.
inline bool streamingTransfer(int32_t transfer, bool hasSharedBuffer) {
    if (hasSharedBuffer) return false;
    return transfer == 0 || transfer == 1 || transfer == 2 || transfer == 3 || transfer == 5;
}

inline size_t stereoFrameBytes(uint32_t format) {
    if (format == kPcm8) return 2;
    if (format == kPcm16) return 2 * sizeof(int16_t);
    if (format == kPcm24Packed) return 6;
    if (format == kPcm32 || format == kPcm8_24) return 2 * sizeof(int32_t);
    if (format == kPcmFloat) return 2 * sizeof(float);
    return 0;
}

inline bool supported(const Track& track) {
    return track.stream == kMusicStream && track.channelMask == kStereoMask
            && stereoFrameBytes(track.format) != 0
            && (track.flags & (kCompressedOffloadFlag | kHwAvSyncFlag)) == 0
            && streamingTransfer(track.transfer, track.hasSharedBuffer);
}

inline bool leaseValid(const Configuration& config, uint64_t nowMs) {
    return config.enabled && nowMs != 0 && nowMs < config.expiresAtMs
            && config.deviceCount == 1 && config.deviceIds[0] > 0;
}

inline bool routeAllowed(const Track& track, const Configuration& config) {
    if (config.deviceCount != 1 || config.deviceIds[0] <= 0) return false;
    const int32_t target = config.deviceIds[0];
    if (track.selectedDeviceId != 0 && track.selectedDeviceId != target) return false;
    if (track.routeEvidence == RouteEvidence::DefaultPolicy) return true;
    return track.routeEvidence == RouteEvidence::Observed && track.routedCount == 1
            && track.routedIds[0] == target;
}

// This is a bounded read-only view of Android 16's platform std::__1::vector<int>. It is used
// only after matching that complete symbol on arm64. The first two fields are pointer begin/end
// in AOSP external/libcxx/include/vector, __vector_base (Android 16, lines 325-345); LLVM libc++
// 19 keeps the same ordering. No std::__ndk1 object is constructed, returned, or destroyed.
// https://android.googlesource.com/platform/external/libcxx/+/refs/heads/android16-release/include/vector
inline bool readPlatformDeviceIds(const void* vectorRef, int32_t* ids, size_t* count) {
    if (vectorRef == nullptr || ids == nullptr || count == nullptr) return false;
    *count = 0;
    uintptr_t ends[2]{};
    __builtin_memcpy(ends, vectorRef, sizeof(ends));
    const uintptr_t begin = ends[0];
    const uintptr_t end = ends[1];
    if (begin == 0 && end == 0) return true;
    if (begin < 4096 || end < begin || (begin % alignof(int32_t)) != 0
            || (end % alignof(int32_t)) != 0) return false;
    const uintptr_t bytes = end - begin;
    if (bytes % sizeof(int32_t) != 0 || bytes / sizeof(int32_t) > kMaxDeviceIds) return false;
    const size_t size = static_cast<size_t>(bytes / sizeof(int32_t));
    if (size != 0) __builtin_memcpy(ids, reinterpret_cast<const void*>(begin), bytes);
    for (size_t i = 0; i < size; ++i) {
        if (ids[i] <= 0) return false;
    }
    *count = size;
    return true;
}

inline bool validBuffer(const AudioBuffer& buffer, size_t frameBytes, size_t* frames) {
    *frames = 0;
    if (frameBytes == 0 || buffer.raw == nullptr || buffer.sizeBytes == 0
            || buffer.sizeBytes > kMaxReleaseBytes || buffer.sizeBytes % frameBytes != 0) {
        return false;
    }
    const uintptr_t raw = reinterpret_cast<uintptr_t>(buffer.raw);
    const size_t sampleBytes = frameBytes / 2;
    const size_t alignment = sampleBytes == 3 ? 1 : sampleBytes;
    if (raw < 4096 || raw % alignment != 0 || UINTPTR_MAX - raw < buffer.sizeBytes) return false;
    // AudioTrack::releaseBuffer consumes mSize / mFrameSize and ignores frameCount. In particular
    // callbacks can release fewer bytes than obtainBuffer originally offered. Never mix the tail.
    *frames = buffer.sizeBytes / frameBytes;
    return true;
}

inline size_t skippedFrames(const Track& track, const AudioBuffer& buffer) {
    const size_t bytes = stereoFrameBytes(track.format);
    if (track.channelMask == kStereoMask && bytes != 0
            && buffer.sizeBytes <= kMaxReleaseBytes && buffer.sizeBytes % bytes == 0) {
        return buffer.sizeBytes / bytes;
    }
    // Unsupported encodings have no PCM frame-byte ratio. This field is only a bounded skip
    // diagnostic; mixedFrames below always counts the actual PCM frames written, never bytes.
    constexpr size_t maxReportedFrames = kMaxReleaseBytes / sizeof(int16_t);
    return buffer.frameCount <= maxReportedFrames ? buffer.frameCount : 0;
}

inline void mixStereo(void* raw, uint32_t format, size_t frames) {
    auto* bytes = static_cast<unsigned char*>(raw);
    if (format == kPcm8) {
        for (size_t i = 0; i < frames; ++i, bytes += 2) {
            const int32_t sum = static_cast<int32_t>(bytes[0]) + bytes[1] - 256;
            bytes[0] = bytes[1] = static_cast<unsigned char>(sum / 2 + 128);
        }
    } else if (format == kPcm16) {
        for (size_t i = 0; i < frames; ++i, bytes += 2 * sizeof(int16_t)) {
            int16_t samples[2];
            __builtin_memcpy(samples, bytes, sizeof(samples));
            const int32_t sum = static_cast<int32_t>(samples[0]) + samples[1];
            samples[0] = samples[1] = static_cast<int16_t>(sum / 2);
            __builtin_memcpy(bytes, samples, sizeof(samples));
        }
    } else if (format == kPcm32 || format == kPcm8_24) {
        for (size_t i = 0; i < frames; ++i, bytes += 2 * sizeof(int32_t)) {
            int32_t samples[2];
            __builtin_memcpy(samples, bytes, sizeof(samples));
            const int64_t sum = static_cast<int64_t>(samples[0]) + samples[1];
            samples[0] = samples[1] = static_cast<int32_t>(sum / 2);
            __builtin_memcpy(bytes, samples, sizeof(samples));
        }
    } else if (format == kPcm24Packed) {
        for (size_t i = 0; i < frames; ++i, bytes += 6) {
            int32_t left = bytes[0] | (static_cast<int32_t>(bytes[1]) << 8)
                    | (static_cast<int32_t>(bytes[2]) << 16);
            int32_t right = bytes[3] | (static_cast<int32_t>(bytes[4]) << 8)
                    | (static_cast<int32_t>(bytes[5]) << 16);
            if ((left & 0x800000) != 0) left -= 0x1000000;
            if ((right & 0x800000) != 0) right -= 0x1000000;
            const uint32_t mono = static_cast<uint32_t>((left + right) / 2);
            bytes[0] = bytes[3] = static_cast<unsigned char>(mono);
            bytes[1] = bytes[4] = static_cast<unsigned char>(mono >> 8);
            bytes[2] = bytes[5] = static_cast<unsigned char>(mono >> 16);
        }
    } else if (format == kPcmFloat) {
        for (size_t i = 0; i < frames; ++i, bytes += 2 * sizeof(float)) {
            float samples[2];
            __builtin_memcpy(samples, bytes, sizeof(samples));
            const float left = __builtin_isfinite(samples[0]) ? samples[0] : 0.0f;
            const float right = __builtin_isfinite(samples[1]) ? samples[1] : 0.0f;
            // Scale first so two finite values near FLT_MAX cannot overflow an intermediate sum.
            const float mono = left * 0.5f + right * 0.5f;
            samples[0] = samples[1] = __builtin_isfinite(mono) ? mono : 0.0f;
            __builtin_memcpy(bytes, samples, sizeof(samples));
        }
    }
}

enum class MixOutcome : uint8_t { Inactive, Unsupported, RouteSkipped, InvalidBuffer, Mixed };
struct MixResult {
    MixOutcome outcome;
    size_t frames;
};

inline MixResult processBuffer(const Track& track, const AudioBuffer& buffer,
                               const Configuration& config, uint64_t nowMs) {
    if (!leaseValid(config, nowMs)) return {MixOutcome::Inactive, 0};
    if (!supported(track)) return {MixOutcome::Unsupported, skippedFrames(track, buffer)};
    if (!routeAllowed(track, config)) return {MixOutcome::RouteSkipped, skippedFrames(track, buffer)};
    size_t frames = 0;
    if (!validBuffer(buffer, stereoFrameBytes(track.format), &frames)) {
        return {MixOutcome::InvalidBuffer, 0};
    }
    mixStereo(buffer.raw, track.format, frames);
    return {MixOutcome::Mixed, frames};
}

struct Counters {
    uint64_t generation = 0;
    uint64_t mixedFrames = 0;
    uint64_t lastMixMs = 0;
    uint64_t unsupportedFrames = 0;
    uint64_t routeSkippedFrames = 0;
    uint64_t invalidBuffers = 0;

    void adoptGeneration(uint64_t current) {
        if (generation == current) return;
        generation = current;
        mixedFrames = lastMixMs = unsupportedFrames = routeSkippedFrames = invalidBuffers = 0;
    }
    void record(const MixResult& result, uint64_t nowMs) {
        switch (result.outcome) {
            case MixOutcome::Mixed: mixedFrames += result.frames; lastMixMs = nowMs; break;
            case MixOutcome::Unsupported: unsupportedFrames += result.frames; break;
            case MixOutcome::RouteSkipped: routeSkippedFrames += result.frames; break;
            case MixOutcome::InvalidBuffer: ++invalidBuffers; break;
            case MixOutcome::Inactive: break;
        }
    }
};

template <size_t Capacity>
struct TrackTable {
    Track tracks[Capacity]{};
    Track* find(void* key) {
        for (auto& track : tracks) if (track.key == key && key != nullptr) return &track;
        return nullptr;
    }
    void remove(void* key) {
        if (Track* track = find(key)) *track = Track{};
    }
    bool insert(const Track& value) {
        if (value.key == nullptr) return false;
        remove(value.key);
        for (auto& track : tracks) {
            if (track.key == nullptr) { track = value; return true; }
        }
        return false;
    }
    size_t count() const {
        size_t count = 0;
        for (const auto& track : tracks) if (track.key != nullptr) ++count;
        return count;
    }
};

}  // namespace melody::pcm
