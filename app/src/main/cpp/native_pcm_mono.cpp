#include <android/log.h>
#include <jni.h>
#include <stdint.h>

#include <atomic>
#include <mutex>
#include <time.h>
#include <type_traits>

#include "pcm_mono_core.h"
#include "pcm_mono_concurrency.h"

// Official LSPosed Native Hook ABI. Modern API 101 discovers this library through
// META-INF/xposed/native_init.list; Java must still load the library (including by absolute path).
// https://github.com/LSPosed/LSPosed/wiki/Native-Hook
using HookFunType = int (*)(void*, void*, void**);
using UnhookFunType = int (*)(void*);
using NativeOnModuleLoaded = void (*)(const char*, void*);
struct NativeAPIEntries {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
};

namespace {
constexpr const char* kLogTag = "MelodyPcmMono";
}

#if defined(__aarch64__)

#include "loaded_elf_symbols.h"
#include "audio_track_set_abi.h"
#include "audio_track_destructor_abi.h"

namespace {
using namespace melody::pcm;

static_assert(sizeof(void*) == 8 && sizeof(size_t) == 8 && sizeof(int32_t) == 4);
static_assert(sizeof(AudioBuffer) == 32 && alignof(AudioBuffer) == 8);
static_assert(offsetof(AudioBuffer, frameCount) == 0 && offsetof(AudioBuffer, sizeBytes) == 8
              && offsetof(AudioBuffer, raw) == 16 && offsetof(AudioBuffer, sequence) == 24);
static_assert(std::atomic<uint64_t>::is_always_lock_free
              && std::atomic<int32_t>::is_always_lock_free
              && std::atomic<bool>::is_always_lock_free);

constexpr const char* kReleaseSymbol = "_ZN7android10AudioTrack13releaseBufferEPKNS0_6BufferE";
constexpr const char* kStreamSymbol = "_ZNK7android10AudioTrack10streamTypeEv";
constexpr const char* kSampleRateSymbol = "_ZNK7android10AudioTrack13getSampleRateEv";
constexpr const char* kGetOutputSymbol = "_ZNK7android10AudioTrack9getOutputEv";
constexpr const char* kSetOutputSymbol = "_ZN7android10AudioTrack15setOutputDeviceEi";
constexpr const char* kDeviceUpdateSymbol =
        "_ZN7android10AudioTrack19onAudioDeviceUpdateEiRKNSt3__16vectorIiNS1_9allocatorIiEEEE";
constexpr const char* kDestructorSymbols[] = {
        "_ZN7android10AudioTrackD0Ev", "_ZN7android10AudioTrackD1Ev", "_ZN7android10AudioTrackD2Ev"};

// All C++ class and smart-pointer reference parameters remain opaque. The exact Android 16
// signatures in audio_track_set_abi.h share this arm64 ABI; no AudioTrack member offset is used.
using SetFn = int32_t (*)(void*, int32_t, uint32_t, uint32_t, uint32_t, size_t, uint32_t,
        const void*, int32_t, const void*, bool, int32_t, int32_t, const void*, const void*,
        const void*, bool, float, int32_t);
using ReleaseFn = void (*)(void*, const AudioBuffer*);
using SetOutputFn = int32_t (*)(void*, int32_t);
using DeviceUpdateFn = void (*)(void*, int32_t, const void*);
using StreamFn = int32_t (*)(const void*);
using SampleRateFn = uint32_t (*)(const void*);
using GetOutputFn = int32_t (*)(const void*);

struct HookSlot {
    void* target = nullptr;
    // LSPosed publishes the backup before activating the replacement. Keep the address stable
    // for that official void** contract, and acquire-load it in an already active replacement.
    void* backup = nullptr;
    bool installed = false;
};

HookSlot g_setHook, g_releaseHook, g_setOutputHook, g_deviceUpdateHook, g_destructorHooks[3];
std::atomic<StreamFn> g_stream{nullptr};
std::atomic<SampleRateFn> g_sampleRate{nullptr};
std::atomic<GetOutputFn> g_getOutput{nullptr};
std::atomic<HookFunType> g_hook{nullptr};
std::atomic<UnhookFunType> g_unhook{nullptr};
std::atomic<int> g_sdk{0};
std::atomic<bool> g_ready{false};
std::mutex g_installMutex;
bool g_hookAttemptFailed = false;  // guarded by g_installMutex; restart is required after a failure

std::mutex g_trackMutex;
TrackTable<256> g_tracks;
TrackRegistry<256> g_trackRegistry;
GenerationCounters<16> g_counters;
uint64_t g_trackSerial = 0;
uint64_t g_setLogCount = 0;
uint64_t g_routeLogCount = 0;

// Writers run on the Java control worker. Readers never acquire this mutex: an odd/changing
// revision immediately makes releaseBuffer pass through. Same-target lease extensions only
// publish expiry; they never invalidate a stable reader. Full publications use SC data/revision
// atomics so one order covers the generation, its counter token, and both revision checks.
std::mutex g_configWriterMutex;
std::atomic<uint64_t> g_configRevision{0};
std::atomic<bool> g_enabled{false};
std::atomic<uint64_t> g_expiryMs{0};
std::atomic<uint64_t> g_generation{0};
std::atomic<uint64_t> g_counterToken{0};
std::atomic<size_t> g_deviceCount{0};
std::atomic<int32_t> g_deviceIds[kMaxDeviceIds]{};

uint64_t boottimeMs() {
    timespec now{};
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0 || now.tv_sec < 0) return 0;
    return static_cast<uint64_t>(now.tv_sec) * 1000ULL + now.tv_nsec / 1000000ULL;
}

