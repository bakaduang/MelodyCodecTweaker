The PCM core tests run on the host without Android, JNI, LSPosed, a connected phone, or audio files.
They cover all six standard PCM representations, independent left/right samples, arithmetic
extremes, cancellation, nonfinite floats, released-byte boundaries, route and lease rejection,
route recovery, DEFAULT/static transfer modes, the bounded platform vector view, reused track
addresses, registry capacity, per-generation counters, and exact AudioTrack initialization ABI
selection (integer aliases, the device's named enums, missing symbols, aliases and ambiguity).

With an ordinary host C++ compiler, from the repository root:

```sh
clang++ -std=c++17 -O2 -Wall -Wextra -Werror app/src/test/cpp/pcm_mono_core_test.cpp -o pcm_mono_core_test
./pcm_mono_core_test
clang++ -std=c++17 -O2 -Wall -Wextra -Werror -pthread app/src/test/cpp/pcm_mono_concurrency_test.cpp -o pcm_mono_concurrency_test
./pcm_mono_concurrency_test
```

On Windows, NDK clang can instead build a small freestanding Windows executable, without
installing a separate host C++ runtime. Set `ANDROID_SDK_ROOT` to your SDK installation and use
your installed NDK version below. The executable returns zero only when all checks pass:

```sh
PCM_TEST_CLANG="$ANDROID_SDK_ROOT/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
"$PCM_TEST_CLANG" --target=x86_64-w64-windows-gnu -fuse-ld=lld -ffreestanding -nostdlib \
  -fno-rtti -fno-exceptions -fno-stack-protector -std=c++17 -O2 -Wall -Wextra -Werror \
  -DMELODY_HOST_FREESTANDING=1 -Wl,--entry,pcm_mono_test_main -Wl,--subsystem,console \
  app/src/test/cpp/pcm_mono_core_test.cpp -o pcm_mono_core_test.exe
./pcm_mono_core_test.exe
```

The concurrency suite exercises the same production atomic registry and generation counters with
real OS threads. It verifies every buffer from two stable tracks is mixed, including with continuous
telemetry snapshots and publication of another track's routing changes. It also checks coherent
same-slot publications, delayed old-generation records, reused generation numbers and bucket tokens,
counter exhaustion that leaves PCM processing enabled, and 100,000 concurrent generation changes.
The rejected-reader test deterministically orders a reader's delayed exit after a writer has ended
its reservation, to check that the writer preserves the pending reference instead of zeroing it.

The freestanding Windows concurrency test uses only six public Kernel32 functions. Build their
small import library with the bundled NDK's `llvm-dlltool`; no host C++ runtime is needed:

```sh
PCM_NDK_BIN="$ANDROID_SDK_ROOT/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin"
mkdir -p build/native-tests
"$PCM_NDK_BIN/llvm-dlltool.exe" -m i386:x86-64 \
  -d app/src/test/cpp/pcm_mono_test_kernel32.def -l build/native-tests/pcm_mono_test_kernel32.a
"$PCM_NDK_BIN/clang++.exe" --target=x86_64-w64-windows-gnu -fuse-ld=lld -ffreestanding -nostdlib \
  -fno-rtti -fno-exceptions -fno-stack-protector -fno-threadsafe-statics -std=c++17 -O2 \
  -Wall -Wextra -Werror -DMELODY_HOST_FREESTANDING=1 \
  -Wl,--entry,pcm_mono_concurrency_test_main -Wl,--subsystem,console \
  app/src/test/cpp/pcm_mono_concurrency_test.cpp build/native-tests/pcm_mono_test_kernel32.a \
  -o build/native-tests/pcm_mono_concurrency_test.exe
./build/native-tests/pcm_mono_concurrency_test.exe
```

The concurrency suite prints its assertion and failure counts. The successful assertion count
varies with scheduling but is at least 2,400,000. The audio helper performs no allocation or mutex
operation; bucket exhaustion may omit diagnostics but never suppresses PCM processing. Timestamps
advance with at most two atomic compare/exchange attempts, so concurrent updates can leave an
earlier real timestamp from the same generation while frame totals remain atomic and exact.

These host tests validate PCM arithmetic, concurrency, and fail-closed policy. They are not an ARM
hardware stress test or a ThreadSanitizer run. They cannot establish whether a
particular ROM exports the exact Android 16 symbols, whether its optimized AudioTrack code reaches
the hooked releaseBuffer entry, or whether the player has actually sent the modified frames to the
earbuds. Those require the native install diagnostics, growing mixed-frame counters, and the
left-only/right-only listening check on the phone.

The destructor ABI regression uses real compiler-generated virtual-base destructors, including
their hidden VTT. It destroys and reconstructs 512 tracks, verifies that D2 leaves virtual-base
destruction to the complete-object caller, and rejects aliases between incompatible destructor
entry points. It uses the same forwarding helper as the production D2 hook.

```sh
clang++ -std=c++17 -O2 -Wall -Wextra -Werror app/src/test/cpp/audio_track_destructor_test.cpp -o destructor_test
./destructor_test
```

For the freestanding Windows target above, substitute `audio_track_destructor_test.cpp` and the
entry point `audio_track_destructor_test_main`. A second build with `-DMELODY_TEST_OMIT_VTT=1`
is a negative control reproducing preview.6's unary call after cleanup clobbers the second
argument register. The normal build must return 0; the negative control must return 1. The
negative control detects the lost VTT before dereferencing it, so no crash is required to fail.

This host test validates forwarding and real C++ object teardown. Inspect the release ARM64
wrapper separately to confirm x1 is preserved across cleanup; phone song-switch testing is
still required to confirm the complete player lifecycle.
