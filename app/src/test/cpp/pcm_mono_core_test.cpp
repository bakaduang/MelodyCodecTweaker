#include "../../main/cpp/pcm_mono_core.h"
#include "../../main/cpp/audio_track_set_abi.h"

#if !defined(MELODY_HOST_FREESTANDING)
#include <stdio.h>
#else
extern "C" void* memset(void* destination, int value, size_t length) {
    auto* bytes = static_cast<volatile unsigned char*>(destination);
    for (size_t i = 0; i < length; ++i) bytes[i] = static_cast<unsigned char>(value);
    return destination;
}
extern "C" void* memcpy(void* destination, const void* source, size_t length) {
    auto* output = static_cast<volatile unsigned char*>(destination);
    const auto* input = static_cast<const volatile unsigned char*>(source);
    for (size_t i = 0; i < length; ++i) output[i] = input[i];
    return destination;
}
#endif

using namespace melody::pcm;

namespace {
unsigned g_checks = 0;
unsigned g_failures = 0;

void expect(bool condition) {
    ++g_checks;
    if (!condition) ++g_failures;
}

Track music(uint32_t format = kPcm16) {
    Track track;
    track.key = reinterpret_cast<void*>(0x12340);
    track.stream = kMusicStream;
    track.format = format;
    track.channelMask = kStereoMask;
    track.transfer = 0;  // DEFAULT is the normal Java/native construction path.
    track.hasSharedBuffer = false;
    return track;
}

Configuration permission() {
    Configuration config;
    config.enabled = true;
    config.expiresAtMs = 5000;
    config.generation = 7;
    config.deviceCount = 1;
    config.deviceIds[0] = 42;
    return config;
}

void pcm8() {
    // Silence is 128, not zero. Include independent left/right signals, both polarities,
    // maximum amplitudes, in-phase material, and the symmetric endpoint pair.
    unsigned char samples[] = {0xa5, 228, 128, 128, 228, 28, 128, 128, 28,
                              0, 0, 255, 255, 0, 255, 128, 128, 0x5a};
    const unsigned char expected[] = {178, 178, 78, 78, 0, 255, 128, 128};
    AudioBuffer buffer{800, 16, samples + 1, 9};
    const auto result = processBuffer(music(kPcm8), buffer, permission(), 4000);
    expect(result.outcome == MixOutcome::Mixed && result.frames == 8);
    for (size_t i = 0; i < 8; ++i) {
        expect(samples[1 + 2 * i] == expected[i]);
        expect(samples[2 + 2 * i] == expected[i]);
    }
    expect(samples[0] == 0xa5 && samples[17] == 0x5a);
}

void pcm16() {
    int16_t samples[] = {12345, 20000, 0, 0, 20000, -20000, 0, 0, -20000,
                         -32768, -32768, 32767, 32767, -32768, 32767,
                         30000, -30000, -1, 0, 12345};
    const int16_t expected[] = {10000, 10000, -10000, -10000, -32768, 32767, 0, 0, 0};
    // frameCount is deliberately smaller than the release byte count. Android consumes sizeBytes.
    AudioBuffer buffer{1, 9 * 4, samples + 1, 3};
    const auto result = processBuffer(music(), buffer, permission(), 4000);
    expect(result.outcome == MixOutcome::Mixed && result.frames == 9);
    for (size_t i = 0; i < 9; ++i) {
        expect(samples[1 + 2 * i] == expected[i]);
        expect(samples[2 + 2 * i] == expected[i]);
    }
    expect(samples[0] == 12345 && samples[19] == 12345);
}

void pcm32(uint32_t format) {
    int32_t samples[] = {13579, 2000000000, 0, 0, 2000000000, -2000000000, 0,
                        0, -2000000000, INT32_MIN, INT32_MIN, INT32_MAX, INT32_MAX,
                        INT32_MIN, INT32_MAX, 1234567890, -1234567890, -1, 0, 13579};
    const int32_t expected[] = {1000000000, 1000000000, -1000000000, -1000000000,
                               INT32_MIN, INT32_MAX, 0, 0, 0};
    AudioBuffer buffer{9, 9 * 8, samples + 1, 3};
    const auto result = processBuffer(music(format), buffer, permission(), 4000);
    expect(result.outcome == MixOutcome::Mixed && result.frames == 9);
    for (size_t i = 0; i < 9; ++i) {
        expect(samples[1 + 2 * i] == expected[i]);
        expect(samples[2 + 2 * i] == expected[i]);
    }
    expect(samples[0] == 13579 && samples[19] == 13579);
}

void pcm8In24() {
    int32_t samples[] = {8388607, 0, 0, 8388607, -8388608, 0, 0, -8388608,
                        -8388608, -8388608, 8388607, 8388607, -8388608, 8388607};
    const int32_t expected[] = {4194303, 4194303, -4194304, -4194304, -8388608, 8388607, 0};
    AudioBuffer buffer{7, sizeof(samples), samples, 0};
    const auto result = processBuffer(music(kPcm8_24), buffer, permission(), 4000);
    expect(result.outcome == MixOutcome::Mixed && result.frames == 7);
    for (size_t i = 0; i < 7; ++i) {
        expect(samples[2 * i] == expected[i]);
        expect(samples[2 * i + 1] == expected[i]);
    }
}

void packed24() {
    // Explicit little-endian representations, with an intentionally unaligned raw pointer.
    unsigned char bytes[] = {
        0xa5,
        0xfe, 0xff, 0x7f, 0, 0, 0,       // +8388606, 0 -> +4194303
        0, 0, 0, 0xfe, 0xff, 0x7f,       // 0, +8388606
        0, 0, 0x80, 0, 0, 0,            // -8388608, 0 -> -4194304
        0, 0, 0, 0, 0, 0x80,            // 0, -8388608
        0, 0, 0x80, 0, 0, 0x80,         // both min
        0xff, 0xff, 0x7f, 0xff, 0xff, 0x7f, // both max
        0, 0, 0x80, 0xff, 0xff, 0x7f,   // endpoint cancellation -> 0
        1, 0, 0, 0xff, 0xff, 0xff,       // +1, -1 -> 0
        0x5a,
    };
    const unsigned char expected[][3] = {
        {0xff, 0xff, 0x3f}, {0xff, 0xff, 0x3f}, {0, 0, 0xc0}, {0, 0, 0xc0},
        {0, 0, 0x80}, {0xff, 0xff, 0x7f}, {0, 0, 0}, {0, 0, 0},
    };
    AudioBuffer buffer{8, 48, bytes + 1, 1};
    const auto result = processBuffer(music(kPcm24Packed), buffer, permission(), 4000);
    expect(result.outcome == MixOutcome::Mixed && result.frames == 8);
    for (size_t i = 0; i < 8; ++i) for (size_t j = 0; j < 3; ++j) {
        expect(bytes[1 + i * 6 + j] == expected[i][j]);
        expect(bytes[4 + i * 6 + j] == expected[i][j]);
    }
    expect(bytes[0] == 0xa5 && bytes[49] == 0x5a);
}

float bitsFloat(uint32_t bits) {
    float value;
    __builtin_memcpy(&value, &bits, sizeof(value));
    return value;
}

void floats() {
    const float maximum = bitsFloat(0x7f7fffffU);
    const float nan = bitsFloat(0x7fc00000U);
    const float infinity = bitsFloat(0x7f800000U);
    float samples[] = {123.0f, 0.75f, 0.0f, 0.0f, 0.75f, -0.75f, 0.0f, 0.0f, -0.75f,
                       1.0f, 1.0f, -1.0f, -1.0f, 0.75f, -0.75f,
                       maximum, maximum, -maximum, -maximum,
                       nan, 0.5f, 0.5f, infinity, nan, -infinity, 456.0f};
    const float expected[] = {0.375f, 0.375f, -0.375f, -0.375f, 1.0f, -1.0f, 0.0f,
                             maximum, -maximum, 0.25f, 0.25f, 0.0f};
    AudioBuffer buffer{100, 12 * 8, samples + 1, 1};
    const auto result = processBuffer(music(kPcmFloat), buffer, permission(), 4000);
    expect(result.outcome == MixOutcome::Mixed && result.frames == 12);
    for (size_t i = 0; i < 12; ++i) {
        expect(samples[1 + 2 * i] == expected[i]);
        expect(samples[2 + 2 * i] == expected[i]);
        expect(__builtin_isfinite(samples[1 + 2 * i]));
    }
    expect(samples[0] == 123.0f && samples[25] == 456.0f);
}

void leasesAndRoutes() {
    int16_t samples[] = {1000, 0};
    const AudioBuffer buffer{1, sizeof(samples), samples, 0};
    Track track = music();
    Configuration config = permission();
    config.enabled = false;
    expect(processBuffer(track, buffer, config, 4000).outcome == MixOutcome::Inactive);
    config.enabled = true;
    expect(processBuffer(track, buffer, config, 5000).outcome == MixOutcome::Inactive);
    expect(processBuffer(track, buffer, config, 6000).outcome == MixOutcome::Inactive);
    expect(processBuffer(track, buffer, config, 0).outcome == MixOutcome::Inactive);
    expect(samples[0] == 1000 && samples[1] == 0);
    config.deviceCount = 0;
    expect(!leaseValid(config, 4000));
    config.deviceCount = 2;
    config.deviceIds[1] = 43;
    expect(!leaseValid(config, 4000));
    config = permission();
    track.selectedDeviceId = 99;
    expect(processBuffer(track, buffer, config, 4000).outcome == MixOutcome::RouteSkipped);
    track.selectedDeviceId = 42;
    expect(routeAllowed(track, config));
    track.selectedDeviceId = 0;
    track.routeEvidence = RouteEvidence::Unknown;
    expect(!routeAllowed(track, config));
    track.routeEvidence = RouteEvidence::Observed;
    track.routedCount = 0;
    expect(!routeAllowed(track, config));
    track.routedCount = 2;
    track.routedIds[0] = 42;
    track.routedIds[1] = 43;
    expect(!routeAllowed(track, config));
    track.routedCount = 1;
    track.routedIds[0] = 43;
    expect(!routeAllowed(track, config));
    // Reconnection to the target route can restore the same live AudioTrack.
    track.routedIds[0] = 42;
    expect(routeAllowed(track, config));
    expect(processBuffer(track, buffer, config, 4000).outcome == MixOutcome::Mixed);
    expect(samples[0] == 500 && samples[1] == 500);
}

void eligibilityAndBuffers() {
    int16_t samples[] = {1000, 0, 7777, -8888};
    AudioBuffer buffer{2, 4, samples, 0};
    Track track = music();
    const int32_t transfers[] = {0, 1, 2, 3, 5};
    for (int32_t transfer : transfers) { // all known streaming transfer modes
        track.transfer = transfer;
        expect(supported(track));
    }
    track.transfer = 4;
    expect(!supported(track));
    track.transfer = 9;
    expect(!supported(track));
    track.transfer = 0;
    track.hasSharedBuffer = true;
    expect(!supported(track));
    track.hasSharedBuffer = false;
    track.stream = 2;
    expect(processBuffer(track, buffer, permission(), 4000).outcome == MixOutcome::Unsupported);
    track.stream = 3;
    track.channelMask = 1;
    expect(!supported(track));
    track.channelMask = 3;
    track.format = 0x1b000000;
    expect(!supported(track));
    track.format = 1;
    track.flags = kCompressedOffloadFlag;
    expect(!supported(track));
    track.flags = kHwAvSyncFlag;
    expect(!supported(track));
    track.flags = 0x2001;  // The feedback's DIRECT PCM flags are allowed.
    expect(supported(track));
    size_t frames = 123;
    buffer.sizeBytes = 3;
    expect(!validBuffer(buffer, 4, &frames) && frames == 0);
    buffer.sizeBytes = 0;
    expect(!validBuffer(buffer, 4, &frames));
    buffer.sizeBytes = kMaxReleaseBytes + 4;
    expect(!validBuffer(buffer, 4, &frames));
    buffer.sizeBytes = 4;
    buffer.raw = nullptr;
    expect(!validBuffer(buffer, 4, &frames));
    buffer.raw = reinterpret_cast<void*>(8);
    expect(!validBuffer(buffer, 4, &frames));
    buffer.raw = reinterpret_cast<void*>(UINTPTR_MAX - 1);
    expect(!validBuffer(buffer, 4, &frames));
    buffer.raw = reinterpret_cast<unsigned char*>(samples) + 1;
    expect(!validBuffer(buffer, 4, &frames));
    buffer.raw = samples;
    expect(processBuffer(track, buffer, permission(), 4000).frames == 1);
    // The offered second frame was not released: neither channel in that tail may be touched.
    expect(samples[0] == 500 && samples[1] == 500 && samples[2] == 7777 && samples[3] == -8888);
}

void vectorView() {
    int32_t ids[] = {42, 43};
    uintptr_t view[] = {reinterpret_cast<uintptr_t>(ids), reinterpret_cast<uintptr_t>(ids + 1)};
    int32_t copied[kMaxDeviceIds]{};
    size_t count = 99;
    expect(readPlatformDeviceIds(view, copied, &count) && count == 1 && copied[0] == 42);
    view[1] = reinterpret_cast<uintptr_t>(ids + 2);
    expect(readPlatformDeviceIds(view, copied, &count) && count == 2 && copied[1] == 43);
    view[1] = view[0] - 4;
    expect(!readPlatformDeviceIds(view, copied, &count));
    view[1] = view[0] + 1;
    expect(!readPlatformDeviceIds(view, copied, &count));
    view[1] = view[0] + 17 * sizeof(int32_t);
    expect(!readPlatformDeviceIds(view, copied, &count));
    view[0] = view[1] = 0;
    expect(readPlatformDeviceIds(view, copied, &count) && count == 0);
    view[0] = 0;
    view[1] = 4;
    expect(!readPlatformDeviceIds(view, copied, &count));
    view[0] = reinterpret_cast<uintptr_t>(ids);
    view[1] = reinterpret_cast<uintptr_t>(ids + 1);
    ids[0] = -1;
    expect(!readPlatformDeviceIds(view, copied, &count));
    expect(!readPlatformDeviceIds(nullptr, copied, &count));
}

void lifetimeAndCounters() {
    TrackTable<2> table;
    Track first = music();
    first.serial = 1;
    expect(table.insert(first) && table.count() == 1);
    Track second = first;
    second.key = reinterpret_cast<void*>(0x56780);
    second.serial = 2;
    expect(table.insert(second) && table.count() == 2);
    Track third = first;
    third.key = reinterpret_cast<void*>(0x78900);
    expect(!table.insert(third) && table.count() == 2);
    table.remove(first.key);
    expect(table.find(first.key) == nullptr && table.count() == 1);
    first.stream = 2;
    first.serial = 3;
    expect(table.insert(first));
    expect(table.find(first.key)->serial == 3 && !supported(*table.find(first.key)));
    table.remove(second.key);
    table.remove(second.key);
    expect(table.count() == 1);
    Counters counters;
    counters.adoptGeneration(7);
    counters.record({MixOutcome::Mixed, 120}, 4000);
    counters.record({MixOutcome::Unsupported, 12}, 4001);
    counters.record({MixOutcome::RouteSkipped, 24}, 4002);
    counters.record({MixOutcome::InvalidBuffer, 0}, 4003);
    counters.record({MixOutcome::Inactive, 0}, 4004);
    expect(counters.generation == 7 && counters.mixedFrames == 120 && counters.lastMixMs == 4000);
    expect(counters.unsupportedFrames == 12 && counters.routeSkippedFrames == 24 && counters.invalidBuffers == 1);
    counters.adoptGeneration(7);
    expect(counters.mixedFrames == 120);
    counters.adoptGeneration(8);
    expect(counters.generation == 8 && counters.mixedFrames == 0 && counters.lastMixMs == 0);
    expect(counters.unsupportedFrames == 0 && counters.routeSkippedFrames == 0 && counters.invalidBuffers == 0);
}

bool sameText(const char* a, const char* b) {
    if (a == nullptr || b == nullptr) return a == b;
    while (*a != '\0' && *a == *b) { ++a; ++b; }
    return *a == *b;
}

void exactAudioTrackSetAbi() {
    // The export taken from the target phone's ELF, including its two named enums.
    constexpr const char* deviceExport =
            "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_t20audio_channel_mask_tm"
            "20audio_output_flags_tRKNS_2wpINS0_19IAudioTrackCallbackEEEiRKNS_2spINS_7IMemoryEEEb15audio_session_t"
            "NS0_13transfer_typeEPK20audio_offload_info_tRKNS_7content22AttributionSourceStateEPK18audio_attributes_tbfi";
    void* first = reinterpret_cast<void*>(0x1000);
    void* second = reinterpret_cast<void*>(0x2000);
    auto result = findAudioTrackSet([=](const char* name) {
        return sameText(name, deviceExport) ? first : nullptr;
    });
    expect(result.address == first && !result.ambiguous);
    expect(result.signature != nullptr && sameText(result.signature->label, "named_audio_enums"));
    result = findAudioTrackSet([=](const char* name) {
        return sameText(name, kAudioTrackSetSignatures[0].symbol) ? first : nullptr;
    });
    expect(result.address == first && result.signature == &kAudioTrackSetSignatures[0]);
    result = findAudioTrackSet([](const char*) -> void* { return nullptr; });
    expect(result.address == nullptr && result.signature == nullptr && !result.ambiguous);
    result = findAudioTrackSet([=](const char*) { return first; });
    expect(result.address == first && !result.ambiguous);  // actual symbol aliases are unambiguous
    result = findAudioTrackSet([=](const char* name) {
        return sameText(name, deviceExport) ? first : second;
    });
    expect(result.address == nullptr && result.signature == nullptr && result.ambiguous);
    // A truncated/prefix-only export cannot authorize a call through the complete ABI.
    result = findAudioTrackSet([=](const char* name) {
        return sameText(name, "_ZN7android10AudioTrack3setE") ? first : nullptr;
    });
    expect(result.address == nullptr && !result.ambiguous);
}

int runTests() {
    pcm8();
    pcm16();
    pcm32(kPcm32);
    pcm32(kPcm8_24);  // the 32-bit container must also be safe at signed32 arithmetic extremes
    pcm8In24();
    packed24();
    floats();
    leasesAndRoutes();
    eligibilityAndBuffers();
    vectorView();
    lifetimeAndCounters();
    exactAudioTrackSetAbi();
#if !defined(MELODY_HOST_FREESTANDING)
    printf("PCM mono core: %u checks, %u failures\n", g_checks, g_failures);
#endif
    return g_failures == 0 && g_checks >= 200 ? 0 : 1;
}
}  // namespace

#if defined(MELODY_HOST_FREESTANDING)
// The bundled Windows NDK clang can run these tests without installing a separate MSVC/MinGW CRT.
// This entry uses no runtime imports and returns its test status to Windows' initial thread.
extern "C" int pcm_mono_test_main() { return runTests(); }
#else
int main() { return runTests(); }
#endif