bool readConfiguration(Configuration* result, uint64_t* counterToken) {
    const uint64_t revision = g_configRevision.load(std::memory_order_seq_cst);
    if ((revision & 1U) != 0) return false;
    result->enabled = g_enabled.load(std::memory_order_seq_cst);
    result->expiresAtMs = g_expiryMs.load(std::memory_order_seq_cst);
    result->generation = g_generation.load(std::memory_order_seq_cst);
    *counterToken = g_counterToken.load(std::memory_order_seq_cst);
    result->deviceCount = g_deviceCount.load(std::memory_order_seq_cst);
    if (result->deviceCount > kMaxDeviceIds) return false;
    for (size_t i = 0; i < result->deviceCount; ++i) {
        result->deviceIds[i] = g_deviceIds[i].load(std::memory_order_seq_cst);
    }
    if (g_configRevision.load(std::memory_order_seq_cst) != revision) return false;
    result->revision = revision;
    return true;
}

template <typename Fn>
Fn original(const HookSlot& slot) {
    return reinterpret_cast<Fn>(__atomic_load_n(&slot.backup, __ATOMIC_ACQUIRE));
}

void publishTrack(const Track* track) {
    // Canonical metadata is used only by writers while holding g_trackMutex. Audio and JNI
    // telemetry read the independently published, entirely atomic registry instead.
    g_trackRegistry.publish(static_cast<size_t>(track - g_tracks.tracks), *track);
}

bool insertTrack(const Track& value) {
    Track* destination = g_tracks.find(value.key);
    if (destination == nullptr) {
        for (Track& track : g_tracks.tracks) {
            if (track.key == nullptr) { destination = &track; break; }
        }
    }
    if (destination == nullptr) return false;
    *destination = value;
    publishTrack(destination);
    return true;
}

void forgetTrack(void* self) {
    std::lock_guard<std::mutex> lock(g_trackMutex);
    if (Track* track = g_tracks.find(self)) {
        *track = Track{};
        publishTrack(track);
    }
}

void hookReleaseBuffer(void* self, const AudioBuffer* buffer) {
    const ReleaseFn release = original<ReleaseFn>(g_releaseHook);
    if (release == nullptr) return;  // a malformed framework backup never triggers a guessed call
    Configuration config;
    uint64_t counterToken = 0;
    if (g_ready.load(std::memory_order_acquire) && readConfiguration(&config, &counterToken)
            && leaseValid(config, boottimeMs())) {
        // This is the only hot hook. It never acquires a metadata lock, waits, allocates, invokes
        // JNI/Binder/logging, or calls back into AudioTrack. Unrelated track publications and
        // snapshots cannot make a stable matching track pass through because of contention.
        Track track;
        if (g_trackRegistry.read(self, &track)) {
            const uint64_t nowMs = boottimeMs();
            if (leaseValid(config, nowMs)
                    && g_configRevision.load(std::memory_order_seq_cst) == config.revision
                    && g_enabled.load(std::memory_order_acquire)
                    && g_ready.load(std::memory_order_acquire)) {
                auto counters = g_counters.pin(counterToken);
                if (buffer == nullptr) {
                    counters.record({MixOutcome::InvalidBuffer, 0}, nowMs);
                } else {
                    AudioBuffer view{};
                    __builtin_memcpy(&view, buffer, sizeof(view));
                    const MixResult mixed = processBuffer(track, view, config, nowMs);
                    counters.record(mixed, nowMs);
                }
            }
        }
    }
    // The framework owns the buffer lifetime. Forward its original structure without changing
    // size, frameCount, sequence, or the input memory supplied to AudioTrack::write().
    release(self, buffer);
}

