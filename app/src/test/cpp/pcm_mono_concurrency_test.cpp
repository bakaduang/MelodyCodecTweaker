#include "../../main/cpp/pcm_mono_concurrency.h"

#if defined(MELODY_HOST_FREESTANDING)
// Only the test harness uses OS threads. The production registry/counters need no runtime.
#define TEST_CALL __stdcall
extern "C" {
__declspec(dllimport) void* __stdcall CreateThread(void*, size_t,
        unsigned long (__stdcall*)(void*), void*, unsigned long, unsigned long*);
__declspec(dllimport) unsigned long __stdcall WaitForSingleObject(void*, unsigned long);
__declspec(dllimport) int __stdcall CloseHandle(void*);
__declspec(dllimport) int __stdcall SwitchToThread();
__declspec(dllimport) void* __stdcall GetStdHandle(unsigned long);
__declspec(dllimport) int __stdcall WriteFile(void*, const void*, unsigned long, unsigned long*, void*);
void* memset(void* destination, int value, size_t length) {
    auto* bytes = static_cast<volatile unsigned char*>(destination);
    for (size_t i = 0; i < length; ++i) bytes[i] = static_cast<unsigned char>(value);
    return destination;
}
void* memcpy(void* destination, const void* source, size_t length) {
    auto* output = static_cast<volatile unsigned char*>(destination);
    const auto* input = static_cast<const volatile unsigned char*>(source);
    for (size_t i = 0; i < length; ++i) output[i] = input[i];
    return destination;
}
}
#else
#define TEST_CALL
#include <stdio.h>
#include <thread>
#endif

using namespace melody::pcm;

