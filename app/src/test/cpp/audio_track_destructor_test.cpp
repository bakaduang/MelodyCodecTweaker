#include <stddef.h>
#include <stdint.h>
#include "../../main/cpp/audio_track_destructor_abi.h"

#if !defined(MELODY_HOST_FREESTANDING)
#include <new>
#include <stdio.h>
#else
void* operator new(size_t, void* address) noexcept { return address; }
void operator delete(void*) noexcept {}
void operator delete(void*, size_t) noexcept {}
extern "C" void* memset(void* destination, int value, size_t length) {
    auto* bytes = static_cast<volatile unsigned char*>(destination);
    for (size_t i = 0; i < length; ++i) bytes[i] = static_cast<unsigned char>(value);
    return destination;
}
#endif

// These real compiler-generated destructors use the Itanium virtual-base/VTT convention on
// Android arm64, ELF hosts and the freestanding MinGW host target. No simulated vtable layout.
struct MonoLifecycleVirtualBase {
    uint32_t baseMagic = 0x456789ab;
    virtual ~MonoLifecycleVirtualBase();
};
struct MonoLifecycleTrack : virtual MonoLifecycleVirtualBase {
    uint32_t trackMagic = 0x12345678;
    ~MonoLifecycleTrack() override;
};
extern "C" void fixtureD2(void*, const void*) asm("_ZN18MonoLifecycleTrackD2Ev");
extern const void* const fixtureVtt[] asm("_ZTT18MonoLifecycleTrack");

namespace {
unsigned checks, failures, trackDestructions, baseDestructions, forwarded;
void* tracked;
const void* expectedVtt;
volatile uint64_t cleanupScratch[16];

void expect(bool value) { ++checks; if (!value) ++failures; }

__attribute__((noinline)) void forgetFixture(void* self) {
    expect(tracked == self);
    tracked = nullptr;
    for (size_t i = 0; i < 16; ++i) cleanupScratch[i] = i;
    // Reproduce cleanup clobbering the caller-saved second-argument register. A correctly
    // typed wrapper must preserve the incoming VTT across this ordinary function call.
#if defined(__aarch64__)
    asm volatile("mov x1, xzr" ::: "x1");
#elif defined(__x86_64__) && defined(_WIN32)
    asm volatile("xor %%edx, %%edx" ::: "rdx");
#elif defined(__x86_64__)
    asm volatile("xor %%esi, %%esi" ::: "rsi");
#endif
}

__attribute__((noinline)) void destroyFixture(void* self, const void* vtt) {
    expect(tracked == nullptr);
    expect(vtt == expectedVtt);
    if (vtt != expectedVtt) return;  // the negative-control build fails without dereferencing junk
    ++forwarded;
    fixtureD2(self, vtt);
}

int runTests() {
    using namespace melody::pcm;
    constexpr unsigned iterations = 512;
    for (unsigned i = 0; i < iterations; ++i) {
        alignas(MonoLifecycleTrack) unsigned char storage[sizeof(MonoLifecycleTrack)];
        auto* track = new (storage) MonoLifecycleTrack;
        auto* virtualBase = static_cast<MonoLifecycleVirtualBase*>(track);
        tracked = track;
        expectedVtt = fixtureVtt;
#if defined(MELODY_TEST_OMIT_VTT)
        // Regression control: the preview.6 unary forwarding loses the hidden argument.
        forgetFixture(track);
        reinterpret_cast<CompleteDestructorFn>(destroyFixture)(track);
#else
        forwardBaseDestructor(track, fixtureVtt, destroyFixture, forgetFixture);
#endif
        expect(trackDestructions == i + 1);
        expect(baseDestructions == i);  // D2 must leave the virtual base to the complete destructor
        virtualBase->MonoLifecycleVirtualBase::~MonoLifecycleVirtualBase();
        expect(baseDestructions == i + 1);
    }
    expect(forwarded == iterations);
    void* d0 = reinterpret_cast<void*>(0x1000);
    void* d1 = reinterpret_cast<void*>(0x2000);
    void* d2 = reinterpret_cast<void*>(0x3000);
    expect(baseDestructorIsDistinct(d0, d1, d2));
    expect(baseDestructorIsDistinct(d0, d0, d2));
    expect(!baseDestructorIsDistinct(d0, d1, d0));
    expect(!baseDestructorIsDistinct(d0, d1, d1));
    expect(!baseDestructorIsDistinct(d0, d1, nullptr));
#if !defined(MELODY_HOST_FREESTANDING)
    printf("AudioTrack destructor ABI: %u checks, %u failures\n", checks, failures);
#endif
    return failures == 0 ? 0 : 1;
}
}

__attribute__((noinline)) MonoLifecycleTrack::~MonoLifecycleTrack() {
    expect(trackMagic == 0x12345678);
    expect(baseMagic == 0x456789ab);  // real virtual-base access requires the forwarded VTT
    trackMagic = 0;
    ++trackDestructions;
}
__attribute__((noinline)) MonoLifecycleVirtualBase::~MonoLifecycleVirtualBase() {
    expect(baseMagic == 0x456789ab);
    baseMagic = 0;
    ++baseDestructions;
}

#if defined(MELODY_HOST_FREESTANDING)
extern "C" int audio_track_destructor_test_main() { return runTests(); }
#else
int main() { return runTests(); }
#endif