int32_t hookSet(void* self, int32_t streamType, uint32_t sampleRate, uint32_t format,
        uint32_t channelMask, size_t frameCount, uint32_t flags, const void* callbackRef,
        int32_t notificationFrames, const void* sharedBufferRef, bool threadCanCallJava,
        int32_t sessionId, int32_t transferType, const void* offloadInfo,
        const void* attributionRef, const void* attributes, bool doNotReconnect,
        float maxRequiredSpeed, int32_t selectedDeviceId) {
    const SetFn set = original<SetFn>(g_setHook);
    if (set == nullptr) return -38;
    forgetTrack(self);
    const int32_t status = set(self, streamType, sampleRate, format, channelMask, frameCount,
            flags, callbackRef, notificationFrames, sharedBufferRef, threadCanCallJava, sessionId,
            transferType, offloadInfo, attributionRef, attributes, doNotReconnect,
            maxRequiredSpeed, selectedDeviceId);
    if (status != 0 || !g_ready.load(std::memory_order_acquire)) return status;
    const StreamFn stream = g_stream.load(std::memory_order_acquire);
    if (stream == nullptr) return status;

    Track track;
    track.key = self;
    track.stream = stream(self);  // final stream, including attribute-to-stream policy selection
    track.sampleRate = sampleRate;
    if (sampleRate == 0) {
        const SampleRateFn getRate = g_sampleRate.load(std::memory_order_acquire);
        if (getRate != nullptr) track.sampleRate = getRate(self);
    }
    track.format = format == 0 ? kPcm16 : format;  // AudioTrack::set's documented default
    track.channelMask = channelMask;
    track.flags = flags;
    if (attributes != nullptr) {
        // The public audio_attributes_t C ABI starts with content_type/usage/source/flags, each
        // 32 bits. AudioTrack::set maps AUDIO_FLAG_HW_AV_SYNC to AUDIO_OUTPUT_FLAG_HW_AV_SYNC;
        // exclude framed data even when the input output-flags argument did not contain it.
        uint32_t prefix[4]{};
        __builtin_memcpy(prefix, attributes, sizeof(prefix));
        if ((prefix[3] & 0x10U) != 0) track.flags |= kHwAvSyncFlag;
    }
    track.transfer = transferType;
    track.selectedDeviceId = selectedDeviceId;
    if (sharedBufferRef != nullptr) {
        // android::sp<T> consists of its one T* data member in this exact platform ABI.
        void* memory = nullptr;
        __builtin_memcpy(&memory, sharedBufferRef, sizeof(memory));
        track.hasSharedBuffer = memory != nullptr;
    }
    bool inserted = false;
    bool log = false;
    {
        std::lock_guard<std::mutex> lock(g_trackMutex);
        track.serial = ++g_trackSerial;
        inserted = insertTrack(track);
        const uint64_t number = ++g_setLogCount;
        log = number <= 32 || number % 64 == 0 || !inserted;
    }
    if (log) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                "track.set ptr=%p serial=%llu stream=%d->%d rate=%u format=%#x->%#x "
                "mask=%#x flags=%#x transfer=%d shared=%d selected=%d supported=%d tracked=%d",
                self, static_cast<unsigned long long>(track.serial), streamType, track.stream,
                track.sampleRate, format, track.format, channelMask, track.flags, transferType,
                track.hasSharedBuffer, selectedDeviceId, supported(track), inserted);
    }
    return status;
}

void hookDestructor0(void* self) {
    forgetTrack(self);
    if (const auto destroy = original<CompleteDestructorFn>(g_destructorHooks[0])) destroy(self);
}
void hookDestructor1(void* self) {
    forgetTrack(self);
    if (const auto destroy = original<CompleteDestructorFn>(g_destructorHooks[1])) destroy(self);
}
void hookDestructor2(void* self, const void* vtt) {
    forwardBaseDestructor(self, vtt, original<BaseDestructorFn>(g_destructorHooks[2]), forgetTrack);
}
static_assert(std::is_same_v<decltype(&hookDestructor2), BaseDestructorFn>);