namespace {
AtomicValue<uint64_t> g_checks;
AtomicValue<uint64_t> g_failures;

void expect(bool condition) {
    g_checks.fetchAdd(1, __ATOMIC_RELAXED);
    if (!condition) g_failures.fetchAdd(1, __ATOMIC_RELAXED);
}

void yieldThread() {
#if defined(MELODY_HOST_FREESTANDING)
    (void) SwitchToThread();
#else
    std::this_thread::yield();
#endif
}

using Worker = unsigned long (TEST_CALL*)(void*);
class TestThread {
public:
    bool start(Worker entry, void* argument) {
#if defined(MELODY_HOST_FREESTANDING)
        handle_ = CreateThread(nullptr, 0, entry, argument, 0, nullptr);
        return handle_ != nullptr;
#else
        thread_ = std::thread([entry, argument] { entry(argument); });
        return true;
#endif
    }
    void join() {
#if defined(MELODY_HOST_FREESTANDING)
        if (handle_ != nullptr) {
            expect(WaitForSingleObject(handle_, 60000) == 0);
            expect(CloseHandle(handle_) != 0);
            handle_ = nullptr;
        }
#else
        if (thread_.joinable()) thread_.join();
#endif
    }
private:
#if defined(MELODY_HOST_FREESTANDING)
    void* handle_ = nullptr;
#else
    std::thread thread_;
#endif
};

Track music(uintptr_t key = 0x12340) {
    Track track;
    track.key = reinterpret_cast<void*>(key);
    track.serial = key;
    track.stream = kMusicStream;
    track.sampleRate = 96000;
    track.format = kPcm16;
    track.channelMask = kStereoMask;
    track.transfer = 0;
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

struct ConcurrentCase {
    TrackRegistry<256> tracks;
    GenerationCounters<16> counters;
    AtomicValue<bool> start;
    AtomicValue<unsigned> finished;
    AtomicValue<unsigned> snapshots;
    AtomicValue<unsigned> updates;
    uint64_t token = 0;
    static constexpr unsigned iterations = 100000;
};
struct AudioArgument { ConcurrentCase* test; uintptr_t key; };

unsigned long TEST_CALL audioWorker(void* opaque) {
    auto* argument = static_cast<AudioArgument*>(opaque);
    ConcurrentCase& test = *argument->test;
    while (!test.start.load(__ATOMIC_ACQUIRE)) yieldThread();
    for (unsigned i = 0; i < ConcurrentCase::iterations; ++i) {
        Track track;
        const bool found = test.tracks.read(reinterpret_cast<void*>(argument->key), &track);
        expect(found);
        int16_t samples[] = {1200, -400, 200, 1600};
        if (found) {
            auto counters = test.counters.pin(test.token);
            expect(static_cast<bool>(counters));
            const MixResult mixed = processBuffer(track, {2, sizeof(samples), samples, 0}, permission(), 4000);
            counters.record(mixed, 4000);
            expect(mixed.outcome == MixOutcome::Mixed && mixed.frames == 2);
        }
        expect(samples[0] == 400 && samples[1] == 400 && samples[2] == 900 && samples[3] == 900);
    }
    test.finished.fetchAdd(1, __ATOMIC_RELEASE);
    return 0;
}

unsigned long TEST_CALL snapshotWorker(void* opaque) {
    auto& test = *static_cast<ConcurrentCase*>(opaque);
    while (!test.start.load(__ATOMIC_ACQUIRE)) yieldThread();
    uint64_t previousFrames = 0;
    do {
        Track track;
        for (size_t i = 0; i < test.tracks.capacity; ++i) (void) test.tracks.readAt(i, &track);
        Counters snapshot;
        auto counters = test.counters.pin(test.token);
        expect(counters.snapshot(&snapshot));
        expect(snapshot.generation == 7 && snapshot.mixedFrames >= previousFrames);
        previousFrames = snapshot.mixedFrames;
        test.snapshots.fetchAdd(1, __ATOMIC_RELAXED);
    } while (test.finished.load(__ATOMIC_ACQUIRE) < 2);
    return 0;
}

unsigned long TEST_CALL unrelatedWriter(void* opaque) {
    auto& test = *static_cast<ConcurrentCase*>(opaque);
    while (!test.start.load(__ATOMIC_ACQUIRE)) yieldThread();
    Track track = music(0x9abcd0);
    do {
        ++track.routeRevision;
        track.routeEvidence = (track.routeRevision & 1U) != 0 ? RouteEvidence::Unknown : RouteEvidence::Observed;
        track.routedCount = (track.routeRevision & 1U) != 0 ? 0 : 1;
        track.routedIds[0] = 42;
        test.tracks.publish(2, track);
        test.updates.fetchAdd(1, __ATOMIC_RELAXED);
    } while (test.finished.load(__ATOMIC_ACQUIRE) < 2);
    return 0;
}

void stableTracks(bool snapshots, bool unrelatedUpdates) {
    // Static storage also permits the freestanding executable to use its small default stack.
    static ConcurrentCase test;
    test.start.store(false);
    test.finished.store(0);
    test.snapshots.store(0);
    test.updates.store(0);
    test.tracks.publish(0, music());
    test.tracks.publish(1, music(0x56780));
    test.tracks.publish(2, Track{});
    // Recreate a different generation between cases, then return to 7; counts must start empty.
    (void) test.counters.adoptGeneration(8);
    test.token = test.counters.adoptGeneration(7);
    AudioArgument first{&test, 0x12340}, second{&test, 0x56780};
    TestThread a, b, telemetry, writer;
    expect(a.start(audioWorker, &first));
    expect(b.start(audioWorker, &second));
    if (snapshots) expect(telemetry.start(snapshotWorker, &test));
    if (unrelatedUpdates) expect(writer.start(unrelatedWriter, &test));
    test.start.store(true, __ATOMIC_RELEASE);
    a.join(); b.join(); telemetry.join(); writer.join();
    Counters result;
    auto counters = test.counters.pin(test.token);
    expect(counters.snapshot(&result));
    expect(result.generation == 7 && result.mixedFrames == 4ULL * ConcurrentCase::iterations);
    expect(result.lastMixMs == 4000 && result.invalidBuffers == 0 && result.routeSkippedFrames == 0);
    if (snapshots) expect(test.snapshots.load() > 0);
    if (unrelatedUpdates) expect(test.updates.load() > 0);
}

struct PublicationCase {
    TrackRegistry<1> tracks;
    AtomicValue<bool> start;
    AtomicValue<bool> finished;
};

Track patternedTrack(bool second) {
    Track track = music();
    const uint32_t pattern = second ? 200 : 100;
    track.serial = pattern;
    track.routeRevision = pattern;
    track.sampleRate = pattern;
    track.format = pattern;
    track.channelMask = pattern;
    track.flags = pattern;
    track.selectedDeviceId = static_cast<int32_t>(pattern);
    track.routeEvidence = RouteEvidence::Observed;
    track.routedCount = second ? kMaxDeviceIds : 1;
    for (size_t i = 0; i < kMaxDeviceIds; ++i) track.routedIds[i] = static_cast<int32_t>(pattern + i);
    return track;
}

unsigned long TEST_CALL relatedWriter(void* opaque) {
    auto& test = *static_cast<PublicationCase*>(opaque);
    while (!test.start.load(__ATOMIC_ACQUIRE)) yieldThread();
    for (unsigned i = 0; i < 100000; ++i) test.tracks.publish(0, patternedTrack((i & 1U) != 0));
    test.finished.store(true, __ATOMIC_RELEASE);
    return 0;
}

void coherentPublication() {
    PublicationCase test;
    test.tracks.publish(0, patternedTrack(false));
    TestThread writer;
    expect(writer.start(relatedWriter, &test));
    test.start.store(true, __ATOMIC_RELEASE);
    do {
        Track track;
        if (test.tracks.read(music().key, &track)) {
            const uint64_t pattern = track.serial;
            expect(pattern == 100 || pattern == 200);
            expect(track.routeRevision == pattern && track.sampleRate == pattern && track.format == pattern
                    && track.channelMask == pattern && track.flags == pattern
                    && static_cast<uint64_t>(track.selectedDeviceId) == pattern);
            expect(track.routedCount == (pattern == 100 ? 1 : kMaxDeviceIds));
            for (size_t i = 0; i < track.routedCount; ++i) expect(track.routedIds[i] == static_cast<int32_t>(pattern + i));
        }
    } while (!test.finished.load(__ATOMIC_ACQUIRE));
    writer.join();
    Track track;
    expect(test.tracks.read(music().key, &track) && track.serial == 200);
    test.tracks.publish(0, Track{});
    expect(!test.tracks.read(music().key, &track));
    test.tracks.publish(0, patternedTrack(false));
    expect(test.tracks.read(music().key, &track) && track.serial == 100);
}

struct GenerationCase {
    GenerationCounters<2> counters;
    uint64_t oldToken = 0;
    AtomicValue<bool> pinned;
    AtomicValue<bool> finishOld;
};

unsigned long TEST_CALL delayedOldAudio(void* opaque) {
    auto& test = *static_cast<GenerationCase*>(opaque);
    auto counters = test.counters.pin(test.oldToken);
    expect(static_cast<bool>(counters));
    test.pinned.store(true, __ATOMIC_RELEASE);
    while (!test.finishOld.load(__ATOMIC_ACQUIRE)) yieldThread();
    counters.record({MixOutcome::Mixed, 999}, 99999);
    counters.record({MixOutcome::Unsupported, 888}, 99999);
    counters.record({MixOutcome::RouteSkipped, 777}, 99999);
    counters.record({MixOutcome::InvalidBuffer, 0}, 99999);
    return 0;
}

void generationOwnership() {
    GenerationCase test;
    test.oldToken = test.counters.adoptGeneration(7);
    TestThread oldAudio;
    expect(oldAudio.start(delayedOldAudio, &test));
    while (!test.pinned.load(__ATOMIC_ACQUIRE)) yieldThread();
    const uint64_t currentToken = test.counters.adoptGeneration(8);
    expect(currentToken != 0 && currentToken != test.oldToken);
    auto current = test.counters.pin(currentToken);
    current.record({MixOutcome::Mixed, 10}, 5000);
    expect(test.counters.adoptGeneration(8) == currentToken);
    test.finishOld.store(true, __ATOMIC_RELEASE);
    oldAudio.join();
    Counters result;
    expect(current.snapshot(&result));
    expect(result.generation == 8 && result.mixedFrames == 10 && result.lastMixMs == 5000);
    expect(result.unsupportedFrames == 0 && result.routeSkippedFrames == 0 && result.invalidBuffers == 0);
    const uint64_t repeated = test.counters.adoptGeneration(7);
    expect(repeated != 0 && repeated != test.oldToken);
    expect(!test.counters.pin(test.oldToken));
    auto reused = test.counters.pin(repeated);
    reused.record({MixOutcome::Mixed, 5}, 6000);
    expect(reused.snapshot(&result) && result.generation == 7 && result.mixedFrames == 5 && result.lastMixMs == 6000);

    GenerationCounters<1> exhausted;
    const uint64_t old = exhausted.adoptGeneration(1);
    {
        auto pinned = exhausted.pin(old);
        expect(static_cast<bool>(pinned));
        expect(exhausted.adoptGeneration(2) == 0);
        auto unavailable = exhausted.pin(0);
        int16_t samples[] = {1000, 0};
        const MixResult mixed = processBuffer(music(), {1, sizeof(samples), samples, 0}, permission(), 4000);
        unavailable.record(mixed, 4000);
        expect(samples[0] == 500 && samples[1] == 500 && mixed.outcome == MixOutcome::Mixed);
    }
    const uint64_t fresh = exhausted.adoptGeneration(2);
    expect(fresh != 0 && fresh != old && !exhausted.pin(old));
    auto freshPin = exhausted.pin(fresh);
    expect(freshPin.snapshot(&result) && result.generation == 2 && result.mixedFrames == 0 && result.lastMixMs == 0);
}

void rejectedReaderExit() {
    CounterReferences references;
    expect(references.tryBeginWrite());
    expect(!references.beginRead());  // reader has seen the writer but has not backed out yet
    references.endWrite();
    expect(!references.tryBeginWrite());  // the pending reader reference must still exist
    expect(references.beginRead());       // a newer reader can use the finished publication
    references.endRead();                 // the rejected old reader backs out after publication
    expect(!references.tryBeginWrite());  // the newer reader is still protected
    references.endRead();
    expect(references.tryBeginWrite());   // no underflow and no leaked reference
    references.endWrite();
    expect(references.beginRead());
    references.endRead();
    expect(references.tryBeginWrite());
    references.endWrite();
}

struct TurnoverCase {
    GenerationCounters<4> counters;
    AtomicValue<uint64_t> token;
    AtomicValue<bool> start;
    AtomicValue<bool> finished;
    AtomicValue<unsigned> records;
};

unsigned long TEST_CALL turnoverReader(void* opaque) {
    auto& test = *static_cast<TurnoverCase*>(opaque);
    while (!test.start.load(__ATOMIC_ACQUIRE)) yieldThread();
    do {
        auto pin = test.counters.pin(test.token.load(__ATOMIC_ACQUIRE));
        Counters snapshot;
        if (pin.snapshot(&snapshot)) {
            const uint64_t generation = snapshot.generation;
            // Every timestamp identifies its generation, including late records after turnover.
            pin.record({MixOutcome::Mixed, 1}, generation);
            expect(pin.snapshot(&snapshot));
            expect(snapshot.generation == generation && snapshot.lastMixMs == generation);
            test.records.fetchAdd(1, __ATOMIC_RELAXED);
        }
    } while (!test.finished.load(__ATOMIC_ACQUIRE));
    return 0;
}

void concurrentTurnover() {
    TurnoverCase test;
    test.token.store(test.counters.adoptGeneration(1), __ATOMIC_RELEASE);
    TestThread first, second;
    expect(first.start(turnoverReader, &test));
    expect(second.start(turnoverReader, &test));
    test.start.store(true, __ATOMIC_RELEASE);
    for (uint64_t generation = 2; generation <= 100000; ++generation) {
        test.token.store(test.counters.adoptGeneration(generation), __ATOMIC_RELEASE);
    }
    test.finished.store(true, __ATOMIC_RELEASE);
    first.join(); second.join();
    expect(test.records.load() > 100);
    const uint64_t finalToken = test.counters.adoptGeneration(100001);
    auto finalPin = test.counters.pin(finalToken);
    finalPin.record({MixOutcome::Mixed, 42}, 100001);
    Counters snapshot;
    expect(finalPin.snapshot(&snapshot) && snapshot.generation == 100001
            && snapshot.mixedFrames == 42 && snapshot.lastMixMs == 100001);
}

#if defined(MELODY_HOST_FREESTANDING)
void writeText(const char* text) {
    unsigned long length = 0, written = 0;
    while (text[length] != '\0') ++length;
    (void) WriteFile(GetStdHandle(static_cast<unsigned long>(-11)), text, length, &written, nullptr);
}
void writeNumber(uint64_t value) {
    char digits[21]{};
    size_t cursor = sizeof(digits) - 1;
    do { digits[--cursor] = static_cast<char>('0' + value % 10); value /= 10; } while (value != 0);
    writeText(digits + cursor);
}
#endif

int runTests() {
    stableTracks(false, false);
    stableTracks(true, false);
    stableTracks(true, true);
    coherentPublication();
    generationOwnership();
    rejectedReaderExit();
    concurrentTurnover();
    const uint64_t checks = g_checks.load(), failures = g_failures.load();
#if defined(MELODY_HOST_FREESTANDING)
    writeText("PCM mono concurrency: "); writeNumber(checks);
    writeText(" checks, "); writeNumber(failures); writeText(" failures\n");
#else
    printf("PCM mono concurrency: %llu checks, %llu failures\n",
            static_cast<unsigned long long>(checks), static_cast<unsigned long long>(failures));
#endif
    return failures == 0 && checks >= 2400000 ? 0 : 1;
}
}  // namespace

#if defined(MELODY_HOST_FREESTANDING)
extern "C" int pcm_mono_concurrency_test_main() { return runTests(); }
#else
int main() { return runTests(); }
#endif