int32_t hookSetOutputDevice(void* self, int32_t deviceId) {
    const auto setOutput = original<SetOutputFn>(g_setOutputHook);
    if (setOutput == nullptr) return -38;
    Track previous;
    uint64_t revision = 0;
    {
        std::lock_guard<std::mutex> lock(g_trackMutex);
        if (Track* track = g_tracks.find(self)) {
            previous = *track;
            revision = ++track->routeRevision;
            // Android updates mSelectedDeviceId even when an active DIRECT track later returns
            // INVALID_OPERATION. Revoke before calling it, including on the failure path.
            track->selectedDeviceId = deviceId;
            track->routeEvidence = RouteEvidence::Unknown;
            track->routedCount = 0;
            publishTrack(track);
        }
    }
    const int32_t status = setOutput(self, deviceId);
    if (previous.key != nullptr && status == 0 && previous.selectedDeviceId == deviceId) {
        std::lock_guard<std::mutex> lock(g_trackMutex);
        if (Track* track = g_tracks.find(self); track != nullptr && track->serial == previous.serial
                && track->routeRevision == revision) {
            // A successful request for the same selection did not change Android's route.
            track->routeEvidence = previous.routeEvidence;
            track->routedCount = previous.routedCount;
            __builtin_memcpy(track->routedIds, previous.routedIds, sizeof(track->routedIds));
            publishTrack(track);
        }
    }
    // A real selection change is restored only by a subsequent verified routing callback.
    // Existing unknown/multiple/non-target callbacks cannot be overridden by a Java lease refresh.
    return status;
}

void hookDeviceUpdate(void* self, int32_t audioIo, const void* deviceVectorRef) {
    const auto update = original<DeviceUpdateFn>(g_deviceUpdateHook);
    if (update == nullptr) return;
    const auto getOutput = g_getOutput.load(std::memory_order_acquire);
    if (!g_ready.load(std::memory_order_acquire) || getOutput == nullptr
            || audioIo <= 0 || getOutput(self) != audioIo) {
        update(self, audioIo, deviceVectorRef);
        return;
    }
    uint64_t serial = 0, revision = 0;
    {
        std::lock_guard<std::mutex> lock(g_trackMutex);
        if (Track* track = g_tracks.find(self)) {
            serial = track->serial;
            revision = ++track->routeRevision;
            track->routeEvidence = RouteEvidence::Unknown;
            track->routedCount = 0;
            publishTrack(track);
        }
    }
    int32_t ids[kMaxDeviceIds]{};
    size_t count = 0;
    const bool valid = readPlatformDeviceIds(deviceVectorRef, ids, &count);
    update(self, audioIo, deviceVectorRef);
    const bool sameOutput = getOutput(self) == audioIo;
    bool log = false;
    {
        std::lock_guard<std::mutex> lock(g_trackMutex);
        if (Track* track = g_tracks.find(self); track != nullptr && track->serial == serial
                && track->routeRevision == revision) {
            if (valid && sameOutput) {
                track->routeEvidence = RouteEvidence::Observed;
                track->routedCount = count;
                __builtin_memcpy(track->routedIds, ids, count * sizeof(int32_t));
                publishTrack(track);
            }
            const uint64_t number = ++g_routeLogCount;
            log = number <= 32 || number % 64 == 0;
        }
    }
    if (log) __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "track.route ptr=%p io=%d valid=%d sameOutput=%d count=%zu first=%d",
            self, audioIo, valid, sameOutput, count, count != 0 ? ids[0] : 0);
}

bool installHook(const char* symbol, void* target, void* replacement, HookSlot* slot) {
    slot->target = target;
    const int result = g_hook.load(std::memory_order_acquire)(target, replacement, &slot->backup);
    slot->installed = result == 0;
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "hook.install symbol=%s address=%p result=%d backup=%p", symbol, target, result, slot->backup);
    return result == 0 && slot->backup != nullptr;
}

const char* install() {
    std::lock_guard<std::mutex> lock(g_installMutex);
    if (g_sdk.load(std::memory_order_acquire) != 36) return "unsupported_platform";
    if (g_ready.load(std::memory_order_acquire)) return "ready";
    if (g_hookAttemptFailed) return "hook_failed";
    if (g_hook.load(std::memory_order_acquire) == nullptr
            || g_unhook.load(std::memory_order_acquire) == nullptr) return "native_api_unavailable";

    melody::elf::LoadedImage image;
    if (!melody::elf::findAudioClient(&image)) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                "hook.symbol_unavailable reason=loaded_system_libaudioclient_not_unique_or_invalid");
        return "symbol_unavailable";
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "hook.library path=%s base=%p build_id=%s",
            image.path, reinterpret_cast<void*>(image.base), image.buildId[0] ? image.buildId : "unavailable");

    const auto set = findAudioTrackSet([&image](const char* symbol) {
        return image.findFunction(symbol);
    });
    if (set.ambiguous) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                "hook.symbol_unavailable reason=distinct_audio_track_set_overloads");
        return "symbol_ambiguous_set";
    }
    if (set.address == nullptr) {
        for (const auto& signature : kAudioTrackSetSignatures) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                    "hook.symbol_unavailable role=audio_track_set abi=%s symbol=%s",
                    signature.label, signature.symbol);
        }
        return "symbol_unavailable_set";
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "hook.abi audio_track_set=%s symbol=%s",
            set.signature->label, set.signature->symbol);

    const char* names[] = {set.signature->symbol, kReleaseSymbol, kSetOutputSymbol, kDeviceUpdateSymbol,
            kDestructorSymbols[0], kDestructorSymbols[1], kDestructorSymbols[2],
            kStreamSymbol, kGetOutputSymbol};
    void* addresses[sizeof(names) / sizeof(names[0])]{};
    bool available = true;
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
        addresses[i] = i == 0 ? set.address : image.findFunction(names[i]);
        if (addresses[i] == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "hook.symbol_unavailable symbol=%s", names[i]);
            available = false;
        }
    }
    if (!available) return "symbol_unavailable";
    if (!baseDestructorIsDistinct(addresses[4], addresses[5], addresses[6])) {
        // A D2 wrapper has a different implicit parameter list from D0/D1. Never replace
        // one with the other's wrapper merely because their exported addresses alias.
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                "hook.symbol_unavailable reason=base_and_complete_destructor_alias");
        return "symbol_ambiguous_destructor";
    }
    // Only destructor aliases may intentionally share a patch address.
    for (size_t i = 0; i < 7; ++i) for (size_t j = 0; j < i; ++j) {
        if (addresses[i] == addresses[j] && !(i >= 4 && j >= 4)) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "hook.symbol_unavailable reason=unexpected_alias");
            return "symbol_unavailable";
        }
    }
    g_stream.store(reinterpret_cast<StreamFn>(addresses[7]), std::memory_order_release);
    g_getOutput.store(reinterpret_cast<GetOutputFn>(addresses[8]), std::memory_order_release);
    g_sampleRate.store(reinterpret_cast<SampleRateFn>(image.findFunction(kSampleRateSymbol)),
                       std::memory_order_release);

    struct Plan { const char* name; void* address; void* replacement; HookSlot* slot; };
    Plan plan[] = {
            {kSetOutputSymbol, addresses[2], reinterpret_cast<void*>(hookSetOutputDevice), &g_setOutputHook},
            {kDeviceUpdateSymbol, addresses[3], reinterpret_cast<void*>(hookDeviceUpdate), &g_deviceUpdateHook},
            {kDestructorSymbols[0], addresses[4], reinterpret_cast<void*>(hookDestructor0), &g_destructorHooks[0]},
            {kDestructorSymbols[1], addresses[5], reinterpret_cast<void*>(hookDestructor1), &g_destructorHooks[1]},
            {kDestructorSymbols[2], addresses[6], reinterpret_cast<void*>(hookDestructor2), &g_destructorHooks[2]},
            {set.signature->symbol, addresses[0], reinterpret_cast<void*>(hookSet), &g_setHook},
            {kReleaseSymbol, addresses[1], reinterpret_cast<void*>(hookReleaseBuffer), &g_releaseHook},
    };
    bool success = true;
    for (size_t i = 0; i < sizeof(plan) / sizeof(plan[0]); ++i) {
        bool alias = false;
        for (size_t j = 0; j < i; ++j) if (plan[i].address == plan[j].address) alias = true;
        if (alias) {
            __android_log_print(ANDROID_LOG_INFO, kLogTag, "hook.alias symbol=%s address=%p", plan[i].name, plan[i].address);
            continue;
        }
        if (!installHook(plan[i].name, plan[i].address, plan[i].replacement, plan[i].slot)) {
            success = false;
            break;
        }
    }
    if (!success) {
        g_hookAttemptFailed = true;
        for (size_t i = sizeof(plan) / sizeof(plan[0]); i-- > 0;) {
            if (!plan[i].slot->installed) continue;
            const int result = g_unhook.load(std::memory_order_acquire)(plan[i].slot->target);
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "hook.rollback symbol=%s result=%d", plan[i].name, result);
            if (result == 0) plan[i].slot->installed = false;
            // Keep the backup address for any in-flight pass-through wrapper; the framework owns
            // trampoline lifetime. Failed rollback also remains pass-through because ready=false.
        }
        return "hook_failed";
    }
    g_ready.store(true, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "hook.ready sdk=36 abi=arm64 formats=pcm8,pcm16,pcm24packed,pcm8_24,pcm32,float32 leaseMaxMs=%llu",
            static_cast<unsigned long long>(kMaxLeaseMs));
    return "ready";
}

void onLibraryLoaded(const char* name, void*) {
    const char* basename = name == nullptr ? nullptr : strrchr(name, '/');
    basename = basename == nullptr ? name : basename + 1;
    if (g_sdk.load(std::memory_order_acquire) == 36 && !g_ready.load(std::memory_order_acquire)
            && basename != nullptr && strcmp(basename, "libaudioclient.so") == 0) {
        const char* result = install();
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "hook.late_library result=%s", result);
    }
}

}  // namespace

extern "C" __attribute__((visibility("default"), used))
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
    // The current official implementation advertises version 2 with this same three-field ABI;
    // version 1 is the original published ABI. Do not reject modern LSPosed by assuming only v1.
    // https://github.com/LSPosed/LSPosed/blob/master/core/src/main/jni/src/native_api.cpp
    if (entries == nullptr || (entries->version != 1 && entries->version != 2) || entries->hook_func == nullptr
            || entries->unhook_func == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "hook.native_api_unavailable version=%u",
                            entries == nullptr ? 0 : entries->version);
        return nullptr;
    }
    g_hook.store(entries->hook_func, std::memory_order_release);
    g_unhook.store(entries->unhook_func, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "hook.native_api version=%u", entries->version);
    if (g_sdk.load(std::memory_order_acquire) == 36) (void) install();
    return onLibraryLoaded;
}

extern "C" JNIEXPORT jstring JNICALL
Java_xyz_melodylsp_codec_mono_NativePcmMono_nativeInstall(JNIEnv* env, jclass, jint sdk) {
    g_sdk.store(sdk, std::memory_order_release);
    if (sdk != 36) {
        g_ready.store(false, std::memory_order_release);
        return env->NewStringUTF("unsupported_platform");
    }
    return env->NewStringUTF(install());
}

extern "C" JNIEXPORT void JNICALL
Java_xyz_melodylsp_codec_mono_NativePcmMono_nativeConfigure(
        JNIEnv* env, jclass, jboolean enabled, jlong expiresAtMs, jlong generation, jintArray deviceIds) {
    int32_t ids[kMaxDeviceIds]{};
    size_t count = 0;
    bool valid = generation >= 0;
    if (deviceIds != nullptr) {
        const jsize length = env->GetArrayLength(deviceIds);
        if (length < 0 || length > static_cast<jsize>(kMaxDeviceIds)) {
            valid = false;
        } else if (length != 0) {
            env->GetIntArrayRegion(deviceIds, 0, length, ids);
            if (env->ExceptionCheck()) {
                g_enabled.store(false, std::memory_order_release);
                return;  // pending JNI exception belongs to the Java caller
            }
            for (jsize i = 0; i < length; ++i) {
                const int32_t id = ids[i];
                if (id <= 0) { valid = false; break; }
                bool duplicate = false;
                for (size_t j = 0; j < count; ++j) if (ids[j] == id) duplicate = true;
                if (!duplicate) ids[count++] = id;
            }
        }
    }
    const uint64_t now = boottimeMs();
    uint64_t expiry = expiresAtMs > 0 ? static_cast<uint64_t>(expiresAtMs) : 0;
    if (now != 0 && expiry > now + kMaxLeaseMs) expiry = now + kMaxLeaseMs;
    valid = valid && count == 1 && now != 0 && expiry > now;
    std::lock_guard<std::mutex> writer(g_configWriterMutex);
    const uint64_t currentGeneration = generation < 0 ? 0 : static_cast<uint64_t>(generation);
    const uint64_t previousExpiry = g_expiryMs.load(std::memory_order_seq_cst);
    if (enabled == JNI_TRUE && valid && g_enabled.load(std::memory_order_seq_cst)
            && g_generation.load(std::memory_order_seq_cst) == currentGeneration
            && g_deviceCount.load(std::memory_order_seq_cst) == count
            && g_deviceIds[0].load(std::memory_order_seq_cst) == ids[0]
            && expiry >= previousExpiry) {
        // A reader may safely use either expiry during an extension. In particular, the
        // one-second control heartbeat must not revoke valid PCM permission for one buffer.
        // All other state, including the current generation's counter bucket, stays unchanged.
        if (expiry > previousExpiry) g_expiryMs.store(expiry, std::memory_order_seq_cst);
        return;
    }
    g_configRevision.fetch_add(1, std::memory_order_seq_cst);
    g_enabled.store(false, std::memory_order_seq_cst);
    g_generation.store(currentGeneration, std::memory_order_seq_cst);
    g_counterToken.store(g_counters.adoptGeneration(currentGeneration), std::memory_order_seq_cst);
    g_expiryMs.store(valid ? expiry : 0, std::memory_order_seq_cst);
    g_deviceCount.store(valid ? count : 0, std::memory_order_seq_cst);
    for (size_t i = 0; i < kMaxDeviceIds; ++i) {
        g_deviceIds[i].store(valid && i < count ? ids[i] : 0, std::memory_order_seq_cst);
    }
    g_enabled.store(enabled == JNI_TRUE && valid, std::memory_order_seq_cst);
    g_configRevision.fetch_add(1, std::memory_order_seq_cst);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_xyz_melodylsp_codec_mono_NativePcmMono_nativeSnapshot(JNIEnv* env, jclass) {
    jlong values[9]{};
    Configuration config;
    uint64_t counterToken = 0;
    const bool consistent = readConfiguration(&config, &counterToken);
    const uint64_t generation = consistent ? config.generation : g_generation.load(std::memory_order_acquire);
    Counters counters;
    counters.generation = generation;
    if (consistent) {
        auto pin = g_counters.pin(counterToken);
        (void) pin.snapshot(&counters);
    }
    const bool ready = g_ready.load(std::memory_order_acquire);
    const bool active = consistent && ready && leaseValid(config, boottimeMs());
    size_t eligible = 0, tracked = 0;
    for (size_t i = 0; i < g_trackRegistry.capacity; ++i) {
        Track track;
        if (g_trackRegistry.readAt(i, &track)) {
            ++tracked;
            if (active && supported(track) && routeAllowed(track, config)) ++eligible;
        }
    }
    values[0] = ready ? 1 : 0;
    values[1] = static_cast<jlong>(generation);
    values[2] = static_cast<jlong>(counters.mixedFrames);
    values[3] = static_cast<jlong>(counters.lastMixMs);
    values[4] = static_cast<jlong>(eligible);
    values[5] = static_cast<jlong>(tracked);
    values[6] = static_cast<jlong>(counters.unsupportedFrames);
    values[7] = static_cast<jlong>(counters.routeSkippedFrames);
    values[8] = static_cast<jlong>(counters.invalidBuffers);
    jlongArray snapshot = env->NewLongArray(9);
    if (snapshot != nullptr) env->SetLongArrayRegion(snapshot, 0, 9, values);
    return snapshot;
}

#else

extern "C" __attribute__((visibility("default"), used))
NativeOnModuleLoaded native_init(const NativeAPIEntries*) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "hook.unsupported_platform abi=not_arm64");
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_xyz_melodylsp_codec_mono_NativePcmMono_nativeInstall(JNIEnv* env, jclass, jint) {
    return env->NewStringUTF("unsupported_platform");
}

extern "C" JNIEXPORT void JNICALL
Java_xyz_melodylsp_codec_mono_NativePcmMono_nativeConfigure(
        JNIEnv*, jclass, jboolean, jlong, jlong, jintArray) {}

extern "C" JNIEXPORT jlongArray JNICALL
Java_xyz_melodylsp_codec_mono_NativePcmMono_nativeSnapshot(JNIEnv* env, jclass) {
    const jlong values[9]{};
    jlongArray snapshot = env->NewLongArray(9);
    if (snapshot != nullptr) env->SetLongArrayRegion(snapshot, 0, 9, values);
    return snapshot;
}

#endif
